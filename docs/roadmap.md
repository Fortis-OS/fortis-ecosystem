# Fortis Ecosystem — Master Roadmap

> **Vision**: A unified, military-grade Personal Infrastructure Management Platform. One daemon. One mobile app. One ecosystem. All connected over Tailscale P2P with zero cloud dependency.

---

## Current State (What's Built)

| Component | Tech | Status |
|-----------|------|--------|
| `FortisAgent` | Go | ✅ Production (REST API on `:8023`) |
| `FortisMobile` | Kotlin / Jetpack Compose | ✅ Production (Dashboard, Actions, Files, Terminal, Webcam, Bluetooth HID) |
| `FortisBackup` | Bash / Rsync / Systemd | ✅ Production |
| `FortisObserve` | Next.js | ✅ Production (web metrics dashboard) |
| `FortisLock` | React / Vite | 🟡 In Progress |
| `FortisEncrypt/fortis-core` | Rust | ✅ Built (Crypto, DB, TOTP, Password Gen) |
| `FortisEncrypt/fortis-sync` | Rust / gRPC | ✅ Built (P2P sync protocol) |
| `FortisEncrypt/fortis-daemon` | Rust / Tokio | ✅ Built (IPC daemon, vault operations) |
| `FortisEncrypt/fortis-cli` | Rust / Clap | ✅ Built (terminal vault client) |
| `FortisEncrypt/fortis-desktop` | Tauri v2 / HTML / CSS / TS | ✅ Built (glassmorphism UI) |
| `FortisEncrypt/fortis-bindings` | Rust / UniFFI | ✅ Built (Kotlin bindings generated) |
| `FortisEncrypt/fortis-android` | Kotlin (scaffold) | ⚠️ Scaffold only — **will be deleted** |

---

## Target State (What We're Building)

```
Fortis-Ecosystem/
│
├── fortis-agent/        ← ONE UNIFIED DAEMON (Rust) replaces Go agent + fortis-daemon
├── fortis-core/         ← Shared crypto/DB/auth library (Rust crate)
├── fortis-sync/         ← P2P gRPC protocol definitions (Rust crate)
├── fortis-cli/          ← Thin terminal client (Rust binary)
├── fortis-pam/          ← Linux PAM module for biometric sudo (Rust .so plugin)
├── fortis-desktop/      ← Tauri v2 desktop UI (HTML/CSS/TS + Rust backend)
├── fortis-bindings/     ← UniFFI bridge for Android (Rust cdylib)
│
├── FortisMobile/        ← ONE UNIFIED ANDROID APP (Kotlin/Compose)
├── FortisObserve/       ← Traffic analysis platform for websites (Next.js)
├── FortisLock/          ← Chrome extension to lock websites (React/Vite)
├── FortisBackup/        ← Backup scripts (Bash/Systemd)
│
├── [ARCHIVED] FortisAgent/       ← Replaced by unified fortis-agent
└── [DELETED]  fortis-android/    ← Merged into FortisMobile
```

---

## The Roadmap (8 Milestones)

### ━━━ MILESTONE 1: Unified Rust Agent ━━━
**Goal**: Replace both `FortisAgent` (Go) and `fortis-daemon` (Rust) with a single Rust binary.
**Priority**: 🔴 Critical — foundation for everything else.

| # | Task | Details |
|---|------|---------|
| 1.1 | Create `fortis-agent` crate | New workspace member with modular architecture |
| 1.2 | Build `transport/rest.rs` | Port the Go REST API (`:8023`) to Rust using `axum`. Maintains backward compatibility with FortisMobile |
| 1.3 | Build `transport/ipc.rs` | Absorb the existing `fortis-daemon` Unix socket IPC logic |
| 1.4 | Build `transport/grpc.rs` | Wire up `fortis-sync` gRPC for P2P and biometric auth |
| 1.5 | Build `modules/telemetry.rs` | Port `handleTelemetry` from Go (system metrics via `/proc`, `sensors`) |
| 1.6 | Build `modules/actions.rs` | Port `handleAction`, `handleMedia`, `handleClipboard` from Go |
| 1.7 | Build `modules/files.rs` | Port `handleFileList`, `handleFileDrop`, `handleFileDownload` from Go |
| 1.8 | Build `modules/vault.rs` | Integrate `fortis-core` DB operations (already built) |
| 1.9 | Build `modules/auth.rs` | Biometric sudo challenge relay (from implementation_plan.md) |
| 1.10 | Build `modules/backup.rs` | Orchestrate FortisBackup triggers |
| 1.11 | Create systemd unit | `fortis-agent.service` — single `systemctl start fortis-agent` |
| 1.12 | Create config | `~/.config/fortis/agent.toml` — unified configuration |

**Deliverable**: `systemctl start fortis-agent` runs ONE process that handles telemetry, remote control, vault, sync, and auth relay. FortisMobile works against it without any changes.

---

### ━━━ MILESTONE 2: Core Auth Module ━━━
**Goal**: Build the cryptographic challenge-response system for biometric sudo.
**Priority**: 🟡 High

| # | Task | Details |
|---|------|---------|
| 2.1 | Create `fortis-core/src/auth/mod.rs` | `AuthChallenge`, `AuthResponse`, `PairedDevice` structs |
| 2.2 | Implement Ed25519 verification | `verify_response()` using `ed25519-dalek` |
| 2.3 | Implement signing payload builder | `build_signing_payload()` — canonical `SHA-512(nonce \|\| username \|\| command \|\| timestamp)` |
| 2.4 | Add `pub mod auth` to `lib.rs` | Expose the module |
| 2.5 | Unit tests | Test challenge generation, signing, verification round-trip |

**Deliverable**: `cargo test -p fortis-core --lib auth` passes.

---

### ━━━ MILESTONE 3: gRPC Auth Protocol ━━━
**Goal**: Define and implement the biometric auth RPC over Tailscale.
**Priority**: 🟡 High

| # | Task | Details |
|---|------|---------|
| 3.1 | Update `proto/sync.proto` | Add `FortisAuth` service, `BiometricAuthRequest`, `BiometricAuthResponse` messages |
| 3.2 | Regenerate Rust gRPC stubs | `tonic-build` |
| 3.3 | Implement server-side in `fortis-agent` | `modules/auth.rs` handles incoming `AUTH_CHALLENGE` IPC, forwards via gRPC |
| 3.4 | Implement client-side in `fortis-agent` | gRPC client connects to phone's Tailscale IP on port `50052` |

**Deliverable**: Agent can send a challenge over Tailscale and receive a signed response.

---

### ━━━ MILESTONE 4: PAM Module ━━━
**Goal**: Build `pam_fortis.so` — the Linux authentication plugin.
**Priority**: 🟡 High

| # | Task | Details |
|---|------|---------|
| 4.1 | Create `fortis-pam` crate | `crate-type = ["cdylib"]`, depends on `fortis-core` |
| 4.2 | Implement `pam_sm_authenticate` | Generate nonce → send `AUTH_CHALLENGE` to agent via IPC → wait for response → verify signature |
| 4.3 | Implement failsafe | Return `PAM_IGNORE` if agent unreachable → falls through to password prompt |
| 4.4 | Write `fortis-pam.conf` | Example PAM config: `auth sufficient pam_fortis.so timeout=30` |
| 4.5 | Safe testing | Test with `pamtester` using a custom `/etc/pam.d/fortis-test` config |
| 4.6 | Deploy to real sudo | Only after all tests pass |

**Deliverable**: Type `sudo` → phone shows fingerprint prompt → approve → command runs. Deny or timeout → falls back to password.

---

### ━━━ MILESTONE 5: Merge Android Apps ━━━
**Goal**: Integrate all FortisEncrypt Android features into the existing FortisMobile app.
**Priority**: 🟡 High

| # | Task | Details |
|---|------|---------|
| 5.1 | Cross-compile `libfortis_bindings.so` | Build for `aarch64-linux-android` target |
| 5.2 | Update `build.gradle.kts` | Add biometric, gRPC, JNI deps to FortisMobile |
| 5.3 | Copy `.so` + `.kt` bindings | Place in `app/src/main/jniLibs/arm64-v8a/` and `uniffi/` package |
| 5.4 | Build `encrypt/VaultScreen.kt` | Password vault browser (monochrome Compose, matches existing design) |
| 5.5 | Build `encrypt/AuthenticatorScreen.kt` | Live TOTP codes with countdown timers |
| 5.6 | Build `encrypt/PasswordGeneratorScreen.kt` | Secure password generator UI |
| 5.7 | Build `encrypt/FortisKeyManager.kt` | Ed25519 keypair management in Android Hardware KeyStore |
| 5.8 | Build `encrypt/BiometricPromptActivity.kt` | Transparent activity → `BiometricPrompt` → sign challenge |
| 5.9 | Build `encrypt/FortisAuthService.kt` | Foreground gRPC server on port `50052` (listens for sudo challenges) |
| 5.10 | Build `encrypt/FortisAutofillService.kt` | OS-level autofill (like Google Password Manager) |
| 5.11 | Build `encrypt/PairDeviceScreen.kt` | 6-digit pairing ceremony UI |
| 5.12 | Update `MainActivity.kt` navigation | Add "Vault" as 5th bottom tab |
| 5.13 | Update `AndroidManifest.xml` | Register new services, permissions, activities |
| 5.14 | Extend `CapabilityManager.kt` | Add `getLocalCapabilities()` for vault/auth (works offline) |
| 5.15 | Delete `FortisEncrypt/fortis-android/` | Code has been migrated |

**Deliverable**: One APK that does everything — infrastructure management AND password vault/TOTP/biometric auth/autofill.

---

### ━━━ MILESTONE 6: CLI Pairing Ceremony ━━━
**Goal**: Enable one-time device pairing between Linux and Android.
**Priority**: 🟢 Medium

| # | Task | Details |
|---|------|---------|
| 6.1 | Add `fortis pair` subcommand | Generates 6-digit code, displays in terminal |
| 6.2 | Agent pairing handler | Accepts incoming pairing requests, verifies code, stores public key |
| 6.3 | Save paired devices | `~/.config/fortis/paired_devices.json` |
| 6.4 | Mobile pairing flow | PairDeviceScreen.kt → enter code → generate keypair → send public key |

**Deliverable**: `fortis pair` → enter code on phone → "✅ Paired with Pixel 7 Pro (100.64.0.2)". From this point, `sudo` uses your fingerprint.

---

### ━━━ MILESTONE 7: Desktop UI Polish ━━━
**Goal**: Complete and polish the Tauri desktop app.
**Priority**: 🟢 Medium

| # | Task | Details |
|---|------|---------|
| 7.1 | Wire Tauri IPC to unified agent | Point `fortis-desktop` at the new unified agent's IPC socket |
| 7.2 | Build "Add Entry" modal | Form to add passwords, cards, notes, TOTP secrets |
| 7.3 | Build TOTP live viewer | Animated countdown ring for authenticator codes |
| 7.4 | Build password generator panel | Slider for length, toggles for special chars |
| 7.5 | Build settings page | Master password change, paired devices list, sync status |

**Deliverable**: A stunning native desktop vault app with full CRUD and live TOTP codes.

---

### ━━━ MILESTONE 8: Integration Testing & Hardening ━━━
**Goal**: End-to-end verification and security hardening.
**Priority**: 🟢 Medium

| # | Task | Details |
|---|------|---------|
| 8.1 | E2E: CLI → Agent → DB | `fortis add` → verify in DB → `fortis get` |
| 8.2 | E2E: Desktop → Agent → DB | Add entry in Tauri UI → verify via CLI |
| 8.3 | E2E: Mobile → Agent → DB | View vault on phone → matches desktop |
| 8.4 | E2E: Biometric sudo | `sudo ls` → phone notification → fingerprint → approved |
| 8.5 | E2E: P2P sync | Add entry on desktop → appears on phone via Tailscale |
| 8.6 | E2E: Autofill | Open Chrome on phone → autofill from vault |
| 8.7 | Security audit | Verify zeroization, nonce uniqueness, timeout behavior |
| 8.8 | Archive old repos | Archive `FortisAgent` (Go), delete `fortis-android` scaffold |

---

## Dependency Graph

```
M1 (Unified Agent) ──────────────────────────────┐
    │                                            │
    ├──► M2 (Core Auth) ──► M3 (gRPC Auth) ──► M4 (PAM Module)
    │                           │
    │                           └──► M5 (Merge Android) ──► M6 (Pairing)
    │
    └──► M7 (Desktop Polish)
    
All ──► M8 (Integration Testing)
```

**Critical path**: M1 → M2 → M3 → M4 + M5 → M6 → M8

---

## Summary

| Milestone | What | Effort | Depends On |
|-----------|------|--------|------------|
| **M1** | Unified Rust Agent | 🔴 Large | Nothing |
| **M2** | Core Auth Module | 🟢 Small | M1 |
| **M3** | gRPC Auth Protocol | 🟡 Medium | M2 |
| **M4** | PAM Module | 🟡 Medium | M3 |
| **M5** | Merge Android Apps | 🔴 Large | M1, M3 |
| **M6** | CLI Pairing | 🟢 Small | M4, M5 |
| **M7** | Desktop UI Polish | 🟡 Medium | M1 |
| **M8** | Integration Testing | 🟡 Medium | All |

---

> **Start here → Milestone 1.** The unified Rust agent is the foundation that unlocks everything else. Once that's running, Milestones 2-7 can proceed in parallel tracks.

---

## Final Directory Tree (After All Milestones)

```
/home/ganeshak11/dev/Fortis-Ecosystem/
│
├── fortis-agent/                          # 🟢 THE UNIFIED DAEMON (Rust)
│   ├── Cargo.toml
│   ├── src/
│   │   ├── main.rs                        # Single entry point, Tokio runtime
│   │   ├── config.rs                      # Parses ~/.config/fortis/agent.toml
│   │   ├── modules/
│   │   │   ├── mod.rs
│   │   │   ├── telemetry.rs               # CPU, RAM, Swap, Disk, Temp (replaces Go)
│   │   │   ├── actions.rs                 # Lock, Shutdown, Media, Clipboard
│   │   │   ├── files.rs                   # File list, upload, download
│   │   │   ├── vault.rs                   # Vault CRUD (uses fortis-core)
│   │   │   ├── auth.rs                    # Biometric sudo challenge relay
│   │   │   ├── backup.rs                  # FortisBackup orchestration
│   │   │   └── terminal.rs                # WebSocket PTY terminal
│   │   └── transport/
│   │       ├── mod.rs
│   │       ├── rest.rs                    # Axum REST API on :8023 (mobile compat)
│   │       ├── grpc.rs                    # Tonic gRPC (P2P sync + biometric auth)
│   │       └── ipc.rs                     # Unix socket (CLI + PAM module)
│   └── systemd/
│       └── fortis-agent.service           # systemctl start fortis-agent
│
├── fortis-core/                           # 🔵 SHARED CRYPTO/DB LIBRARY (Rust crate)
│   ├── Cargo.toml
│   └── src/
│       ├── lib.rs
│       ├── crypto/
│       │   └── mod.rs                     # XChaCha20-Poly1305, Argon2id, key derivation
│       ├── vault/
│       │   └── mod.rs                     # VaultEntry, EntryType, SecretString schemas
│       ├── db/
│       │   └── mod.rs                     # SQLCipher encrypted database (add/get entries)
│       ├── auth/
│       │   └── mod.rs                     # AuthChallenge, AuthResponse, Ed25519 verify
│       ├── sync/
│       │   └── mod.rs                     # Automerge CRDT document logic
│       ├── totp.rs                        # TOTP token generation (RFC 6238)
│       └── password_gen.rs                # Cryptographically secure password generator
│
├── fortis-sync/                           # 🔵 P2P gRPC PROTOCOL (Rust crate)
│   ├── Cargo.toml
│   ├── build.rs
│   ├── proto/
│   │   └── sync.proto                     # SyncService + FortisAuth protobuf definitions
│   └── src/
│       ├── lib.rs
│       ├── server.rs                      # gRPC server implementation
│       ├── client.rs                      # gRPC client implementation
│       └── discovery.rs                   # Tailscale peer discovery
│
├── fortis-cli/                            # 🟣 THIN CLI CLIENT (Rust binary)
│   ├── Cargo.toml
│   └── src/
│       └── main.rs                        # Clap CLI: add, get, sync, pair, generate
│
├── fortis-pam/                            # 🔴 LINUX PAM MODULE (Rust cdylib → .so)
│   ├── Cargo.toml
│   ├── fortis-pam.conf                    # Example /etc/pam.d/sudo config
│   └── src/
│       └── lib.rs                         # pam_sm_authenticate → nonce → IPC → verify
│
├── fortis-bindings/                       # 🟠 UNIFFI BRIDGE (Rust cdylib → Kotlin JNI)
│   ├── Cargo.toml
│   ├── src/
│   │   └── lib.rs                         # UniFFI scaffolding, exports Rust functions
│   └── out/
│       └── uniffi/
│           └── fortis_bindings/
│               └── fortis_bindings.kt     # Auto-generated Kotlin bindings (~37KB)
│
├── fortis-desktop/                        # 🟢 TAURI v2 DESKTOP APP
│   ├── package.json
│   ├── vite.config.ts
│   ├── index.html                         # Multi-pane layout (sidebar + grid)
│   ├── src/
│   │   ├── main.ts                        # Vault rendering, search, Tauri IPC
│   │   └── styles.css                     # Glassmorphism dark theme, micro-animations
│   └── src-tauri/
│       ├── Cargo.toml                     # Depends on fortis-core
│       └── src/
│           └── lib.rs                     # Tauri commands: get_vault_entries, etc.
│
├── FortisMobile/                          # 📱 THE UNIFIED ANDROID APP
│   └── android/
│       ├── build.gradle.kts
│       ├── settings.gradle.kts
│       └── app/
│           ├── build.gradle.kts           # Compose + OkHttp + Biometric + gRPC + JNI
│           └── src/
│               └── main/
│                   ├── AndroidManifest.xml # All services registered
│                   ├── jniLibs/
│                   │   └── arm64-v8a/
│                   │       └── libfortis_bindings.so
│                   └── java/com/example/fortismobile/
│                       ├── MainActivity.kt              # 5-tab nav: Dashboard, Actions, Vault, Files, Workspace
│                       ├── MjpegServer.kt                # Webcam streaming (existing)
│                       ├── FortisWidgetProvider.kt        # Home widget (existing)
│                       ├── FortisNotificationListener.kt  # Notification sync (existing)
│                       ├── data/
│                       │   ├── Device.kt                  # Device model (existing)
│                       │   └── DeviceRepository.kt        # SharedPrefs store (existing)
│                       ├── domain/
│                       │   ├── ActiveDeviceProvider.kt     # Active device state (existing)
│                       │   └── CapabilityManager.kt        # Extended with local capabilities
│                       ├── network/
│                       │   ├── FortisApiClient.kt          # REST → fortis-agent (existing)
│                       │   ├── AgentClient.kt              # Agent helper (existing)
│                       │   ├── BluetoothHidManager.kt      # Bluetooth HID (existing)
│                       │   └── InputSocketClient.kt        # WebSocket input (existing)
│                       ├── encrypt/                        # 🆕 VAULT & AUTH PACKAGE
│                       │   ├── VaultScreen.kt              # Password vault browser
│                       │   ├── AuthenticatorScreen.kt      # Live TOTP codes with countdown
│                       │   ├── PasswordGeneratorScreen.kt  # Secure password generator
│                       │   ├── FortisKeyManager.kt         # Ed25519 in Android HW KeyStore
│                       │   ├── BiometricPromptActivity.kt  # Fingerprint → sign challenge
│                       │   ├── FortisAuthService.kt        # gRPC listener for sudo challenges
│                       │   ├── FortisAutofillService.kt    # OS-level autofill service
│                       │   └── PairDeviceScreen.kt         # 6-digit pairing ceremony
│                       ├── ui/
│                       │   ├── FortisViewModel.kt          # ViewModel (existing)
│                       │   ├── RemoteInputScreen.kt        # Remote trackpad (existing)
│                       │   └── theme/                      # Material 3 monochrome (existing)
│                       └── uniffi/
│                           └── fortis_bindings/
│                               └── fortis_bindings.kt      # UniFFI generated Kotlin
│
├── FortisObserve/                         # 🌐 TRAFFIC ANALYSIS PLATFORM (Next.js)
│   ├── package.json
│   ├── next.config.ts
│   ├── schema.sql
│   └── src/
│       └── app/                           # Next.js App Router pages
│
├── FortisLock/                            # 🔒 CHROME EXTENSION TO LOCK WEBSITES
│   ├── package.json
│   ├── vite.config.ts
│   └── src/                               # React UI for DNS probe error & password input
│
├── FortisBackup/                          # 💾 BACKUP ENGINE (Bash/Systemd)
│   ├── fortis-backup.sh
│   ├── backup.conf
│   └── systemd/
│       └── fortis-backup.service
│
├── [ARCHIVED] FortisAgent/                # ⬛ REPLACED by fortis-agent (Rust)
│
└── README.md                              # Ecosystem overview
```

