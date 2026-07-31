# Fortis Ecosystem — Troubleshooting

## Common Issues

---

### Agent won't start: "Address already in use"

**Cause:** Another instance of `fortis-agent` is already running on port 8023.

**Fix:**
```bash
# Kill existing instances
killall fortis-agent

# Or find and kill the process on port 8023
lsof -i :8023
kill <PID>

# Remove stale IPC socket
rm -f /tmp/fortis_agent.sock

# Restart
FORTIS_AGENT_KEY=$FORTIS_AGENT_KEY RUST_LOG=info ./target/release/fortis-agent
```

---

### REST API returns 401 Unauthorized

**Cause:** Missing or incorrect API key.

**Fix:**
```bash
# Verify the key is set
echo $FORTIS_AGENT_KEY

# Or read from config file
cat ~/.config/fortis/env

# Make sure you're using the correct header format
curl -H "Authorization: Bearer $FORTIS_AGENT_KEY" http://127.0.0.1:8023/api/v1/telemetry
```

---

### Desktop app shows empty vault

**Cause:** The agent isn't running, or the API key doesn't match.

**Fix:**
1. Ensure `fortis-agent` is running: `ps aux | grep fortis-agent`
2. Verify the key:
   - The desktop app reads `FORTIS_AGENT_KEY` from the environment variable first
   - Falls back to `~/.config/fortis/env` file
   - Both must match the key the running agent was started with
3. Check the Tauri dev tools (`F12` in the desktop app) for network errors

---

### Browser extension shows "Agent is unreachable"

**Cause:** The `fortis-agent` daemon is not running on port 8023.

**Fix:**
```bash
# Start the agent
FORTIS_AGENT_KEY=$FORTIS_AGENT_KEY RUST_LOG=info ./target/release/fortis-agent

# Verify the auth/unlock endpoint is accessible (this is a public endpoint)
curl -X POST -H "Content-Type: application/json" \
  -d '{"domain":"test.com"}' \
  http://127.0.0.1:8023/api/v1/auth/unlock
```

---

### `cargo build` fails with `glib-sys` errors

**Cause:** Missing GTK/WebKit development headers (needed for Tauri desktop app).

**Fix (Ubuntu):**
```bash
sudo apt install libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev patchelf
```

**Workaround:** If you only need the backend, build individual crates:
```bash
cd fortis-backend
cargo build -p fortis-agent
cargo build -p fortis-core
```

> **Note:** This happens because `fortis-desktop/src-tauri` has a path dependency on `fortis-core`. If the desktop repo is present in the expected location, Cargo may try to validate it. Building from within the `fortis-backend` workspace avoids this.

---

### Mobile pairing fails

**Cause:** Pairing code expired, wrong IP, or Tailscale not connected.

**Fix:**
1. Ensure Tailscale is running on both devices: `tailscale status`
2. Use the Tailscale IP (starts with `100.x.y.z`), not the local LAN IP
3. Generate a fresh code:
   ```bash
   cd fortis-cli
   ./target/release/fortis-cli pair
   ```
4. Enter the code promptly on the phone — codes are single-use

---

### SQLCipher "database is locked" error

**Cause:** Multiple processes trying to open the vault database simultaneously.

**Fix:**
```bash
# Make sure only one agent is running
ps aux | grep fortis-agent | grep -v grep

# Remove stale WAL/journal files
rm -f ~/.local/share/fortis/vault.db-journal
rm -f ~/.local/share/fortis/vault.db-wal
```

> **Note:** Both `fortis-agent` (IPC server) and `fortis-desktop` (Tauri backend) open independent database connections. Running both simultaneously may cause contention.

---

### PAM module causes sudo lockout

**Cause:** Incorrect PAM configuration or agent not running.

**Fix — If you have a root shell open:**
```bash
sudo cp /etc/pam.d/sudo.backup /etc/pam.d/sudo
```

**Fix — If locked out completely:**
1. Boot into recovery mode (hold Shift during boot → Advanced options → Recovery mode)
2. Mount the root filesystem as read-write: `mount -o remount,rw /`
3. Edit PAM config: `nano /etc/pam.d/sudo`
4. Remove the line containing `pam_fortis.so`
5. Reboot: `reboot`

> **Safety note:** `fortis-pam` returns `PAM_IGNORE` if the agent socket is unreachable, which should allow PAM to fall through to password authentication. If this fallback isn't working, check the PAM line ordering — `auth sufficient pam_fortis.so` must come *before* other auth lines.

---

### Backup fails with "Security Error: backup.conf"

**Cause:** `backup.conf` has wrong ownership or permissions.

**Fix:**
```bash
# Check current permissions
ls -la ~/dev/Fortis-Ecosystem/fortis-backup/backup.conf

# Fix ownership (must be owned by you)
chown $(whoami) ~/dev/Fortis-Ecosystem/fortis-backup/backup.conf

# Fix permissions (must not be group/world-writable)
chmod 600 ~/dev/Fortis-Ecosystem/fortis-backup/backup.conf
```

---

### Backup fails with "Destination unavailable"

**Cause:** The backup drive at `/mnt/FortisVault` is not mounted.

**Fix:**
```bash
# Check if the drive is mounted
mountpoint -q /mnt/FortisVault && echo "Mounted" || echo "NOT mounted"

# Mount the drive (adjust device path)
sudo mount /dev/sdX1 /mnt/FortisVault

# For encrypted (LUKS) drives:
sudo cryptsetup open /dev/sdX fortisvault
sudo mount /dev/mapper/fortisvault /mnt/FortisVault
```

---

### Backup lock error: "Backup is already running"

**Cause:** A previous backup run didn't clean up its lock file.

**Fix:**
```bash
# Check if backup is actually running
ps aux | grep fortis-backup | grep -v grep

# If not running, remove the stale lock
rm -f /mnt/FortisVault/fortis-backup.lock
```

---

## Debug Commands

```bash
# --- Agent ---
ss -tlnp | grep 8023                  # Check if agent is listening
echo "GET" | socat - UNIX-CONNECT:/tmp/fortis_agent.sock  # Test IPC
ls -la ~/.local/share/fortis/vault.db  # Check vault database
cat ~/.config/fortis/paired_devices.json  # Check paired devices
journalctl --user -u fortis-agent -f   # View agent logs (systemd)

# --- Backup ---
systemctl --user list-timers --all | grep fortis  # Check backup timer
tail -50 /mnt/FortisVault/Logs/mirror/mirror.log  # View last backup log
ls /mnt/FortisVault/FortisGIT/Daily/              # List daily snapshots

# --- Desktop ---
# Open Tauri dev tools: press F12 in the desktop app window

# --- Chrome Extension ---
# Open chrome://extensions → Click "Inspect views: service worker" on FortisLock
```

---

## Getting Help

If you encounter an issue not covered here:

1. Check the agent logs: `journalctl --user -u fortis-agent -n 50`
2. Enable debug logging: `RUST_LOG=debug` when starting the agent
3. Check the backup logs: `cat /mnt/FortisVault/Logs/mirror/mirror.log`
4. Open an issue on the relevant repository at [github.com/Fortis-Ecosystem](https://github.com/Fortis-Ecosystem)
