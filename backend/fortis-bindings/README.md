# fortis-bindings

> FFI (Foreign Function Interface) bindings for integrating `fortis-core` with non-Rust platforms. Used for cross-platform integration with Android (JNI), iOS (C FFI), and other targets.

## Overview

`fortis-bindings` provides shared library exports that allow native code on Android and iOS to call into the Rust `fortis-core` library for vault operations, encryption, and authentication.

## Build

```bash
cargo build -p fortis-bindings --release
```

For Android cross-compilation:
```bash
# Install Android NDK targets
rustup target add aarch64-linux-android armv7-linux-androideabi

# Build for Android
cargo build -p fortis-bindings --target aarch64-linux-android --release
```

## Project Structure

```
fortis-bindings/
├── Cargo.toml
├── src/
│   └── lib.rs               # FFI function exports
└── out/                     # Generated binding headers
```
