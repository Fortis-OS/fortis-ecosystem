# Fortis Ecosystem — Deployment Guide

## Overview

This guide covers deploying the Fortis Ecosystem on a production Linux machine. The primary deployment target is a single-user Linux workstation.

---

## 1. Agent Deployment (systemd)

### Create a systemd Service

```bash
sudo tee /etc/systemd/system/fortis-agent.service << 'EOF'
[Unit]
Description=Fortis Agent - Unified Security Daemon
After=network.target tailscaled.service
Wants=tailscaled.service

[Service]
Type=simple
User=%i
ExecStart=/usr/local/bin/fortis-agent
Environment=FORTIS_AGENT_KEY=YOUR_API_KEY_HERE
Environment=RUST_LOG=info
Restart=always
RestartSec=5

# Security hardening
ProtectHome=read-only
ProtectSystem=strict
ReadWritePaths=/home/%i/.local/share/fortis /home/%i/.config/fortis /tmp
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
```

### Install and Start

```bash
# Copy the binary
sudo cp target/release/fortis-agent /usr/local/bin/

# Generate and store the API key
FORTIS_AGENT_KEY=$(openssl rand -hex 32)
sudo sed -i "s/YOUR_API_KEY_HERE/$FORTIS_AGENT_KEY/" /etc/systemd/system/fortis-agent.service

# Enable and start
sudo systemctl daemon-reload
sudo systemctl enable fortis-agent@$USER
sudo systemctl start fortis-agent@$USER

# Check status
sudo systemctl status fortis-agent@$USER
journalctl -u fortis-agent@$USER -f
```

---

## 2. PAM Module Deployment

> ⚠️ **Critical**: Always keep a root shell open while editing PAM configuration.

```bash
# Install the PAM module
sudo cp target/release/libfortis_pam.so /lib/security/pam_fortis.so

# Configure sudo to use it
sudo cp /etc/pam.d/sudo /etc/pam.d/sudo.backup
sudo tee -a /etc/pam.d/sudo << 'EOF'
# Fortis Biometric Authentication
auth sufficient pam_fortis.so
EOF

# Test (in a SEPARATE terminal, keep root shell open)
sudo -k
sudo whoami
```

### Rolling Back

```bash
sudo cp /etc/pam.d/sudo.backup /etc/pam.d/sudo
```

---

## 3. Desktop App Distribution

### Build a .deb Package (Tauri)

```bash
cd fortis-desktop
npm install
npm run tauri build

# The .deb is at:
# src-tauri/target/release/bundle/deb/fortis-desktop_*.deb

# Install
sudo dpkg -i src-tauri/target/release/bundle/deb/fortis-desktop_*.deb
```

### Build an AppImage

```bash
npm run tauri build
# AppImage is at:
# src-tauri/target/release/bundle/appimage/fortis-desktop_*.AppImage
```

---

## 4. Browser Extension Distribution

```bash
cd fortis-lock
npm install
npm run build

# For personal use: Load fortis-lock/dist as unpacked extension
# For distribution: Package as .crx or submit to Chrome Web Store
```

---

## 5. Backup Automation

```bash
# Install the backup script
sudo cp fortis-backup/fortis-backup.sh /usr/local/bin/
sudo chmod +x /usr/local/bin/fortis-backup.sh

# Copy config
cp fortis-backup/backup.conf.example ~/.config/fortis/backup.conf
# Edit the config with your preferences

# Install systemd timer
sudo cp fortis-backup/systemd/fortis-backup.service /etc/systemd/system/
sudo cp fortis-backup/systemd/fortis-backup.timer /etc/systemd/system/
sudo systemctl enable --now fortis-backup.timer
```

---

## 6. Tailscale VPN Setup

Tailscale is required for mobile ↔ agent communication.

```bash
# Install Tailscale
curl -fsSL https://tailscale.com/install.sh | sh

# Start and authenticate
sudo tailscale up

# Verify connectivity
tailscale status
```

On the Android device, install Tailscale from the Play Store and sign in with the same account.

---

## 7. Health Checks

```bash
# Check agent is running
systemctl is-active fortis-agent@$USER

# Check REST API
curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer $FORTIS_AGENT_KEY" \
  http://127.0.0.1:8023/api/v1/telemetry

# Check IPC socket exists
ls -la /tmp/fortis_agent.sock

# Check vault database integrity
sqlite3 ~/.local/share/fortis/vault.db "PRAGMA cipher_version;"

# Run E2E tests
./run_e2e_tests.sh
```

---

## 8. Log Management

```bash
# View real-time agent logs
journalctl -u fortis-agent@$USER -f

# View last 100 lines
journalctl -u fortis-agent@$USER -n 100

# Filter by log level
journalctl -u fortis-agent@$USER | grep ERROR
```
