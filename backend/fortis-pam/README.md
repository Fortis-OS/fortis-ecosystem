# fortis-pam

> A Linux PAM module that replaces traditional sudo password prompts with biometric authentication via your phone.

## Overview

`fortis-pam` is a C/Rust shared library that plugs into Linux's Pluggable Authentication Module (PAM) framework. When you run `sudo`, instead of typing a password, it sends a biometric challenge to your paired Android device.

## How It Works

```
┌──────────┐     ┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│  sudo    │───▶│  fortis-pam  │────▶│ fortis-agent │────▶│ fortis-mobile │
│  command │     │  (PAM module)│IPC   │  (daemon)    │gRPC  │  (fingerprint)│
│          │◀───│              │◀────│              │◀────│              │
│ Success  │     └──────────────┘      └──────────────┘      └──────────────┘
```

1. User runs `sudo` → PAM loads `fortis-pam`
2. `fortis-pam` sends `AUTH_CHALLENGE` over the Unix socket to `fortis-agent`
3. The agent forwards the challenge to the paired phone via gRPC
4. The user scans their fingerprint on the phone
5. The phone signs the challenge with Ed25519 and returns the signature
6. The agent verifies the signature and approves the authentication
7. `sudo` succeeds — no password typed

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Rust | 1.80+ |
| GCC | For C PAM bindings |
| libpam | `libpam0g-dev` on Debian/Ubuntu |
| `fortis-agent` | Running with a paired device |

## Build

```bash
cargo build -p fortis-pam --release
```

## Install

```bash
# Copy the shared library to PAM modules directory
sudo cp target/release/libfortis_pam.so /lib/security/pam_fortis.so

# Configure PAM to use it (add to /etc/pam.d/sudo)
# Add this line BEFORE other auth lines:
echo "auth sufficient pam_fortis.so" | sudo tee -a /etc/pam.d/sudo
```

> ⚠️ **Warning**: Always keep a root terminal open when editing PAM configuration. Incorrect PAM setup can lock you out of sudo entirely.

## Testing

```bash
# Test without modifying PAM config
sudo -k  # Clear cached credentials
sudo whoami  # Should trigger biometric prompt on your phone
```

## Project Structure

```
fortis-pam/
├── Cargo.toml
└── src/
    └── lib.rs               # PAM module implementation (pam_sm_authenticate)
```

## Reverting

To disable biometric sudo and restore password-based authentication:

```bash
# Remove the fortis line from /etc/pam.d/sudo
sudo sed -i '/pam_fortis/d' /etc/pam.d/sudo
```
