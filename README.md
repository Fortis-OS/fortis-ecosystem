# Fortis Ecosystem

A privacy-first system for **your Android phone and your Linux laptop(s)**: an encrypted password vault on both with autofill, TOTP codes, fingerprint-approved `sudo`, laptop remote control, phone-as-webcam and encrypted backups — joined over Tailscale, with no cloud service in between.

**The [docs](docs/README.md) are the specification.** Code follows them; when they disagree, the docs win. Start with the [Security Model](docs/security/README.md).

| Folder | What |
|---|---|
| [backend/](backend) | Rust workspace: `fortis-core`, `fortis-agent` (laptop daemon), `fortis-pam` (`sudo` module), `fortis-bindings` (phone crypto) |
| [cli/](cli) | `fortis` terminal client |
| [desktop/](desktop) | Tauri desktop vault app |
| [mobile/](mobile) | Android app + Termux backup CLI |
| [browser/](browser) | Browser extension + native host (laptop autofill) |
| [backup/](backup) | Backup engine + systemd timer |
| [docs/](docs) | Specification, install, deployment, development, testing |

Get started: [Installation](docs/installation/README.md) · [Development](docs/development/README.md) · [Real-device test plan](docs/testing/README.md)

Part of [fortis-os](https://github.com/fortis-os). Maintainer: **Ganesh Angadi** ([@ganeshak11](https://github.com/ganeshak11)).
