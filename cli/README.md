# fortis-cli

`fortis` — terminal client for [fortis-agent](https://github.com/Fortis-Ecosystem/fortis-backend). It talks to the agent only over its Unix socket (`$XDG_RUNTIME_DIR/fortis/agent.sock`). Spec: [docs/development §3](https://github.com/Fortis-Ecosystem/docs/blob/main/development/README.md#3-fortis-cli) and [API §6](https://github.com/Fortis-Ecosystem/docs/blob/main/api/README.md#6-ipc-protocol).

## Build

The crate uses `fortis-core` from `../fortis-backend/fortis-core`, so clone both repos side by side.

```bash
cargo build --release
install -Dm755 target/release/fortis ~/.local/bin/fortis
```

## Commands

| Command | What it does |
|---|---|
| `fortis status` | Vault state, auto-lock countdown, Tailscale address, phones, sessions |
| `fortis init` / `unlock` / `lock` | Create / unlock / lock the vault (`--password-stdin` for scripts) |
| `fortis list [query] [--type login\|note\|card\|identity\|totp] [--json]` | List entries — never shows secrets |
| `fortis get <name>` | Show an entry without secrets |
| `fortis get <name> --field password\|username\|url\|totp\|notes\|number\|cvv\|content\|email` | Print one field |
| `fortis get <name> --copy [--field …]` | Copy a field (default password); cleared after 30 s if unchanged |
| `fortis get <name> --json` | Full entry including secrets |
| `fortis add [--type …]` | Interactive; leave the password empty or type `g` to generate one |
| `fortis add --from-json file.json` / `-` | Add from an `EntryInput` JSON object |
| `fortis edit <name>` | Interactive; Enter keeps each current value |
| `fortis rm <name> [-y]` | Delete (syncs to the phone as a deletion) |
| `fortis generate [-l N] [--no-symbols] [--no-numbers] [--no-uppercase] [-c]` | Generate a password (no agent needed) |
| `fortis pair` | Show a QR code + one-time code for the phone; prints key fingerprints when it pairs |
| `fortis devices list` / `devices revoke <id-prefix> [-y]` | Paired phones |
| `fortis sessions list` / `sessions end [id]` | Remote-control sessions started from a phone |
| `fortis backup run` | Start a backup |
| `sudo fortis pam-enroll [-y]` | Let paired phones approve `sudo` (writes `/etc/fortis/pam/<user>.json`) |

`<name>` is a title (exact, or case-insensitive if only one matches), a full ID, or the 8-character ID prefix shown by `fortis list`.

Secret prompts don't echo on a terminal. When stdin isn't a terminal, each prompt reads one line, so you can script it.

## Exit codes

`0` success, `1` any error (message on stderr), `2` bad command-line usage.

## Test

```bash
cargo test
```
