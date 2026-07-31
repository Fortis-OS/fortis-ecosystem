# fortis-agent

> The unified background daemon that powers the entire Fortis Ecosystem. It exposes a REST API, Unix IPC socket, and gRPC server — serving as the single point of contact for all clients (Desktop, Mobile, CLI, and Browser Extension).

## Overview

`fortis-agent` is a multi-transport Rust daemon built on [Tokio](https://tokio.rs/) and [Axum](https://github.com/tokio-rs/axum). It runs as a background service on the user's Linux machine and handles:

- **REST API** (port `8023`) — Authenticated endpoints for the Desktop app, Browser extension, and remote clients
- **Unix IPC Socket** (`/tmp/fortis_agent.sock`) — Fast local communication for the CLI
- **gRPC** (port `50051`) — Bidirectional streaming for mobile biometric authentication

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Rust | 1.80+ |
| `fortis-core` | Built from workspace |
| `fortis-sync` | Built from workspace |

## Build

```bash
# From the workspace root
cargo build -p fortis-agent --release
```

## Configuration

Set the following environment variables before running:

```bash
# Required: API authentication key
export FORTIS_AGENT_KEY=$(openssl rand -hex 32)

# Optional: Log level (default: info)
export RUST_LOG=info
```

> **Note:** The agent will also attempt to load `FORTIS_AGENT_KEY` from `~/.config/fortis/env` if the environment variable is not set.

## Run

```bash
# Run directly
./target/release/fortis-agent

# Or with environment variables inline
FORTIS_AGENT_KEY=my_secret_key RUST_LOG=info ./target/release/fortis-agent
```

## Systemd Service

For production deployment, install as a user-level systemd service:

```bash
mkdir -p ~/.config/systemd/user/
cp systemd/fortis-agent.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now fortis-agent

# Check status
systemctl --user status fortis-agent

# View logs
journalctl --user -u fortis-agent -f
```

## API Endpoints

All authenticated endpoints require:
```
Authorization: Bearer <FORTIS_AGENT_KEY>
```

### Authenticated Routes (`/api/v1/`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/telemetry` | System telemetry (CPU, RAM, storage, temp) |
| `GET` | `/capabilities` | Agent feature flags |
| `POST` | `/clipboard` | Copy text to system clipboard |
| `POST` | `/action` | Execute system actions (lock, suspend, media control) |
| `GET` | `/media` | Get current media player metadata |
| `POST` | `/files/drop` | Upload a file (multipart) |
| `POST` | `/files/list` | List directory contents |
| `GET` | `/files/download?path=` | Download a file |
| `GET` | `/backup/logs` | Retrieve backup logs |
| `GET` | `/vault/entries` | List all vault entries |
| `POST` | `/vault/entries` | Create a new vault entry |

### Public Routes (`/api/v1/`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/pair` | Device pairing (6-digit code + public key) |
| `POST` | `/auth/unlock` | Biometric unlock request (from browser extension) |

## Project Structure

```
fortis-agent/
├── Cargo.toml
├── src/
│   ├── main.rs              # Entry point — spawns all transport servers
│   ├── config.rs            # Configuration loading
│   ├── transport/
│   │   ├── mod.rs
│   │   ├── rest.rs          # Axum REST API server
│   │   ├── ipc.rs           # Unix domain socket server
│   │   └── grpc.rs          # Tonic gRPC server
│   └── modules/
│       ├── mod.rs
│       ├── telemetry.rs     # System metrics collection
│       ├── actions.rs       # System action handlers
│       ├── files.rs         # File management
│       ├── backup.rs        # Backup log retrieval
│       └── auth.rs          # Biometric auth relay & pairing
└── systemd/
    └── fortis-agent.service # Systemd unit file
```

## Connecting from Clients

### From fortis-desktop (Tauri)

The desktop app reads `FORTIS_AGENT_KEY` from the Tauri Rust backend via IPC, then calls:
```typescript
const key = await invoke("get_api_key");
const res = await fetch("http://127.0.0.1:8023/api/v1/vault/entries", {
  headers: { Authorization: `Bearer ${key}` }
});
```

### From fortis-cli

The CLI connects over the Unix domain socket at `/tmp/fortis_agent.sock`:
```bash
echo "GET" | socat - UNIX-CONNECT:/tmp/fortis_agent.sock
```

### From fortis-lock (Chrome Extension)

The browser extension makes unauthenticated POST requests to the biometric unlock endpoint:
```typescript
fetch("http://127.0.0.1:8023/api/v1/auth/unlock", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ domain: "example.com" })
});
```

### From fortis-mobile (Android)

The mobile app connects via gRPC over the Tailscale VPN:
```kotlin
val channel = ManagedChannelBuilder.forAddress(agentIp, 50051).usePlaintext().build()
val stub = FortisAuthGrpc.newBlockingStub(channel)
```
