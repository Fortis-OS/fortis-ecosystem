# Fortis Ecosystem — Architecture Guide

## System Architecture

The Fortis Ecosystem follows a **hub-and-spoke architecture** with `fortis-agent` as the central hub and all other components as spokes that communicate through it.

```
                         ┌──────────────────────┐
                         │    fortis-mobile      │
                         │   (Android/Compose)   │
                         └──────────┬───────────┘
                                    │ gRPC (:50051)
                                    │ via Tailscale VPN
                                    │
┌──────────────┐   HTTP    ┌────────▼────────────────────────┐   IPC
│ fortis-lock   │─────────▶│          fortis-agent           │◀──────── fortis-cli
│ (Chrome Ext)  │ :8023    │                                 │  Unix
└──────────────┘           │  ┌─────────┬─────────┬────────┐ │  Socket
                           │  │  REST   │  IPC    │  gRPC  │ │
┌──────────────┐   HTTP    │  │  Server │ Server  │ Server │ │
│ fortis-desktop│─────────▶│  └────┬────┴────┬────┴────┬───┘ │
│ (Tauri App)   │ :8023    │       │         │         │     │
└──────────────┘           │  ┌────▼─────────▼─────────▼───┐ │
                           │  │        Module Layer         │ │
                           │  │ telemetry│actions│files│auth│ │
                           │  └────────────────┬───────────┘ │
                           │                   │             │
                           │  ┌────────────────▼───────────┐ │
                           │  │        fortis-core          │ │
                           │  │   vault│db│crypto│auth│totp │ │
                           │  └────────────────────────────┘ │
                           └─────────────────────────────────┘
                                         │
                              ┌──────────▼──────────┐
                              │  ~/.local/share/     │
                              │  fortis/vault.db     │
                              │  (SQLCipher AES-256) │
                              └─────────────────────┘
```

## Component Layers

### Layer 1: Core Library (`fortis-core`)

The foundation. Contains no I/O or networking — pure cryptographic logic and data models.

| Module | Responsibility |
|--------|---------------|
| `vault/` | Data models: `VaultEntry`, `EntryType`, `SecretString` |
| `db/` | SQLCipher database: open, init_schema, add_entry, get_entries |
| `auth/` | Ed25519 key generation, signing, verification |
| `crypto/` | ChaCha20-Poly1305 encryption primitives |
| `totp.rs` | RFC 6238 TOTP code generation |
| `password_gen.rs` | Secure random password generation |

### Layer 2: Transport Protocol (`fortis-sync`)

Defines the gRPC protocol used between the agent and mobile devices.

| File | Responsibility |
|------|---------------|
| `proto/sync.proto` | Protobuf service and message definitions |
| `build.rs` | Compiles `.proto` to Rust via `tonic-build` |
| `src/lib.rs` | Re-exports generated client/server stubs |

### Layer 3: Agent Daemon (`fortis-agent`)

The central hub. Runs as a background process and exposes three transport interfaces.

| Transport | Port/Path | Protocol | Clients |
|-----------|-----------|----------|---------|
| REST | `:8023` | HTTP/JSON | Desktop, Browser Extension |
| IPC | `/tmp/fortis_agent.sock` | Unix Socket | CLI |
| gRPC | `:50051` | Protobuf | Mobile |

### Layer 4: Client Applications

| Client | Platform | Transport | Auth Method |
|--------|----------|-----------|-------------|
| `fortis-desktop` | Linux/macOS/Windows | REST (HTTP) | Bearer Token |
| `fortis-lock` | Chrome | REST (HTTP) | Unauthenticated (localhost only) |
| `fortis-cli` | Terminal | IPC (Unix Socket) | Process-level (socket permissions) |
| `fortis-mobile` | Android | gRPC | Ed25519 Signature |

### Layer 5: System Integration

| Component | Role |
|-----------|------|
| `fortis-pam` | Plugs into Linux PAM for biometric sudo |
| `fortis-backup` | Encrypted incremental backups via cron/systemd |

---

## Data Flow Examples

### Adding a Password (Desktop → Agent → Database)

```
fortis-desktop                   fortis-agent                     fortis-core
      │                               │                               │
      │  POST /vault/entries           │                               │
      │  { title, username, password } │                               │
      │──────────────────────────────▶│                               │
      │                               │  db.add_entry(&entry)         │
      │                               │──────────────────────────────▶│
      │                               │                               │  INSERT INTO entries
      │                               │           Ok(())              │
      │                               │◀──────────────────────────────│
      │  200 { "status": "success" }  │                               │
      │◀──────────────────────────────│                               │
```

### Biometric Sudo (PAM → Agent → Mobile → Agent → PAM)

```
sudo          fortis-pam       fortis-agent      fortis-mobile
  │               │                 │                  │
  │  auth req     │                 │                  │
  │──────────────▶│                 │                  │
  │               │ AUTH_CHALLENGE  │                  │
  │               │────────────────▶│                  │
  │               │                 │  gRPC challenge  │
  │               │                 │─────────────────▶│
  │               │                 │                  │  [fingerprint scan]
  │               │                 │  gRPC signature  │
  │               │                 │◀─────────────────│
  │               │                 │  verify(sig, pk) │
  │               │  "Success"      │                  │
  │               │◀────────────────│                  │
  │  PAM_SUCCESS  │                 │                  │
  │◀──────────────│                 │                  │
```

### Browser Unlock (FortisLock → Agent → Mobile → Agent → FortisLock)

```
fortis-lock                fortis-agent             fortis-mobile
      │                         │                        │
      │  POST /auth/unlock      │                        │
      │  { domain: "x.com" }    │                        │
      │────────────────────────▶│                        │
      │                         │  gRPC biometric push   │
      │                         │───────────────────────▶│
      │                         │                        │ [fingerprint]
      │                         │  gRPC Ed25519 sig      │
      │                         │◀───────────────────────│
      │  200 { status: success }│                        │
      │◀────────────────────────│                        │
      │                         │                        │
      │  [redirect to website]  │                        │
```

---

## Security Boundaries

| Boundary | Protection |
|----------|-----------|
| Vault at rest | AES-256 via SQLCipher + Argon2 KDF (256k iterations) |
| Vault in memory | `zeroize` crate wipes secrets on drop |
| REST API | Bearer token authentication |
| IPC socket | `chmod 0600` — owner-only access |
| gRPC channel | Tailscale WireGuard VPN encryption |
| Mobile keys | Android Hardware Keystore (never exported) |
| Config files | `chmod 0600` on `paired_devices.json` |
| PAM module | Runs as root, communicates via restricted Unix socket |

---

## Technology Stack

| Category | Technology |
|----------|-----------|
| Core Language | Rust |
| HTTP Framework | Axum |
| gRPC | Tonic + Protobuf |
| Database | SQLCipher (rusqlite) |
| Desktop | Tauri v2 + Vite + TypeScript |
| Mobile | Kotlin + Jetpack Compose |
| Browser Extension | React + Vite + Chrome Manifest V3 |
| Cryptography | ed25519-dalek, ChaCha20-Poly1305, Argon2 |
| Sync | Automerge CRDT (WIP) |
| VPN | Tailscale (WireGuard) |
