# Fortis Ecosystem — Security Model

## Overview

The Fortis Ecosystem is designed with a **zero-trust, defense-in-depth** security model. Every layer of the system applies its own security controls, so a compromise in one layer does not expose the entire system.

---

## Encryption

### Vault Database (Data at Rest)

| Property | Value |
|----------|-------|
| Engine | SQLCipher 4.x |
| Cipher | AES-256-CBC |
| Page Size | 4096 bytes |
| KDF | PBKDF2-HMAC-SHA512 |
| KDF Iterations | 256,000 |
| HMAC | SHA-512 |

The vault database at `~/.local/share/fortis/vault.db` is fully encrypted. Without the master key, the file is indistinguishable from random bytes.

### Secrets in Memory

All sensitive values (`SecretString`) are wrapped in `zeroize::Zeroizing<String>`, which:
- Overwrites the memory with zeros when the value is dropped
- Prevents the compiler from optimizing away the zeroing operation
- Ensures secrets cannot be recovered from a memory dump after use

### Encryption Primitives

| Algorithm | Use Case |
|-----------|----------|
| ChaCha20-Poly1305 | Symmetric encryption for vault field values |
| Argon2id | Master password key derivation |
| AES-256-CBC | SQLCipher database encryption |

---

## Authentication

### REST API (Bearer Token)

All authenticated REST endpoints require:
```
Authorization: Bearer <FORTIS_AGENT_KEY>
```

- The key is a 256-bit random hex string generated via `openssl rand -hex 32`
- Stored only in the process environment — never written to disk
- Comparison uses constant-time equality to prevent timing attacks

### Biometric Challenge-Response (Ed25519)

Used for sudo authentication and browser unlock:

1. **Agent** generates a random challenge (32 bytes)
2. Challenge is sent to the paired mobile device via gRPC
3. **Mobile** prompts the user for biometric verification
4. On success, the mobile app signs the challenge with its Ed25519 private key
5. The signed response is returned to the agent
6. **Agent** verifies the signature against the device's stored public key

| Property | Value |
|----------|-------|
| Algorithm | Ed25519 (RFC 8032) |
| Key Size | 256-bit |
| Library | `ed25519-dalek` |
| Mobile Key Storage | Android Hardware Keystore |
| Key Exportability | Non-exportable (hardware-bound) |

### Device Pairing

Pairing uses a temporary 6-digit numeric code:

1. User runs `fortis-cli pair` on the Linux machine
2. Agent generates a cryptographically random 6-digit code
3. User enters the code + agent IP on the mobile app
4. Mobile sends its Ed25519 public key with the code to `POST /api/v1/pair`
5. Agent validates the code and stores the public key in `~/.config/fortis/paired_devices.json`
6. The code is immediately invalidated (single-use)

---

## Network Security

### Transport Encryption

| Channel | Encryption |
|---------|-----------|
| REST API (local) | Plaintext HTTP on `127.0.0.1` only (localhost) |
| gRPC (remote) | WireGuard encryption via Tailscale VPN |
| IPC Socket | Unix domain socket — no network exposure |

### Network Exposure

| Port | Binding | Access |
|------|---------|--------|
| 8023 (REST) | `0.0.0.0` | Protected by Bearer token |
| 50051 (gRPC) | `0.0.0.0` | Accessible only over Tailscale VPN |
| IPC Socket | `/tmp/fortis_agent.sock` | Owner-only (`chmod 0600`) |

---

## File System Security

| Path | Permissions | Contents |
|------|-------------|----------|
| `~/.local/share/fortis/vault.db` | `0600` | Encrypted vault database |
| `~/.config/fortis/paired_devices.json` | `0600` | Paired device public keys |
| `/tmp/fortis_agent.sock` | `0600` | Unix IPC socket |
| `FORTIS_AGENT_KEY` | Environment only | REST API authentication key |

---

## Threat Model

### What We Protect Against

| Threat | Mitigation |
|--------|-----------|
| **Stolen laptop** | Vault is AES-256 encrypted at rest |
| **Memory forensics** | Zeroize wipes secrets from RAM |
| **Shoulder surfing** | No passwords typed — biometric only |
| **Network sniffing** | gRPC over WireGuard VPN; REST on localhost |
| **Brute-force API** | 256-bit random key = ~2^256 keyspace |
| **Rogue browser extension** | Extension only talks to localhost agent |
| **PAM bypass** | PAM runs as root with restricted socket |

### Assumptions

- The user's Linux machine is not fully compromised (root access by attacker)
- The user's Android device is not rooted or compromised
- Tailscale VPN is properly configured and running
- The master key is strong (not a weak password)

---

## Audit Checklist

- [ ] `FORTIS_AGENT_KEY` is randomly generated (≥32 bytes hex)
- [ ] `vault.db` has `0600` permissions
- [ ] `paired_devices.json` has `0600` permissions
- [ ] IPC socket has `0600` permissions
- [ ] No hardcoded keys or passwords in source code
- [ ] Tailscale is running on both devices
- [ ] PAM config has a fallback in case of agent failure
