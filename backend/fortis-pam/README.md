# fortis-pam (`pam_fortis.so`)

Fingerprint-approved `sudo`: the module sends a fresh challenge to the paired phone through fortis-agent and accepts only a valid P-256 signature from a device listed in the **root-owned** `/etc/fortis/pam/<user>.json`. Spec: [security §4 and §4.7](../../docs/security/README.md).

It returns only `PAM_SUCCESS` or `PAM_IGNORE`. On deny, timeout, a missing agent, a bad signature, an unsafe trust file — anything — PAM moves on to your password.

## Install (never hand-edit PAM)

```bash
cargo build --release -p fortis-pam
sudo install -o root -g root -m 0644 ../target/release/libfortis_pam.so /usr/lib/x86_64-linux-gnu/security/pam_fortis.so   # Fedora: /usr/lib64/security/
sudo fortis pam-enroll        # trust your paired phone
sudo fortis pam-install       # stage 1: /etc/pam.d/fortis-test only
sudo fortis pam-test          # approve → PASS, deny → FALLBACK OK
sudo fortis pam-install --sudo   # stage 2: one `-auth sufficient pam_fortis.so` line in /etc/pam.d/sudo
```

Undo: `sudo fortis pam-uninstall`, or `pkexec fortis pam-uninstall` if sudo is ever broken.

## Module arguments

| Argument | Default | |
|---|---|---|
| `timeout=N` | `30` | Seconds to wait for the phone (1–60); hard deadline is `N + 2` |
| `services=a,b` | `sudo,sudo-i` | Services the module acts for; anything else is ignored |
| `debug` | off | Log decisions via `pam_syslog` (`sudo journalctl -g pam_fortis`) |

## Layout

- `src/logic.rs` — all decisions, safe Rust, unit-tested (approve, deny, forged approval, replay, hang/deadline, oversized reply, unsafe trust files, other services).
- `src/lib.rs` — thin FFI (`pam_sm_authenticate`, `pam_sm_setcred`), every entry wrapped in `catch_unwind`.

## Tests

```bash
cargo test -p fortis-pam                                  # unit tests
fortis-pam/tests/real_pam.sh                              # the .so through real libpam (no root; user namespace)
fortis-pam/tests/install_flow.sh ../cli/target/release/fortis   # pam-install/test/uninstall + real sudo stack
```

Both scripts run in a user + mount namespace with overlays, so nothing on the host changes.
