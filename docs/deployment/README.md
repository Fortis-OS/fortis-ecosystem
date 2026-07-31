# Fortis Ecosystem — Deployment Guide

## Overview

This guide covers deploying the Fortis Ecosystem on a production Linux machine. The primary deployment target is a single-user Linux workstation with Tailscale VPN for mobile connectivity.

All repositories are under the [Fortis-Ecosystem](https://github.com/Fortis-Ecosystem) GitHub organization. Clone them side-by-side in the same parent directory (see [docs/README.md](../README.md#recommended-directory-layout)).

---

## 1. Agent Deployment (systemd)

The `fortis-agent` systemd service file is located at `fortis-backend/fortis-agent/systemd/fortis-agent.service`.

### Install the Binary

```bash
cd fortis-backend
cargo build -p fortis-agent --release
sudo cp target/release/fortis-agent /usr/local/bin/
```

### Install the Systemd Service

The provided service file runs as a user-level service via `WantedBy=default.target`:

```bash
# Copy the service file
mkdir -p ~/.config/systemd/user/
cp fortis-backend/fortis-agent/systemd/fortis-agent.service ~/.config/systemd/user/

# Generate and set the API key
FORTIS_AGENT_KEY=$(openssl rand -hex 32)

# Store the key for the desktop app to read
mkdir -p ~/.config/fortis
echo "FORTIS_AGENT_KEY=$FORTIS_AGENT_KEY" > ~/.config/fortis/env
chmod 600 ~/.config/fortis/env

# Edit the service to set the real key (replace change_me_in_production)
sed -i "s/change_me_in_production/$FORTIS_AGENT_KEY/" ~/.config/systemd/user/fortis-agent.service

# Enable and start
systemctl --user daemon-reload
systemctl --user enable --now fortis-agent

# Check status
systemctl --user status fortis-agent
journalctl --user -u fortis-agent -f
```

> **Note:** The actual service file uses `%i` for User/Group, which is for template instantiation. For a user-level service, these are ignored (runs as the calling user).

---

## 2. PAM Module Deployment

> **⚠️ Critical**: Always keep a root terminal open while editing PAM configuration. Incorrect PAM setup can permanently lock you out of sudo.

### Install

```bash
cd fortis-backend
cargo build -p fortis-pam --release

# Copy the shared library to PAM modules directory
sudo cp target/release/libfortis_pam.so /lib/security/pam_fortis.so

# Backup existing PAM config FIRST
sudo cp /etc/pam.d/sudo /etc/pam.d/sudo.backup

# Add Fortis biometric auth BEFORE other auth lines
sudo sed -i '1a auth sufficient pam_fortis.so' /etc/pam.d/sudo
```

### Test (in a separate terminal, keep root shell open)

```bash
sudo -k       # Clear cached credentials
sudo whoami   # Should trigger biometric prompt on your phone
```

### Rolling Back

```bash
# If biometric works but you want to remove it:
sudo sed -i '/pam_fortis/d' /etc/pam.d/sudo

# If locked out — restore from backup:
sudo cp /etc/pam.d/sudo.backup /etc/pam.d/sudo
```

> **Fallback behavior:** If the agent is unreachable or biometric auth fails, `fortis-pam` returns `PAM_IGNORE`, allowing PAM to fall through to the next module (standard password prompt).

---

## 3. Desktop App Distribution

### Development

```bash
cd fortis-desktop
npm install
npm run tauri dev
```

### Build Distributable Packages

```bash
npm run tauri build

# Outputs:
# .deb — src-tauri/target/release/bundle/deb/fortis-desktop_*.deb
# AppImage — src-tauri/target/release/bundle/appimage/fortis-desktop_*.AppImage

# Install .deb
sudo dpkg -i src-tauri/target/release/bundle/deb/fortis-desktop_*.deb
```

> **Dependency note:** The Tauri Rust backend has a path dependency on `fortis-core` at `../../fortis-backend/fortis-core`. Ensure the repos are cloned in the correct layout before building.

---

## 4. Browser Extension Distribution

```bash
cd fortis-lock
npm install
npm run build

# For personal use:
# chrome://extensions → Enable Developer Mode → Load Unpacked → select fortis-lock/dist

# For distribution:
# Package as .crx or submit to Chrome Web Store
```

---

## 5. Backup Automation

The backup system uses systemd user-level timers. Service and timer files are in `fortis-backup/systemd/`.

```bash
# Configure backup
cd fortis-backup
cp backup.conf.example backup.conf
chmod 600 backup.conf
# Edit backup.conf with your paths (nano backup.conf)

# Install systemd timer (user-level)
mkdir -p ~/.config/systemd/user/
cp systemd/fortis-backup.service ~/.config/systemd/user/
cp systemd/fortis-backup.timer ~/.config/systemd/user/

systemctl --user daemon-reload
systemctl --user enable --now fortis-backup.timer

# Check timer status
systemctl --user list-timers --all
```

> **Note:** The backup service's `ExecStart` path points to `%h/dev/Fortis-Ecosystem/fortis-backup/fortis-backup.sh` (where `%h` is `$HOME`). Adjust this path in the `.service` file if your repo is cloned elsewhere.

> **Note:** The backup config file (`backup.conf`) is read from the same directory as the script (`$SCRIPT_DIR/backup.conf`), not from `~/.config/fortis/`.

---

## 6. Tailscale VPN Setup

Tailscale is required for mobile ↔ agent communication.

### Linux (Agent Side)

```bash
# Install Tailscale
curl -fsSL https://tailscale.com/install.sh | sh

# Start and authenticate
sudo tailscale up

# Verify connectivity
tailscale status
tailscale ip -4   # Note the 100.x.y.z IP
```

### Android (Mobile Side)

1. Install Tailscale from the Play Store
2. Sign in with the same Tailscale account
3. Enable **Always-on VPN** in Android Settings → Network → VPN → Tailscale

---

## 7. Health Checks

```bash
# Check agent is running
systemctl --user is-active fortis-agent

# Check REST API
curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer $(cat ~/.config/fortis/env | grep FORTIS_AGENT_KEY | cut -d= -f2)" \
  http://127.0.0.1:8023/api/v1/telemetry

# Check IPC socket exists
ls -la /tmp/fortis_agent.sock

# Check vault database exists
ls -la ~/.local/share/fortis/vault.db

# Check paired devices
cat ~/.config/fortis/paired_devices.json

# Check backup timer
systemctl --user list-timers --all | grep fortis-backup
```

---

## 8. Log Management

```bash
# View real-time agent logs
journalctl --user -u fortis-agent -f

# View last 100 lines
journalctl --user -u fortis-agent -n 100

# View backup logs
cat /mnt/FortisVault/Logs/mirror/mirror.log | tail -50

# View daily snapshot logs
ls /mnt/FortisVault/Logs/snapshots/daily/
cat /mnt/FortisVault/Logs/snapshots/daily/$(date +%Y-%m-%d).log
```
