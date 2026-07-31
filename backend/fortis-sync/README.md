# fortis-sync

> gRPC protocol definitions, sync server/client, and peer discovery for the Fortis Ecosystem. Defines the `SyncService` for vault synchronization and `FortisAuth` for biometric challenge-response authentication.

## Overview

`fortis-sync` contains:
- **Protobuf Definitions** — `.proto` files compiled to Rust via `tonic-build`
- **Sync Server** — `FortisSyncService` implementing the `SyncService` gRPC trait (stub)
- **Sync Client** — `FortisSyncClient` for connecting to peer devices
- **Peer Discovery** — `PeerDiscovery` for finding Fortis peers on the Tailscale network (planned)

## Build

```bash
cargo build -p fortis-sync
```

The `build.rs` script uses vendored `protoc` (via `protobuf-src`) to compile the `.proto` files into Rust code at build time.

## Proto Services

### SyncService

Handles peer-to-peer vault synchronization.

```protobuf
service SyncService {
    rpc Handshake (DeviceAuth) returns (Challenge);
    rpc ExchangeClocks (VectorClock) returns (Delta);
    rpc PushEntries (EncryptedBatch) returns (Ack);
    rpc PullEntries (SinceTimestamp) returns (EncryptedBatch);
}
```

| RPC | Purpose | Status |
|---|---|---|
| `Handshake` | Device authentication with public key exchange | Stub (returns zero nonce) |
| `ExchangeClocks` | Compare vector clocks to determine missing entries | Stub (returns empty delta) |
| `PushEntries` | Upload encrypted vault entries to a peer | Stub (returns success) |
| `PullEntries` | Download encrypted vault entries from a peer | Stub (returns empty batch) |

### FortisAuth

Handles biometric authentication relay between the agent and mobile devices.

```protobuf
service FortisAuth {
    rpc RequestBiometricAuth (BiometricAuthRequest) returns (BiometricAuthResponse);
}

message BiometricAuthRequest {
    string nonce = 1;
    uint64 timestamp = 2;
    string device_name = 3;
    string requested_action = 4;
}

message BiometricAuthResponse {
    bool approved = 1;
    string signature = 2;  // Hex-encoded Ed25519 signature
}
```

## Source Files

### `server.rs` — FortisSyncService

Implements the `SyncService` gRPC trait with stub handlers:

```rust
use crate::pb::sync_service_server::SyncService;

#[derive(Default)]
pub struct FortisSyncService {}

impl SyncService for FortisSyncService {
    async fn handshake(&self, request: Request<DeviceAuth>) -> Result<Response<Challenge>, Status>;
    async fn exchange_clocks(&self, request: Request<VectorClock>) -> Result<Response<Delta>, Status>;
    async fn push_entries(&self, request: Request<EncryptedBatch>) -> Result<Response<Ack>, Status>;
    async fn pull_entries(&self, request: Request<SinceTimestamp>) -> Result<Response<EncryptedBatch>, Status>;
}
```

### `client.rs` — FortisSyncClient

Wraps the generated `SyncServiceClient` for connecting to peer devices:

```rust
pub struct FortisSyncClient {
    client: SyncServiceClient<Channel>,
}

impl FortisSyncClient {
    pub async fn connect(addr: String) -> Result<Self, tonic::transport::Error>;
    pub async fn handshake(&mut self, device_id: &str) -> Result<(), Box<dyn std::error::Error>>;
}
```

### `discovery.rs` — PeerDiscovery

Planned: queries the Tailscale local API (`/localapi/v0/status`) to discover peers running Fortis. Currently returns hardcoded localhost.

```rust
pub struct PeerDiscovery;

impl PeerDiscovery {
    pub fn new() -> Self;
    pub async fn discover_peers(&self) -> Vec<String>;
    // Currently returns: vec!["http://[::1]:50051"]
}
```

## Project Structure

```
fortis-sync/
├── Cargo.toml
├── build.rs                # tonic-build proto compilation (vendored protoc)
├── proto/
│   └── sync.proto          # SyncService + FortisAuth service definitions
└── src/
    ├── lib.rs              # Module exports + generated proto re-export (pb::)
    ├── server.rs           # FortisSyncService — stub SyncService implementation
    ├── client.rs           # FortisSyncClient — gRPC client wrapper
    └── discovery.rs        # PeerDiscovery — Tailscale peer finder (planned)
```
