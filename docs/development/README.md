# Fortis Ecosystem — Development Guide

## Repository Layout

The Fortis Ecosystem is split across multiple independent repositories under the [Fortis-Ecosystem](https://github.com/Fortis-Ecosystem) GitHub organization.

```
~/dev/Fortis-Ecosystem/
├── fortis-backend/         # Cargo workspace
│   ├── Cargo.toml          # Workspace manifest
│   ├── fortis-core/        # Core crypto library
│   ├── fortis-sync/        # gRPC proto + sync logic
│   ├── fortis-agent/       # REST/IPC/gRPC daemon
│   ├── fortis-bindings/    # UniFFI bindings
│   └── fortis-pam/         # PAM module
├── fortis-cli/             # Standalone Rust CLI
├── fortis-desktop/         # Tauri v2 app
├── fortis-lock/            # Chrome extension
├── fortis-mobile/          # Android app
├── fortis-backup/          # Bash backup engine
├── fortis-observe/         # Next.js analytics
└── docs/                   # Documentation
```

### Cargo Workspace

The `fortis-backend` Cargo workspace contains these members:

```toml
[workspace]
members = [
    "fortis-core",
    "fortis-sync",
    "fortis-bindings",
    "fortis-agent",
    "fortis-pam",
]
resolver = "2"
```

### Cross-Repo Dependencies

- `fortis-desktop/src-tauri/Cargo.toml` depends on `fortis-core` via path: `../../fortis-backend/fortis-core`
- `fortis-cli` is a **standalone** project — it communicates with the agent over IPC, not through library imports

---

## Setting Up the Development Environment

### 1. Clone All Repositories

```bash
mkdir -p ~/dev/Fortis-Ecosystem && cd ~/dev/Fortis-Ecosystem

# Core backend workspace
git clone git@github.com:Fortis-Ecosystem/fortis-backend.git

# Client applications
git clone git@github.com:Fortis-Ecosystem/fortis-cli.git
git clone git@github.com:Fortis-Ecosystem/fortis-desktop.git
git clone git@github.com:Fortis-Ecosystem/fortis-lock.git
git clone git@github.com:Fortis-Ecosystem/fortis-mobile.git

# Standalone services
git clone git@github.com:Fortis-Ecosystem/fortis-backup.git
git clone git@github.com:Fortis-Ecosystem/fortis-observe.git
```

### 2. Install Prerequisites

See the [Installation Guide](../installation/README.md) for full system dependency installation.

### 3. Build the Backend Workspace

```bash
cd fortis-backend

# Debug build (faster compilation)
cargo build

# Release build (optimized)
cargo build --release
```

### 4. Run Tests

```bash
# Run all workspace tests
cargo test

# Run tests for a specific crate
cargo test -p fortis-core
```

---

## Development Workflows

### Working on `fortis-core`

This is a pure library crate. Changes here affect all downstream crates (`fortis-agent`, `fortis-bindings`, `fortis-desktop`).

```bash
cd fortis-backend

# Build and test
cargo test -p fortis-core

# Check for compilation errors without full build
cargo check -p fortis-core
```

### Working on `fortis-agent`

```bash
cd fortis-backend

# Run the agent in dev mode
FORTIS_AGENT_KEY=dev_key RUST_LOG=debug cargo run -p fortis-agent

# Test the REST API with curl
curl -H "Authorization: Bearer dev_key" http://127.0.0.1:8023/api/v1/telemetry

# Test the IPC socket
echo "GET" | socat - UNIX-CONNECT:/tmp/fortis_agent.sock
```

### Working on `fortis-cli`

```bash
cd fortis-cli

# Build
cargo build

# Test (requires running agent)
./target/debug/fortis-cli add "Test Entry"
./target/debug/fortis-cli get "Test Entry"
./target/debug/fortis-cli generate --length 24
./target/debug/fortis-cli pair
./target/debug/fortis-cli sync
```

### Working on `fortis-desktop`

```bash
cd fortis-desktop

# Start Vite dev server + Tauri hot-reload
npm run tauri dev

# Build TypeScript only (type checking)
npm run build
```

> **Prerequisite:** Ensure `fortis-backend/` is cloned at `../../fortis-backend/` relative to `fortis-desktop/src-tauri/`.

### Working on `fortis-lock`

```bash
cd fortis-lock

# Build the extension
npm run build

# Then reload in Chrome:
# chrome://extensions → Click refresh icon on FortisLock
```

### 4. Documentation

Documentation is maintained in the `docs` repository. It uses standard GitHub Flavored Markdown and Mermaid.

### Architecture Diagrams

The architecture diagrams in `docs/architecture` are dynamic SVGs generated via a build script. If you add a new Rust crate or change the ecosystem topology, you must regenerate them:

```bash
cd docs
./scripts/build_diagrams.sh
```

- High-level topology is defined in `docs/architecture/fortis_ecosystem.dot`.
- The Rust workspace dependency graph is auto-generated using `cargo depgraph --workspace-only`.

### Working on `fortis-mobile`

Open `fortis-mobile/android` in Android Studio. Use the built-in build and run tools.

---

## Adding a New REST API Endpoint

1. **Define the handler** in `fortis-backend/fortis-agent/src/transport/rest.rs`:
   ```rust
   async fn handle_my_endpoint() -> Json<serde_json::Value> {
       Json(json!({ "hello": "world" }))
   }
   ```

2. **Register the route** in `start_server()`:
   ```rust
   let v1_routes = Router::new()
       // ... existing routes
       .route("/my-endpoint", get(handle_my_endpoint))
       .route_layer(middleware::from_fn(auth_middleware));
   ```

3. **Rebuild and test**:
   ```bash
   cd fortis-backend
   cargo build -p fortis-agent
   FORTIS_AGENT_KEY=dev_key RUST_LOG=debug cargo run -p fortis-agent

   # In another terminal:
   curl -H "Authorization: Bearer dev_key" http://127.0.0.1:8023/api/v1/my-endpoint
   ```

---

## Adding a New Vault Entry Type

1. **Add the variant** in `fortis-backend/fortis-core/src/vault/mod.rs`:
   ```rust
   pub enum EntryType {
       // ... existing variants
       ApiKey {
           key: SecretString,
           service: String,
       },
   }
   ```

2. **Update the database** serialization in `fortis-core/src/db/mod.rs`

3. **Update the REST handler** in `fortis-agent/src/transport/rest.rs` to handle the new type

4. **Update the Desktop UI** in `fortis-desktop/src/main.ts` to render and create the new type

---

## Adding a New IPC Command

1. **Add the command pattern** in `fortis-backend/fortis-agent/src/transport/ipc.rs` inside the `match` block:
   ```rust
   cmd if cmd.starts_with("MY_CMD") => {
       let arg = cmd.strip_prefix("MY_CMD ").unwrap_or("");
       // Handle the command
       stream.write_all(b"Success").await.ok();
   }
   ```

2. **Add the CLI subcommand** in `fortis-cli/src/main.rs`:
   ```rust
   #[derive(Subcommand)]
   enum Commands {
       // ... existing
       MyCommand { arg: String },
   }
   ```

3. **Map the subcommand to IPC message** in the `match` block:
   ```rust
   Commands::MyCommand { arg } => format!("MY_CMD {}", arg),
   ```

---

## Code Style

- **Rust**: Follow standard `rustfmt` formatting. Run `cargo fmt` before committing.
- **TypeScript**: Use the project's ESLint configuration. Run `npm run lint` in `fortis-lock`.
- **Kotlin**: Follow Android Studio's default Kotlin style guide.
- **Bash**: Follow `shellcheck` linting rules.

---

## Useful Commands

```bash
# ---- Rust (run from fortis-backend/) ----
cargo fmt                       # Format all Rust code
cargo clippy                    # Run linter
cargo machete                   # Check for unused dependencies
cargo build -p fortis-core      # Build a specific crate
cargo doc --open                # Generate and open API docs

# ---- Desktop (run from fortis-desktop/) ----
npm run tauri dev               # Development mode
npm run tauri build             # Production build
npm run build                   # TypeScript type check only

# ---- Chrome Extension (run from fortis-lock/) ----
npm run build                   # Build extension to dist/
npm run lint                    # ESLint

# ---- CLI (run from fortis-cli/) ----
cargo build                     # Build CLI
cargo run -- add "Test"         # Run with args
```
