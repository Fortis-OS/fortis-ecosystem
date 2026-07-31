# FortisMobile — Product Requirements Document (PRD)

## 1. Vision & Purpose
To build a bidirectional control and orchestration layer between the Android device and the Fortis ecosystem (laptop/servers). While named **FortisMobile**, it is evolving far beyond backups into a full cross-device ecosystem controller.

## 2. Core Modules (Proposed)
1. **Backup Orchestration (V1)**: Handle `fortis-mobile start/stop` for FortisBackup.
2. **Clipboard Sync**: Securely share clipboard contents between phone and laptop.
3. **Remote Execution**: Run predefined commands on the laptop directly from the phone (e.g., lock screen, trigger CI, restart services).
4. **System Telemetry**: View live laptop metrics (CPU, RAM, temp) on the phone.

## 3. Go Agent API Schema (V1)
- `GET /api/v1/telemetry`: Returns laptop CPU usage, RAM, Temp, and Uptime.
- `POST /api/v1/clipboard`: Sets the laptop's clipboard content.
- `POST /api/v1/action`: Triggers predefined laptop scripts (e.g., `{"action": "lock_screen"}`).

## 4. Android UI Layout
- **Navigation**: Bottom Navigation Bar.
  - **Dashboard**: Backup status & Live Laptop Telemetry.
  - **Actions**: Grid of quick-action buttons (Lock Screen, Sync Clipboard, etc.).
  - **Settings**: Configuration, Thresholds, and Static API Key setup.

## 5. Target Audience
Personal use (Single user, developer-centric, Tailscale-connected environment).

*Note: This document will be iteratively updated as architectural decisions are made.*
