# fortis-backend

> The Rust workspace powering the Fortis Ecosystem. Contains the agent daemon, core cryptographic library, gRPC sync protocol, PAM biometric module, and UniFFI mobile bindings.

[![Language](https://img.shields.io/badge/Language-Rust-orange?style=flat-square)](https://www.rust-lang.org)
[![Workspace](https://img.shields.io/badge/Cargo-Workspace-blue?style=flat-square)](https://doc.rust-lang.org/cargo/reference/workspaces.html)

---

## Workspace Members

| Crate | Type | Description |
|---|---|---|
| [fortis-core](./fortis-core/) | Library | Cryptographic primitives, vault data models, SQLCipher database, TOTP, Ed25519 auth |
| [fortis-sync](./fortis-sync/) | Library | gRPC (Tonic/Protobuf) service definitions and P2P sync logic |
| [fortis-agent](./fortis-agent/) | Binary | Unified daemon: REST (Axum), IPC (Unix Socket), gRPC server |
| [fortis-bindings](./fortis-bindings/) | Library (cdylib) | UniFFI (Mozilla) bindings for Android/iOS — exports TOTP and password generation |
| [fortis-pam](./fortis-pam/) | Library (cdylib) | Linux PAM module for biometric sudo via IPC to the agent |

## Build

```bash
# All crates
cargo build --release

# Individual crate
cargo build -p fortis-agent --release

# Run tests
cargo test

# Run the agent in development
FORTIS_AGENT_KEY=dev_key RUST_LOG=debug cargo run -p fortis-agent
```

## Workspace Layout

```
fortis-backend/
├── Cargo.toml              # Workspace manifest
├── Cargo.lock
├── fortis-core/            # Core crypto library (no I/O)
├── fortis-sync/            # gRPC protocol + sync engine
├── fortis-agent/           # Daemon binary
├── fortis-bindings/        # UniFFI mobile bindings
└── fortis-pam/             # PAM shared library
```

See each crate's individual README for detailed documentation.

---

## Maintainer

**Ganesh Angadi** — [@ganeshak11](https://github.com/ganeshak11)
