#!/bin/bash
set -uo pipefail

# ==========================================
# FORTIS BACKUP
# Enterprise DevOps Utility
# ==========================================

# --- EXIT CODES ---
# 0 = Success
# 1 = Configuration Error
# 2 = Mirror Failure
# 3 = Snapshot Failure
# 4 = Destination Unavailable
# 5 = Lock exists (Already running)
# 6 = Local backup OK, but remote (mobile) sync failed

# ==========================================
# 1. CONFIGURATION & SECURITY VALIDATION
# ==========================================
SCRIPT_DIR="$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")"
CONFIG_FILE="$SCRIPT_DIR/backup.conf"

if [[ ! -f "$CONFIG_FILE" ]]; then
    echo "✖ Error: backup.conf not found at $CONFIG_FILE"
    exit 1
fi

# Fix #2: Validate config ownership and permissions before sourcing to prevent
# arbitrary code execution if the file is tampered with or world-writable.
CONFIG_OWNER=$(stat -Lc "%u" "$CONFIG_FILE")
CONFIG_PERMS=$(stat -Lc "%a" "$CONFIG_FILE")
PERM_GROUP="${CONFIG_PERMS: -2:1}"
PERM_OTHERS="${CONFIG_PERMS: -1}"

if [[ "$CONFIG_OWNER" != "$(id -u)" ]]; then
    echo "✖ Security Error: $CONFIG_FILE is not owned by the current user (owner UID: $CONFIG_OWNER). Refusing to source."
    exit 1
fi
if [[ "$PERM_GROUP" =~ [2367] ]] || [[ "$PERM_OTHERS" =~ [2367] ]]; then
    echo "✖ Security Error: $CONFIG_FILE is group or world-writable (permissions: $CONFIG_PERMS)."
    echo "  Fix with: chmod 600 $CONFIG_FILE"
    exit 1
fi

# Fix #15: Pre-initialise all variables that backup.conf may or may not define,
# so set -u never fires on missing optional config keys.
BACKUP_ROOT=""
PROJECTS=()
SECRETS=()
MEMORIES=()
MOBILE_IP=""
MOBILE_PORT=""

# shellcheck source=backup.conf
source "$CONFIG_FILE"

# Name an item gets inside the backup: normally its folder name. When two items in the same
# list share a folder name (e.g. ~/.config/fortis and ~/.local/share/fortis), each uses its
# path under $HOME instead (".config__fortis", ".local__share__fortis") so neither overwrites
# the other.
item_name() {
    local path="$1"; shift
    local base other n=0
    base=$(basename "$path")
    for other in "$@"; do [[ "$(basename "$other")" == "$base" ]] && n=$((n + 1)); done
    if (( n > 1 )); then
        local rel="${path#"$HOME"/}"
        echo "${rel//\//__}"
    else
        echo "$base"
    fi
}

if [[ ! -d "$BACKUP_ROOT" ]]; then
    echo "✖ Error: Destination '$BACKUP_ROOT' unavailable (drive not mounted?)"
    exit 4
fi

# ==========================================
# RESTORE CLI ENGINE
# ==========================================
if [[ "${1:-}" == "restore" ]]; then
    shift
    RESTORE_TYPE=""
    RESTORE_NAME=""
    RESTORE_DATE="latest"

    while [[ $# -gt 0 ]]; do
        case $1 in
            --project) RESTORE_TYPE="Projects"; RESTORE_NAME="$2"; shift 2 ;;
            --secret)  RESTORE_TYPE="Secrets";  RESTORE_NAME="$2"; shift 2 ;;
            --memory)  RESTORE_TYPE="Memories"; RESTORE_NAME="$2"; shift 2 ;;
            --date)    RESTORE_DATE="$2"; shift 2 ;;
            *) echo "Unknown parameter: $1"; exit 1 ;;
        esac
    done

    if [[ -z "$RESTORE_TYPE" || -z "$RESTORE_NAME" ]]; then
        echo "Usage: fortis-backup restore [--project | --secret | --memory] <name> [--date YYYY-MM-DD]"
        exit 1
    fi

    # Resolve source path
    if [[ "$RESTORE_DATE" == "latest" ]]; then
        SOURCE_PATH="$BACKUP_ROOT/Mirror/$RESTORE_TYPE/$RESTORE_NAME"
    else
        if [[ "$RESTORE_TYPE" == "Memories" ]]; then
            SOURCE_PATH="$BACKUP_ROOT/FortisGIT/Weekly/$RESTORE_DATE/$RESTORE_TYPE/$RESTORE_NAME"
        else
            SOURCE_PATH="$BACKUP_ROOT/FortisGIT/Daily/$RESTORE_DATE/$RESTORE_TYPE/$RESTORE_NAME"
        fi
    fi

    if [[ ! -e "$SOURCE_PATH" ]]; then
        echo "✖ Snapshot not found at: $SOURCE_PATH"
        exit 1
    fi

    # Smart Path Resolution: reverse-lookup original target from backup.conf
    TARGET_PATH=""
    ARRAY=()
    if [[ "$RESTORE_TYPE" == "Projects" ]]; then        ARRAY=("${PROJECTS[@]+"${PROJECTS[@]}"}")
    elif [[ "$RESTORE_TYPE" == "Secrets" ]]; then       ARRAY=("${SECRETS[@]+"${SECRETS[@]}"}")
    elif [[ "$RESTORE_TYPE" == "Memories" ]]; then      ARRAY=("${MEMORIES[@]+"${MEMORIES[@]}"}"); fi

    for item in "${ARRAY[@]+"${ARRAY[@]}"}"; do
        if [[ "$(item_name "$item" "${ARRAY[@]}")" == "$RESTORE_NAME" ]]; then
            TARGET_PATH="$item"
            break
        fi
    done

    if [[ -z "$TARGET_PATH" ]]; then
        echo "✖ Target path for '$RESTORE_NAME' not found in backup.conf"
        exit 1
    fi

    echo "========================================"
    echo "       FortisVault Restore Utility      "
    echo "========================================"
    echo "Source: $SOURCE_PATH"
    echo "Target: $TARGET_PATH"
    echo ""
    echo "⚠️  WARNING: This will overwrite the target directory with the snapshot."
    read -p "Are you sure you want to proceed? [y/N] " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Restore cancelled."
        exit 0
    fi

    echo "Restoring..."
    mkdir -p "$(dirname "$TARGET_PATH")"
    if [[ -d "$SOURCE_PATH" ]]; then
        mkdir -p "$TARGET_PATH"
        rsync -a --delete "$SOURCE_PATH/" "$TARGET_PATH/"
    else
        rsync -a "$SOURCE_PATH" "$(dirname "$TARGET_PATH")/"
    fi

    if [[ $? -eq 0 ]]; then
        echo "✔ Restore completed successfully."
        exit 0
    else
        echo "✖ Restore failed."
        exit 1
    fi
fi

# ==========================================
# BACKUP DAEMON ENGINE
# ==========================================

# Fix #1: Lock file scoped to $BACKUP_ROOT (not global /tmp), so concurrent
# runs against the same destination are prevented without cross-user collisions.
# Acquired here, after config + destination are already validated.
LOCK_FILE="$BACKUP_ROOT/fortis-backup.lock"
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
    echo "Backup is already running (lock: $LOCK_FILE). Exiting."
    exit 5
fi

# 3. DIRECTORY SETUP
mkdir -p \
    "$BACKUP_ROOT/Mirror/Projects" \
    "$BACKUP_ROOT/Mirror/Secrets" \
    "$BACKUP_ROOT/Mirror/Memories" \
    "$BACKUP_ROOT/FortisGIT/Daily" \
    "$BACKUP_ROOT/FortisGIT/Weekly" \
    "$BACKUP_ROOT/Logs/mirror" \
    "$BACKUP_ROOT/Logs/snapshots/daily" \
    "$BACKUP_ROOT/Logs/snapshots/weekly"

# Fix #4: Restrict the entire backup root to owner-only access.
# This ensures that even if the drive is mounted on a multi-user system,
# no other user can browse or read backup data (filesystem hardening).
chmod 700 "$BACKUP_ROOT"

# 4. LOGGING SETUP
MIRROR_LOG="$BACKUP_ROOT/Logs/mirror/mirror.log"
TODAY=$(date +"%Y-%m-%d")
TIMESTAMP=$(date +"%Y-%m-%d %I:%M:%S %p")
START_TIME=$SECONDS

# Fix #10: Rotate mirror.log if it exceeds 5 MB to prevent unbounded growth.
# Keeps one generation (.log.1). No external logrotate dependency required.
if [[ -f "$MIRROR_LOG" ]]; then
    LOG_SIZE=$(stat -c%s "$MIRROR_LOG" 2>/dev/null || echo 0)
    if [[ "$LOG_SIZE" -gt 5242880 ]]; then
        mv "$MIRROR_LOG" "${MIRROR_LOG}.1"
    fi
fi

log_mirror() { echo "$1" | tee -a "$MIRROR_LOG"; }

log_mirror "========================================"
log_mirror "[$TIMESTAMP] Backup Started"
log_mirror "========================================"

# Fix #5: Health tracking for all three categories (original only tracked Projects).
EXPECTED_PROJECTS=${#PROJECTS[@]}
SUCCESS_PROJECTS=0
EXPECTED_SECRETS=${#SECRETS[@]}
SUCCESS_SECRETS=0
EXPECTED_MEMORIES=${#MEMORIES[@]}
SUCCESS_MEMORIES=0
MIRROR_FAILED=0


# ==========================================
# PHASE A: MIRROR ENGINE
# ==========================================
log_mirror "--- Projects ---"
for PROJECT in "${PROJECTS[@]+"${PROJECTS[@]}"}"; do
    PROJECT_NAME=$(item_name "$PROJECT" "${PROJECTS[@]}")
    if [[ ! -d "$PROJECT" ]]; then
        log_mirror "✖ $PROJECT_NAME (Directory not found)"
        MIRROR_FAILED=1
        continue
    fi
    rsync -a --delete \
        --exclude="node_modules" \
        --exclude=".dist" \
        --exclude="dist" \
        --exclude="build" \
        --exclude=".next" \
        --exclude="target" \
        --exclude=".venv" \
        --exclude="venv" \
        --exclude="env" \
        --exclude="__pycache__" \
        --exclude="*.pyc" \
        --exclude=".pytest_cache" \
        --exclude=".mypy_cache" \
        --exclude=".ruff_cache" \
        --exclude=".cache" \
        --exclude="wandb" \
        "$PROJECT/" "$BACKUP_ROOT/Mirror/Projects/$PROJECT_NAME/" 2>>"$MIRROR_LOG"
    if [[ $? -eq 0 ]]; then
        log_mirror "✔ $PROJECT_NAME"
        ((SUCCESS_PROJECTS++))
    else
        log_mirror "✖ $PROJECT_NAME (rsync failed)"
        MIRROR_FAILED=1
    fi
done

log_mirror "--- Secrets ---"
for SECRET in "${SECRETS[@]+"${SECRETS[@]}"}"; do
    SECRET_NAME=$(item_name "$SECRET" "${SECRETS[@]}")
    if [[ ! -e "$SECRET" ]]; then
        log_mirror "✖ $SECRET_NAME (Not found)"
        MIRROR_FAILED=1
        continue
    fi
    if [[ -d "$SECRET" ]]; then
        rsync -a --delete "$SECRET/" "$BACKUP_ROOT/Mirror/Secrets/$SECRET_NAME/" 2>>"$MIRROR_LOG"
    else
        rsync -a "$SECRET" "$BACKUP_ROOT/Mirror/Secrets/" 2>>"$MIRROR_LOG"
    fi
    if [[ $? -eq 0 ]]; then
        log_mirror "✔ $SECRET_NAME"
        ((SUCCESS_SECRETS++))
    else
        log_mirror "✖ $SECRET_NAME (rsync failed)"
        MIRROR_FAILED=1
    fi
done

log_mirror "--- Memories ---"
for MEMORY in "${MEMORIES[@]+"${MEMORIES[@]}"}"; do
    MEMORY_NAME=$(item_name "$MEMORY" "${MEMORIES[@]}")
    if [[ ! -d "$MEMORY" ]]; then
        log_mirror "✖ $MEMORY_NAME (Not found)"
        MIRROR_FAILED=1
        continue
    fi
    rsync -a --delete "$MEMORY/" "$BACKUP_ROOT/Mirror/Memories/$MEMORY_NAME/" 2>>"$MIRROR_LOG"
    if [[ $? -eq 0 ]]; then
        log_mirror "✔ $MEMORY_NAME"
        ((SUCCESS_MEMORIES++))
    else
        log_mirror "✖ $MEMORY_NAME (rsync failed)"
        MIRROR_FAILED=1
    fi
done

# Fix #5: Full health summary for all three categories.
log_mirror "--- Health Verification ---"
log_mirror "Projects : $SUCCESS_PROJECTS / $EXPECTED_PROJECTS"
log_mirror "Secrets  : $SUCCESS_SECRETS / $EXPECTED_SECRETS"
log_mirror "Memories : $SUCCESS_MEMORIES / $EXPECTED_MEMORIES"
if [[ "$EXPECTED_PROJECTS" -eq "$SUCCESS_PROJECTS" && \
      "$EXPECTED_SECRETS"  -eq "$SUCCESS_SECRETS"  && \
      "$EXPECTED_MEMORIES" -eq "$SUCCESS_MEMORIES"  ]]; then
    log_mirror "Result: PASS"
else
    log_mirror "Result: FAIL (Some items did not sync — see above)"
fi
log_mirror ""


# ==========================================
# PHASE B: FORTISGIT SNAPSHOT ENGINE
# ==========================================
SNAPSHOT_FAILED=0

DAILY_SNAP_DIR="$BACKUP_ROOT/FortisGIT/Daily/$TODAY"
DAILY_LOG="$BACKUP_ROOT/Logs/snapshots/daily/$TODAY.log"
log_daily() { echo "$1" | tee -a "$DAILY_LOG"; }

if [[ ! -d "$DAILY_SNAP_DIR" ]]; then
    # Fix #8: Threshold-based snapshot policy.
    # Calculate overall mirror success rate across all three categories combined.
    # - 100%      → snapshot normally.
    # - 95–99%    → snapshot with a WARNING (partial data, still useful).
    # - <95%      → skip snapshot (catastrophic failure; a bad snapshot is worse than none).
    TOTAL_EXPECTED=$(( EXPECTED_PROJECTS + EXPECTED_SECRETS + EXPECTED_MEMORIES ))
    TOTAL_SUCCESS=$(( SUCCESS_PROJECTS + SUCCESS_SECRETS + SUCCESS_MEMORIES ))
    # Integer percentage (floor). Guard against division-by-zero on empty config.
    if [[ "$TOTAL_EXPECTED" -gt 0 ]]; then
        MIRROR_PCT=$(( TOTAL_SUCCESS * 100 / TOTAL_EXPECTED ))
    else
        MIRROR_PCT=100
    fi

    if [[ "$MIRROR_PCT" -lt 95 ]]; then
        log_daily "[$TIMESTAMP] ✖ Mirror success rate ${MIRROR_PCT}% is below 95% threshold ($TOTAL_SUCCESS/$TOTAL_EXPECTED items). Skipping daily snapshot to avoid capturing an incomplete state."
        log_mirror "FortisGIT: Daily Snapshot SKIPPED (mirror success ${MIRROR_PCT}% < 95% — fix errors and re-run)."
        SNAPSHOT_FAILED=1
    else
        mkdir -p "$DAILY_SNAP_DIR"
        log_daily "[$TIMESTAMP] Starting FortisGIT Daily Snapshot (mirror success: ${MIRROR_PCT}% — $TOTAL_SUCCESS/$TOTAL_EXPECTED items)..."
        if [[ "$MIRROR_PCT" -lt 100 ]]; then
            log_daily "⚠ WARNING: Partial snapshot — some mirror items failed. This snapshot may be incomplete."
        fi

        # Fix #7: Link-dest configuration for hard-link deduplication
        LATEST_DAILY=$(find "$BACKUP_ROOT/FortisGIT/Daily" -maxdepth 1 -mindepth 1 -type d ! -name "$TODAY" 2>/dev/null | sort -r | head -n 1)
        LINK_DEST_PROJECTS=()
        LINK_DEST_SECRETS=()
        if [[ -n "${LATEST_DAILY:-}" ]]; then
            if [[ -d "$LATEST_DAILY/Projects" ]]; then
                LINK_DEST_PROJECTS=("--link-dest=$LATEST_DAILY/Projects")
            fi
            if [[ -d "$LATEST_DAILY/Secrets" ]]; then
                LINK_DEST_SECRETS=("--link-dest=$LATEST_DAILY/Secrets")
            fi
            log_daily "Using previous snapshot for hard-links: $LATEST_DAILY"
        else
            log_daily "No previous snapshot found. Performing full initial snapshot."
        fi

        mkdir -p "$DAILY_SNAP_DIR/Projects" "$DAILY_SNAP_DIR/Secrets"

        # Per-project snapshot execution and logging (matches mirror format)
        log_daily "--- Projects Snapshot ---"
        DAILY_SUCCESS_PROJECTS=0
        for PROJECT in "${PROJECTS[@]+"${PROJECTS[@]}"}"; do
            PROJECT_NAME=$(item_name "$PROJECT" "${PROJECTS[@]}")
            if [[ ! -d "$BACKUP_ROOT/Mirror/Projects/$PROJECT_NAME" ]]; then
                log_daily "✖ $PROJECT_NAME (Mirror source not found)"
                SNAPSHOT_FAILED=1
                continue
            fi
            rsync -a "${LINK_DEST_PROJECTS[@]+"${LINK_DEST_PROJECTS[@]}"}" \
                "$BACKUP_ROOT/Mirror/Projects/$PROJECT_NAME" "$DAILY_SNAP_DIR/Projects/" 2>>"$DAILY_LOG"
            if [[ $? -eq 0 ]]; then
                log_daily "✔ $PROJECT_NAME"
                ((DAILY_SUCCESS_PROJECTS++))
            else
                log_daily "✖ $PROJECT_NAME (snapshot failed)"
                SNAPSHOT_FAILED=1
            fi
        done

        # Per-secret snapshot execution and logging (matches mirror format)
        log_daily "--- Secrets Snapshot ---"
        DAILY_SUCCESS_SECRETS=0
        for SECRET in "${SECRETS[@]+"${SECRETS[@]}"}"; do
            SECRET_NAME=$(item_name "$SECRET" "${SECRETS[@]}")
            if [[ ! -e "$BACKUP_ROOT/Mirror/Secrets/$SECRET_NAME" ]]; then
                log_daily "✖ $SECRET_NAME (Mirror source not found)"
                SNAPSHOT_FAILED=1
                continue
            fi
            rsync -a "${LINK_DEST_SECRETS[@]+"${LINK_DEST_SECRETS[@]}"}" \
                "$BACKUP_ROOT/Mirror/Secrets/$SECRET_NAME" "$DAILY_SNAP_DIR/Secrets/" 2>>"$DAILY_LOG"
            if [[ $? -eq 0 ]]; then
                log_daily "✔ $SECRET_NAME"
                ((DAILY_SUCCESS_SECRETS++))
            else
                log_daily "✖ $SECRET_NAME (snapshot failed)"
                SNAPSHOT_FAILED=1
            fi
        done

        log_daily "--- Snapshot Verification ---"
        log_daily "Projects : $DAILY_SUCCESS_PROJECTS / $EXPECTED_PROJECTS snapshotted"
        log_daily "Secrets  : $DAILY_SUCCESS_SECRETS / $EXPECTED_SECRETS snapshotted"

        # Fix #16: Verify the snapshot directory was actually populated.
        # An empty snapshot directory means rsync silently wrote nothing — worse than no snapshot.
        SNAP_FILE_COUNT=$(find "$DAILY_SNAP_DIR" -mindepth 1 | head -n 1)
        if [[ -z "$SNAP_FILE_COUNT" ]]; then
            log_daily "✖ Post-snapshot verification FAILED: snapshot directory is empty ($DAILY_SNAP_DIR)"
            log_mirror "FortisGIT: Daily Snapshot EMPTY — possible silent rsync failure."
            rm -rf "$DAILY_SNAP_DIR"
            SNAPSHOT_FAILED=1
        elif [[ "$SNAPSHOT_FAILED" -eq 0 ]]; then
            log_daily "✔ Created Daily Snapshot: $TODAY"
            log_mirror "FortisGIT: Created Daily Snapshot ($TODAY)"
        else
            log_daily "✖ Daily Snapshot completed with errors: $TODAY"
            log_mirror "FortisGIT: Daily Snapshot had errors ($TODAY)"
        fi
        log_daily ""
    fi
else
    log_daily "[$TIMESTAMP] Daily Snapshot already exists for $TODAY. Skipping."
    log_mirror "FortisGIT: Daily Snapshot already exists for $TODAY. Skipping."
fi

# Weekly Snapshot (Memories) — Sundays only
DAY_OF_WEEK=$(date +%u)
WEEKLY_SNAP_DIR="$BACKUP_ROOT/FortisGIT/Weekly/$TODAY"
WEEKLY_LOG="$BACKUP_ROOT/Logs/snapshots/weekly/$TODAY.log"
log_weekly() { echo "$1" | tee -a "$WEEKLY_LOG"; }

if [[ "$DAY_OF_WEEK" -eq 7 ]]; then
    if [[ ! -d "$WEEKLY_SNAP_DIR" ]]; then
        mkdir -p "$WEEKLY_SNAP_DIR/Memories"
        log_weekly "[$TIMESTAMP] Starting FortisGIT Weekly Snapshot..."

        LATEST_WEEKLY=$(find "$BACKUP_ROOT/FortisGIT/Weekly" -maxdepth 1 -mindepth 1 -type d ! -name "$TODAY" 2>/dev/null | sort -r | head -n 1)
        # Fix #7: Proper Bash array for weekly link-dest.
        # Point link-dest to the previous weekly's Memories/ subdirectory so that
        # relative paths resolve correctly when syncing individual memory items.
        LINK_DEST_ARGS_W=()
        if [[ -n "${LATEST_WEEKLY:-}" ]]; then
            LINK_DEST_ARGS_W=("--link-dest=$LATEST_WEEKLY/Memories")
            log_weekly "Using previous weekly snapshot: $LATEST_WEEKLY"
        fi

        # Fix #9: Per-item success/failure reporting for weekly Memories snapshot.
        WEEKLY_SUCCESS=0
        WEEKLY_EXPECTED=${#MEMORIES[@]}
        for MEMORY in "${MEMORIES[@]+"${MEMORIES[@]}"}"; do
            MEMORY_NAME=$(item_name "$MEMORY" "${MEMORIES[@]}")
            rsync -a "${LINK_DEST_ARGS_W[@]+"${LINK_DEST_ARGS_W[@]}"}" \
                "$BACKUP_ROOT/Mirror/Memories/$MEMORY_NAME" \
                "$WEEKLY_SNAP_DIR/Memories/" 2>>"$WEEKLY_LOG"
            if [[ $? -eq 0 ]]; then
                log_weekly "✔ $MEMORY_NAME"
                ((WEEKLY_SUCCESS++))
            else
                log_weekly "✖ $MEMORY_NAME (snapshot failed)"
                SNAPSHOT_FAILED=1
            fi
        done

        log_weekly "Weekly Memories: $WEEKLY_SUCCESS / $WEEKLY_EXPECTED snapshotted"

        # Fix #16: Verify the weekly snapshot directory was actually populated.
        WEEKLY_SNAP_FILE_COUNT=$(find "$WEEKLY_SNAP_DIR" -mindepth 1 | head -n 1)
        if [[ -z "$WEEKLY_SNAP_FILE_COUNT" ]]; then
            log_weekly "✖ Post-snapshot verification FAILED: weekly snapshot directory is empty ($WEEKLY_SNAP_DIR)"
            log_mirror "FortisGIT: Weekly Snapshot EMPTY — possible silent rsync failure."
            rm -rf "$WEEKLY_SNAP_DIR"
            SNAPSHOT_FAILED=1
        elif [[ "$WEEKLY_SUCCESS" -eq "$WEEKLY_EXPECTED" ]]; then
            log_weekly "✔ Created Weekly Memories Snapshot: $TODAY"
            log_mirror "FortisGIT: Created Weekly Snapshot ($TODAY)"
        else
            log_weekly "✖ Weekly Snapshot completed with errors: $TODAY"
            log_mirror "FortisGIT: Weekly Snapshot had errors ($TODAY)"
        fi
        log_weekly ""
    else
        log_weekly "[$TIMESTAMP] Weekly Snapshot already exists for $TODAY. Skipping."
        log_mirror "FortisGIT: Weekly Snapshot already exists for $TODAY. Skipping."
    fi
fi


# ==========================================
# PHASE C: RETENTION CLEANUP
# ==========================================
# Fix #6: Cleanup now runs BEFORE remote sync (correct order: A→B→C→D).
# Old snapshots are pruned locally first; then only valid, current data is pushed remotely.
log_mirror "Cleaning up old snapshots..."
if [[ -d "$BACKUP_ROOT/FortisGIT/Daily" ]]; then
    find "$BACKUP_ROOT/FortisGIT/Daily" -maxdepth 1 -mindepth 1 -type d -mtime +14 -exec rm -rf {} + 2>/dev/null || true
    find "$BACKUP_ROOT/Logs/snapshots/daily" -maxdepth 1 -type f -mtime +14 -exec rm -f {} + 2>/dev/null || true
fi
if [[ -d "$BACKUP_ROOT/FortisGIT/Weekly" ]]; then
    find "$BACKUP_ROOT/FortisGIT/Weekly" -maxdepth 1 -mindepth 1 -type d -mtime +56 -exec rm -rf {} + 2>/dev/null || true
    find "$BACKUP_ROOT/Logs/snapshots/weekly" -maxdepth 1 -type f -mtime +56 -exec rm -f {} + 2>/dev/null || true
fi
log_mirror "✔ Cleanup complete"
log_mirror ""


# ==========================================
# PHASE D: REMOTE OFF-SITE SYNC (TAILSCALE)
# ==========================================
# Fix #6: Remote sync now runs AFTER cleanup (correct order: A→B→C→D).
REMOTE_FAILED=0
if [[ -n "${MOBILE_IP:-}" && -n "${MOBILE_PORT:-}" ]]; then
    log_mirror "[$TIMESTAMP] Starting Remote Off-Site Sync (Tailscale)..."

    # Build SSH options for all connections
    SSH_OPTS=(
        -p "$MOBILE_PORT"
        -i "$HOME/.ssh/fortis_mobile"
        -o "StrictHostKeyChecking=yes"
        -o "UserKnownHostsFile=$HOME/.ssh/known_hosts_fortis_mobile"
        -o "ConnectTimeout=5"
    )
    # Build the SSH command string needed by rsync's -e flag
    SSH_CMD_STR="ssh -p $MOBILE_PORT -i $HOME/.ssh/fortis_mobile -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$HOME/.ssh/known_hosts_fortis_mobile -o ConnectTimeout=5"

    REMOTE_AVAILABLE=0
    # 5-minute holding loop (30 attempts × 10 seconds)
    for ((i=1; i<=30; i++)); do
        # 1. Check if SSH is accessible at all
        if nc -z -w 3 "$MOBILE_IP" "$MOBILE_PORT" 2>/dev/null; then
            # Note: We must use the absolute path because non-interactive SSH does not load ~/.bashrc
            STATUS_JSON=$(ssh "${SSH_OPTS[@]}" "$MOBILE_IP" "bash /data/data/com.termux/files/home/.local/bin/fortis-mobile status --json" 2>&1)
            STATUS_RC=$?
            if [[ $STATUS_RC -eq 0 ]]; then
                READY=$(echo "$STATUS_JSON" | jq -r '.ready')
                if [[ "$READY" == "true" ]]; then
                    REMOTE_AVAILABLE=1
                    break
                else
                    REASON=$(echo "$STATUS_JSON" | jq -r '.reason' | tr -d '"')
                    log_mirror "Mobile connected but NOT READY: $REASON. Retrying..."
                fi
            else
                # Log the actual error instead of a generic message. ssh exits 255 for its own
                # failures (auth, host key, corrupted connection); any other code comes from
                # the fortis-mobile command on the phone.
                STATUS_ERR=$(echo "$STATUS_JSON" | tail -n 2 | tr '\n' ' ' | sed 's/[[:space:]]*$//')
                if [[ $STATUS_RC -eq 255 ]]; then
                    log_mirror "Mobile port open but SSH failed (exit 255): ${STATUS_ERR:-no output}. Retrying..."
                else
                    log_mirror "fortis-mobile status failed on phone (exit $STATUS_RC): ${STATUS_ERR:-no output}. Retrying..."
                fi
            fi
        fi

        # On first loop, notify the user to start the session
        if [[ $i -eq 1 ]]; then
            log_mirror "Waiting for mobile device readiness... (up to 5 minutes)"
            if command -v notify-send >/dev/null 2>&1; then
                # Fix #13: Detect display server safely
                if [[ -z "${WAYLAND_DISPLAY:-}" && -z "${DISPLAY:-}" ]]; then
                    export DISPLAY=:0
                fi
                notify-send -u critical "FortisVault Backup" \
                    "Tap to start FortisMobile Backup Mode." 2>/dev/null || true
            fi
        fi
        sleep 10
    done

    if [[ $REMOTE_AVAILABLE -eq 1 ]]; then
        log_mirror "Mobile device READY! Syncing today's snapshot..."

        # Dynamically find the previous snapshot name to use for remote deduplication.
        LATEST_LOCAL=$(find "$BACKUP_ROOT/FortisGIT/Daily" -maxdepth 1 -mindepth 1 -type d ! -name "$TODAY" 2>/dev/null | sort -r | head -n 1)
        REMOTE_LINK_DEST=()
        if [[ -n "${LATEST_LOCAL:-}" ]]; then
            PREV_SNAP=$(basename "$LATEST_LOCAL")
            # Verify the previous snapshot actually exists on the mobile device before using it for hard-links
            if ssh "${SSH_OPTS[@]}" "$MOBILE_IP" "[ -d ~/FortisVault/Daily/$PREV_SNAP ]" 2>/dev/null; then
                # --link-dest path is evaluated relative to the destination directory
                REMOTE_LINK_DEST=("--link-dest=../$PREV_SNAP")
                log_mirror "Using remote deduplication against previous snapshot: $PREV_SNAP"
            else
                log_mirror "Previous snapshot '$PREV_SNAP' not found on mobile device; performing full sync."
            fi
        fi

        # Push today's freshly created snapshot to remote
        # Fix: Replaced -a (archive) with -rlt (recursive, links, times) to avoid Android metadata hang-ups.
        # Added --link-dest for deduplication. Removed --inplace as it is incompatible with --link-dest.
        # Retry/resume: the phone can drop the SSH session mid-transfer (Android killing
        # Termux sshd, device sleep). Each retry re-runs rsync, which skips files already
        # transferred; --partial-dir keeps half-sent files so large files resume instead
        # of restarting from zero.
        if [[ ! -d "$BACKUP_ROOT/FortisGIT/Daily/$TODAY" ]]; then
            log_mirror "✖ No local snapshot for $TODAY (snapshot was skipped). Nothing to push."
            REMOTE_FAILED=1
        else
            REMOTE_MAX_ATTEMPTS=4
            for ((attempt=1; attempt<=REMOTE_MAX_ATTEMPTS; attempt++)); do
                if [[ $attempt -gt 1 ]]; then
                    # Wait up to 60s for the phone's sshd to come back before retrying.
                    log_mirror "Retrying remote sync (attempt $attempt/$REMOTE_MAX_ATTEMPTS)..."
                    for ((w=1; w<=6; w++)); do
                        nc -z -w 3 "$MOBILE_IP" "$MOBILE_PORT" 2>/dev/null && break
                        sleep 10
                    done
                fi

                # Ensure target directory exists on remote
                ssh "${SSH_OPTS[@]}" "$MOBILE_IP" "mkdir -p ~/FortisVault/Daily/$TODAY" 2>/dev/null

                rsync -rlt --omit-dir-times --timeout=60 --partial-dir=.rsync-partial \
                    "${REMOTE_LINK_DEST[@]+"${REMOTE_LINK_DEST[@]}"}" -e "$SSH_CMD_STR" \
                    "$BACKUP_ROOT/FortisGIT/Daily/$TODAY/" \
                    "$MOBILE_IP:~/FortisVault/Daily/$TODAY/" 2>>"$MIRROR_LOG"
                RSYNC_RC=$?

                if [[ $RSYNC_RC -eq 0 ]]; then
                    REMOTE_FAILED=0
                    log_mirror "✔ Remote Sync completed successfully (attempt $attempt/$REMOTE_MAX_ATTEMPTS)."
                    break
                fi
                REMOTE_FAILED=1
                log_mirror "✖ Remote Sync attempt $attempt/$REMOTE_MAX_ATTEMPTS failed (rsync exit $RSYNC_RC)."
            done
            if [[ $REMOTE_FAILED -ne 0 ]]; then
                log_mirror "✖ Remote Sync failed after $REMOTE_MAX_ATTEMPTS attempts."
            fi
        fi

        # Prune older remote snapshots on mobile device (retain last 7 days) to prevent storage exhaustion
        log_mirror "Cleaning up old remote snapshots on mobile (retention: 7 days)..."
        ssh "${SSH_OPTS[@]}" "$MOBILE_IP" \
            "find ~/FortisVault/Daily -maxdepth 1 -mindepth 1 -type d -mtime +7 -exec rm -rf {} + 2>/dev/null || true"
        log_mirror "✔ Remote cleanup complete."
        
        # Stop Backup Mode on the phone to release wake lock and shut down sshd
        ssh "${SSH_OPTS[@]}" "$MOBILE_IP" "bash /data/data/com.termux/files/home/.local/bin/fortis-mobile stop" >/dev/null 2>&1 || true
        log_mirror "Told mobile device to stop Backup Mode." 
    else
        log_mirror "✖ Mobile device did not come online after 5 minutes. Skipping Remote Sync."
        REMOTE_FAILED=1
    fi
    log_mirror ""
fi


# ==========================================
# FINALISE
# ==========================================
DURATION=$((SECONDS - START_TIME))
log_mirror "Backup finished in ${DURATION} seconds."

if [[ $MIRROR_FAILED -ne 0 || $SNAPSHOT_FAILED -ne 0 ]]; then
    log_mirror "Status: COMPLETED WITH ERRORS"
elif [[ $REMOTE_FAILED -ne 0 ]]; then
    log_mirror "Status: LOCAL SUCCESS, REMOTE SYNC FAILED"
else
    log_mirror "Status: SUCCESS"
fi
log_mirror ""

if [[ $MIRROR_FAILED -ne 0 ]]; then
    exit 2
fi
if [[ $SNAPSHOT_FAILED -ne 0 ]]; then
    exit 3
fi
if [[ $REMOTE_FAILED -ne 0 ]]; then
    exit 6
fi

exit 0
