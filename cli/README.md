# fortis-cli

> The command-line interface for interacting with the Fortis Agent daemon over Unix IPC.

## Overview

`fortis-cli` connects to the running `fortis-agent` via a Unix domain socket (`/tmp/fortis_agent.sock`) and provides terminal access to vault operations, pairing, and sync.

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Rust | 1.80+ |
| `fortis-agent` | Running locally |

## Build

```bash
cargo build -p fortis-cli --release
```

## Usage

```bash
# Add a new vault entry
fortis-cli add "My Secret Note"

# List all vault entries
fortis-cli list

# Start device pairing ceremony
fortis-cli pair

# Trigger P2P sync
fortis-cli sync

# Trigger biometric sudo authentication
fortis-cli auth
```

## How It Works

The CLI sends text commands over the Unix socket at `/tmp/fortis_agent.sock`:

| Command | IPC Message | Description |
|---------|-------------|-------------|
| `add <title>` | `ADD <title>` | Creates a SecureNote entry |
| `list` | `GET` | Lists all vault entries |
| `pair` | `PAIR_START` | Generates a 6-digit pairing code |
| `sync` | `SYNC` | Initiates P2P vault sync |
| `auth` | `AUTH_CHALLENGE` | Triggers biometric challenge to mobile |

## Project Structure

```
fortis-cli/
├── Cargo.toml
└── src/
    └── main.rs              # CLI argument parsing + Unix socket client
```
