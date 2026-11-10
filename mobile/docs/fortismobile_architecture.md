# FortisMobile — Architecture Reference

> Companion app/CLI for FortisBackup. Eliminates the manual nightly flow of unlocking the phone, connecting Tailscale, and starting sshd.

---

## The Problem

The current nightly backup flow has a single human-dependent weak link:

```
11:45 PM
    │
Laptop reminder
    │
Open phone
    │
Open Tailscale → Connect
    │
Open Termux → Run: sshd
    │
Keep screen alive
    │
Backup starts
```

This is fragile. One forgotten tap = silent backup failure.

---

## Core Architecture Decision: SSH as the Protocol

**Rejected**: A Python/Flask HTTP status server running in Termux.

**Reason**: Introduces a third long-running service alongside `sshd` and `tailscaled`. Two auth systems, two firewall rules, two failure modes.

**Chosen**: SSH is already the transport. Let it also be the protocol.

```bash
ssh mobile "fortis-mobile status --json"
```

One protocol. One auth method. One port. No extra services.

---

## System Design

```
         Fortis Ecosystem

  ┌─────────────────────────┐
  │      FortisBackup        │
  │    (Laptop Daemon)       │
  └────────────┬─────────────┘
               │ SSH / Tailscale
               │
  ┌────────────▼─────────────┐
  │      FortisMobile         │
  │   (Android Companion)     │
  │                           │
  │  Android App              │
  │       │                   │
  │  fortis-mobile CLI ←──────┼── ssh mobile "fortis-mobile ..."
  │       │                   │
  │     sshd                  │
  └───────────────────────────┘
```

**Layering:**
- `Android App` → GUI convenience layer (optional, not required)
- `fortis-mobile CLI` → the contract between laptop and phone
- `sshd` → the transport

The laptop can operate the phone headlessly forever. The Android app is a skin, not a dependency.

---

## Tailscale Decision: Always-On

Leave Tailscale connected 24/7.

**Why not automate VPN startup?**
- Android's battery optimizer, VPN permission model, and background process killer make automated VPN startup unreliable.
- Tailscale already handles reconnection, background keepalive, and battery efficiency.
- Fighting Android's security model is not worth the engineering cost.

**Action**: Enable **Always-on VPN** in Android Settings → Network → VPN → Tailscale. It reconnects automatically on boot and network changes.

---

## The Handshake Protocol

Instead of the laptop blindly polling for 5 minutes and hoping the phone showed up:

```
Current (dumb poll):
Laptop ──► "Is anyone there?" × 30
           10s × 30 = 5 min wait

New (readiness handshake):
Laptop ──► ssh mobile "fortis-mobile status --json"
       ◄── { "ready": true, ... }
           Start backup immediately
```

---

## `fortis-mobile` CLI — Command Contract

```bash
fortis-mobile status          # Full status, human-readable
fortis-mobile status --json   # Machine-readable for laptop consumption
fortis-mobile start           # Start sshd + any required services
fortis-mobile stop            # Stop services cleanly post-backup
fortis-mobile health          # Quick pass/fail check
fortis-mobile logs            # Last N log lines
fortis-mobile version         # Version info
```

---

## Status JSON Schema

```json
{
  "battery": 82,
  "charging": true,
  "storage_free_gb": 14.3,
  "sshd": true,
  "tailscale": true,
  "tailscale_latency_ms": 42,
  "ready": true,
  "reason": null
}
```

**Key field**: `reason`. When `ready` is `false`, the CLI populates this with a human-readable explanation:

```json
{ "ready": false, "reason": "battery below threshold (12%)" }
```

The laptop logs exactly one line explaining why the backup didn't start. No re-evaluation of individual fields required.

---

## Readiness Thresholds (suggested defaults)

| Check | Threshold | Notes |
|---|---|---|
| Battery | ≥ 20% | Configurable |
| Charging | Preferred, not required | Warn if not charging |
| Storage free | ≥ 5 GB | Configurable |
| sshd | Running | Hard requirement |
| Tailscale | Connected | Hard requirement |

---

## FortisBackup Integration (Phase D change)

Replace the current dumb 5-minute poll with a readiness check before the poll even starts:

```bash
# Before starting remote sync:
STATUS=$(ssh mobile "fortis-mobile status --json" 2>/dev/null)
READY=$(echo "$STATUS" | jq -r '.ready')
REASON=$(echo "$STATUS" | jq -r '.reason')

if [[ "$READY" != "true" ]]; then
    log_mirror "✖ Remote Sync skipped: $REASON"
    # optionally: notify-send the reason
    exit/continue
fi
# proceed with rsync
```

---

## Build Sequence

### Stage 1 — Shell Script (Termux, no app)
- Write `fortis-mobile` as a Bash script in Termux
- Install via Termux:Boot to auto-start sshd on device boot
- Implement `status --json` and `health` commands
- **Get the protocol right before writing any Android code**

### Stage 2 — FortisBackup Integration
- Modify Phase D of `fortis-backup.sh` to call `fortis-mobile status --json`
- Replace 5-minute blind poll with a single SSH readiness check
- Add `reason` field to all log output on skip

### Stage 3 — Android App (GUI layer)
- Build the Android app as a GUI wrapper around the existing CLI
- The app calls `fortis-mobile` commands locally; adds no new logic
- UI surfaces: status dashboard, last backup summary, start/stop controls

### Stage 4 — Observability
- Laptop periodically collects `fortis-mobile status --json` as metrics
- No new protocol needed — the CLI already exists
- Feed into whatever monitoring/dashboard the Fortis ecosystem uses

---

## Future Roadmap

### V1 — Automate the Manual Steps
- Termux:Boot starts sshd automatically on device boot
- `fortis-mobile` CLI with `status`, `start`, `stop`
- Foreground notification keeps services alive
- Auto-stop after backup completes

### V2 — Handshake Protocol
- `fortis-mobile status --json` with `ready` + `reason` fields
- FortisBackup Phase D uses readiness check instead of blind poll
- Configurable thresholds in `fortis-mobile.conf`

### V3 — Android App
- Status dashboard (battery, storage, Tailscale, sshd, last backup)
- Backup progress display (fed from laptop over SSH)
- One-tap Enable/Disable Backup Mode
- Auto-stop after backup completes

### V4 — Observability Node
- Phone treated as a monitored node in the Fortis ecosystem
- Metrics: battery, temperature, storage, Tailscale latency, SSH latency, last backup time
- Dashboard entry: `Node: [device name] — Status: Healthy`

### V5 (stretch) — Restore from Phone
- Browse backup history via SSH
- Select and restore files directly from the app
- View backup logs, storage usage charts

---

## Naming Convention

| Tool | Responsibility |
|---|---|
| `FortisBackup` | Backup engine (laptop daemon) |
| `FortisMobile` | Android companion + CLI |
| `FortisVault` | External backup storage |
| `FortisSync` | Future cloud synchronisation |

One tool. One responsibility. Each can be operated independently.

---

## What This Solves

```
New nightly flow:

11:45 PM
    │
Phone (Tailscale always-on, sshd via Termux:Boot) ← already running
    │
Laptop: ssh mobile "fortis-mobile health"
    │
READY → backup starts
    │
Backup completes
    │
ssh mobile "fortis-mobile stop"  ← optional cleanup
```

**Human steps required: zero.**
