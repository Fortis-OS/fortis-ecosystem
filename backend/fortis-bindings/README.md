# fortis-bindings

> UniFFI bindings for the Fortis Ecosystem. Exposes core Rust cryptographic primitives as native libraries for Android (Kotlin/JNA) and iOS (Swift).

## Overview

`fortis-bindings` bridges the gap between `fortis-core` (pure Rust) and the mobile applications (`fortis-mobile`). Instead of manual JNI/C FFI, it uses Mozilla's [UniFFI](https://mozilla.github.io/uniffi-rs/) to automatically generate safe foreign-language bindings.

## Build

To build the dynamic library (`.so`, `.dylib`, or `.dll`):

```bash
cargo build -p fortis-bindings --release
```

To generate the Kotlin bindings:

```bash
cargo run --bin uniffi-bindgen generate src/lib.udl --language kotlin --out-dir out/
```

> **Note:** The `uniffi-bindgen` step is typically integrated into the mobile app's Gradle build process.

## Exported Functions

Currently exported functions (via the `#[uniffi::export]` macro):

### `get_totp`

Generates a Time-based One-Time Password (TOTP) code for a given base32 secret.

```rust
pub fn get_totp(secret_base32: String) -> String;
```
- **Returns:** A 6-digit TOTP string, or `"ERROR"` if the secret is invalid.
- **Delegates to:** `fortis_core::totp::generate_totp_code()`

### `generate_secure_password`

Generates a cryptographically secure random password.

```rust
pub fn generate_secure_password(length: u32) -> String;
```
- **Note:** Currently implemented as a stub (`"SecurePassword123!"`). Future versions will delegate to `fortis_core::password_gen::PasswordGenerator`.

## Project Structure

```
fortis-bindings/
├── Cargo.toml
└── src/
    └── lib.rs        # UniFFI exports and setup scaffolding
```
