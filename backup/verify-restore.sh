#!/bin/bash
# Restore drill: prove the vault in the backup can really be unlocked.
#
# Copies vault.db + vault.meta.json out of the backup mirror into a temp folder, starts a
# throwaway fortis-agent on that copy (its own socket, port 18023, no Tailscale), asks for your
# master password, lists a few entries, then stops the agent and deletes the copy.
# Your real agent and vault are not touched.
#
#   bash backup/verify-restore.sh [BACKUP_ROOT]      (default: BACKUP_ROOT from backup.conf)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="${1:-}"
if [[ -z "$ROOT" && -f "$HERE/backup.conf" ]]; then
    ROOT=$(sed -n 's/^BACKUP_ROOT="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$HERE/backup.conf" | head -1)
fi
ROOT="${ROOT:-/mnt/FortisVault}"

SRC=""
for name in .local__share__fortis fortis; do
    if [[ -f "$ROOT/Mirror/Secrets/$name/vault.db" ]]; then SRC="$ROOT/Mirror/Secrets/$name"; break; fi
done
[[ -n "$SRC" ]] || { echo "✖ No vault.db under $ROOT/Mirror/Secrets — the vault is not being backed up."; exit 1; }
[[ -f "$SRC/vault.meta.json" ]] || { echo "✖ $SRC has vault.db but no vault.meta.json (salt): it could never be unlocked."; exit 1; }

AGENT=$(command -v fortis-agent || echo "$HOME/.local/bin/fortis-agent")
CLI=$(command -v fortis)
T=$(mktemp -d)
cleanup() { [[ -n "${PID:-}" ]] && kill "$PID" 2>/dev/null; wait "${PID:-}" 2>/dev/null || true; rm -rf "$T"; }
trap cleanup EXIT

install -d -m 700 "$T/data/fortis" "$T/config/fortis" "$T/run"
cp "$SRC/vault.db" "$SRC/vault.meta.json" "$T/data/fortis/"
printf '[rest]\nport = 18023\ntailnet = false\n' > "$T/config/fortis/agent.toml"
chmod 600 "$T/config/fortis/agent.toml"

echo "Backup copy: $SRC  (vault.db modified $(date -r "$SRC/vault.db" '+%d %b %Y %H:%M'))"
XDG_DATA_HOME="$T/data" XDG_CONFIG_HOME="$T/config" XDG_RUNTIME_DIR="$T/run" \
    "$AGENT" >"$T/agent.log" 2>&1 & PID=$!
for _ in $(seq 1 50); do [[ -S "$T/run/fortis/agent.sock" ]] && break; sleep 0.1; done
[[ -S "$T/run/fortis/agent.sock" ]] || { echo "✖ Throwaway agent did not start:"; tail -5 "$T/agent.log"; exit 1; }

export XDG_RUNTIME_DIR="$T/run"
STATE=$("$CLI" status | awk '/^vault/{print $2}')
[[ "$STATE" == "locked" ]] || { echo "✖ The restored copy is not a usable vault (state: $STATE)."; exit 1; }

echo "Enter your master password to unlock the BACKUP copy:"
"$CLI" unlock
N=$("$CLI" list | grep -c . || true)
echo
"$CLI" list | head -5
echo "…"
echo "✔ Restore drill passed: the backup unlocks and lists $N line(s) of entries."
