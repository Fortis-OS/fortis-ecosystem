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
FORTIS_AGENT_KEY=your_key RUST_LOG=info ./target/release/fortis-agent
```

---

### REST API returns 401 Unauthorized

**Cause:** Missing or incorrect API key.

**Fix:**
```bash
# Verify the key is set
echo $FORTIS_AGENT_KEY

# Make sure you're using the correct header format
curl -H "Authorization: Bearer $FORTIS_AGENT_KEY" http://127.0.0.1:8023/api/v1/telemetry
```

---

### Desktop app shows empty vault

**Cause:** The agent isn't running, or the API key doesn't match.

**Fix:**
1. Ensure `fortis-agent` is running (`ps aux | grep fortis-agent`)
2. Ensure `FORTIS_AGENT_KEY` is set in the environment where the Tauri app launches
3. Check the browser console in the Tauri dev tools (`F12`) for network errors

---

### Browser extension shows "Agent is unreachable"

**Cause:** The `fortis-agent` daemon is not running.

**Fix:**
```bash
# Start the agent
FORTIS_AGENT_KEY=your_key RUST_LOG=info ./target/release/fortis-agent

# Verify it's accessible
curl -X POST -H "Content-Type: application/json" \
  -d '{"domain":"test.com"}' \
  http://127.0.0.1:8023/api/v1/auth/unlock
```

---

### `cargo build` fails with `glib-sys` errors

**Cause:** Missing GTK/WebKit development headers (needed for Tauri).

**Fix (Ubuntu):**
```bash
sudo apt install libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev patchelf
```

**Workaround:** Build individual crates instead of the whole workspace:
```bash
cargo build -p fortis-agent
cargo build -p fortis-core
```

---

### Mobile pairing fails

**Cause:** Pairing code expired, wrong IP, or Tailscale not connected.

**Fix:**
1. Ensure Tailscale is running on both devices: `tailscale status`
2. Use the Tailscale IP (starts with `100.x.y.z`), not the local IP
3. Generate a fresh code: `fortis-cli pair`
4. Enter it promptly — codes are single-use

---

### SQLCipher "database is locked" error

**Cause:** Multiple processes trying to open the vault database simultaneously.

**Fix:**
```bash
# Make sure only one agent is running
ps aux | grep fortis-agent

# Remove stale lock files
rm -f ~/.local/share/fortis/vault.db-journal
rm -f ~/.local/share/fortis/vault.db-wal
```

---

### PAM module causes sudo lockout

**Cause:** Incorrect PAM configuration.

**Fix:**
If you have a root shell open:
```bash
sudo cp /etc/pam.d/sudo.backup /etc/pam.d/sudo
```

If locked out completely:
1. Boot into recovery mode
2. Mount the root filesystem
3. Edit `/etc/pam.d/sudo` to remove the `pam_fortis.so` line

---

### IPC "Execute returned results" error on startup

**Cause:** The vault database hasn't been initialized yet (no schema).

**Fix:**
```bash
# The agent automatically creates and initializes the DB on first IPC connection.
# Ensure the directory exists:
mkdir -p ~/.local/share/fortis
chmod 700 ~/.local/share/fortis

# Restart the agent
killall fortis-agent
FORTIS_AGENT_KEY=your_key RUST_LOG=info ./target/release/fortis-agent
```

---

## Debug Commands

```bash
# Check if agent is listening
ss -tlnp | grep 8023

# Test IPC socket
echo "GET" | socat - UNIX-CONNECT:/tmp/fortis_agent.sock

# Check vault database
ls -la ~/.local/share/fortis/vault.db

# Check paired devices
cat ~/.config/fortis/paired_devices.json

# View agent logs (systemd)
journalctl -u fortis-agent -f

# Run E2E tests
./run_e2e_tests.sh
```

---

## Getting Help

If you encounter an issue not covered here:

1. Check the agent logs: `journalctl -u fortis-agent -n 50`
2. Run the E2E test script: `./run_e2e_tests.sh`
3. Open an issue on GitHub with the error output
