# fortis-cli

> Standalone command-line interface for the Fortis Ecosystem. Interacts with the `fortis-agent` daemon over a fast local Unix domain socket.

## Overview

`fortis-cli` provides terminal-based access to the Fortis vault and system capabilities. Instead of implementing crypto and database logic itself, the CLI acts as a thin client, sending commands to the `fortis-agent` IPC socket at `/tmp/fortis_agent.sock`.

This separation of concerns means the CLI can run without unlocking the database itself, and it has no network access.

## Build

```bash
cargo build --release
```

## Usage

```bash
# General format
fortis <COMMAND> [ARGS]
```

### 1. Add Entry

Creates a new "Secure Note" in the vault. The agent encrypts and stores it.

```bash
fortis add "My Secret Note"
```

### 2. Get Entry

Retrieves vault entries by title.

> **Note:** Currently, the underlying agent IPC implementation treats all `GET` commands as a "list all" request, returning all vault entries in a debug format.

```bash
fortis get "My Secret Note"
```

### 3. Generate Password

Generates a cryptographically secure password of the specified length.

```bash
# Default length (16)
fortis generate

# Custom length
fortis generate --length 24
```

### 4. Device Pairing

Initiates the pairing process with a mobile device.

```bash
fortis pair
```

Returns a 6-digit code (e.g., `482-916`) which must be entered into the FortisMobile app along with the agent's Tailscale IP to exchange Ed25519 public keys.

### 5. Trigger Sync

Triggers an immediate peer-to-peer sync over gRPC.

> **Note:** This is currently a stub implementation.

```bash
fortis sync
```

## Security

- **No I/O** — The CLI has no network dependencies and does not read the vault database file.
- **Process Isolation** — All crypto occurs in the background daemon.
- **Socket Permissions** — The IPC socket `/tmp/fortis_agent.sock` is created with `0600` permissions (owner-only access). Only your user can run CLI commands.
