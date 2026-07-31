# Fortis Ecosystem — Development Guide

## Setting Up the Development Environment

### 1. Fork and Clone

```bash
git clone https://github.com/yourusername/Fortis-Ecosystem.git
cd Fortis-Ecosystem
```

### 2. Install Prerequisites

See the [Installation Guide](../installation/README.md) for full system dependency installation.

### 3. Build the Workspace

```bash
# Debug build (faster compilation)
cargo build

# Release build (optimized)
cargo build --release
```

### 4. Run Tests

```bash
# Run all tests
cargo test

# Run tests for a specific crate
cargo test -p fortis-core
cargo test -p fortis-agent
```

---

## Workspace Structure

The project uses a Cargo workspace. The root `Cargo.toml` declares all member crates:

```toml
[workspace]
members = [
    "fortis-core",
    "fortis-sync",
    "fortis-daemon",
    "fortis-cli",
    "fortis-desktop/src-tauri",
    "fortis-bindings",
    "fortis-agent",
    "fortis-pam",
]
resolver = "2"
```

---

## Development Workflows

### Working on `fortis-core`

This is a pure library crate. Changes here affect all downstream crates.

```bash
# Build and test
cargo test -p fortis-core

# Check for compilation errors without building
cargo check -p fortis-core
```

### Working on `fortis-agent`

```bash
# Run the agent in dev mode
FORTIS_AGENT_KEY=dev_key RUST_LOG=debug cargo run -p fortis-agent

# Test the REST API with curl
curl -H "Authorization: Bearer dev_key" http://127.0.0.1:8023/api/v1/telemetry
```

### Working on `fortis-desktop`

```bash
cd fortis-desktop

# Start Vite dev server + Tauri hot-reload
npm run tauri dev

# Build the TypeScript only (for checking types)
npm run build
```

### Working on `fortis-lock`

```bash
cd fortis-lock

# Build the extension
npm run build

# Then reload the extension in Chrome:
# chrome://extensions → Click refresh icon on FortisLock
```

### Working on `fortis-mobile`

Open `fortis-mobile/android` in Android Studio. Use the built-in build and run tools.

---

## Adding a New REST API Endpoint

1. **Define the handler** in `fortis-agent/src/transport/rest.rs`:
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
   cargo build -p fortis-agent
   curl -H "Authorization: Bearer dev_key" http://127.0.0.1:8023/api/v1/my-endpoint
   ```

---

## Adding a New Vault Entry Type

1. **Add the variant** in `fortis-core/src/vault/mod.rs`:
   ```rust
   pub enum EntryType {
       // ... existing variants
       ApiKey {
           key: SecretString,
           service: String,
       },
   }
   ```

2. **Update the REST handler** in `fortis-agent/src/transport/rest.rs` to handle the new type

3. **Update the Desktop UI** in `fortis-desktop/src/main.ts` to render and create the new type

---

## Code Style

- **Rust**: Follow standard `rustfmt` formatting. Run `cargo fmt` before committing.
- **TypeScript**: Use the project's ESLint configuration. Run `npm run lint`.
- **Kotlin**: Follow Android Studio's default Kotlin style guide.

---

## Useful Commands

```bash
# Format all Rust code
cargo fmt

# Run clippy linter
cargo clippy

# Check for unused dependencies
cargo machete

# Build a specific crate
cargo build -p fortis-core

# Run E2E tests
./run_e2e_tests.sh

# Generate docs
cargo doc --open
```
