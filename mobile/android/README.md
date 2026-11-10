# FortisMobile

**FortisMobile** is a professional Linux Infrastructure Management Tool for Android. Built natively in Kotlin using Jetpack Compose, it features a stark True Monochrome design aesthetic, ditching traditional mobile paradigms in favor of a sleek, terminal-like interface.

It serves as the frontend for the **Fortis Agent**, allowing you to monitor and control your Linux nodes securely over your Tailscale VPN.

## Design Philosophy

FortisMobile is built with a **True Monochrome (Linear/Obsidian-esque)** design language. 
- **Colors:** Strictly Black, White, and Gray. No gradients, no purple, no vivid colors except for minimal status indicators (🟢 Online, 🔴 Offline, 🟡 Warning).
- **Typography:** Universal use of `Monospace` to reflect its terminal roots.
- **Components:** Flat cards, thin black borders, zero shadows, and extreme whitespace for high data legibility.

## Core Capabilities

1. **Dashboard (System Telemetry & Operations)**
   - Live metrics: Uptime, CPU, RAM, Swap, Storage, Temperature.
   - Remote execution: Trigger backups, start remote SSH daemons.
   - Live log viewing for background tasks.
2. **True Terminal**
   - Seamless deep integration with Termux.
   - Launch instant, secure, VT100 SSH sessions directly to your laptop via Tailscale.
3. **Actions (Power & Media Controls)**
   - Granular media playback controls (Next, Play/Pause, Vol Up/Down).
   - Remote Power operations (Sleep, Shutdown) with safety confirmation dialogs.
   - Clipboard synchronization.
4. **Workspace & Services**
   - Configuration management (IP & API Keys).
   - Notification listener sync.
   - Remote Webcam viewer (MJPEG streaming).

## Architecture & Navigation

The application uses a flat, infrastructure-focused navigation hierarchy:
- `Devices`: (Landing Page) Overview of all nodes and their current status.
- `Dashboard`: Deep-dive into telemetry and quick-actions for a selected node.
- `Actions`: Remote control payloads (media, power, clipboard).
- `Files`: Remote filesystem explorer.
- `Workspace`: Global application settings and background service toggles.

## Build Instructions

FortisMobile is a standard Android project using Gradle.

```bash
# Clone the repository
git clone <repository_url>

# Open in Android Studio
# Build and run the 'app' module
./gradlew assembleDebug
```

## Security

FortisMobile communicates via REST to the Go-based Fortis Agent. It expects all communication to be routed through an active **Tailscale** VPN, ensuring your infrastructure is never exposed to the public internet. Ensure Tailscale is active on your Android device before launching operations.
