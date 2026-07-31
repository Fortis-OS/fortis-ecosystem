# FortisMobile — Progress & Next Steps

## What We Did (Stages 1 & 2)

We successfully replaced the fragile, manual remote backup flow with a robust, automated orchestration loop.

### 1. Built the `fortis-mobile` CLI (Stage 1)
- **Created a Termux-based CLI** on the Android phone that acts as the source of truth for the phone's readiness.
- **Implemented health checks**: The script now programmatically verifies the phone's battery level, free storage, and whether the Tailscale VPN is active (by pinging Tailscale's MagicDNS).
- **Established an SSH Contract**: The laptop now talks to the phone over SSH using a structured JSON protocol (`fortis-mobile status --json`).

### 2. Integrated with FortisBackup (Stage 2)
- **Readiness Handshake**: We modified Phase D of `fortis-backup.sh`. Instead of blindly polling the SSH port for 5 minutes, it now strictly validates the phone's JSON health response before starting the backup.
- **Automated Teardown**: After the backup `rsync` completes, the laptop sends a `fortis-mobile stop` command. This cleanly kills `sshd`, releases the CPU wake lock, and clears the Android notification—saving battery automatically.
- **SSH Key Deployment**: We properly deployed the laptop's `fortis_mobile` SSH key to the phone's `authorized_keys`, ensuring the background service can connect without hanging on a password prompt.

### The Current Flow (V2)
1. Laptop backup triggers at 11:45 PM.
2. Laptop reaches Phase D and waits up to 5 minutes, sending a desktop notification.
3. You tap Tailscale and run `fortis-mobile start` in Termux.
4. Laptop detects readiness, syncs files, and remotely shuts down the phone's session.

---

## What We Are Going to Do Next (Stage 3)

The only remaining manual friction is opening Termux and typing a command. Stage 3 will solve this by building a native Android UI.

### 1. The Android Companion App
- **Goal**: A clean, modern Android app that acts as a UI wrapper around the `fortis-mobile` CLI.
- **Features**:
  - One-tap "Start Backup Session" button.
  - Live status dashboard (Battery %, Storage, Tailscale status).
  - Background execution (so you don't even need to keep the app open while the backup runs).

### 2. Deep Linking / Intents
- We can configure the app to automatically launch Tailscale when you tap "Start", or potentially integrate them so one tap brings up the VPN *and* the SSH server simultaneously.

### 3. Observability (Stage 4)
- Eventually, we'll have the laptop periodically pull `fortis-mobile status --json` metrics to plot your phone's health over time on a Grafana/InfluxDB dashboard.
