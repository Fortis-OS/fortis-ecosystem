#!/usr/bin/env bash
# Run pam_fortis through the real libpam without root and without touching
# the system: user + mount namespace, overlay on /etc, tmpfs on /run/user.
#
#   fortis-pam/tests/real_pam.sh            (from the backend/ workspace)
set -euo pipefail
cd "$(dirname "$0")/../.."
# --lib: building only the example would leave a stale .so in place
cargo build -q --release -p fortis-pam --lib --example pam_harness
MOD=$PWD/target/release/libfortis_pam.so
HARNESS=$PWD/target/release/examples/pam_harness
USER_NAME=$(id -un)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/up" "$WORK/wk"
chmod 755 "$WORK/up"   # like the real /etc; the module rejects a group-writable /etc

exec unshare -rm bash -s "$MOD" "$HARNESS" "$USER_NAME" "$WORK" <<'INNER'
set -uo pipefail
MOD=$1 HARNESS=$2 U=$3 WORK=$4
mount -t overlay overlay -o "lowerdir=/etc,upperdir=$WORK/up,workdir=$WORK/wk" /etc
mount -t tmpfs -o mode=755 tmpfs /run/user
# A real install leaves a root-owned /etc/fortis we can't modify here: use our own.
[ -d /etc/fortis ] || mkdir /etc/fortis
mkdir -p "${WORK:-$W}/fortis-etc" && chmod 755 "${WORK:-$W}/fortis-etc" && mount --bind "${WORK:-$W}/fortis-etc" /etc/fortis
pass=0 fail=0
# name | module | agent | next | dash | expected code | max seconds | extra args
check() {
  local name=$1 mod=$2 agent=$3 next=$4 dash=$5 want=$6 max=$7; shift 7
  out=$("$HARNESS" "$mod" "$U" "$agent" "$next" "$dash" "$@" 2>&1)
  got=$(sed -n 's/^RESULT \([0-9]*\).*/\1/p' <<<"$out")
  secs=$(sed -n 's/.* in \([0-9.]*\)s$/\1/p' <<<"$out")
  if [[ "$got" == "$want" ]] && awk "BEGIN{exit !($secs <= $max)}"; then
    printf 'PASS  %-58s %s\n' "$name" "$(grep RESULT <<<"$out" | cut -d' ' -f2-)"; pass=$((pass+1))
  else
    printf 'FAIL  %-58s want %s ≤%ss, got:\n%s\n' "$name" "$want" "$max" "$out"; fail=$((fail+1))
  fi
}
# PAM codes: 0 = success, 7 = authentication failure
check "phone approves                      → success"         "$MOD"   approve deny   yes 0 5 timeout=5
check "phone denies                        → next module runs" "$MOD"   deny    permit yes 0 5 timeout=5
check "phone denies                        → never success"    "$MOD"   deny    deny   yes 7 5 timeout=5
check "forged approval (compromised agent) → never success"    "$MOD"   forge   deny   yes 7 5 timeout=5
check "forged approval                     → next module runs" "$MOD"   forge   permit yes 0 5 timeout=5
check "agent not running                   → next module runs" "$MOD"   none    permit yes 0 2 timeout=5
check "agent hangs (timeout=2)             → gives up ≤ 4 s"   "$MOD"   hang    permit yes 0 4.5 timeout=2
check "module file missing, -auth          → next module runs" missing  approve permit yes 0 2
check "service not in services=            → ignored"          "$MOD"   approve deny   yes 7 2 services=sudo
echo "--- for comparison, WITHOUT the leading '-' (not what we install):"
"$HARNESS" missing "$U" approve permit no 2>&1 | grep RESULT || true
echo "$pass passed, $fail failed"
[[ $fail -eq 0 ]]
INNER
