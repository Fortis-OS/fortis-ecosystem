# fortis-desktop

> A sleek, glassmorphic Tauri desktop application for managing your Fortis vault. Built with Vite + TypeScript on the frontend and Rust on the backend.

## Overview

`fortis-desktop` is a cross-platform desktop application built on [Tauri v2](https://v2.tauri.app/). It provides a beautiful dark-mode UI for:

- Browsing and searching vault entries (passwords, cards, notes)
- Adding new vault entries via an interactive modal
- Viewing system telemetry from `fortis-agent`

The app communicates with the local `fortis-agent` daemon over HTTP, using a securely exposed API key.

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Node.js | 20+ |
| Rust | 1.80+ |
| Tauri CLI | 2.x |
| `fortis-agent` | Running locally on port 8023 |

### System Dependencies (Linux)

```bash
sudo apt install libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev patchelf
```

## Install & Build

```bash
cd fortis-desktop

# Install Node dependencies
npm install

# Development mode (hot-reload)
npm run tauri dev

# Production build
npm run tauri build
```

> **Important:** The Tauri Rust backend has a path dependency on `fortis-core` at `../../fortis-backend/fortis-core`. Ensure both repositories are cloned side-by-side in the same parent directory before building.

## Configuration

The desktop app requires `fortis-agent` to be running. Set the environment variable before launching:

```bash
export FORTIS_AGENT_KEY=your_api_key_here
```

The Tauri Rust backend reads this variable and securely passes it to the frontend via the `get_api_key` IPC command.

## How It Works

1. **Startup** — The frontend calls `invoke("get_api_key")` via Tauri IPC to retrieve the API key from the Rust backend
2. **Fetch Vault** — It uses the API key to call `GET http://127.0.0.1:8023/api/v1/vault/entries`. Alternatively, it can call the `get_vault_entries` Tauri command to query the database directly.
3. **Display** — Entries are rendered in a responsive, glassmorphic card layout
4. **Add Entry** — The "+" button opens a modal where the user can create passwords (optionally using the `generate_password` Tauri command), cards, or secure notes via `POST /api/v1/vault/entries`

## Project Structure

```
fortis-desktop/
├── package.json
├── tsconfig.json
├── vite.config.ts
├── index.html               # Main HTML shell
├── src/
│   ├── main.ts              # Frontend logic (fetch, render, modal)
│   └── styles.css           # Glassmorphic dark-mode styles
└── src-tauri/
    ├── Cargo.toml            # Rust backend dependencies
    ├── tauri.conf.json       # Tauri configuration
    └── src/
        └── lib.rs            # Tauri commands (get_api_key)
```

## Design

The UI uses a monochrome, glassmorphic aesthetic:
- Dark background with `backdrop-filter: blur()`
- Subtle white borders with low opacity
- Smooth hover animations and transitions
- Custom modal with floating card design
