#!/usr/bin/env bash
# ==========================================
# FortisMobile — Termux:Boot Script
# Runs automatically when the phone boots.
# Starts sshd so the phone is always reachable
# over Tailscale without manual steps.
# ==========================================

# Give Android a moment to fully boot before starting services
sleep 10

# Start sshd
sshd 2>/dev/null

# Log the boot start
LOG="$HOME/.local/share/fortis-mobile/session.log"
mkdir -p "$(dirname "$LOG")"
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Boot: sshd started via Termux:Boot" >> "$LOG"