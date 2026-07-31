# fortis-core

> The cryptographic heart of the Fortis Ecosystem. This library provides encrypted vault storage, TOTP generation, Ed25519 authentication, and secure password generation.

## Overview

`fortis-core` is a pure Rust library crate that all other Fortis modules depend on. It handles:

- **Vault Management** — Encrypted CRUD operations for passwords, credit cards, identities, and secure notes
- **Database** — SQLCipher-encrypted SQLite database with Argon2 key derivation
- **TOTP** — Time-based One-Time Password generation (RFC 6238)
- **Ed25519 Authentication** — Key generation, signing, and signature verification for biometric challenges
- **Password Generation** — Cryptographically secure random password generation
- **Zeroized Memory** — All secrets are automatically wiped from RAM via the `zeroize` crate

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Rust | 1.80+ |
| SQLCipher | 4.x (bundled via `rusqlite`) |

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

let code = totp::generate("JBSWY3DPEHPK3PXP")?;
println!("Current OTP: {}", code);
```

### Ed25519 Authentication

```rust
use fortis_core::auth;

// Generate a keypair
let (public_key, secret_key) = auth::generate_keypair();

// Sign a challenge
let challenge = b"authenticate_sudo_12345";
let signature = auth::sign(challenge, &secret_key);

// Verify the signature
let is_valid = auth::verify(challenge, &signature, &public_key);
assert!(is_valid);
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
    Login { username, password, url, totp_secret },
    Identity { first_name, last_name, email, phone, address },
    CreditCard { cardholder_name, card_number, expiration_month, expiration_year, cvv },
    SecureNote { content },
}
```

## Project Structure

```
fortis-core/
├── Cargo.toml
└── src/
    ├── lib.rs               # Module exports
    ├── vault/
    │   └── mod.rs           # Vault data models (VaultEntry, EntryType, SecretString)
    ├── db/
    │   └── mod.rs           # SQLCipher database operations
    ├── auth/
    │   └── mod.rs           # Ed25519 key generation, signing, verification
    ├── crypto/
    │   └── mod.rs           # ChaCha20-Poly1305 encryption primitives
    ├── sync/
    │   └── mod.rs           # Automerge CRDT sync logic
    ├── totp.rs              # TOTP generation (RFC 6238)
    └── password_gen.rs      # Secure random password generation
```

## Security Notes

- The vault database uses **SQLCipher** with AES-256 encryption and 256,000 KDF iterations
- All `SecretString` fields are wrapped in `zeroize::Zeroizing<String>` to ensure secrets are zeroed from memory on drop
- Ed25519 keys are generated using `ed25519-dalek` with a CSPRNG
