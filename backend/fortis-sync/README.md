# fortis-sync

> gRPC protocol definitions and sync logic for the Fortis Ecosystem. Defines the `FortisAuth` service used for biometric challenge-response authentication between the agent and mobile devices.

## Overview

`fortis-sync` contains:
- **Protobuf Definitions** — `.proto` files that define the gRPC services and message types
- **Generated Code** — Tonic-generated Rust client/server stubs via `build.rs`
- **Sync Logic** — Automerge CRDT-based vault synchronization (WIP)

## Build

```bash
cargo build -p fortis-sync
```

The `build.rs` script automatically runs `tonic-build` to compile the `.proto` files into Rust code.

## Proto Service

### FortisAuth

```protobuf
service FortisAuth {
    rpc AuthenticateChallenge (ChallengeRequest) returns (ChallengeResponse);
}

message ChallengeRequest {
    string challenge_id = 1;
    bytes challenge_data = 2;
    string device_name = 3;
}

message ChallengeResponse {
    bool approved = 1;
    bytes signature = 2;
}
```

## Project Structure

```
fortis-sync/
├── Cargo.toml
├── build.rs                # tonic-build proto compilation
├── proto/
│   └── sync.proto          # gRPC service definitions
└── src/
    └── lib.rs              # Re-exports generated code
```
