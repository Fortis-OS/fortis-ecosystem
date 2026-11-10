# Fortis Ecosystem — Troubleshooting

Start with:

```bash
fortis status
journalctl --user -u fortis-agent -n 50
```

---

## Agent

### Agent won't start: "address already in use"

Another agent (or something else) holds port 8023.

```bash
ss -tlnp | grep 8023
systemctl --user stop fortis-agent
pkill -u "$USER" fortis-agent
systemctl --user start fortis-agent
```

The agent removes its own stale socket on start; you don't need to delete it by hand.

### Agent refuses to start: "refusing to bind 0.0.0.0" / "unknown key in agent.toml"

The config asks for something the spec forbids, or has a typo. Fix `~/.config/fortis/agent.toml` (see [Architecture §3.2](../architecture/README.md#32-configuration-configfortisagenttoml)) or delete it to regenerate defaults.

### Phone can't reach the agent

1. `tailscale status` on both devices — both online, same tailnet.
2. `ss -tlnp | grep 8023` shows `100.x.y.z:8023`. If it only shows `127.0.0.1`, check `rest.tailnet = true` and that Tailscale was up; the agent retries every 30 s.
3. Tailscale ACLs allow the phone → laptop `tcp:8023`.
4. The phone's clock is correct — signed requests more than 60 s off are rejected (`journalctl` shows `stale timestamp`).

### `401 unauthorized` from the desktop app

The token changed (deleted or rotated). Restart fortis-desktop so it re-reads `~/.config/fortis/agent.token`.

### `423 vault_locked`

Expected after 15 minutes idle. `fortis unlock`, or unlock in the desktop app. Change `vault.auto_lock_minutes` if needed.

### `429 rate_limited` on unlock

Too many wrong passwords. Wait for the `Retry-After` time; restarting the agent also resets the counter (it's per process by design — the defence against offline guessing is Argon2id, not the counter).

### "IPC: permission denied" / CLI can't connect

```bash
ls -la /run/user/$(id -u)/fortis/
```

The socket must exist and be owned by you. Running `fortis` under a different user (or with plain `sudo fortis …` other than `pam-enroll`) is rejected by the peer-UID check.

---

## Pairing

### "Invalid or expired code"

Codes last 120 s, work once, and allow 5 wrong tries. Run `fortis pair` again and scan the new QR.

### Fingerprints don't match on laptop and phone

**Stop.** Don't confirm. Revoke the device (`fortis devices revoke <id>`) and pair again; a mismatch means the phone talked to something other than your agent, or the reverse.

### Phone says "vault key changed" after a password change

Expected: changing the master password re-wraps the vault key. On the phone: Settings → Re-sync vault, and enter the new master password once.

---

## Fingerprint `sudo`

### Phone never shows the prompt

- `fortis status` lists the phone as **online**. If not: the app's foreground service isn't running — open the app, check battery setting is **Unrestricted**, and notifications are allowed.
- `sudo fortis pam-enroll` was run after the most recent pairing.
- `/etc/fortis/pam/$USER.json` is `root:root` and `0644`; `pam_fortis` ignores it otherwise:

  ```bash
  sudo stat -c '%a %U:%G %n' /etc/fortis /etc/fortis/pam /etc/fortis/pam/$USER.json
  ```

- Add `debug` to the PAM line and check `sudo journalctl -g pam_fortis -n 20`.

### sudo always asks for the password

That is the safe fallback: something failed (agent down, phone offline, timeout, deny, bad signature). The `debug` log says which.

### `sudo fortis pam-install` refuses

- "module not found": install it into the **multiarch** directory on Ubuntu, `/usr/lib/x86_64-linux-gnu/security/pam_fortis.so` (not `/usr/lib/security/`).
- "module is writable by others / not owned by root": reinstall with `install -o root -g root -m 0644 …`.
- "run pam-test first": Stage 2 needs a passing `sudo fortis pam-test` run.

### Locked out of sudo

`pam_fortis` only returns success or "ignore", is skipped if missing (`-auth`), and gives up after its hard deadline, so it shouldn't be able to block the password prompt. If sudo is broken anyway:

- `pkexec fortis pam-uninstall` — pkexec uses the `polkit-1` PAM service, which Fortis never touches.
- Or with a root shell open: `cp /etc/pam.d/sudo.fortis-backup /etc/pam.d/sudo`.
- Last resort: boot to recovery mode, `mount -o remount,rw /`, restore `/etc/pam.d/sudo.fortis-backup`, reboot.

---

## Phone vault

### "Laptop vault locked" during sync

Sync needs the laptop vault unlocked. Unlock it on the laptop; the phone retries.

### Sync shows conflicts

Normal when the same entry was edited on both sides. The newer edit (by `updated_at`) wins automatically. Phone and laptop clocks should both be on network time.

---

## Remote input and webcam

### Remote input screen says "not available"

- `input.enabled = true` in `agent.toml`, then restart the agent.
- `fortis status` / `GET /capabilities` shows `input: "xdotool"` or `"ydotool"`. On Wayland, `ydotoold` must be running and your user must be able to write `/dev/uinput` (`input` group, log out and back in).
- The fingerprint prompt must succeed; a cancelled prompt means no session.
- Bluetooth mode doesn't need any of this — pair the phone in the laptop's Bluetooth settings.

### Input stops after a while

Sessions end after 2 minutes idle or 30 minutes total, or when the laptop screen locks. Re-open the screen to start a new one.

### Webcam doesn't show up in video apps

```bash
ls /dev/video10 && v4l2-ctl --list-devices      # loopback device exists?
journalctl --user -u fortis-agent | grep -i webcam
```

- `webcam.enabled = true` and `ffmpeg` installed.
- `exclusive_caps=1` in the `v4l2loopback` options (Chrome/Firefox need it).
- Phone and laptop both on Tailscale — the stream never uses Wi-Fi directly.

### Clipboard / remote input / notifications don't work from the systemd service

The agent runs as a systemd user service and only sees `WAYLAND_DISPLAY` / `DISPLAY` if your desktop session exports them. Most desktops do; if `fortis status` / `/capabilities` shows `clipboard: null` or `input: null`, run once per login (or add it to your session autostart):

```bash
systemctl --user import-environment WAYLAND_DISPLAY DISPLAY XAUTHORITY
systemctl --user restart fortis-agent
```

### Phone notifications don't appear on the laptop

- Android: Settings → Notification access → Fortis is enabled, and the app is ticked in Fortis → More → Notification mirroring.
- Laptop: `notify-send test` works in your session.

---

## Desktop app

### Build fails with `glib-sys` / `webkit2gtk` errors

```bash
sudo apt install libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev patchelf
```

### Shows "Agent not running"

```bash
systemctl --user status fortis-agent
```

---

## Backup

### "Security Error: backup.conf"

```bash
chown "$USER" fortis-backup/backup.conf
chmod 600 fortis-backup/backup.conf
```

### "Destination unavailable"

```bash
mountpoint -q /mnt/FortisVault && echo mounted || echo NOT mounted
sudo mount /dev/sdX1 /mnt/FortisVault
# LUKS:
sudo cryptsetup open /dev/sdX fortisvault && sudo mount /dev/mapper/fortisvault /mnt/FortisVault
```

### "Backup is already running"

The script uses `flock`, so a stale lock file is harmless once no backup process is running:

```bash
pgrep -af fortis-backup || echo "not running — safe to re-run"
```

### Phone push fails (exit code 6)

Termux `sshd` not running, phone asleep, or host key changed. Check `nc -z <phone-tailscale-ip> 8022` and the end of `/mnt/FortisVault/Logs/mirror/mirror.log`.

---

## Debug commands

```bash
# Agent
ss -tlnp | grep 8023
printf '{"id":1,"cmd":"status","args":{}}\n' | nc -U /run/user/$(id -u)/fortis/agent.sock
journalctl --user -u fortis-agent -f
stat -c '%a %n' ~/.config/fortis/* ~/.local/share/fortis/*

# PAM
sudo journalctl -g pam_fortis -n 50

# Backup
systemctl --user list-timers | grep fortis
tail -50 /mnt/FortisVault/Logs/mirror/mirror.log
ls /mnt/FortisVault/FortisGIT/Daily/
```
