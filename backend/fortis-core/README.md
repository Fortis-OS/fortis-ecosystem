# fortis-core

Crypto, data models and the SQLCipher vault database for Fortis. No networking.

The behaviour is specified in the [docs](https://github.com/Fortis-Ecosystem/docs) — mainly the
[Security Model](https://github.com/Fortis-Ecosystem/docs/blob/main/security/README.md) §2–§4 and
[Architecture](https://github.com/Fortis-Ecosystem/docs/blob/main/architecture/README.md) §4.

## Modules

| Module | What it does |
|---|---|
| `crypto` | Argon2id (random per-vault salt) → master key → HKDF split into **db key** and **wrap key**; XChaCha20-Poly1305 for the wrapped vault key and for entries (AAD binds entry ID + version); constant-time compare |
| `meta` | `vault.meta.json` header (format version, KDF params, salt, vault ID), atomic `0600` writes |
| `vault` | `VaultEntry`, `EntryType` (Login, CreditCard, Identity, SecureNote, Totp), `EntryInput` + validation, `EntrySummary`, `SecretString` (zeroised, redacted in `Debug`) |
| `db` *(feature `db`)* | `VaultDb`: create / open / change password (crash-safe), entry CRUD with tombstones, phone sync (`bootstrap`, `pull`, `push` with version conflicts) |
| `sign` | P-256 ECDSA verify, agent identity key, canonical bytes for signed requests, sudo approvals and sessions |
| `totp` | RFC 6238 codes (SHA-1/256/512, 6–8 digits) |
| `password_gen` | `OsRng` passwords with at least one character from each enabled class |

## Features

- `db` — SQLCipher vault (`rusqlite` `bundled-sqlcipher`). Enabled by `fortis-agent`; not by `fortis-bindings` or `fortis-pam`.

## Example

```rust
use fortis_core::db::{VaultDb, VaultPaths};
use fortis_core::vault::{EntryInput, EntryType, SecretString};

let paths = VaultPaths::in_dir("/home/me/.local/share/fortis");
let mut db = VaultDb::create(&paths, "correct horse battery staple")?;   // or VaultDb::open
let entry = db.insert(
    EntryInput {
        folder: None,
        title: "GitHub".into(),
        entry_type: EntryType::Login {
            username: "me".into(),
            password: SecretString::new("hunter2"),
            urls: vec!["https://github.com".into()],
            totp: None,
        },
        custom_fields: vec![],
        notes: SecretString::default(),
    },
    fortis_core::unix_now(),
)?;
drop(db); // locking = dropping the VaultDb (keys are zeroised)
```

## Tests

```bash
cargo test -p fortis-core --features db
```

Covers the RFC 6238 vectors, key separation, AAD binding (swapped or rolled-back blobs fail), wrong-password handling, no plaintext in `vault.db`, file modes, password change including recovery from a crash mid-change, and sync conflicts.

Argon2id runs with the real spec parameters in tests; the workspace optimises the crypto crates in dev builds so this stays fast.
