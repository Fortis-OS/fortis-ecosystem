# Fortis Ecosystem — Real-Device Test Plan

Everything below has passed automated tests (unit tests, sandboxed PAM through the real `sudo` stack, Android emulator, real `ffmpeg`). What those can't cover is the real thing: a phone on Tailscale, a real fingerprint, the real `sudo`. This plan covers that, in an order where each step only depends on the ones before it.

Work through it top to bottom. Each test says what to do and what you should see. If something doesn't match, stop and collect the logs from [§6](#6-when-something-fails).

**Setup used here:** one Linux laptop (Ubuntu, KDE on Wayland) + one Android phone, both on the same tailnet.

---

## 0. Safety first

- Fingerprint `sudo` is the only step that can lock you out. It is last (T9), installed in two stages, and you keep a root shell open the whole time.
- The v2 agent **cannot read the v1 vault**. §1 moves the old data aside — nothing is deleted.
- Nothing here touches the phone's other apps or Tailscale itself.

---

## 1. Retire the v1 agent

The v1 agent runs as the systemd user service `fortis-agent`. Its unit runs `fortis-backend/target/release/fortis-agent` — **the same path a v2 release build writes to** — and it reads `~/.local/share/fortis/vault.db`, the same folder v2 uses. So stop it and move its data aside **before** building v2:

```bash
systemctl --user disable --now fortis-agent
```

```bash
mkdir -p ~/fortis-v1-backup && cp -a ~/.config/systemd/user/fortis-agent.service ~/.config/fortis ~/.local/share/fortis ~/fortis-v1-backup/
```

```bash
mv ~/.local/share/fortis ~/.local/share/fortis.v1 && mv ~/.config/fortis ~/.config/fortis.v1
```

Check: `ss -tlnp | grep 8023` prints nothing.

Rollback at any time: [§7](#7-roll-back).

---

## 2. Install the laptop side (v2)

```bash
cd ~/dev/Fortis/Fortis-Ecosystem/fortis-backend && git switch v2 && cargo build --release -p fortis-agent -p fortis-pam
```

```bash
install -Dm755 ~/dev/Fortis/Fortis-Ecosystem/fortis-backend/target/release/fortis-agent ~/.local/bin/fortis-agent
```

```bash
cd ~/dev/Fortis/Fortis-Ecosystem/fortis-cli && git switch v2 && cargo build --release && sudo install -m755 target/release/fortis /usr/local/bin/fortis
```

(The CLI goes in `/usr/local/bin` so `sudo fortis …` and `pkexec fortis …` find it; `sudo` doesn't search `~/.local/bin`.)

```bash
install -Dm644 ~/dev/Fortis/Fortis-Ecosystem/fortis-backend/fortis-agent/systemd/fortis-agent.service ~/.config/systemd/user/fortis-agent.service && systemctl --user daemon-reload && systemctl --user enable --now fortis-agent
```

### T1 — Agent listens only where it should

```bash
ss -tlnp | grep 8023
```

Expect exactly two lines: `127.0.0.1:8023` and `100.96.179.60:8023` (your Tailscale IP). No `0.0.0.0`, no `192.168.…`.

```bash
ls -la ~/.config/fortis
```

Expect the folder `drwx------` and `agent.toml`, `agent.token`, `agent.key` all `-rw-------`.

### T2 — Vault on the laptop

```bash
fortis init
```

```bash
fortis add
```

Add one **Login** with a username, password and a TOTP secret (e.g. `JBSWY3DPEHPK3PXP`), then:

```bash
fortis list && fortis status
```

Expect the entry listed and `state: unlocked`. Open the desktop app too (`fortis-desktop`, or `npm run tauri dev` in `fortis-desktop/`) and check the entry shows there.

---

## 3. Install the phone app (v2)

The v2 app is a new package (`com.fortis.mobile`), so it installs next to the old one. Turn on **Developer options → USB debugging** on the phone, plug it in, then:

```bash
cd ~/dev/Fortis/Fortis-Ecosystem/fortis-mobile && git switch v2 && ./scripts/build-rust.sh && cd android && ./gradlew assembleDebug
```

```bash
adb install -r ~/dev/Fortis/Fortis-Ecosystem/fortis-mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

Phone settings (Infinix/XOS kills background apps aggressively — these matter):

- **Tailscale:** connected; Android Settings → Network → VPN → Tailscale → **Always-on VPN**.
- **Fortis → Battery:** Unrestricted / "No restrictions". Same for **Tailscale**.
- **Fortis → Auto-start** (XOS Phone Master / App launch management): allowed.
- **Fortis → Notifications:** allowed (the app also asks).

---

## 4. Tests — pairing and everyday features

### T3 — Pairing

1. Laptop: `fortis pair` → QR + 10-character code.
2. Phone: **PAIR LAPTOP** → scan, tick **Use for vault sync**.
3. Compare the key fingerprints on both screens; confirm on the laptop.

Expect: the laptop appears on the phone's Laptops screen with a green dot, and `fortis devices list` shows the phone.

Negative: run `fortis pair` and wait 2 minutes before scanning → the phone shows an error; nothing is paired.

### T4 — Signed requests

On the phone, check each:

| Screen | Do | Expect |
|---|---|---|
| Dashboard | open | CPU / RAM / battery numbers update |
| Control | VOL +, VOL − | laptop volume changes |
| Control | Lock screen | laptop locks |
| Files | browse `Downloads`, download a file | file opens on the phone |
| Files | upload a photo | appears in `~/Downloads/FortisMobileDrop/` |
| Home screen | add the Fortis widget, tap an action | it runs on the laptop |

Negative, from the laptop (no signature → refused):

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://100.96.179.60:8023/api/v1/telemetry
```

Expect `401`. And from the LAN address (should not even be listening):

```bash
curl -s -m 3 http://$(hostname -I | awk '{print $1}'):8023/ ; echo "exit=$?"
```

Expect `exit=7` (connection refused).

### T5 — Phone vault and sync

1. Phone: More → Vault → enter the master password once → then fingerprint.
2. Expect the T2 entry, with the TOTP code matching `fortis get <title> --field totp` (same 30-second window).
3. Phone: add an entry → sync → `fortis list` on the laptop shows it.
4. Laptop: `fortis edit <title>`, change the username → sync on the phone → change shows.
5. Leave the app for over 60 s, come back → the vault asks for the fingerprint again.
6. Try a screenshot on a vault screen → blocked.

### T6 — Optional features

- Notification mirroring: More → Notifications → pick one app → send yourself a message in it → desktop notification on the laptop.
- Camera-roll sync: turn on, take a photo, connect to Wi-Fi → it shows up in `~/Pictures/FortisCameraRoll/` (WorkManager may take a while; it's a daily job on unmetered network).

---

## 5. Tests — the fingerprint features

### T7 — Events connection

Phone Laptops screen → **Sudo approvals** card: switch on, fix any ⚠ items it shows. Expect "Connected to 1 laptop(s)", and on the laptop:

```bash
fortis status
```

lists the phone as **online**.

Resilience: put the phone in airplane mode for 30 s, then back. Expect it back online within about a minute. Reboot the phone (don't open the app) → it reconnects by itself and the "Fortis connected" notification is back.

### T8 — Sudo approvals, test stage only (sudo untouched)

Keep a root shell open in a **second terminal** for the rest of this plan:

```bash
sudo -s
```

Then, in your normal terminal:

```bash
sudo install -o root -g root -m 0644 ~/dev/Fortis/Fortis-Ecosystem/fortis-backend/target/release/libfortis_pam.so /usr/lib/x86_64-linux-gnu/security/pam_fortis.so
```

```bash
sudo fortis pam-enroll
```

Check the fingerprint it lists matches the one shown at pairing; confirm.

```bash
sudo fortis pam-install
```

Now run `sudo fortis pam-test` once for each row:

| Phone | Expect on the phone | Expect on the laptop |
|---|---|---|
| **Approve** + fingerprint | full-screen prompt with your user, laptop, `fortis-test`, tty; "Approved." | `PASS — the phone approved and PAM accepted it.` |
| **Deny** + fingerprint | prompt closes | `FALLBACK OK …` |
| **Ignore (use password)** | prompt closes | `FALLBACK OK …` after ~30 s |
| do nothing, **screen locked** | prompt lights the screen over the lock screen | approve works the same |
| Ctrl-C on the laptop while it waits | prompt disappears within a second | — |
| Cancel the fingerprint dialog, then Approve | first cancel does nothing; approve works | `PASS` |

### T9 — Real sudo (with the root shell still open)

```bash
sudo fortis pam-install --sudo
```

In a **new** terminal:

```bash
sudo -k && sudo true
```

Approve → no password asked. Then again and **Deny** → normal `[sudo] password` prompt; typing the password works. Then turn the phone's **Sudo approvals** switch off → `sudo -k && sudo true` goes straight to the password after about a second.

Lockout check: `systemctl --user stop fortis-agent`, then `sudo -k && sudo true` → the password prompt appears immediately. Start the agent again afterwards.

If anything at all goes wrong with sudo, in the root shell: `fortis pam-uninstall`. Without a root shell: `pkexec fortis pam-uninstall`.

### T10 — Remote keyboard and mouse over the network

This laptop runs **Wayland**, so the agent uses `ydotool` (it only uses `xdotool` on X11). One-time setup:

```bash
sudo apt install -y ydotool
```

`ydotoold` needs access to `/dev/uinput`. Start it the way your ydotool package documents (`systemctl --user enable --now ydotool` if the package ships that unit, otherwise run `sudo ydotoold` in a spare terminal), and check by hand first — the word must appear where your cursor is:

```bash
sleep 3; ydotool type hello
```

Then enable the feature (the agent's default `agent.toml` already has an `[input]` table with `enabled = false`; this flips it):

```bash
sed -i '/^\[input\]/,/^\[/ s/^enabled = false/enabled = true/' ~/.config/fortis/agent.toml && grep -A1 '^\[input\]' ~/.config/fortis/agent.toml && systemctl --user restart fortis-agent
```

On the phone: Laptops → tap the laptop (refreshes capabilities) → Control → REMOTE_INPUT_CONTROL → switch to **network** → **START** → fingerprint.

| Do | Expect |
|---|---|
| start | desktop notification "📱 … is controlling keyboard and mouse"; `fortis sessions list` shows one `input` session |
| drag one finger / two fingers / tap / long-press | cursor moves / scrolls / left-click / right-click |
| type `hello` + Send | `hello` + Enter on the laptop |
| leave the screen | notification "… stopped controlling …"; `fortis sessions list` empty |
| start again, then `fortis sessions end` on the laptop | phone shows "Session ended" |
| start, wait 2 min untouched, then move | phone shows the session ended (idle timeout) |
| cancel the fingerprint | nothing starts; no notification |

### T11 — Phone as webcam

One-time setup:

```bash
sudo apt install -y v4l2loopback-dkms ffmpeg
```

```bash
echo 'options v4l2loopback video_nr=10 card_label="Fortis Phone Cam" exclusive_caps=1' | sudo tee /etc/modprobe.d/fortis-v4l2loopback.conf && echo v4l2loopback | sudo tee /etc/modules-load.d/fortis-v4l2loopback.conf && sudo modprobe v4l2loopback
```

```bash
sed -i '/^\[webcam\]/,/^\[/ s/^enabled = false/enabled = true/' ~/.config/fortis/agent.toml && grep -A2 '^\[webcam\]' ~/.config/fortis/agent.toml && systemctl --user restart fortis-agent
```

Phone: Laptops → tap the laptop → Control → PHONE_AS_WEBCAM → allow camera → **START**. Then on the laptop:

```bash
ffplay -f v4l2 /dev/video10
```

| Do | Expect |
|---|---|
| start | phone shows "● Streaming to …", the green camera dot and the "Camera streaming" notification; `ffplay` shows the picture |
| switch to BACK CAM | picture switches |
| any video app (browser meeting test page, OBS) | "Fortis Phone Cam" is listed and works |
| leave the screen / press home | stream stops, notification goes away |

Security check while it streams: find the phone's port with `ss -tnp | grep ffmpeg` (the remote side is `phone-ip:port`), then from the laptop:

```bash
curl -s -m 3 http://<phone-tailscale-ip>:<port>/wrongtoken/stream ; echo "exit=$?"
```

Expect no reply (exit `52` or `56`): one viewer at a time, and ffmpeg holds the slot. If you have another device on the tailnet, the same `curl` from there is also closed with no reply, even after you stop ffmpeg. Each START uses a new port and token.

---

## 6. When something fails

Note the test number and collect:

```bash
journalctl --user -u fortis-agent --since "15 min ago" --no-pager | tail -100
```

```bash
adb logcat -d --pid=$(adb shell pidof com.fortis.mobile) | tail -200
```

For `sudo` (T8/T9):

```bash
sudo journalctl -t sudo -t pam_fortis --since "15 min ago" --no-pager | tail -50
```

Also [Troubleshooting](../troubleshooting/README.md).

---

## 7. Roll back

```bash
sudo fortis pam-uninstall
```

```bash
systemctl --user disable --now fortis-agent
```

```bash
rm -rf ~/.config/fortis ~/.local/share/fortis && mv ~/.config/fortis.v1 ~/.config/fortis && mv ~/.local/share/fortis.v1 ~/.local/share/fortis && cp ~/fortis-v1-backup/fortis-agent.service ~/.config/systemd/user/
```

The v1 unit runs `target/release/fortis-agent`, which is now a v2 build. Rebuild v1 there first (`git switch main` in `fortis-backend`, then `cargo build --release`), then:

```bash
systemctl --user daemon-reload && systemctl --user enable --now fortis-agent
```

---

## 8. Results

| Test | Result | Notes |
|---|---|---|
| T1 listeners | | |
| T2 laptop vault | | |
| T3 pairing | | |
| T4 signed requests | | |
| T5 phone vault | | |
| T6 optional features | | |
| T7 events | | |
| T8 pam-test | | |
| T9 real sudo | | |
| T10 remote input | | |
| T11 webcam | | |
