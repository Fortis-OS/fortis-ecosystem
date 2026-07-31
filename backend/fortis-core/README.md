# fortis-core

> The cryptographic heart of the Fortis Ecosystem. This library provides encrypted vault storage, TOTP generation, Ed25519 authentication, key derivation, and secure password generation.

## Overview

`fortis-core` is a pure Rust library crate that all other Fortis modules depend on. It handles:

- **Vault Management** — Data models for passwords, credit cards, identities, and secure notes
- **Database** — SQLCipher-encrypted SQLite database with full CRUD operations
- **Key Derivation** — Argon2id master password → 32-byte key derivation
- **Encryption** — XChaCha20-Poly1305 field-level encryption with zeroized vault keys
- **TOTP** — Time-based One-Time Password generation (RFC 6238, SHA-1, 6 digits, 30s)
- **Ed25519 Authentication** — Challenge generation and signature verification for biometric auth
- **Password Generation** — Cryptographically secure random passwords with configurable rules
- **Zeroized Memory** — All secrets wrapped in `zeroize::Zeroizing<>` and automatically wiped from RAM on drop

## Prerequisites

| Requirement | Version |
|---|---|
| Rust | 1.80+ (edition 2024) |
| SQLCipher | 4.x (bundled via `rusqlite[bundled-sqlcipher-vendored-openssl]`) |

## Build

```bash
# From the workspace root
cargo build -p fortis-core

# Run tests
cargo test -p fortis-core
```

## Usage

### Opening the Vault Database

```rust
use fortis_core::db::Database;

let db = Database::open("~/.local/share/fortis/vault.db", "master_key")?;
db.init_schema()?;
```

### Adding a Vault Entry

```rust
use fortis_core::vault::*;
use uuid::Uuid;

let entry = VaultEntry {
    id: Uuid::new_v4(),
    folder_id: None,
    title: "GitHub".to_string(),
    entry_type: EntryType::Login {
        username: "user@example.com".to_string(),
        password: SecretString(zeroize::Zeroizing::new("s3cur3!".to_string())),
        url: Some("https://github.com".to_string()),
        totp_secret: None,
    },
    custom_fields: vec![],
    notes: "My GitHub account".to_string(),
};

db.add_entry(&entry)?;
```

### Retrieving Entries

```rust
let entries = db.get_entries()?;
for entry in entries {
    println!("{}: {:?}", entry.title, entry.entry_type);
}
```

### TOTP Generation

```rust
use fortis_core::totp;

// Generate a 6-digit TOTP code (SHA-1, 30-second period)
let code = totp::generate_totp_code("JBSWY3DPEHPK3PXP")?;
println!("Current OTP: {}", code);

// Get remaining seconds until next code
let remaining = totp::get_remaining_seconds();
println!("Expires in: {}s", remaining);
```

### Ed25519 Authentication

```rust
use fortis_core::auth::{AuthChallenge, AuthResponse, PairedDevice, verify_response};

// Generate a challenge (32-byte random nonce + timestamp)
let challenge = AuthChallenge::new();

// The mobile device signs challenge.payload() with its Ed25519 private key
// and returns the hex-encoded signature

// Verify the response
let response = AuthResponse {
    signature: "hex_encoded_ed25519_signature".to_string(),
};
let device = PairedDevice {
    public_key: "hex_encoded_ed25519_public_key".to_string(),
};

let is_valid = verify_response(&challenge, &response, &device);
```

### Key Derivation (Argon2id)

```rust
use fortis_core::crypto::{derive_master_key, generate_vault_key, encrypt_entry, decrypt_entry};
use rand::{rngs::OsRng, RngCore};

// Derive a master key from a passphrase
let mut salt = [0u8; 16];
OsRng.fill_bytes(&mut salt);
let master_key = derive_master_key("my_passphrase", &salt)?;

// Generate a random vault key
let vault_key = generate_vault_key();

// Encrypt/decrypt data
let ciphertext = encrypt_entry(&vault_key, b"secret data")?;
let plaintext = decrypt_entry(&vault_key, &ciphertext)?;
```

### Password Generation

```rust
use fortis_core::password_gen::PasswordGenerator;

let generator = PasswordGenerator {
    length: 24,
    include_uppercase: true,
    include_numbers: true,
    include_symbols: true,
};

let password = generator.generate(); // Returns Zeroizing<String>
println!("Generated: {}", *password);
```

## Data Models

### VaultEntry

```rust
pub struct VaultEntry {
    pub id: Uuid,
    pub folder_id: Option<Uuid>,
    pub title: String,
    pub entry_type: EntryType,
    pub custom_fields: Vec<CustomField>,
    pub notes: String,
}
```

### EntryType

```rust
pub enum EntryType {
    Login {
        username: String,
        password: SecretString,
        url: Option<String>,
        totp_secret: Option<String>,
    },
    Identity {
        first_name: String,
        last_name: String,
        email: String,
        phone: String,
        address: String,
    },
    CreditCard {
        cardholder_name: String,
        card_number: SecretString,
        expiration_month: String,
        expiration_year: String,
        cvv: SecretString,
    },
    SecureNote {
        content: SecretString,
    },
}
```

### CustomField

```rust
pub struct CustomField {
    pub name: String,
    pub value: SecretString,
    pub field_type: FieldType,
}

pub enum FieldType {
    Text,
    Hidden,
    Boolean,
}
```

## Project Structure

```
fortis-core/
├── Cargo.toml
└── src/
    ├── lib.rs               # Module exports
    ├── vault/
    │   └── mod.rs           # Vault data models (VaultEntry, EntryType, SecretString, CustomField)
    ├── db/
    │   └── mod.rs           # SQLCipher database operations (open, init_schema, add/get entries)
    ├── auth/
    │   └── mod.rs           # AuthChallenge, AuthResponse, PairedDevice, verify_response()
    ├── crypto/
    │   └── mod.rs           # Argon2id key derivation, XChaCha20-Poly1305 encrypt/decrypt
    ├── sync/
    │   └── crdt.rs          # SyncEngine — Automerge CRDT wrapper (WIP)
    ├── totp.rs              # generate_totp_code(), get_remaining_seconds()
    └── password_gen.rs      # PasswordGenerator struct with configurable rules
```

## Security Notes

- The vault database uses **SQLCipher** with AES-256 encryption and 256,000 KDF iterations
- All `SecretString` fields are wrapped in `zeroize::Zeroizing<String>` — secrets are zeroed from memory on drop
- `MasterKey` and `VaultKey` structs implement `ZeroizeOnDrop`
- Ed25519 keys use `ed25519-dalek` with `OsRng` (CSPRNG)
- Challenge payloads are SHA-512 hashed before signing to bind the nonce and timestamp
