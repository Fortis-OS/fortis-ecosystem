# Fortis Ecosystem — Installation Guide

This guide walks you through installing and configuring every component of the Fortis Ecosystem from scratch.

---

## Table of Contents

1. [System Requirements](#system-requirements)
2. [Install Rust Toolchain](#1-install-rust-toolchain)
3. [Install Node.js](#2-install-nodejs)
4. [Install System Dependencies](#3-install-system-dependencies)
5. [Clone All Repositories](#4-clone-all-repositories)
6. [Build the Backend](#5-build-the-backend)
7. [Build the CLI](#6-build-the-cli)
8. [Set Up the Agent](#7-set-up-the-agent)
9. [Set Up the Desktop App](#8-set-up-the-desktop-app)
10. [Set Up the Browser Extension](#9-set-up-the-browser-extension)
11. [Set Up the Mobile App](#10-set-up-the-mobile-app)
12. [Install the PAM Module](#11-install-the-pam-module)
13. [Set Up Backups](#12-set-up-backups)
14. [Verify Installation](#13-verify-installation)

---

## System Requirements

| Component | Minimum | Recommended |
|---|---|---|
| **OS** | Ubuntu 22.04 / Fedora 38 | Ubuntu 24.04 LTS |
| **CPU** | 2 cores | 4+ cores |
| **RAM** | 4 GB | 8 GB+ |
| **Disk** | 2 GB free | 5 GB+ free |
| **Rust** | 1.80 (edition 2024) | Latest stable |
| **Node.js** | 20.x | 22.x LTS |
| **Android Studio** | Latest | Latest |
| **Chrome** | 120+ (Manifest V3) | Latest |

---

## 1. Install Rust Toolchain

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env

# Verify
rustc --version   # Expected: 1.80+
cargo --version
```

## 2. Install Node.js

```bash
# Using nvm (recommended)
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
source ~/.bashrc
nvm install 22
nvm use 22

# Verify
node --version   # Expected: v22.x
npm --version
```

## 3. Install System Dependencies

### Ubuntu / Debian

```bash
sudo apt update && sudo apt install -y \
    build-essential \
    pkg-config \
    libssl-dev \
    libsqlite3-dev \
    libpam0g-dev \
    libwebkit2gtk-4.1-dev \
    libappindicator3-dev \
    librsvg2-dev \
    patchelf \
    protobuf-compiler \
    wl-clipboard \
    playerctl \
    socat \
    curl \
    git
```

### Fedora

```bash
sudo dnf install -y \
    gcc gcc-c++ \
    pkg-config \
    openssl-devel \
    sqlite-devel \
    pam-devel \
    webkit2gtk4.1-devel \
    libappindicator-gtk3-devel \
    librsvg2-devel \
    patchelf \
    protobuf-compiler \
    wl-clipboard \
    playerctl \
    socat \
    curl \
    git
```

> **Note:** `wl-clipboard` is for Wayland clipboard support (used by `/clipboard` endpoint). If running X11, install `xclip` instead. `playerctl` is for media control. `socat` is for IPC debugging.

## 4. Clone All Repositories

```bash
mkdir -p ~/dev/Fortis-Ecosystem && cd ~/dev/Fortis-Ecosystem

# Backend workspace (agent, core, sync, bindings, pam)
git clone git@github.com:Fortis-Ecosystem/fortis-backend.git

# Client applications
git clone git@github.com:Fortis-Ecosystem/fortis-cli.git
git clone git@github.com:Fortis-Ecosystem/fortis-desktop.git
git clone git@github.com:Fortis-Ecosystem/fortis-lock.git
git clone git@github.com:Fortis-Ecosystem/fortis-mobile.git

# Standalone services
git clone git@github.com:Fortis-Ecosystem/fortis-backup.git
git clone git@github.com:Fortis-Ecosystem/fortis-observe.git

# Documentation
git clone git@github.com:Fortis-Ecosystem/docs.git
```

## 5. Build the Backend

```bash
cd ~/dev/Fortis-Ecosystem/fortis-backend

# Build all workspace crates in release mode
cargo build --release

# This builds:
# - fortis-core     (crypto library)
# - fortis-sync     (gRPC proto definitions)
# - fortis-agent    (daemon binary)
# - fortis-bindings (UniFFI shared library)
# - fortis-pam      (PAM shared library)
```

## 6. Build the CLI

The CLI is a standalone project, not part of the backend workspace.

```bash
cd ~/dev/Fortis-Ecosystem/fortis-cli
cargo build --release
```

## 7. Set Up the Agent

```bash
# Create data directories
mkdir -p ~/.local/share/fortis
mkdir -p ~/.config/fortis
chmod 700 ~/.local/share/fortis ~/.config/fortis

# Generate a secure API key
export FORTIS_AGENT_KEY=$(openssl rand -hex 32)

# Store the key (for desktop app and future use)
echo "FORTIS_AGENT_KEY=$FORTIS_AGENT_KEY" > ~/.config/fortis/env
chmod 600 ~/.config/fortis/env

# Persist in shell
echo "export FORTIS_AGENT_KEY=$FORTIS_AGENT_KEY" >> ~/.bashrc

# Test run
cd ~/dev/Fortis-Ecosystem/fortis-backend
RUST_LOG=info ./target/release/fortis-agent
# Expected: "REST server started on 0.0.0.0:8023"
# Press Ctrl+C to stop

# Optional: Install as systemd service
# See the Deployment Guide for full instructions
```

## 8. Set Up the Desktop App

```bash
cd ~/dev/Fortis-Ecosystem/fortis-desktop
npm install
npm run tauri dev    # Development (hot-reload)
npm run tauri build  # Production (.deb / AppImage)
```

> **Important:** The Tauri backend depends on `fortis-core` via `path = "../../fortis-backend/fortis-core"`. Both repos must be in the same parent directory.

## 9. Set Up the Browser Extension

```bash
cd ~/dev/Fortis-Ecosystem/fortis-lock
npm install
npm run build

# Load in Chrome:
# 1. Open chrome://extensions
# 2. Enable Developer Mode (toggle in top-right)
# 3. Click "Load Unpacked"
# 4. Select the fortis-lock/dist folder
```

## 10. Set Up the Mobile App

1. Install [Android Studio](https://developer.android.com/studio)
2. Install [Tailscale](https://tailscale.com/) on both your Linux machine and Android phone
3. Open `fortis-mobile/android` in Android Studio
4. Build and install the app on your device
5. Pair with the agent:
   ```bash
   cd ~/dev/Fortis-Ecosystem/fortis-cli
   ./target/release/fortis-cli pair
   # Note the 6-digit code (format: XXX-XXX)
   # Enter it in the mobile app along with your Tailscale IP
   ```

## 11. Install the PAM Module

> **⚠️ Caution**: Keep a root terminal open while configuring PAM to avoid lockout.

```bash
cd ~/dev/Fortis-Ecosystem/fortis-backend

# Copy the PAM library
sudo cp target/release/libfortis_pam.so /lib/security/pam_fortis.so

# Backup PAM config FIRST
sudo cp /etc/pam.d/sudo /etc/pam.d/sudo.backup

# Add biometric auth to sudo
# Add this line BEFORE other auth lines in /etc/pam.d/sudo:
sudo nano /etc/pam.d/sudo
# Add: auth sufficient pam_fortis.so
```

## 12. Set Up Backups

```bash
cd ~/dev/Fortis-Ecosystem/fortis-backup

# Create config from example
cp backup.conf.example backup.conf
chmod 600 backup.conf

# Edit with your paths
nano backup.conf

# Make script executable
chmod +x fortis-backup.sh

# Test run
./fortis-backup.sh

# Install systemd timer for daily automation
mkdir -p ~/.config/systemd/user/
cp systemd/fortis-backup.service ~/.config/systemd/user/
cp systemd/fortis-backup.timer ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now fortis-backup.timer
```

## 13. Verify Installation

```bash
# Check agent is responding
curl -s -H "Authorization: Bearer $FORTIS_AGENT_KEY" \
  http://127.0.0.1:8023/api/v1/telemetry | head -c 200

# Check IPC socket
echo "GET" | socat - UNIX-CONNECT:/tmp/fortis_agent.sock

# Check CLI
cd ~/dev/Fortis-Ecosystem/fortis-cli
./target/release/fortis-cli add "Installation Test"

# Check vault database exists
ls -la ~/.local/share/fortis/vault.db

# Check backup timer
systemctl --user list-timers --all | grep fortis
```

Expected output for telemetry:
```json
{"cpu":"...","mem_total":"...","mem_used":"...","storage_total":"...","storage_used":"...","temp":"...","uptime":"..."}
```
