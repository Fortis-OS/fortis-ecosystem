# Fortis Ecosystem — Recovery

What to do when something is lost. Print this page and keep it with your master-password hint.

## Where your data lives

| What | Live copy | Backups |
|---|---|---|
| Vault (`vault.db`, encrypted) + `vault.meta.json` (salt — **needed to unlock**) | `~/.local/share/fortis/` | 1 TB backup disk: `/mnt/FortisVault/Mirror/Secrets/.local__share__fortis/` and daily snapshots in `/mnt/FortisVault/FortisGIT/Daily/<date>/`; phone (Termux): `~/FortisVault/Daily/<date>/` (last 7 days) |
| Agent identity, paired phones, settings | `~/.config/fortis/` | same places, folder `.config__fortis` |
| Fingerprint-sudo trust file | `/etc/fortis/pam/<user>.json` (root) | not backed up — recreate with `sudo fortis pam-enroll` |
| Phone vault | inside the Fortis app (its own encrypted copy) | syncs from the laptop |

Without the **master password**, no copy can be opened. Nobody, including you, can recover it.

**Check that this all works:** `fortis doctor` shows "Vault backup ✔", and once in a while
run the restore drill, which unlocks the backup copy without touching your real vault:

```bash
bash ~/dev/Fortis/fortis-ecosystem/backup/verify-restore.sh
```

## The laptop's SSD died (backup disk fine)

1. Reinstall Linux, Tailscale and Fortis ([installation](../installation/README.md)) up to — not including — `fortis init`.
2. Restore the vault and agent files:

   ```bash
   fortis-backup.sh restore --secret .local__share__fortis
   fortis-backup.sh restore --secret .config__fortis
   systemctl --user restart fortis-agent
   fortis unlock
   ```

   Restoring `.config__fortis` brings back the agent's identity, so the phone stays paired.
3. Re-enable fingerprint sudo: `sudo fortis pam-enroll`, then `sudo fortis pam-install` and, after `fortis pam-test` passes, `sudo fortis pam-install --sudo`.
4. `fortis doctor` — everything should be ✔.

## The whole laptop is gone (stolen, fire)

1. On a new laptop: install Linux, Tailscale, Fortis.
2. Copy the newest snapshot from the phone (Termux sshd on port 8022):

   ```bash
   scp -P 8022 -r <phone-tailscale-ip>:~/FortisVault/Daily/<newest-date>/Secrets/.local__share__fortis ~/.local/share/fortis
   ```

   (If the snapshot predates the `.local__share__fortis` name, the folder may be called `fortis`; check that it contains `vault.db` **and** `vault.meta.json`.)
3. `systemctl --user restart fortis-agent && fortis unlock`.
4. If you restored `~/.config/fortis` too, the phone is still paired. Otherwise re-pair with `fortis pair` and revoke the old pairing in the app.
5. On the phone: Fortis keeps its own vault copy, so your passwords are available there even before the laptop is back.

## The phone is lost or stolen

1. On the laptop, revoke it at once — the agent rejects its keys from that moment:

   ```bash
   fortis devices list
   fortis devices revoke <device_id>
   ```

2. Fingerprint sudo stops trusting it; re-enroll a new phone with `sudo fortis pam-enroll` after pairing it.
3. The phone's vault copy is encrypted with your master password and a key in the phone's secure hardware. Still, change the passwords of your most important accounts if the phone was unlocked when it was lost.
4. Pair the new phone: `fortis pair`.

## sudo is broken after a PAM change

`pkexec` doesn't use the `sudo` PAM service, so this works even if `sudo` doesn't:

```bash
pkexec cp /etc/pam.d/sudo.fortis-backup /etc/pam.d/sudo
```

Details: [security §4](../security/README.md).

## The backup disk failed

The vault is still on the laptop and the phone keeps 7 days of snapshots. Replace the disk,
mount it at `/mnt/FortisVault`, and run `fortis backup run` — the first run rebuilds the mirror.
