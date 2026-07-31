# Fortis Ecosystem — Installation Guide

This guide walks you through installing and configuring every component of the Fortis Ecosystem from scratch.

---

## Table of Contents

1. [System Requirements](#system-requirements)
2. [Install Rust Toolchain](#1-install-rust-toolchain)
3. [Install Node.js](#2-install-nodejs)
4. [Install System Dependencies](#3-install-system-dependencies)
5. [Clone the Repository](#4-clone-the-repository)
6. [Build the Rust Workspace](#5-build-the-rust-workspace)
7. [Set Up the Agent](#6-set-up-the-agent)
8. [Set Up the Desktop App](#7-set-up-the-desktop-app)
9. [Set Up the Browser Extension](#8-set-up-the-browser-extension)
10. [Set Up the Mobile App](#9-set-up-the-mobile-app)
11. [Install the PAM Module](#10-install-the-pam-module)
12. [Verify Installation](#11-verify-installation)

---

## System Requirements

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| **OS** | Ubuntu 22.04 / Fedora 38 | Ubuntu 24.04 LTS |
| **CPU** | 2 cores | 4+ cores |
| **RAM** | 4 GB | 8 GB+ |
| **Disk** | 2 GB free | 5 GB+ free |
| **Rust** | 1.80 | Latest stable |
| **Node.js** | 20.x | 22.x LTS |
| **Android Studio** | Latest | Latest |
| **Chrome** | 120+ | Latest |

---

## 1. Install Rust Toolchain

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env

# Verify
rustc --version
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
node --version
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
    curl \
    git
```

## 4. Clone the Repository

```bash
git clone https://github.com/yourusername/Fortis-Ecosystem.git
cd Fortis-Ecosystem
```

## 5. Build the Rust Workspace

```bash
# Build all Rust crates in release mode
cargo build --release

# This builds:
# - fortis-core (crypto library)
# - fortis-sync (gRPC proto)
# - fortis-agent (daemon)
# - fortis-cli (command-line)
# - fortis-pam (PAM module)
# - fortis-bindings (FFI)
# - fortis-daemon (legacy)
# - fortis-desktop/src-tauri (Tauri backend)
```

## 6. Set Up the Agent

```bash
# Generate a secure API key
export FORTIS_AGENT_KEY=$(openssl rand -hex 32)
echo "export FORTIS_AGENT_KEY=$FORTIS_AGENT_KEY" >> ~/.bashrc

# Create data directories
mkdir -p ~/.local/share/fortis
mkdir -p ~/.config/fortis
chmod 700 ~/.local/share/fortis ~/.config/fortis

# Test run
RUST_LOG=info ./target/release/fortis-agent

# Install as systemd service (optional)
sudo cp fortis-agent/systemd/fortis-agent.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now fortis-agent
```

## 7. Set Up the Desktop App

```bash
cd fortis-desktop
npm install
npm run tauri dev    # Development
npm run tauri build  # Production
```

## 8. Set Up the Browser Extension

```bash
cd fortis-lock
npm install
npm run build

# Then load fortis-lock/dist as an unpacked extension in Chrome
```

## 9. Set Up the Mobile App

1. Install [Android Studio](https://developer.android.com/studio)
2. Install [Tailscale](https://tailscale.com/) on both your Linux machine and Android phone
3. Open `fortis-mobile/android` in Android Studio
4. Build and install the app on your device
5. Pair with the agent:
   ```bash
   ./target/release/fortis-cli pair
   # Note the 6-digit code, enter it in the mobile app
   ```

## 10. Install the PAM Module

> ⚠️ **Caution**: Keep a root terminal open while configuring PAM to avoid lockout.

```bash
# Copy the library
sudo cp target/release/libfortis_pam.so /lib/security/pam_fortis.so

# Add to PAM config
sudo nano /etc/pam.d/sudo
# Add this line BEFORE other auth lines:
# auth sufficient pam_fortis.so
```

## 11. Verify Installation

```bash
# Run the E2E test script
./run_e2e_tests.sh

# Expected output:
# ✅ Telemetry OK
# ✅ Add entry OK
# ✅ Get entries OK
# 🎉 All E2E tests passed successfully!
```
