# FortisVault Backup Architecture 🚀

FortisVault is a high-performance, enterprise-grade, zero-dependency backup utility built in Bash. It uses `rsync` and `systemd` to provide automated, resilient backups with lightning-fast hard-linked "Time Machine" snapshots.

## 🏗 Architecture

FortisVault operates in four sequential phases to protect your data from both hardware failure and human error:

### Phase A: The Mirror (`Hot Standby`)
Every day, the script perfectly synchronises your defined source directories to the backup drive using `rsync`. It deletes files you've removed and updates files you've changed. If your main drive fails, this is your immediate 1:1 recovery point.

### Phase B: FortisGIT (`Time Machine Snapshots`)
Immediately after the Mirror updates successfully, FortisVault uses `rsync --link-dest` to generate a lightning-fast *hard-linked* snapshot of the Mirror.
- Because it uses hard links, unchanged files consume **0 additional bytes** of disk space.
- Protects against accidental `rm -rf` or corrupted saves: if the Mirror syncs a bad deletion, you can still recover the file from yesterday's snapshot.
- **The snapshot is skipped if the mirror phase had any failures**, preventing the capture of a partial/incomplete state.

### Phase C: Retention Cleanup
Old snapshots beyond the retention window are pruned locally before any data is pushed remotely. This ensures the remote device only ever receives valid, current data.

### Phase D: Remote Off-Site Sync (Tailscale)
If `MOBILE_IP` and `MOBILE_PORT` are configured, today's cleaned snapshot is pushed to your mobile device over SSH via your Tailscale network, providing an off-site copy.

---

## 📂 Data Categories & Retention

You define arrays of paths in the configuration file, and FortisVault applies smart lifecycles to them:

| Category | Frequency | Retention Policy | Notes |
| :--- | :--- | :--- | :--- |
| **Projects** | Daily | 14 Days | Automatically excludes heavy build folders (`node_modules`, `.next`, `.dist`) |
| **Secrets** | Daily | 14 Days | Ideal for global keys (`~/.ssh`, `~/.aws`, `~/.kube`) |
| **Memories** | Weekly (Sundays) | 8 Weeks (56 Days) | Heavy media (Photos, Music). Weekly frequency saves IO overhead. |

---

## 🚀 Installation & Setup

### 1. Clone & Configure
```bash
git clone https://github.com/Fortis-Tools/Fortis-Backup.git ~/dev/Fortis-Backup
cd ~/dev/Fortis-Backup

# Copy the example config
cp backup.conf.example backup.conf

# Edit the config with your specific paths
nano backup.conf

# Lock down config permissions (required — script will refuse to run otherwise)
chmod 600 backup.conf
```

### 2. Symlink the Script (Optional but Recommended)
Keep your scripts organised by symlinking the runner to your local bin or scripts folder:
```bash
mkdir -p ~/Scripts
ln -sf ~/dev/Fortis-Backup/fortis-backup.sh ~/Scripts/fortis-backup.sh
ln -sf ~/dev/Fortis-Backup/backup.conf ~/Scripts/backup.conf
chmod +x ~/dev/Fortis-Backup/fortis-backup.sh
```

### 3. Setup Systemd Automation
Instead of `cron`, FortisVault uses `systemd` timers for reliable, persistent scheduling (so backups run immediately on boot if your laptop was powered off during the scheduled time).

1. Copy the systemd files to your user config:
```bash
mkdir -p ~/.config/systemd/user/
cp systemd/fortis-backup.service ~/.config/systemd/user/
cp systemd/fortis-backup.timer ~/.config/systemd/user/
```
2. Enable and start the timer:
```bash
systemctl --user daemon-reload
systemctl --user enable --now fortis-backup.timer
```
3. Check the status:
```bash
systemctl --user list-timers --all
```

---

## 🛠 Manual Execution

You can always run a full backup manually by running the script without arguments:
```bash
~/Scripts/fortis-backup.sh
```

## ⏪ Restore CLI

FortisVault includes a built-in interactive CLI to safely recover lost or corrupted data without needing to manually write dangerous `rsync --delete` commands.

### Usage
```bash
fortis-backup.sh restore [--project | --secret | --memory] <name> [--date YYYY-MM-DD]
```

### Examples
**1. Restore from the Mirror (Immediate rollback):**
```bash
fortis-backup.sh restore --project Fortis-CI
```

**2. Restore from a specific Time Machine snapshot:**
```bash
fortis-backup.sh restore --project Fortis-CI --date 2026-07-03
```

**3. Restore a specific secret:**
```bash
fortis-backup.sh restore --secret .ssh
```

*Note: The CLI automatically reverse-lookups the original target paths from your `backup.conf` and prompts you for confirmation before executing the overwrite.*

---

## 🔒 Security Hardening

### Configuration File Permissions
FortisVault **refuses to run** if `backup.conf` is owned by a different user or is group/world-writable. Always set:
```bash
chmod 600 backup.conf
```

### Backup Drive Permissions
On every run, FortisVault enforces `chmod 700` on the entire `$BACKUP_ROOT`. This ensures only the owning user can read backup data, even if the drive is mounted on a multi-user system.

### Remote Sync SSH Security
Remote sync uses `StrictHostKeyChecking=yes` with a **dedicated** `~/.ssh/known_hosts_fortis_mobile` file to prevent MITM attacks during secret transit. Before your **first** remote sync, register your mobile device's host key once:

```bash
# Replace with your Tailscale IP and port from backup.conf
ssh-keyscan -p 8022 100.x.x.x >> ~/.ssh/known_hosts_fortis_mobile
chmod 600 ~/.ssh/known_hosts_fortis_mobile
```

> **Note:** If you reinstall Termux or your mobile device's SSH host key changes, you must remove the old entry and re-run the command above.

### Full At-Rest Encryption (Recommended for sensitive data)
For maximum protection, format your backup drive with **LUKS** (Linux Unified Key Setup):
```bash
# One-time setup (destructive — erases the drive)
cryptsetup luksFormat /dev/sdX
cryptsetup open /dev/sdX fortisvault
mkfs.ext4 /dev/mapper/fortisvault
```
Add a `/etc/crypttab` entry for auto-unlock on boot, or unlock manually before each backup run.

### What to NEVER commit
**NEVER** commit your `backup.conf` file to a public repository, as it contains absolute paths to your user directories and secrets. A `.gitignore` file is included by default to prevent this.

---

## 📋 Logging

Logs are written to `$BACKUP_ROOT/Logs/`:

| Log | Location | Rotation |
| :--- | :--- | :--- |
| Mirror (all phases) | `Logs/mirror/mirror.log` | Auto-rotated to `mirror.log.1` when >5 MB |
| Daily snapshots | `Logs/snapshots/daily/YYYY-MM-DD.log` | Removed with snapshots at 14-day retention |
| Weekly snapshots | `Logs/snapshots/weekly/YYYY-MM-DD.log` | Removed with snapshots at 56-day retention |

---

## 👨‍💻 Maintainer

**Ganesh Angadi**
- 🐙 GitHub: [@ganeshak11](https://github.com/ganeshak11)
- 🌐 Portfolio: [ganeshangadi.online](https://ganeshangadi.online)
- 💼 LinkedIn: [Ganesh Angadi](https://linkedin.com/in/ganeshangadi1301)

--
