# fortis-agent

The laptop daemon. It owns the encrypted vault, relays fingerprint `sudo` approvals, and serves the paired phone.
Its behaviour is specified in the [docs](https://github.com/Fortis-Ecosystem/docs): [Architecture §3](https://github.com/Fortis-Ecosystem/docs/blob/main/architecture/README.md), [API](https://github.com/Fortis-Ecosystem/docs/blob/main/api/README.md), [Security](https://github.com/Fortis-Ecosystem/docs/blob/main/security/README.md).

## Listeners

| Listener | Address | Clients | Auth |
|---|---|---|---|
| Loopback REST | `127.0.0.1:8023` | fortis-desktop | Bearer token from `~/.config/fortis/agent.token`, `Host`/`Origin` checks |
| Tailnet REST | `<tailscale IPv4>:8023` | fortis-mobile | Signed requests (P-256), timestamp ±60 s, single-use nonce |
| IPC socket | `$XDG_RUNTIME_DIR/fortis/agent.sock` | fortis-cli, pam_fortis | Peer UID (own user; root only for `auth_request` / `pam_devices`) |

It never binds `0.0.0.0` or a LAN address, and sends no CORS headers.

## Files

| Path | Mode | |
|---|---|---|
| `~/.config/fortis/agent.toml` | 0600 | Config (created with defaults on first run; unknown keys are an error) |
| `~/.config/fortis/agent.token` | 0600 | Desktop bearer token (generated) |
| `~/.config/fortis/agent.key` | 0600 | Agent P-256 identity key (generated) |
| `~/.config/fortis/paired_devices.json` | 0600 | Paired phones (public keys only) |
| `~/.local/share/fortis/vault.{db,meta.json}` | 0600 | Vault (see `fortis-core`) |

## Run

```bash
cargo build --release -p fortis-agent
install -Dm755 ../target/release/fortis-agent ~/.local/bin/fortis-agent
install -Dm644 systemd/fortis-agent.service ~/.config/systemd/user/fortis-agent.service
systemctl --user daemon-reload && systemctl --user enable --now fortis-agent
journalctl --user -u fortis-agent -f
```

`XDG_RUNTIME_DIR` must be set (systemd user services always have it). `FORTIS_AGENT_KEY` is no longer used.

## Layout

```text
src/
├── main.rs            start-up, secrets, listeners, auto-lock ticker, signals
├── config.rs          agent.toml
├── paths.rs           directories and permission tightening
├── state.rs           vault slot (auto-lock, unlock back-off), nonce cache
├── devices.rs         paired devices, pairing codes
├── hub.rs             signed event stream, sudo approvals, fingerprint sessions
├── error.rs           JSON error bodies
├── util.rs            safe process execution, user lookup, notifications
├── transport/
│   ├── auth.rs        loopback + tailnet authentication layers
│   ├── rest.rs        the two routers, tailnet listener supervisor
│   ├── ipc.rs         JSON-lines socket
│   └── tests.rs       router-level tests
└── modules/
    ├── vault.rs       vault, sync, pairing, devices
    ├── system.rs      telemetry (/proc), capabilities, actions, media, clipboard, backup log, notify
    ├── files.rs       root-confined list/download/drop
    ├── phone.rs       events WS, sudo replies, sessions, remote input WS, webcam
    └── input.rs       xdotool/ydotool commands and input validation
```

## Test

```bash
cargo test -p fortis-agent
```
