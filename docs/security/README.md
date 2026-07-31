# Fortis Ecosystem — Security Model

## Overview

The Fortis Ecosystem is designed with a **defense-in-depth** security model. Every layer applies its own security controls, so a compromise in one layer does not expose the entire system.

---

## Encryption

### Vault Database (Data at Rest)

The vault database at `~/.local/share/fortis/vault.db` is encrypted by SQLCipher.

| Property | Value | Source |
|---|---|---|
| Engine | SQLCipher 4.x | `rusqlite` with `bundled-sqlcipher-vendored-openssl` |
| Cipher | AES-256-CBC (page-level) | SQLCipher default |
| Page Size | 4096 bytes | SQLCipher default |
| KDF | PBKDF2-HMAC-SHA512 | SQLCipher internal |
| KDF Iterations | 256,000 | SQLCipher default |
| HMAC | SHA-512 per page | SQLCipher default |

Without the database key, the file is indistinguishable from random bytes.

> **Current implementation note:** The database key is hardcoded as `"dev_master_key"` in both `fortis-agent` (`ipc.rs`) and `fortis-desktop` (`lib.rs`). This will be replaced by the Argon2id-derived key from the user's master password.

### Master Key Derivation

| Property | Value | Source |
|---|---|---|
| Algorithm | Argon2id | `fortis-core/src/crypto/mod.rs` |
| Memory | 65,536 KiB (64 MB) | `m_cost` parameter |
| Iterations | 3 passes | `t_cost` parameter |
| Parallelism | 4 lanes | `p_cost` parameter |
| Output | 32 bytes | `output_len` parameter |
| Salt | 16 bytes random | `OsRng` via `rand` crate |

The master password → Argon2id → 32-byte key, which is then used as the SQLCipher database key and for field-level encryption.

### Field-Level Encryption

Individual vault entry fields are encrypted with XChaCha20-Poly1305.

| Property | Value | Source |
|---|---|---|
| Algorithm | XChaCha20-Poly1305 | `chacha20poly1305` crate |
| Nonce | 24 bytes random | `XChaCha20Poly1305::generate_nonce(OsRng)` |
| Key | 32 bytes (derived from Argon2id) | `generate_vault_key()` |

### Secrets in Memory

All sensitive values use `SecretString`, which wraps `zeroize::Zeroizing<String>`:
- Overwrites memory with zeros when the value is dropped
- Prevents compiler optimization from removing the zeroing
- Ensures secrets cannot be recovered from a memory dump after use

---

## Authentication

### REST API (Bearer Token)

All authenticated REST endpoints require:
```
Authorization: Bearer <FORTIS_AGENT_KEY>
```

| Property | Detail |
|---|---|
| Key generation | `openssl rand -hex 32` (256-bit) |
| Storage | Environment variable `FORTIS_AGENT_KEY` |
| Fallback | Can also be read from `~/.config/fortis/env` file (used by desktop app) |
| Comparison | Standard string equality (`==`) |

> **⚠️ Security note:** The bearer token comparison uses standard string equality, not constant-time comparison. This is acceptable for a localhost-only threat model where the attacker does not have network timing side-channel access, but should be addressed if the agent is exposed to untrusted networks.

### Biometric Challenge-Response (Ed25519)

Used for sudo authentication and browser unlock:

1. **Agent** generates a random 32-byte nonce + timestamp
2. Challenge is sent to the paired mobile device via gRPC (`BiometricAuthRequest`)
3. **Mobile** prompts the user for biometric verification (Android `BiometricPrompt`)
4. On success, the mobile app signs the nonce with its Ed25519 private key
5. The hex-encoded signature is returned in `BiometricAuthResponse`
6. **Agent** verifies the signature against the device's stored public key

| Property | Value |
|---|---|
| Algorithm | Ed25519 (RFC 8032) |
| Key Size | 256-bit |
| Library | `ed25519-dalek` (Rust), Android Keystore (mobile) |
| Mobile Key Storage | Android Hardware Keystore |
| Key Exportability | Non-exportable (hardware-bound) |

### Device Pairing

Pairing uses a temporary 6-digit numeric code:

1. User runs `fortis-cli pair` → CLI sends `PAIR_START` over IPC
2. Agent generates a cryptographically random 6-digit code (format: `XXX-XXX`)
3. Agent stores the code and a pending state in memory (`PairingState`)
4. User enters the code + agent's Tailscale IP on the mobile app
5. Mobile sends its Ed25519 public key + the code to `POST /api/v1/pair`
6. Agent validates the code and stores the public key in `~/.config/fortis/paired_devices.json`
7. The code is immediately invalidated (single-use)

---

## Network Security

### Transport Encryption

| Channel | Encryption | Binding |
|---|---|---|
| REST API | Plaintext HTTP | `0.0.0.0:8023` |
| gRPC | WireGuard (via Tailscale VPN) | `0.0.0.0:50051` |
| IPC Socket | N/A (local only) | `/tmp/fortis_agent.sock` |

> **⚠️ Security note:** The REST API binds to `0.0.0.0`, making it accessible from the network. It is protected only by the Bearer token. In a hostile network environment, consider firewall rules to restrict port 8023 to `127.0.0.1` and Tailscale IP ranges.

### Network Exposure

| Port | Binding | Access Control |
|---|---|---|
| 8023 (REST) | `0.0.0.0` | Bearer token required for authenticated endpoints |
| 50051 (gRPC) | `0.0.0.0` | Accessible only over Tailscale VPN (stub currently) |
| IPC Socket | `/tmp/fortis_agent.sock` | Owner-only (`chmod 0600`) |

---

## File System Security

| Path | Permissions | Contents |
|---|---|---|
| `~/.local/share/fortis/vault.db` | `0600` | SQLCipher-encrypted vault database |
| `~/.config/fortis/paired_devices.json` | `0600` | Paired device Ed25519 public keys |
| `~/.config/fortis/env` | `0600` | Optional: `FORTIS_AGENT_KEY=<value>` file |
| `/tmp/fortis_agent.sock` | `0600` | Unix IPC socket |
| `/mnt/FortisVault/` | `0700` | Backup drive (enforced every run by `fortis-backup`) |
| `backup.conf` | `0600` | Backup configuration (script refuses to run if world-writable) |

---

## Threat Model

### What We Protect Against

| Threat | Mitigation |
|---|---|
| **Stolen laptop** | Vault is AES-256 encrypted at rest (SQLCipher) |
| **Memory forensics** | `zeroize` wipes secrets from RAM on drop |
| **Shoulder surfing** | No passwords typed — biometric push-to-unlock |
| **Network sniffing (remote)** | gRPC over WireGuard VPN |
| **Brute-force API** | 256-bit random key = ~2^256 keyspace |
| **Rogue browser extension** | Extension only talks to localhost agent via public endpoints |
| **PAM bypass** | Falls back to standard password auth on any failure |
| **Backup tampering** | `chmod 700` on backup root, config ownership validation |
| **Config poisoning** | `fortis-backup` refuses to run if config is owned by another user or is writable |

### Assumptions

- The user's Linux machine is not fully compromised (no attacker root access)
- The user's Android device is not rooted or compromised
- Tailscale VPN is properly configured and running
- The master password is strong (not a dictionary word)
- Development hardcoded keys are replaced before production deployment

---

## Audit Checklist

- [ ] `FORTIS_AGENT_KEY` is randomly generated (`openssl rand -hex 32`)
- [ ] `vault.db` has `0600` permissions
- [ ] `paired_devices.json` has `0600` permissions
- [ ] `backup.conf` has `0600` permissions and is owned by the current user
- [ ] IPC socket has `0600` permissions
- [ ] No hardcoded keys in source code (currently using `dev_master_key` — replace before production)
- [ ] Tailscale is running on both devices
- [ ] PAM config has a fallback in case of agent failure
- [ ] REST API is firewalled to localhost + Tailscale if on an untrusted network
