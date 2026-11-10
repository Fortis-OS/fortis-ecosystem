#!/usr/bin/env bash
# End-to-end test of `fortis pam-install / pam-test / pam-install --sudo /
# pam-uninstall` and the real /etc/pam.d/sudo stack, in a sandbox:
# user + mount namespace with overlays on /etc and the PAM module dir.
#
#   fortis-pam/tests/install_flow.sh /path/to/fortis
set -euo pipefail
FORTIS=$(readlink -f "$1")
cd "$(dirname "$0")/../.."
cargo build -q --release -p fortis-pam --lib --example pam_harness
MOD=$PWD/target/release/libfortis_pam.so
H=$PWD/target/release/examples/pam_harness
MODDIR=/usr/lib/x86_64-linux-gnu/security
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W"/{eu,ew,mu,mw}; chmod 755 "$W/eu" "$W/mu"
exec unshare -rm bash -s "$FORTIS" "$MOD" "$H" "$MODDIR" "$W" "$(id -un)" <<'INNER'
set -uo pipefail
F=$1 MOD=$2 H=$3 MODDIR=$4 W=$5 U=$6
mount -t overlay overlay -o "lowerdir=/etc,upperdir=$W/eu,workdir=$W/ew" /etc
# Sandbox-owned copies of the PAM config and module dirs (overlay copy-up
# can't write into directories owned by the real, unmapped root).
mkdir -p "$W/pamd" "$W/mods"; chmod 755 "$W/pamd" "$W/mods"
cp -r /etc/pam.d/. "$W/pamd/"; cp -r "$MODDIR/." "$W/mods/"
mount --bind "$W/pamd" /etc/pam.d
mount --bind "$W/mods" "$MODDIR"
mount -t tmpfs -o mode=755 tmpfs /run/user
export SUDO_USER=$U
ORIG=$(cat /etc/pam.d/sudo)
step(){ echo; echo "### $*"; }

step "pam-install --sudo before anything → refused"
$F pam-install --sudo -y 2>&1 | sed 's/^/  /'
step "install module, pam-install (stage 1)"
install -m 0644 "$MOD" "$MODDIR/pam_fortis.so" && echo "  installed $MODDIR/pam_fortis.so"
$F pam-install 2>&1 | sed 's/^/  /'
step "pam-install --sudo without a passing test → refused"
$F pam-install --sudo -y 2>&1 | sed 's/^/  /'
step "pam-test, phone denies"
$H serve "$U" deny & sleep 0.3; $F pam-test 2>&1 | sed 's/^/  /'; kill %1 2>/dev/null; wait 2>/dev/null
step "pam-test, phone approves"
$H serve "$U" approve & sleep 0.3; $F pam-test 2>&1 | sed 's/^/  /'; kill %1 2>/dev/null; wait 2>/dev/null
step "pam-install --sudo (stage 2)"
$F pam-install --sudo -y 2>&1 | sed 's/^/  /'
echo "  diff against the original /etc/pam.d/sudo:"; diff <(echo "$ORIG") /etc/pam.d/sudo | sed 's/^/    /'
step "real sudo stack: phone approves"
$H system "$U" approve 2>&1 | grep -E 'RESULT|conv' | sed 's/^/  /'
step "real sudo stack: phone denies → must reach the password prompt"
$H system "$U" deny 2>&1 | grep -E 'RESULT|conv' | sed 's/^/  /'
step "real sudo stack: agent not running → password prompt"
$H system "$U" none 2>&1 | grep -E 'RESULT|conv' | sed 's/^/  /'
step "module file deleted while enabled → password prompt"
rm "$MODDIR/pam_fortis.so"
$H system "$U" approve 2>&1 | grep -E 'RESULT|conv' | sed 's/^/  /'
step "pam-uninstall → original restored byte for byte"
$F pam-uninstall 2>&1 | sed 's/^/  /'
[[ "$(cat /etc/pam.d/sudo)" == "$ORIG" ]] && echo "  IDENTICAL to original" || echo "  DIFFERENT from original!"
INNER
