# Fortis Ecosystem — Development Guide

## 0. Rules

1. **Docs first.** A behaviour change starts as a docs change (security, architecture or API). Code follows. A PR that changes behaviour without the matching docs change is incomplete.
2. **No dev bypasses.** No hardcoded keys, salts, tokens, "mock success" paths or `if debug { skip auth }`, in any build profile. Tests use generated test keys.
3. **No `unwrap()`/`expect()` on runtime input or I/O** in `fortis-agent` and `fortis-pam`. Use `Result` and map to an API error. `pam_fortis` must never panic across FFI.
4. **Secrets** use `SecretString` / `Zeroizing`, never appear in logs or `Debug` output.
5. Every MUST in the [Security Model](../security/README.md) needs a test.

---

## 1. Layout

```text
~/dev/Fortis/Fortis-Ecosystem/
├── fortis-backend/        # Cargo workspace
│   ├── fortis-core/       # lib (feature "db" = SQLCipher)
│   ├── fortis-agent/      # bin
│   ├── fortis-pam/        # cdylib → pam_fortis.so
│   └── fortis-bindings/   # cdylib → libfortis_bindings.so (UniFFI)
├── fortis-cli/            # bin "fortis" (standalone crate)
├── fortis-desktop/        # Tauri v2
├── fortis-mobile/
│   ├── android/           # Android app (Gradle)
│   └── cli/               # Termux backup CLI (Bash)
├── fortis-browser/
│   ├── extension/         # WebExtension (Firefox + Chromium, Manifest V3)
│   └── host/              # Rust bin "fortis-browser-host" (native messaging)
├── fortis-backup/         # Bash
└── docs/
```

```toml
# fortis-backend/Cargo.toml
[workspace]
members = ["fortis-core", "fortis-agent", "fortis-pam", "fortis-bindings"]
resolver = "2"
```

Cross-repo dependencies:

- `fortis-cli` may depend on `fortis-core` with `default-features = false` (path `../fortis-backend/fortis-core`) for the password generator; it talks to the agent only over IPC.
- `fortis-desktop` does **not** depend on `fortis-core`. It is an HTTP client of the agent.
- `fortis-mobile` uses the compiled `fortis-bindings` library and the generated Kotlin file.

---

## 2. fortis-backend

```bash
cd fortis-backend
cargo build
cargo test
cargo clippy --all-targets -- -D warnings
cargo fmt --check
```

### Run the agent against a throw-away home

Never point a dev build at your real vault:

```bash
export DEV_HOME=$(mktemp -d)
HOME=$DEV_HOME XDG_RUNTIME_DIR=$DEV_HOME/run RUST_LOG=debug cargo run -p fortis-agent
```

The agent creates `$DEV_HOME/.config/fortis/agent.token`. In another shell:

```bash
TOKEN=$(cat $DEV_HOME/.config/fortis/agent.token)
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8023/api/v1/vault/status
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"password":"a long test password"}' http://127.0.0.1:8023/api/v1/vault/init
```

To keep the tailnet listener off during development set `rest.tailnet = false` in `$DEV_HOME/.config/fortis/agent.toml`.

IPC by hand:

```bash
printf '{"id":1,"cmd":"status","args":{}}\n' | nc -U $DEV_HOME/run/fortis/agent.sock
```

### Testing signed requests

`fortis-core` exposes `sign::canonical_request(...)`. Integration tests in `fortis-agent/tests/` generate a P-256 key, pair it through `POST /pair`, and call tailnet endpoints with signed headers. A small helper binary `fortis-agent/examples/signed_curl.rs` does the same from the command line.

### Testing pam_fortis without touching the system

`fortis-pam` keeps its logic in a plain Rust function, `authenticate(cfg: &PamConfig, io: &mut dyn AgentIo) -> Decision`, with the trust-file path and socket path injected. Unit tests cover: missing file, bad permissions, wrong signature, wrong request ID, expired, deny, approve. Only the thin `pam_sm_authenticate` wrapper is untested outside a real PAM stack.

Real-PAM tests run without root: a test harness calls libpam's `pam_start_confdir` with a temporary config directory, inside a user + mount namespace (`unshare -rm`) with an overlay on `/etc` so `/etc/fortis/pam/<user>.json` can be created as namespace-root. A fake agent socket answers with signed approvals. It covers approve, deny, bad signature, agent missing, agent hanging (deadline), and module file missing (the `-auth` line is skipped).

On a real machine, `sudo fortis pam-test` exercises the installed module through the `fortis-test` service before `sudo` is ever changed.

---

## 3. fortis-cli

```bash
cd fortis-cli
cargo build
./target/debug/fortis status
```

The binary is named `fortis` (`[[bin]] name = "fortis"`). Commands:

| Command | IPC |
|---|---|
| `fortis status` | `status` |
| `fortis init [--password-stdin]` | `init` (prompts twice on a terminal) |
| `fortis unlock [--password-stdin]` / `fortis lock` | `unlock` / `lock` |
| `fortis list [query] [--type T] [--json]` | `list` |
| `fortis get <title, id or id-prefix> [--field F] [--copy] [--json]` | `get` / `totp` |
| `fortis add [--type T] [--from-json FILE\|-]` / `fortis edit <name>` / `fortis rm <name>` | `add` / `edit` / `delete` |
| `fortis generate [--length N] [--no-symbols]` | `generate` |
| `fortis pair` | `pair_start` |
| `fortis devices list` / `fortis devices revoke <id>` | `devices_list` / `devices_revoke` |
| `sudo fortis pam-enroll` | `pam_devices`, then writes `/etc/fortis/pam/<user>.json` |
| `sudo fortis pam-install [--sudo]` / `sudo fortis pam-test` / `sudo fortis pam-uninstall` | — (edits `/etc/pam.d`, runs a PAM conversation; security §4.7) |
| `fortis backup run` | `backup_run` |
| `fortis sessions list` / `fortis sessions end [id]` | `sessions_list` / `sessions_end` |

Passwords are read with no echo. `--copy` puts the value on the clipboard and clears it after 30 s (only if the clipboard still holds it). Secrets are printed only when explicitly asked for with `--field` or `--json`. `fortis generate` works without the agent. There is no `change-password` CLI command yet; use the desktop app.

`pam-enroll` runs as root via sudo: it finds the invoking user from `SUDO_UID`, connects to `/run/user/$SUDO_UID/fortis/agent.sock`, and writes the trust file atomically (write temp file, `fsync`, `rename`).

---

## 4. fortis-desktop

```bash
cd fortis-desktop
npm install
npm run tauri dev
```

- All agent calls happen in `src-tauri/src/` (Rust, `reqwest`), exposed to the UI as Tauri commands (`vault_status`, `unlock`, `lock`, `list_entries`, `get_entry`, `save_entry`, `delete_entry`, `totp`, `generate_password`, `copy_secret`).
- The UI never sees the agent token and never calls `fetch` to the agent.
- Rendering untrusted strings (titles, notes) uses `textContent`, never `innerHTML`.
- Fonts and icons are bundled; no CDN requests.

---

## 5. fortis-mobile and fortis-bindings

### Build the Rust library for Android

```bash
cargo install cargo-ndk
rustup target add aarch64-linux-android x86_64-linux-android
cd fortis-backend/fortis-bindings
cargo ndk -t arm64-v8a -t x86_64 -o ../../fortis-mobile/android/app/src/main/jniLibs build --release
cargo run --bin uniffi-bindgen generate --library \
  ../target/aarch64-linux-android/release/libfortis_bindings.so \
  --language kotlin --out-dir ../../fortis-mobile/android/app/src/main/java
```

### Bindings surface

| Function | Purpose |
|---|---|
| `derive_wrap_key(password, meta_json) -> bytes` | Argon2id + HKDF, same as the agent |
| `unwrap_vault_key(wrap_key, wrapped, vault_id) -> bytes` | Decrypt the vault key from `/sync/bootstrap` |
| `decrypt_entry(vault_key, entry_id, version, blob) -> String` | JSON `VaultEntry` |
| `encrypt_entry(vault_key, entry_id, version, json) -> bytes` | For local edits before push |
| `generate_totp(config_json, unix_time) -> String` | TOTP code |
| `generate_password(length, upper, numbers, symbols) -> String` | |
| `canonical_request(method, path, ts, nonce, body) -> bytes` | Bytes to sign with the request key |
| `canonical_approval(request_json, decision) -> bytes` | Bytes to sign with the approval key |

Signing itself happens in Kotlin with the Android Keystore (keys never leave it); Rust only builds the bytes.

### App structure (target)

```text
android/app/src/main/java/com/fortis/mobile/
├── keys/       # Keystore: per-pairing request + approval keys, local wrap key
├── net/        # signed HTTP client (OkHttp interceptor), events + input WebSockets
├── pairing/    # paired laptops store, vault-laptop choice, QR pairing
├── service/    # foreground service holding the events connection
├── vault/      # local encrypted store (Room), sync engine
├── sudo/       # approval notification + full-screen activity + BiometricPrompt
├── remote/     # input (network + Bluetooth HID), webcam (CameraX + MJPEG)
├── sync/       # camera-roll WorkManager job, notification listener
├── widget/     # home-screen widget
└── ui/         # Compose screens: Dashboard, Control, Files, Monitor, Vault, TOTP, Generator, Devices, More
```

### Migrating the existing app

The app in `fortis-mobile/android` already has the screens; its network and security layers change:

| Today | Target |
|---|---|
| Package `com.example.fortismobile` | `com.fortis.mobile` (changing it resets app data — do it before re-pairing) |
| API key per device in SharedPreferences, `Authorization: Bearer` | Per-pairing Keystore keys; every request signed (`net/`) |
| `FortisKeyManager` asks for an `ed25519` key (fails on Android) | P-256 keys as in security §6.2 |
| `FortisAuthService` stub ("gRPC on 50052") | Foreground service with the events WebSocket |
| Vault screen fetches decrypted entries over the network | Local encrypted vault + sync |
| `/api/v1/input` WebSocket with no session | Fingerprint-confirmed session first (security §5.6) |
| `MjpegServer(8080)` on all interfaces, no auth | Tailscale IP only, random port + token (security §7.6) |
| Camera sync reads `MediaStore.DATA` file paths | Content URIs; `dest=camera` |
| `POST /files/list` | `GET /files/list?path=` |
| Terminal screen | Removed |
| `fix_ui.py`, `scratch.py`, `.kotlin/errors/` | Removed from the repo |

---

## 6. fortis-browser

```bash
cd fortis-browser
(cd host && cargo test)      # native host unit tests
extension/build.sh           # dist/chrome (MV3) + dist/firefox (MV2)
tests/e2e.sh                 # real browser + extension + host + a throw-away agent (port 8124, own HOME)
```

The Chromium extension ID is pinned by the public key in `extension/chrome-public-key.txt`; the host manifest allows only that ID (and `fortis@fortis.local` in Firefox).

## 6b. fortis-backup

```bash
cd fortis-backup
shellcheck fortis-backup.sh
```

Test against a scratch `BACKUP_ROOT` (an empty directory you create) before pointing it at the real drive.

---

## 7. Docs and diagrams

```bash
cd docs
./scripts/build_diagrams.sh       # needs graphviz + cargo-depgraph
```

- Topology source: `architecture/fortis_ecosystem.dot`.
- Rust dependency graph: generated from the workspace with `cargo depgraph --workspace-only`.

---

## 8. Checklist for a change

- [ ] Docs updated (spec first)
- [ ] `cargo fmt`, `cargo clippy -D warnings`, `cargo test` pass in `fortis-backend` and `fortis-cli`
- [ ] New security rule has a test
- [ ] No secrets in logs (grep test output for the test password)
- [ ] Implementation status table in `docs/README.md` updated
