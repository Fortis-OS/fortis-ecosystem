# Fortis Ecosystem

> A unified, privacy-first security platform. Password manager, authenticator, biometric sudo, encrypted backup, and browser privacy firewall — all in one cohesive system.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Fortis Ecosystem                             │
│                                                                     │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────────┐     │
│  │ fortis-lock  │   │fortis-desktop│   │ fortis-mobile        │     │
│  │ (Chrome Ext) │   │ (Tauri App)  │   │ (Android/Compose)    │     │
│  └──────┬───────┘   └──────┬───────┘   └──────────┬───────────┘     │
│         │ HTTP             │ HTTP/IPC             │ gRPC            │
│         ▼                  ▼                      ▼                 │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    fortis-agent (Rust Daemon)                │   │
│  │  REST API (:8023)  │  Unix IPC Socket  │  gRPC (:50051)      │   │
│  └──────────┬─────────┴────────┬──────────┴────────┬────────────┘   │
│             │                  │                   │                │
│  ┌──────────▼──────┐ ┌────────▼────────┐ ┌────────▼────────┐      │
│  │  fortis-core    │ │  fortis-sync    │ │  fortis-pam     │      │
│  │  (Crypto/Vault) │ │  (gRPC Proto)  │ │  (PAM Module)   │      │
│  └─────────────────┘ └────────────────┘ └─────────────────┘      │
│                                                                     │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────────┐    │
│  │ fortis-cli    │   │ fortis-backup │   │ fortis-observe       │    │
│  │ (Terminal UI) │   │ (Bash/Systemd)│   │ (Next.js - Isolated) │    │
│  └──────────────┘   └──────────────┘   └──────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

## Modules

| Module | Language | Description |
|--------|----------|-------------|
| [fortis-agent](./fortis-agent/) | Rust | Unified background daemon — REST, IPC, and gRPC server |
| [fortis-core](./fortis-core/) | Rust | Core cryptographic library — vault encryption, TOTP, Ed25519, password generation |
| [fortis-sync](./fortis-sync/) | Rust/Protobuf | gRPC protocol definitions and sync logic |
| [fortis-cli](./fortis-cli/) | Rust | Command-line interface for interacting with the daemon |
| [fortis-desktop](./fortis-desktop/) | TypeScript/Rust | Tauri desktop application with glassmorphic UI |
| [fortis-mobile](./fortis-mobile/) | Kotlin | Android app (Jetpack Compose) — vault, authenticator, biometric auth |
| [fortis-lock](./fortis-lock/) | TypeScript/React | Chrome extension — browser privacy firewall with biometric unlock |
| [fortis-pam](./fortis-pam/) | C/Rust | Linux PAM module for biometric sudo authentication |
| [fortis-backup](./fortis-backup/) | Bash | Encrypted incremental backup system with systemd integration |
| [fortis-observe](./fortis-observe/) | TypeScript/Next.js | Production traffic analysis platform (isolated, standalone) |
| [fortis-daemon](./fortis-daemon/) | Rust | Legacy daemon module (superseded by fortis-agent) |
| [fortis-bindings](./fortis-bindings/) | Rust | FFI bindings for cross-platform integration |

## Quick Start

### Prerequisites

| Requirement | Version | Purpose |
|-------------|---------|---------|
| Rust | 1.80+ | Core daemon, CLI, PAM module |
| Node.js | 20+ | Desktop app, Chrome extension |
| Android Studio | Latest | Mobile app |
| SQLCipher | 4.x | Encrypted vault database |

### 1. Clone and Build

```bash
git clone https://github.com/yourusername/Fortis-Ecosystem.git
cd Fortis-Ecosystem

# Build all Rust crates
cargo build --release

# Set the API key
export FORTIS_AGENT_KEY=$(openssl rand -hex 32)
```

### 2. Start the Agent

```bash
# Run the daemon
RUST_LOG=info ./target/release/fortis-agent

# Or install as a systemd service
sudo cp fortis-agent/systemd/fortis-agent.service /etc/systemd/system/
sudo systemctl enable --now fortis-agent
```

### 3. Use the CLI

```bash
./target/release/fortis-cli add "My Secret Note"
./target/release/fortis-cli list
./target/release/fortis-cli pair
```

### 4. Launch the Desktop App

```bash
cd fortis-desktop
npm install
npm run tauri dev
```

### 5. Load the Browser Extension

```bash
cd fortis-lock
npm install
npm run build
# Open chrome://extensions → Enable Developer Mode → Load Unpacked → select fortis-lock/dist
```

## Security Model

- **AES-256 Vault Encryption** via SQLCipher with Argon2 key derivation
- **Ed25519 Signatures** for biometric challenge-response authentication
- **Zero-Trust Pairing** — devices exchange public keys over a local network with a 6-digit OTP
- **Zeroize Memory** — all secrets are wiped from RAM after use via the `zeroize` crate
- **Restrictive File Permissions** — `0600` on vault database and config files
- **Bearer Token Auth** — all REST API calls require `Authorization: Bearer <FORTIS_AGENT_KEY>`

## Documentation

Full documentation is available in the [docs/](./docs/) directory:

- [Installation Guide](./docs/installation/README.md)
- [API Reference](./docs/api/README.md)
- [Architecture Guide](./docs/architecture/README.md)
- [Security Model](./docs/security/README.md)
- [Development Guide](./docs/development/README.md)
- [Deployment Guide](./docs/deployment/README.md)
- [Troubleshooting](./docs/troubleshooting/README.md)

## Project Structure

```
Fortis-Ecosystem/
├── Cargo.toml              # Rust workspace manifest
├── Cargo.lock
├── README.md               # This file
├── docs/                   # Comprehensive documentation
├── fortis-agent/           # Unified Rust daemon
├── fortis-core/            # Core crypto library
├── fortis-sync/            # gRPC protocol & sync
├── fortis-cli/             # Command-line interface
├── fortis-desktop/         # Tauri desktop app
├── fortis-mobile/          # Android Kotlin app
├── fortis-lock/            # Chrome privacy extension
├── fortis-pam/             # Linux PAM module
├── fortis-backup/          # Encrypted backup scripts
├── fortis-observe/         # Traffic analysis (isolated)
├── fortis-bindings/        # FFI bindings
├── fortis-daemon/          # Legacy daemon
├── run_e2e_tests.sh        # E2E test script
└── archives/               # Archived legacy code
```

## License

MIT License — See individual module directories for details.
