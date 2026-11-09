# Fortis Ecosystem — Installation Guide

Set up the whole system on a Linux laptop and an Android phone (repeat the laptop steps for each extra laptop). For the systemd and PAM details behind these steps, see the [Deployment Guide](../deployment/README.md).

---

## Requirements

| | Minimum |
|---|---|
| Laptop OS | Ubuntu 22.04+ / Fedora 38+ (systemd, PipeWire or PulseAudio) |
| Phone | Android 9 (API 28)+ with a fingerprint sensor; Termux + Termux:API + Termux:Boot (F-Droid) for backups |
| Rust | 1.85+ (edition 2024) |
| Node.js | 20+ (desktop app only) |
| Android Studio | Current stable (to build the phone app) |
| Tailscale | On both devices, same tailnet |

---

## 1. Toolchains

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source "$HOME/.cargo/env"
rustc --version
```

```bash
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
source ~/.bashrc
nvm install 22
```

## 2. System packages

Ubuntu / Debian:

```bash
sudo apt update && sudo apt install -y build-essential pkg-config libssl-dev libpam0g-dev \
  libwebkit2gtk-4.1-dev libappindicator3-dev librsvg2-dev patchelf \
  wl-clipboard xclip playerctl wireplumber rsync openssh-client netcat-openbsd jq curl git
```

Fedora:

```bash
sudo dnf install -y gcc gcc-c++ pkg-config openssl-devel pam-devel \
  webkit2gtk4.1-devel libappindicator-gtk3-devel librsvg2-devel patchelf \
  wl-clipboard xclip playerctl wireplumber rsync openssh-clients nmap-ncat jq curl git
```

`protobuf-compiler` and `socat` are no longer needed.

Optional, only for the features you turn on:

| Feature | Ubuntu / Debian | Fedora |
|---|---|---|
| Remote input on X11 | `xdotool` | `xdotool` |
| Remote input on Wayland | `ydotool` (then `sudo systemctl enable --now ydotoold` or the user unit, and add yourself to the `input` group) | `ydotool` |
| Phone as webcam | `ffmpeg v4l2loopback-dkms` | `ffmpeg` + `v4l2loopback` (RPM Fusion) |
| Phone notifications on the laptop | `libnotify-bin` | `libnotify` |

## 3. Tailscale

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
tailscale ip -4      # 100.x.y.z — the phone will connect here
```

Install Tailscale on the phone from the Play Store, sign in to the same tailnet, and enable **Always-on VPN**.

## 4. Clone

```bash
mkdir -p ~/dev/Fortis/Fortis-Ecosystem && cd ~/dev/Fortis/Fortis-Ecosystem
for r in fortis-backend fortis-cli fortis-desktop fortis-mobile fortis-backup docs; do
  git clone "git@github.com:Fortis-Ecosystem/$r.git"
done
```

## 5. Build and install the laptop side

```bash
cd ~/dev/Fortis/Fortis-Ecosystem/fortis-backend
cargo build --release -p fortis-agent -p fortis-pam
install -Dm755 target/release/fortis-agent ~/.local/bin/fortis-agent

cd ../fortis-cli
cargo build --release
install -Dm755 target/release/fortis ~/.local/bin/fortis
```

Make sure `~/.local/bin` is on your `PATH`.

## 6. Start the agent

```bash
install -Dm644 ~/dev/Fortis/Fortis-Ecosystem/fortis-backend/fortis-agent/systemd/fortis-agent.service \
  ~/.config/systemd/user/fortis-agent.service
systemctl --user daemon-reload
systemctl --user enable --now fortis-agent
systemctl --user status fortis-agent
```

On first start the agent creates `~/.config/fortis/agent.toml`, `agent.token` and `agent.key` with safe permissions. You do **not** create or export an API key yourself.

Check it is listening only where it should:

```bash
ss -tlnp | grep 8023
# expected: 127.0.0.1:8023 and 100.x.y.z:8023 — nothing else
```

## 7. Create the vault

```bash
fortis init          # prompts for a new master password (min 12 chars), twice
fortis status        # state: unlocked
fortis add           # interactive: type, title, username, password…
fortis lock
```

## 8. Desktop app

```bash
cd ~/dev/Fortis/Fortis-Ecosystem/fortis-desktop
npm install
npm run tauri build
sudo dpkg -i src-tauri/target/release/bundle/deb/fortis-desktop_*.deb   # or use the AppImage
```

The app finds the agent token in `~/.config/fortis/agent.token` automatically.

## 9. Phone app

1. Open `fortis-mobile/android/` in Android Studio, build, and install on the phone (or `./gradlew installRelease` from that folder).
2. On the laptop: `fortis pair` — it prints a QR code and a 10-character code (valid 2 minutes).
3. In the app: **Devices → Pair laptop** → scan the QR (or type the code and the Tailscale IP). Tick **Use as vault laptop** for the laptop that holds your passwords.
4. Check the key fingerprints shown on both screens match, and confirm on the laptop.
5. The app asks for your **master password once** to set up its vault copy, then uses your fingerprint.
6. Allow the app's notification and "run in background" permissions — it needs them to receive `sudo` prompts.
7. Optional, in **More**: notification mirroring (pick apps), camera-roll sync, Bluetooth keyboard/mouse, home-screen widget.

To pair more laptops, repeat steps 2–4 on each one (leave **Use as vault laptop** unticked).

### Optional features on the laptop

Edit `~/.config/fortis/agent.toml`, then `systemctl --user restart fortis-agent`:

```toml
[input]
enabled = true        # remote keyboard/mouse (fingerprint needed to start each session)

[webcam]
enabled = true
device = "/dev/video10"
```

For the webcam, create the loopback device once:

```bash
echo 'options v4l2loopback video_nr=10 card_label="Fortis Phone Cam" exclusive_caps=1' | sudo tee /etc/modprobe.d/fortis-v4l2loopback.conf
echo v4l2loopback | sudo tee /etc/modules-load.d/fortis-v4l2loopback.conf
sudo modprobe v4l2loopback
```

## 10. Fingerprint `sudo` (optional)

> Keep a second terminal open with `sudo -s` until you have tested this.

```bash
cd ~/dev/Fortis/Fortis-Ecosystem/fortis-backend
cargo build --release -p fortis-pam
sudo install -o root -g root -m 0644 target/release/libfortis_pam.so /usr/lib/x86_64-linux-gnu/security/pam_fortis.so
# Fedora: /usr/lib64/security/pam_fortis.so
sudo fortis pam-enroll          # lists paired phones and key fingerprints; confirm
sudo fortis pam-install         # Stage 1: test service only — sudo is not touched
sudo fortis pam-test            # approve on the phone; then run again and deny
```

Only when both `pam-test` runs behave (approve → PASS, deny → FALLBACK OK), enable it for sudo. Keep a `sudo -s` shell open in another terminal while you do this:

```bash
sudo fortis pam-install --sudo
```

Then, in a **new** terminal:

```bash
sudo -k && sudo true     # phone prompts; approve with fingerprint
sudo -k && sudo true     # deny on the phone → you get the normal password prompt
```

To remove it: `sudo fortis pam-uninstall` (or `pkexec fortis pam-uninstall` if sudo is ever broken).

## 11. Backups

```bash
cd ~/dev/Fortis/Fortis-Ecosystem/fortis-backup
cp backup.conf.example backup.conf && chmod 600 backup.conf
$EDITOR backup.conf          # set BACKUP_ROOT, PROJECTS, SECRETS (add ~/.local/share/fortis), MEMORIES
./fortis-backup.sh           # first run
install -Dm644 systemd/fortis-backup.service ~/.config/systemd/user/fortis-backup.service
install -Dm644 systemd/fortis-backup.timer   ~/.config/systemd/user/fortis-backup.timer
systemctl --user daemon-reload
systemctl --user enable --now fortis-backup.timer
```

### Off-site copy to the phone (optional)

On the phone, in Termux:

```bash
pkg install git openssh termux-api jq
git clone https://github.com/Fortis-Ecosystem/fortis-mobile.git
bash fortis-mobile/cli/install.sh            # installs ~/.local/bin/fortis-mobile + Termux:Boot hook
```

On the laptop:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/fortis_mobile -N ''
# copy ~/.ssh/fortis_mobile.pub into the phone's ~/.ssh/authorized_keys
ssh-keyscan -p 8022 <phone-tailscale-ip> >> ~/.ssh/known_hosts_fortis_mobile
```

Set `MOBILE_IP` and `MOBILE_PORT=8022` in `backup.conf`. At backup time, tap **Start backup mode** in the app's Dashboard (or run `fortis-mobile start` in Termux).

## 12. Verify

```bash
fortis status                                         # agent reachable, vault state, paired phones
curl -s -H "Authorization: Bearer $(cat ~/.config/fortis/agent.token)" \
  http://127.0.0.1:8023/api/v1/telemetry
ls -la ~/.config/fortis ~/.local/share/fortis         # all 0700 / 0600
systemctl --user list-timers | grep fortis-backup
```

Then walk through the [audit checklist](../security/README.md#9-release-audit-checklist).
