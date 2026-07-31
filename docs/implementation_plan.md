# Phase 6: Biometric Sudo Authentication (Fingerprint-over-Tailscale)

Replace Linux `sudo` password prompts with a cryptographic challenge sent to your Android phone, authenticated by your fingerprint via Android's hardware-backed Keystore. Zero passwords. Zero cloud. Pure P2P.

---

## User Review Required

> [!IMPORTANT]
> **Security Model**: This design uses Ed25519 asymmetric challenge-response. The private key lives exclusively inside your phone's hardware security chip (TEE/StrongBox) and is unlocked only by your biometric. Even if someone steals your phone AND roots it, the key cannot be extracted. The challenge includes a timeout (default 30s) and a single-use nonce, making replay attacks impossible.

> [!WARNING]
> **Root-level change**: Installing a PAM module modifies your system's authentication chain. A bug here could lock you out of `sudo`. We will implement a **failsafe**: the module falls through to the normal password prompt if the phone is unreachable. We will also test with a **secondary PAM config** before touching the real `/etc/pam.d/sudo`.

---

## Open Questions

> [!IMPORTANT]
> **Timeout Duration**: How long should the phone wait before auto-denying? I'm defaulting to **30 seconds**. Would you prefer longer (60s) for cases where the phone might be across the room?

> [!IMPORTANT]
> **Scope**: Should this protect only `sudo`, or also `su`, SSH login, and screen unlock (`login`, `gdm-password`)? PAM makes it trivial to extend to all of these once the module is built. I'd recommend starting with `sudo` only and expanding later.

---

## Cryptographic Protocol Specification

This is the exact byte-level flow that happens every time you type `sudo`:

```
┌─────────────────┐                           ┌─────────────────┐
│  PAM Module     │                           │  Android App    │
│  (Linux)        │                           │  (Phone)        │
└────────┬────────┘                           └────────┬────────┘
         │                                             │
         │  1. Generate 32-byte nonce (OsRng)          │
         │  2. Build ChallengeRequest:                 │
         │     {                                       │
         │       nonce: [u8; 32],                      │
         │       username: "ganeshak11",                │
         │       command: "apt install neovim",         │
         │       hostname: "devbox",                   │
         │       timestamp: 1753456789,                │
         │       ttl_seconds: 30,                      │
         │     }                                       │
         │                                             │
         │───── gRPC over Tailscale (mTLS) ──────────▶│
         │                                             │
         │                            3. Show notification:
         │                               "sudo on devbox:   
         │                                apt install neovim"
         │                                             │
         │                            4. BiometricPrompt
         │                               (Fingerprint scan)
         │                                             │
         │                            5. On success:
         │                               Android KeyStore 
         │                               unlocks Ed25519 
         │                               private key
         │                                             │
         │                            6. Sign SHA-512(
         │                                 nonce ||
         │                                 username ||
         │                                 command ||
         │                                 timestamp
         │                               ) with private key
         │                                             │
         │◀── ChallengeResponse ──────────────────────│
         │     {                                       │
         │       nonce: [u8; 32],  (echo back)         │
         │       signature: [u8; 64],                  │
         │       device_id: "fortis-pixel-7",          │
         │     }                                       │
         │                                             │
         │  7. Verify:                                 │
         │     - nonce matches                         │
         │     - timestamp within TTL                  │
         │     - Ed25519 signature valid               │
         │       against stored public key             │
         │                                             │
         │  8. Return PAM_SUCCESS ✅                    │
         │     (or PAM_AUTH_ERR on failure)             │
```

---

## Proposed Changes

### Component 1: `fortis-pam` (New Crate — The PAM Module)

This is the heart of the system. A Rust shared library (`.so`) that Linux loads during authentication.

#### [NEW] [Cargo.toml](file:///home/ganeshak11/dev/Fortis-Ecosystem/FortisEncrypt/fortis-pam/Cargo.toml)
- New workspace member: `fortis-pam`
- Dependencies: `pam` (PAM bindings), `tokio` (async runtime for gRPC call), `tonic` (gRPC client), `ed25519-dalek` (signature verification), `rand` (nonce), `serde`/`serde_json`
- `crate-type = ["cdylib"]` — compiles to `pam_fortis.so`

#### [NEW] [src/lib.rs](file:///home/ganeshak11/dev/Fortis-Ecosystem/FortisEncrypt/fortis-pam/src/lib.rs)
The PAM module implementation:
1. **`pam_sm_authenticate`** — the function PAM calls when `sudo` needs auth
2. Reads the paired device config from `~/.config/fortis/paired_devices.json`
3. Generates a 32-byte cryptographic nonce
4. Connects to `fortis-daemon` over the Unix socket (`/tmp/fortis_daemon.sock`) with an `AUTH_CHALLENGE` IPC command
5. The daemon relays to the phone over Tailscale gRPC
6. Waits for signed response (with configurable timeout, default 30s)
7. Verifies Ed25519 signature against the stored public key
8. Returns `PAM_SUCCESS` or `PAM_AUTH_ERR`
9. **Failsafe**: If the daemon is unreachable or times out, returns `PAM_IGNORE` to let the next PAM module (normal password) handle it

#### [NEW] [fortis-pam.conf](file:///home/ganeshak11/dev/Fortis-Ecosystem/FortisEncrypt/fortis-pam/fortis-pam.conf)
Example PAM configuration snippet:
```
# /etc/pam.d/sudo — Add BEFORE the existing pam_unix.so line:
auth    sufficient    pam_fortis.so    timeout=30
# Existing fallback:
auth    required      pam_unix.so
```
The `sufficient` keyword means: if biometric succeeds, skip the password. If it fails/times out, fall through to the normal password prompt. You can never be locked out.

---

### Component 2: `fortis-core` (Auth Challenge Module)

#### [NEW] [src/auth/mod.rs](file:///home/ganeshak11/dev/Fortis-Ecosystem/FortisEncrypt/fortis-core/src/auth/mod.rs)
New module in `fortis-core` containing the shared challenge-response types and verification logic, used by both `fortis-pam` and `fortis-daemon`:

```rust
pub struct AuthChallenge {
    pub nonce: [u8; 32],
    pub username: String,
    pub command: String,
    pub hostname: String,
    pub timestamp: u64,
    pub ttl_seconds: u32,
}

pub struct AuthResponse {
    pub nonce: [u8; 32],
    pub signature: [u8; 64],
    pub device_id: String,
}

pub struct PairedDevice {
    pub device_id: String,
    pub public_key: [u8; 32],  // Ed25519 public key
    pub tailscale_ip: String,  // e.g., "100.64.0.2"
    pub name: String,          // e.g., "Pixel 7 Pro"
    pub paired_at: u64,
}
```

- `fn verify_response(challenge, response, public_key) -> bool` — Ed25519 verification
- `fn build_signing_payload(challenge) -> Vec<u8>` — canonical byte representation for signing

#### [MODIFY] [lib.rs](file:///home/ganeshak11/dev/Fortis-Ecosystem/FortisEncrypt/fortis-core/src/lib.rs)
Add `pub mod auth;` to expose the new module.

---

### Component 3: `fortis-daemon` (Auth Relay)

#### [MODIFY] [main.rs](file:///home/ganeshak11/dev/Fortis-Ecosystem/FortisEncrypt/fortis-daemon/src/main.rs)
Add a new IPC command handler alongside the existing `ADD`/`GET`/`SYNC`:

```
AUTH_CHALLENGE <json_payload>
```

When the PAM module sends this command:
1. Deserialize the `AuthChallenge` from JSON
2. Look up the paired device's Tailscale IP from `~/.config/fortis/paired_devices.json`
3. Forward the challenge to the Android app via gRPC over Tailscale (`100.x.x.x:50052`)
4. Wait for the `AuthResponse` (with timeout)
5. Verify the Ed25519 signature locally
6. Return `AUTH_OK` or `AUTH_DENIED` to the PAM module over the Unix socket

---

### Component 4: `fortis-sync` (Auth gRPC Service)

#### [MODIFY] [proto/sync.proto](file:///home/ganeshak11/dev/Fortis-Ecosystem/FortisEncrypt/fortis-sync/proto/sync.proto)
Add new RPC methods to the existing protobuf definition:

```protobuf
service FortisAuth {
    // Sent from Linux -> Phone: "Please authenticate this sudo"
    rpc RequestBiometricAuth (BiometricAuthRequest) returns (BiometricAuthResponse);
}

message BiometricAuthRequest {
    bytes nonce = 1;
    string username = 2;
    string command = 3;
    string hostname = 4;
    uint64 timestamp = 5;
    uint32 ttl_seconds = 6;
}

message BiometricAuthResponse {
    bytes nonce = 1;
    bytes signature = 2;
    string device_id = 3;
    bool approved = 4;
}
```

---

### Component 5: `fortis-android` (Biometric Prompt + Hardware Keystore)

#### [NEW] [FortisAuthService.kt](file:///home/ganeshak11/dev/Fortis-Ecosystem/FortisEncrypt/fortis-android/app/src/main/java/com/fortis/encrypt/FortisAuthService.kt)
A foreground service that listens for incoming gRPC auth challenges:
1. Runs a gRPC server on port `50052`, bound to the Tailscale interface
2. When a `BiometricAuthRequest` arrives:
   - Shows a high-priority notification: *"sudo on devbox: apt install neovim"*
   - Launches `BiometricPromptActivity`

#### [NEW] [BiometricPromptActivity.kt](file:///home/ganeshak11/dev/Fortis-Ecosystem/FortisEncrypt/fortis-android/app/src/main/java/com/fortis/encrypt/BiometricPromptActivity.kt)
A transparent Activity that shows the Android `BiometricPrompt`:
1. Displays: **"Approve sudo?"** with the command text
2. On fingerprint success:
   - Retrieves the Ed25519 private key from Android `KeyStore` (hardware-backed, `setUserAuthenticationRequired(true)`)
   - Signs `SHA-512(nonce || username || command || timestamp)` 
   - Returns `BiometricAuthResponse { approved: true, signature: [...] }`
3. On cancel/failure:
   - Returns `BiometricAuthResponse { approved: false }`

#### [NEW] [FortisKeyManager.kt](file:///home/ganeshak11/dev/Fortis-Ecosystem/FortisEncrypt/fortis-android/app/src/main/java/com/fortis/encrypt/FortisKeyManager.kt)
Manages the Ed25519 keypair in Android's hardware Keystore:
- `generateKeyPair()` — creates an Ed25519 keypair inside the TEE/StrongBox chip, with `setUserAuthenticationRequired(true)` so the key can only be used after biometric auth
- `getPublicKey()` — exports the public key bytes (this gets sent to Linux during pairing)
- `sign(payload: ByteArray)` — signs data with the private key (only works after `BiometricPrompt` success)

#### [MODIFY] [AndroidManifest.xml](file:///home/ganeshak11/dev/Fortis-Ecosystem/FortisEncrypt/fortis-android/app/src/main/AndroidManifest.xml)
- Add `<uses-permission android:name="android.permission.USE_BIOMETRIC" />`
- Register `FortisAuthService` as a foreground service
- Register `BiometricPromptActivity` with `android:theme="@android:style/Theme.Translucent.NoTitleBar"`

---

### Component 6: `fortis-cli` (Pairing Ceremony)

#### [MODIFY] [main.rs](file:///home/ganeshak11/dev/Fortis-Ecosystem/FortisEncrypt/fortis-cli/src/main.rs)
Add a new CLI subcommand: `fortis pair`

```
$ fortis pair
```

The pairing flow:
1. Linux generates a 6-digit pairing code and displays it in the terminal
2. User opens the Fortis Android app → taps "Pair Device"
3. User enters the 6-digit code on the phone
4. Phone generates an Ed25519 keypair in the hardware Keystore
5. Phone sends its **public key** + **device_id** + **Tailscale IP** to the daemon over gRPC
6. Daemon verifies the pairing code matches
7. Daemon saves `PairedDevice { public_key, tailscale_ip, device_id }` to `~/.config/fortis/paired_devices.json`
8. Terminal displays: *"✅ Paired with Pixel 7 Pro (100.64.0.2)"*

From this point on, `sudo` uses your fingerprint.

---

## Full Ecosystem Architecture (After Phase 6)

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        FORTIS ECOSYSTEM                                 │
│                                                                         │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐                  │
│  │ fortis-core  │  │ fortis-sync  │  │ fortis-pam    │  ◄── NEW!       │
│  │ Crypto/DB/   │  │ gRPC/P2P/    │  │ PAM Module    │                  │
│  │ Auth         │  │ Tailscale    │  │ (.so plugin)  │                  │
│  └──────┬───────┘  └──────┬───────┘  └───────┬───────┘                  │
│         │                 │                  │                           │
│  ┌──────┴────────────────┴──────────────────┴────────┐                  │
│  │                  fortis-daemon                     │                  │
│  │  Unix Socket IPC + Auth Relay + gRPC Bridge        │                  │
│  └────────┬──────────────┬───────────────┬────────────┘                  │
│           │              │               │                               │
│  ┌────────┴───┐  ┌───────┴───┐  ┌───────┴──────────────┐               │
│  │ fortis-cli │  │ fortis-   │  │ fortis-android        │               │
│  │ Terminal   │  │ desktop   │  │ Autofill + Biometric  │  ◄── UPDATED │
│  │ + Pairing  │  │ Tauri UI  │  │ Auth + TOTP           │               │
│  └────────────┘  └───────────┘  └──────────────────────┘               │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Implementation Order

| Step | Crate | Task | Risk |
|------|-------|------|------|
| 1 | `fortis-core` | Add `auth` module (challenge/response types, Ed25519 verify) | Low |
| 2 | `fortis-sync` | Add `FortisAuth` gRPC service to protobuf | Low |
| 3 | `fortis-daemon` | Add `AUTH_CHALLENGE` IPC handler + gRPC relay | Medium |
| 4 | `fortis-pam` | Build the PAM `.so` module | Medium |
| 5 | `fortis-android` | `FortisKeyManager` (KeyStore), `BiometricPromptActivity`, `FortisAuthService` (gRPC server) | Medium |
| 6 | `fortis-cli` | `fortis pair` pairing ceremony | Low |
| 7 | Integration | End-to-end test: `sudo` → fingerprint → approved | High |

---

## Verification Plan

### Automated Tests
```bash
# Unit test the challenge-response crypto
cargo test -p fortis-core --lib auth

# Build the PAM module
cargo build -p fortis-pam --release
# Verify it's a valid shared library
file target/release/libpam_fortis.so
```

### Manual Verification
1. **Safe testing**: Create a test PAM config at `/etc/pam.d/fortis-test` instead of modifying the real `sudo` config
2. Use `pamtester fortis-test ganeshak11 authenticate` to trigger the module without risking lockout
3. Verify the phone receives the challenge notification
4. Verify fingerprint approval causes `pamtester` to return success
5. Verify timeout causes graceful fallback to password
6. Only after all tests pass, add `pam_fortis.so` to `/etc/pam.d/sudo`
