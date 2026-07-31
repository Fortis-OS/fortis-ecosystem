## The Problem Right Now

You currently have **two daemons running on the same Linux machine** doing overlapping things:

| Daemon | Language | Port/Socket | What it does |
|--------|----------|-------------|-------------|
| `FortisAgent` | Go | `:8023` (REST) | Telemetry, actions, terminal, files, clipboard, webcam |
| `fortis-daemon` | Rust | `/tmp/fortis_daemon.sock` (IPC) | Vault DB, crypto, sync, (soon) biometric auth relay |

And **two mobile apps** talking to them via different protocols (REST vs gRPC). We already decided to merge the mobile side. But the server side has the same problem.

## My Recommendation: **Unified Agent, Separate Libraries**

```
Fortis-Ecosystem/
│
├── fortis-agent/              ← THE ONE DAEMON (rewritten in Rust)
│   ├── src/
│   │   ├── main.rs            # Single entry point
│   │   ├── modules/
│   │   │   ├── telemetry.rs   # What FortisAgent/Go does today
│   │   │   ├── actions.rs     # Remote control (lock, media, power)
│   │   │   ├── files.rs       # File manager
│   │   │   ├── vault.rs       # Password vault (uses fortis-core)
│   │   │   ├── auth.rs        # Biometric sudo relay
│   │   │   └── backup.rs      # Backup orchestration
│   │   └── transport/
│   │       ├── rest.rs         # REST API (backwards compat with mobile)
│   │       ├── grpc.rs         # gRPC (for P2P sync)
│   │       └── ipc.rs         # Unix socket (for CLI, PAM module)
│
├── fortis-core/               ← CRYPTO/DB LIBRARY (stays separate)
│
├── fortis-cli/                ← THIN CLI CLIENT (stays separate)
│
├── fortis-pam/                ← PAM MODULE (stays separate, it's a .so plugin)
│
├── fortis-desktop/            ← TAURI UI (stays separate)
│
├── FortisMobile/              ← THE ONE ANDROID APP (stays)
│
├── FortisObserve/             ← WEB DASHBOARD (stays, it's a frontend)
│
└── FortisBackup/              ← BASH SCRIPTS (stays, it's scripts)
```

## The Rule of Thumb

| Question | Answer |
|----------|--------|
| **Is it a daemon/service running on Linux?** | Merge it into `fortis-agent`. One process, one systemd unit, one port. |
| **Is it a mobile app?** | Merge it into `FortisMobile`. One APK. |
| **Is it a reusable library?** | Keep it as a separate crate/package (`fortis-core`). |
| **Is it a standalone UI?** | Keep it separate (`fortis-desktop`, `FortisObserve`). Frontends are stateless. |
| **Is it a system plugin?** | Keep it separate (`fortis-pam`). It's loaded by the OS, not by you. |
| **Is it scripts/infra?** | Keep it separate (`FortisBackup`). Different lifecycle. |

## Why One Daemon Matters

Every new repo that adds a daemon means:
- Another `systemd` service to manage
- Another port/socket to configure
- Another process consuming RAM
- Another thing to restart after updates
- Another thing that can crash independently

With **one agent**, you get:
- `systemctl start fortis-agent` → everything is running
- One config file: `~/.config/fortis/agent.toml`
- One log: `journalctl -u fortis-agent`
- Modules can share state (e.g., the vault module can look up a credential for the terminal module)

## The Go → Rust Question

Your current `FortisAgent` is in Go. Your `fortis-daemon` is in Rust. You have two options:

**Option A**: Rewrite `FortisAgent` in Rust and absorb `fortis-daemon` — gives you one language for the entire backend, and `fortis-core` integrates natively without FFI.

**Option B**: Keep `FortisAgent` in Go and call Rust via CGo/FFI — faster short-term, but two languages forever.

Given that you said *"I don't care about complexity, I want the absolutely best"*, I'd recommend **Option A**. One Rust binary that does everything the Go agent does plus everything the Rust daemon does. The Go agent is ~600 lines — rewriting it in Rust is straightforward.

---

**Bottom line**: Stop creating new repos for new features. New features become **modules** inside the unified agent. New repos are only for things that run in fundamentally different contexts (a PAM plugin, a web frontend, a mobile app).