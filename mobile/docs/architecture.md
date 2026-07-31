# FortisMobile — System Architecture

## 1. System Overview
FortisMobile operates as a decentralized, bidirectional control system over a Tailscale mesh network. 
- **The Phone** runs a native Android app (UI) and a Termux environment (CLI + SSH daemon).
- **The Laptop** runs `FortisBackup` (which SSHes into the phone) and a `fortis-agent` (an HTTP server that listens for commands from the phone).

## 2. App Tech Stack
- **Framework**: Native Android (Kotlin + Jetpack Compose). Chosen for maximum performance, native OS integration, and reliability.
- **Local State**: (To be decided - ViewModel/Flows, Room Database for history)
- **Background Execution**: **Foreground Services**. When immediate command execution or backup monitoring is required, a Foreground Service with a persistent notification ensures Android does not kill the process. Passive tasks can optionally fallback to WorkManager.

## 3. Communication Protocol
- **Transport**: Tailscale Mesh Network (100.x.x.x IPs). All traffic is end-to-end encrypted by WireGuard.
- **Laptop -> Phone**: SSH via Termux (`ssh mobile "fortis-mobile status"`).
- **Phone -> Laptop**: A custom **Go (Golang) HTTP Agent** (`fortis-agent`) running as a systemd service on the laptop. Chosen for its extreme performance, low footprint, and simple concurrency model.

## 4. Security Boundaries
- **Network Layer**: Tailscale restricts routing exclusively to authenticated personal devices.
- **Application Layer (Phone -> Laptop)**: **Static API Key (Bearer Token)**. The Android app passes a secret token in the `Authorization` header. `fortis-agent` rejects any request lacking this token, providing defense-in-depth even if the Tailscale network is somehow compromised.
- **Application Layer (Laptop -> Phone)**: SSH Key Authentication. The laptop uses a dedicated `fortis_mobile` ED25519 key authorized in Termux.

*Note: This document will be iteratively updated as architectural decisions are made.*
