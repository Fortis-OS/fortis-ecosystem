//! SQLCipher vault database (security §3, architecture §4.2, API §2 sync).
//!
//! Files (security §3.1), all in one directory:
//! - `vault.meta.json`          plaintext header (KDF params + salt, vault ID)
//! - `vault.meta.json.pending`  only during a master-password change
//! - `vault.db`                 SQLCipher database
//!
//! A `VaultDb` value *is* the unlocked vault: it owns the vault key and the
//! open connection. Dropping it locks the vault.

use std::fs;
use std::path::{Path, PathBuf};

use rusqlite::{Connection, ErrorCode, OptionalExtension, Transaction, params};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::crypto::{self, CryptoError, VaultKey, WrapKey, derive_master_key};
use crate::meta::{MetaError, VaultMeta};
use crate::vault::{EntryInput, ValidationError, VaultEntry};

pub const SCHEMA_VERSION: i64 = 2;
/// Max rows returned by one sync pull / accepted in one push (API §2).
pub const SYNC_BATCH: usize = 500;

const K_SCHEMA: &str = "schema_version";
const K_VAULT_ID: &str = "vault_id";
const K_WRAPPED: &str = "wrapped_vault_key";
const K_WRAPPED_PENDING: &str = "wrapped_vault_key_pending";
const K_SEQ: &str = "sync_seq";

#[derive(Debug, thiserror::Error)]
pub enum DbError {
    /// Same error for every unlock failure (security §3.4).
    #[error("invalid password")]
    InvalidPassword,
    #[error("a vault already exists")]
    AlreadyExists,
    #[error("no vault has been created yet")]
    Uninitialised,
    #[error("entry not found")]
    NotFound,
    #[error(transparent)]
    Validation(#[from] ValidationError),
    #[error("vault data is corrupt: {0}")]
    Corrupt(String),
    #[error(transparent)]
    Meta(#[from] MetaError),
    #[error("database: {0}")]
    Sql(#[from] rusqlite::Error),
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error(transparent)]
    Crypto(#[from] CryptoError),
}

pub type Result<T> = std::result::Result<T, DbError>;

/// Where the vault files live.
#[derive(Clone, Debug)]
pub struct VaultPaths {
    pub meta: PathBuf,
    pub pending_meta: PathBuf,
    pub db: PathBuf,
}

impl VaultPaths {
    pub fn in_dir(dir: impl AsRef<Path>) -> Self {
        let d = dir.as_ref();
        Self {
            meta: d.join("vault.meta.json"),
            pending_meta: d.join("vault.meta.json.pending"),
            db: d.join("vault.db"),
        }
    }

    pub fn exists(&self) -> bool {
        self.meta.exists() || self.pending_meta.exists() || self.db.exists()
    }
}

/// One encrypted row as exchanged with the phone.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct SyncRow {
    pub id: Uuid,
    pub version: u64,
    pub updated_at: i64,
    pub deleted: bool,
    #[serde(with = "crypto::b64")]
    pub encrypted_data: Vec<u8>,
}

/// One change pushed by the phone.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct SyncChange {
    pub id: Uuid,
    pub base_version: u64,
    pub version: u64,
    pub updated_at: i64,
    pub deleted: bool,
    #[serde(with = "crypto::b64")]
    pub encrypted_data: Vec<u8>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct PushResult {
    pub seq: i64,
    pub accepted: Vec<Uuid>,
    pub conflicts: Vec<SyncRow>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct PullResult {
    pub seq: i64,
    pub rows: Vec<SyncRow>,
}

/// Entries plus IDs of rows that failed to decrypt (never silently hidden).
#[derive(Debug, Default)]
pub struct ListResult {
    pub entries: Vec<VaultEntry>,
    pub corrupt: Vec<Uuid>,
}

/// Material the phone needs to build its own copy (API `GET /sync/bootstrap`).
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct Bootstrap {
    pub meta: VaultMeta,
    #[serde(with = "crypto::b64")]
    pub wrapped_vault_key: Vec<u8>,
    pub seq: i64,
}

/// The unlocked vault.
pub struct VaultDb {
    conn: Connection,
    vault_key: VaultKey,
    meta: VaultMeta,
}

impl std::fmt::Debug for VaultDb {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "VaultDb(vault_id={})", self.meta.vault_id)
    }
}

fn is_wrong_key(e: &rusqlite::Error) -> bool {
    matches!(e, rusqlite::Error::SqliteFailure(err, _) if err.code == ErrorCode::NotADatabase)
}

fn set_private(path: &Path) -> std::io::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    }
    Ok(())
}

/// Open the SQLCipher file with a raw key and prove the key is right.
fn open_conn(path: &Path, db_key: &crypto::DbKey) -> Result<Connection> {
    let conn = Connection::open(path)?;
    conn.pragma_update(None, "key", db_key.sqlcipher_pragma().as_str())?;
    match conn.query_row("SELECT count(*) FROM sqlite_master", [], |r| {
        r.get::<_, i64>(0)
    }) {
        Ok(_) => {}
        Err(e) if is_wrong_key(&e) => return Err(DbError::InvalidPassword),
        Err(e) => return Err(e.into()),
    }
    conn.pragma_update(None, "secure_delete", "ON")?;
    conn.pragma_update(None, "foreign_keys", "ON")?;
    Ok(conn)
}

fn meta_get_blob(conn: &Connection, key: &str) -> Result<Option<Vec<u8>>> {
    Ok(conn
        .query_row("SELECT value FROM metadata WHERE key = ?1", [key], |r| {
            r.get(0)
        })
        .optional()?)
}

fn next_seq(tx: &Transaction<'_>) -> Result<i64> {
    let seq: i64 = tx.query_row("SELECT value FROM metadata WHERE key = ?1", [K_SEQ], |r| {
        r.get(0)
    })?;
    let next = seq + 1;
    tx.execute(
        "UPDATE metadata SET value = ?1 WHERE key = ?2",
        params![next, K_SEQ],
    )?;
    Ok(next)
}

struct RawRow {
    version: u64,
    updated_at: i64,
    deleted: bool,
    data: Vec<u8>,
}

fn load_row(conn: &Connection, id: &Uuid) -> Result<Option<RawRow>> {
    Ok(conn
        .query_row(
            "SELECT version, updated_at, deleted, encrypted_data FROM entries WHERE id = ?1",
            [id.as_bytes().as_slice()],
            |r| {
                Ok(RawRow {
                    version: r.get::<_, i64>(0)? as u64,
                    updated_at: r.get(1)?,
                    deleted: r.get::<_, i64>(2)? != 0,
                    data: r.get(3)?,
                })
            },
        )
        .optional()?)
}

fn write_row(
    tx: &Transaction<'_>,
    id: &Uuid,
    version: u64,
    updated_at: i64,
    deleted: bool,
    data: &[u8],
) -> Result<i64> {
    let seq = next_seq(tx)?;
    tx.execute(
        "INSERT INTO entries (id, version, seq, updated_at, deleted, encrypted_data)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)
         ON CONFLICT(id) DO UPDATE SET version = ?2, seq = ?3, updated_at = ?4, deleted = ?5, encrypted_data = ?6",
        params![id.as_bytes().as_slice(), version as i64, seq, updated_at, deleted as i64, data],
    )?;
    Ok(seq)
}

impl VaultDb {
    /// Create a brand-new vault and return it unlocked (API `POST /vault/init`).
    pub fn create(paths: &VaultPaths, password: &str) -> Result<Self> {
        if paths.exists() {
            return Err(DbError::AlreadyExists);
        }
        let meta = VaultMeta::new();
        let master = derive_master_key(password.as_bytes(), &meta.kdf)?;
        let vault_key = VaultKey::generate();
        let wrapped = crypto::wrap_vault_key(&master.wrap_key(), &vault_key, &meta.vault_id)?;

        let result = (|| -> Result<Connection> {
            let mut conn = open_conn(&paths.db, &master.db_key())?;
            set_private(&paths.db)?;
            let tx = conn.transaction()?;
            tx.execute_batch(
                "CREATE TABLE metadata (
                     key   TEXT PRIMARY KEY,
                     value BLOB NOT NULL
                 );
                 CREATE TABLE entries (
                     id             BLOB PRIMARY KEY,
                     version        INTEGER NOT NULL,
                     seq            INTEGER NOT NULL,
                     updated_at     INTEGER NOT NULL,
                     deleted        INTEGER NOT NULL DEFAULT 0,
                     encrypted_data BLOB NOT NULL
                 );
                 CREATE INDEX entries_seq ON entries(seq);",
            )?;
            let mut ins = tx.prepare("INSERT INTO metadata (key, value) VALUES (?1, ?2)")?;
            ins.execute(params![K_SCHEMA, SCHEMA_VERSION])?;
            ins.execute(params![K_VAULT_ID, meta.vault_id.as_bytes().as_slice()])?;
            ins.execute(params![K_WRAPPED, wrapped])?;
            ins.execute(params![K_SEQ, 0i64])?;
            drop(ins);
            tx.commit()?;
            meta.save(&paths.meta)?;
            Ok(conn)
        })();

        match result {
            Ok(conn) => Ok(Self {
                conn,
                vault_key,
                meta,
            }),
            Err(e) => {
                let _ = fs::remove_file(&paths.db);
                let _ = fs::remove_file(&paths.meta);
                Err(e)
            }
        }
    }

    /// Unlock an existing vault (API `POST /vault/unlock`). Also finishes an
    /// interrupted master-password change.
    pub fn open(paths: &VaultPaths, password: &str) -> Result<Self> {
        if !paths.meta.exists() && !paths.pending_meta.exists() {
            return Err(DbError::Uninitialised);
        }
        if !paths.db.exists() {
            return Err(DbError::Corrupt("vault.db missing".into()));
        }

        // Normal case: current header.
        if paths.meta.exists() {
            match Self::try_open(paths, &VaultMeta::load(&paths.meta)?, password) {
                Ok(db) => {
                    // A pending header here means a change that never reached rekey.
                    let _ = fs::remove_file(&paths.pending_meta);
                    db.conn
                        .execute("DELETE FROM metadata WHERE key = ?1", [K_WRAPPED_PENDING])?;
                    return Ok(db);
                }
                Err(DbError::InvalidPassword) if paths.pending_meta.exists() => {}
                Err(e) => return Err(e),
            }
        }

        // Interrupted change after rekey: the pending header is now the real one.
        let pending = VaultMeta::load(&paths.pending_meta)?;
        let db = Self::try_open(paths, &pending, password)?;
        if let Some(w) = meta_get_blob(&db.conn, K_WRAPPED_PENDING)? {
            db.conn.execute(
                "UPDATE metadata SET value = ?1 WHERE key = ?2",
                params![w, K_WRAPPED],
            )?;
            db.conn
                .execute("DELETE FROM metadata WHERE key = ?1", [K_WRAPPED_PENDING])?;
        }
        fs::rename(&paths.pending_meta, &paths.meta)?;
        Ok(db)
    }

    fn try_open(paths: &VaultPaths, meta: &VaultMeta, password: &str) -> Result<Self> {
        let master = derive_master_key(password.as_bytes(), &meta.kdf)?;
        let conn = open_conn(&paths.db, &master.db_key())?;

        let schema: i64 = conn
            .query_row(
                "SELECT value FROM metadata WHERE key = ?1",
                [K_SCHEMA],
                |r| r.get(0),
            )
            .optional()?
            .ok_or_else(|| DbError::Corrupt("missing schema_version".into()))?;
        if schema != SCHEMA_VERSION {
            return Err(DbError::Corrupt(format!("unsupported schema {schema}")));
        }
        let id_bytes = meta_get_blob(&conn, K_VAULT_ID)?
            .ok_or_else(|| DbError::Corrupt("missing vault_id".into()))?;
        if id_bytes.as_slice() != meta.vault_id.as_bytes() {
            return Err(DbError::Corrupt(
                "vault.meta.json does not belong to vault.db".into(),
            ));
        }

        let wrap = master.wrap_key();
        let unwrap = |key: &str| -> Result<Option<VaultKey>> {
            Ok(meta_get_blob(&conn, key)?
                .and_then(|w| crypto::unwrap_vault_key(&wrap, &w, &meta.vault_id).ok()))
        };
        let vault_key = match unwrap(K_WRAPPED)? {
            Some(k) => k,
            None => unwrap(K_WRAPPED_PENDING)?.ok_or(DbError::InvalidPassword)?,
        };
        Ok(Self {
            conn,
            vault_key,
            meta: meta.clone(),
        })
    }

    pub fn meta(&self) -> &VaultMeta {
        &self.meta
    }

    pub fn vault_id(&self) -> Uuid {
        self.meta.vault_id
    }

    /// Check a password against this (already unlocked) vault, in constant time.
    pub fn verify_password(&self, password: &str) -> Result<bool> {
        let wrapped = meta_get_blob(&self.conn, K_WRAPPED)?
            .ok_or_else(|| DbError::Corrupt("missing wrapped key".into()))?;
        let wrap: WrapKey = derive_master_key(password.as_bytes(), &self.meta.kdf)?.wrap_key();
        Ok(
            match crypto::unwrap_vault_key(&wrap, &wrapped, &self.meta.vault_id) {
                Ok(k) => crypto::ct_eq(k.as_bytes(), self.vault_key.as_bytes()),
                Err(_) => false,
            },
        )
    }

    /// Change the master password: new salt, re-wrap the vault key, rekey SQLCipher.
    /// Crash-safe: an interrupted change is completed by the next [`open`](Self::open).
    pub fn change_password(&mut self, paths: &VaultPaths, current: &str, new: &str) -> Result<()> {
        if !self.verify_password(current)? {
            return Err(DbError::InvalidPassword);
        }
        let new_meta = self.meta.with_new_salt();
        let master = derive_master_key(new.as_bytes(), &new_meta.kdf)?;
        let wrapped =
            crypto::wrap_vault_key(&master.wrap_key(), &self.vault_key, &new_meta.vault_id)?;

        // 1. Pending header + pending wrapped key (old db key still active).
        new_meta.save(&paths.pending_meta)?;
        self.conn.execute(
            "INSERT INTO metadata (key, value) VALUES (?1, ?2) ON CONFLICT(key) DO UPDATE SET value = ?2",
            params![K_WRAPPED_PENDING, wrapped],
        )?;
        // 2. Switch the database key.
        self.conn
            .pragma_update(None, "rekey", master.db_key().sqlcipher_pragma().as_str())?;
        // 3. Promote.
        self.conn.execute(
            "UPDATE metadata SET value = ?1 WHERE key = ?2",
            params![wrapped, K_WRAPPED],
        )?;
        self.conn
            .execute("DELETE FROM metadata WHERE key = ?1", [K_WRAPPED_PENDING])?;
        fs::rename(&paths.pending_meta, &paths.meta)?;
        self.meta = new_meta;
        Ok(())
    }

    fn decrypt_row(&self, id: &Uuid, version: u64, data: &[u8]) -> Result<VaultEntry> {
        let pt = crypto::decrypt_entry(&self.vault_key, id, version, data)
            .map_err(|_| DbError::Corrupt(format!("entry {id} does not decrypt")))?;
        let entry: VaultEntry = serde_json::from_slice(&pt)
            .map_err(|_| DbError::Corrupt(format!("entry {id} is not valid JSON")))?;
        if entry.id != *id {
            return Err(DbError::Corrupt(format!(
                "entry {id} contains a different ID"
            )));
        }
        Ok(entry)
    }

    /// All live entries.
    pub fn list(&self) -> Result<ListResult> {
        let mut stmt = self
            .conn
            .prepare("SELECT id, version, encrypted_data FROM entries WHERE deleted = 0")?;
        let rows = stmt.query_map([], |r| {
            Ok((
                r.get::<_, Vec<u8>>(0)?,
                r.get::<_, i64>(1)?,
                r.get::<_, Vec<u8>>(2)?,
            ))
        })?;
        let mut out = ListResult::default();
        for row in rows {
            let (id_bytes, version, data) = row?;
            let Ok(id) = Uuid::from_slice(&id_bytes) else {
                continue;
            };
            match self.decrypt_row(&id, version as u64, &data) {
                Ok(e) => out.entries.push(e),
                Err(_) => out.corrupt.push(id),
            }
        }
        out.entries.sort_by_key(|e| e.title.to_lowercase());
        Ok(out)
    }

    pub fn get(&self, id: &Uuid) -> Result<VaultEntry> {
        match load_row(&self.conn, id)? {
            Some(r) if !r.deleted => self.decrypt_row(id, r.version, &r.data),
            _ => Err(DbError::NotFound),
        }
    }

    pub fn insert(&mut self, input: EntryInput, now: i64) -> Result<VaultEntry> {
        input.validate()?;
        let entry = VaultEntry::from_input(Uuid::new_v4(), input, now, now);
        let data = crypto::encrypt_entry(&self.vault_key, &entry.id, 1, &entry.to_json())?;
        let tx = self.conn.transaction()?;
        write_row(&tx, &entry.id, 1, now, false, &data)?;
        tx.commit()?;
        Ok(entry)
    }

    pub fn update(&mut self, id: &Uuid, input: EntryInput, now: i64) -> Result<VaultEntry> {
        input.validate()?;
        let row = match load_row(&self.conn, id)? {
            Some(r) if !r.deleted => r,
            _ => return Err(DbError::NotFound),
        };
        let old = self.decrypt_row(id, row.version, &row.data)?;
        let entry = VaultEntry::from_input(*id, input, old.created_at, now);
        let version = row.version + 1;
        let data = crypto::encrypt_entry(&self.vault_key, id, version, &entry.to_json())?;
        let tx = self.conn.transaction()?;
        write_row(&tx, id, version, now, false, &data)?;
        tx.commit()?;
        Ok(entry)
    }

    /// Turn the entry into a tombstone so the deletion syncs.
    pub fn delete(&mut self, id: &Uuid, now: i64) -> Result<()> {
        let row = match load_row(&self.conn, id)? {
            Some(r) if !r.deleted => r,
            _ => return Err(DbError::NotFound),
        };
        let tx = self.conn.transaction()?;
        write_row(&tx, id, row.version + 1, now, true, &[])?;
        tx.commit()?;
        Ok(())
    }

    pub fn seq(&self) -> Result<i64> {
        Ok(self
            .conn
            .query_row("SELECT value FROM metadata WHERE key = ?1", [K_SEQ], |r| {
                r.get(0)
            })?)
    }

    pub fn bootstrap(&self) -> Result<Bootstrap> {
        Ok(Bootstrap {
            meta: self.meta.clone(),
            wrapped_vault_key: meta_get_blob(&self.conn, K_WRAPPED)?
                .ok_or_else(|| DbError::Corrupt("missing wrapped key".into()))?,
            seq: self.seq()?,
        })
    }

    /// Rows changed after `since` (API `GET /sync/pull`).
    pub fn pull(&self, since: i64) -> Result<PullResult> {
        let mut stmt = self.conn.prepare(
            "SELECT id, version, seq, updated_at, deleted, encrypted_data FROM entries
             WHERE seq > ?1 ORDER BY seq LIMIT ?2",
        )?;
        let mut last_seq = since;
        let rows = stmt
            .query_map(params![since, SYNC_BATCH as i64], |r| {
                Ok((
                    r.get::<_, Vec<u8>>(0)?,
                    r.get::<_, i64>(1)?,
                    r.get::<_, i64>(2)?,
                    r.get::<_, i64>(3)?,
                    r.get::<_, i64>(4)?,
                    r.get::<_, Vec<u8>>(5)?,
                ))
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        let mut out = Vec::with_capacity(rows.len());
        for (id, version, seq, updated_at, deleted, data) in rows {
            let id = Uuid::from_slice(&id).map_err(|_| DbError::Corrupt("bad entry id".into()))?;
            last_seq = seq;
            out.push(SyncRow {
                id,
                version: version as u64,
                updated_at,
                deleted: deleted != 0,
                encrypted_data: data,
            });
        }
        let seq = if out.len() == SYNC_BATCH {
            last_seq
        } else {
            self.seq()?
        };
        Ok(PullResult { seq, rows: out })
    }

    /// Apply the phone's changes (API `POST /sync/push`). Malformed changes
    /// reject the whole batch; version conflicts are returned per row.
    pub fn push(&mut self, changes: &[SyncChange]) -> Result<PushResult> {
        if changes.len() > SYNC_BATCH {
            return Err(ValidationError(format!("at most {SYNC_BATCH} changes per push")).into());
        }
        for c in changes {
            if c.version != c.base_version + 1 {
                return Err(
                    ValidationError(format!("{}: version must be base_version + 1", c.id)).into(),
                );
            }
            if c.deleted {
                if !c.encrypted_data.is_empty() {
                    return Err(
                        ValidationError(format!("{}: deleted rows carry no data", c.id)).into(),
                    );
                }
            } else {
                let entry = self
                    .decrypt_row(&c.id, c.version, &c.encrypted_data)
                    .map_err(|_| ValidationError(format!("{}: data does not decrypt", c.id)))?;
                if entry.updated_at != c.updated_at {
                    return Err(ValidationError(format!("{}: updated_at mismatch", c.id)).into());
                }
            }
        }

        let tx = self.conn.transaction()?;
        let mut accepted = Vec::new();
        let mut conflicts = Vec::new();
        for c in changes {
            let current = load_row(&tx, &c.id)?;
            let ok = match &current {
                None => c.base_version == 0,
                Some(r) => r.version == c.base_version,
            };
            if ok {
                write_row(
                    &tx,
                    &c.id,
                    c.version,
                    c.updated_at,
                    c.deleted,
                    &c.encrypted_data,
                )?;
                accepted.push(c.id);
            } else if let Some(r) = current {
                conflicts.push(SyncRow {
                    id: c.id,
                    version: r.version,
                    updated_at: r.updated_at,
                    deleted: r.deleted,
                    encrypted_data: r.data,
                });
            } else {
                // Phone thinks the row exists but we never had it: report an empty v0 tombstone.
                conflicts.push(SyncRow {
                    id: c.id,
                    version: 0,
                    updated_at: 0,
                    deleted: true,
                    encrypted_data: vec![],
                });
            }
        }
        tx.commit()?;
        Ok(PushResult {
            seq: self.seq()?,
            accepted,
            conflicts,
        })
    }

    /// Encrypt an entry exactly as a client would (used by tests and the agent).
    pub fn encrypt_for_sync(&self, entry: &VaultEntry, version: u64) -> Result<Vec<u8>> {
        Ok(crypto::encrypt_entry(
            &self.vault_key,
            &entry.id,
            version,
            &entry.to_json(),
        )?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::vault::{EntryType, SecretString};

    const PW: &str = "correct horse battery staple";

    fn note(title: &str) -> EntryInput {
        EntryInput {
            folder: None,
            title: title.into(),
            entry_type: EntryType::SecureNote {
                content: SecretString::new("top secret"),
            },
            custom_fields: vec![],
            notes: SecretString::default(),
        }
    }

    fn fresh() -> (tempfile::TempDir, VaultPaths, VaultDb) {
        let dir = tempfile::tempdir().unwrap();
        let paths = VaultPaths::in_dir(dir.path());
        let db = VaultDb::create(&paths, PW).unwrap();
        (dir, paths, db)
    }

    #[test]
    fn create_open_and_wrong_password() {
        let (_d, paths, mut db) = fresh();
        let e = db.insert(note("A"), 10).unwrap();
        drop(db);
        assert!(matches!(
            VaultDb::create(&paths, PW),
            Err(DbError::AlreadyExists)
        ));
        assert!(matches!(
            VaultDb::open(&paths, "wrong password"),
            Err(DbError::InvalidPassword)
        ));
        let db = VaultDb::open(&paths, PW).unwrap();
        assert_eq!(db.get(&e.id).unwrap().title, "A");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(&paths.db).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
    }

    #[test]
    fn uninitialised() {
        let dir = tempfile::tempdir().unwrap();
        assert!(matches!(
            VaultDb::open(&VaultPaths::in_dir(dir.path()), PW),
            Err(DbError::Uninitialised)
        ));
    }

    #[test]
    fn file_contains_no_plaintext() {
        let (_d, paths, mut db) = fresh();
        db.insert(note("VisibleTitle"), 1).unwrap();
        drop(db);
        let raw = fs::read(&paths.db).unwrap();
        assert!(!raw.windows(12).any(|w| w == b"VisibleTitle"));
        assert!(!raw.windows(10).any(|w| w == b"top secret"));
        assert!(!raw.starts_with(b"SQLite format 3"));
    }

    #[test]
    fn crud_and_tombstones() {
        let (_d, _p, mut db) = fresh();
        let a = db.insert(note("A"), 10).unwrap();
        let b = db.insert(note("B"), 11).unwrap();
        assert_eq!(db.list().unwrap().entries.len(), 2);
        let a2 = db.update(&a.id, note("A2"), 20).unwrap();
        assert_eq!((a2.created_at, a2.updated_at), (10, 20));
        db.delete(&b.id, 30).unwrap();
        assert!(matches!(db.get(&b.id), Err(DbError::NotFound)));
        assert!(matches!(db.delete(&b.id, 31), Err(DbError::NotFound)));
        assert!(matches!(
            db.update(&b.id, note("x"), 31),
            Err(DbError::NotFound)
        ));
        let titles: Vec<_> = db
            .list()
            .unwrap()
            .entries
            .into_iter()
            .map(|e| e.title)
            .collect();
        assert_eq!(titles, vec!["A2"]);
        assert!(db.insert(note(""), 1).is_err());
    }

    #[test]
    fn swapped_blobs_are_detected() {
        let (_d, _p, mut db) = fresh();
        let a = db.insert(note("A"), 1).unwrap();
        let b = db.insert(note("B"), 1).unwrap();
        // Copy A's ciphertext onto B's row.
        db.conn
            .execute(
                "UPDATE entries SET encrypted_data = (SELECT encrypted_data FROM entries WHERE id = ?1) WHERE id = ?2",
                params![a.id.as_bytes().as_slice(), b.id.as_bytes().as_slice()],
            )
            .unwrap();
        let l = db.list().unwrap();
        assert_eq!(l.entries.len(), 1);
        assert_eq!(l.corrupt, vec![b.id]);
        assert!(matches!(db.get(&b.id), Err(DbError::Corrupt(_))));
    }

    #[test]
    fn change_password() {
        let (_d, paths, mut db) = fresh();
        let e = db.insert(note("A"), 1).unwrap();
        let old_salt = db.meta().kdf.salt.clone();
        assert!(matches!(
            db.change_password(&paths, "nope", "new password 123"),
            Err(DbError::InvalidPassword)
        ));
        db.change_password(&paths, PW, "new password 123").unwrap();
        assert_ne!(db.meta().kdf.salt, old_salt);
        assert_eq!(db.get(&e.id).unwrap().title, "A");
        drop(db);
        assert!(matches!(
            VaultDb::open(&paths, PW),
            Err(DbError::InvalidPassword)
        ));
        let db = VaultDb::open(&paths, "new password 123").unwrap();
        assert_eq!(db.get(&e.id).unwrap().title, "A");
        assert!(!paths.pending_meta.exists());
    }

    #[test]
    fn interrupted_change_after_rekey_recovers() {
        let (_d, paths, mut db) = fresh();
        let e = db.insert(note("A"), 1).unwrap();
        // Simulate a crash right after step 2 (rekey) of change_password.
        let new_meta = db.meta().with_new_salt();
        let master = derive_master_key(b"new password 123", &new_meta.kdf).unwrap();
        let wrapped =
            crypto::wrap_vault_key(&master.wrap_key(), &db.vault_key, &new_meta.vault_id).unwrap();
        new_meta.save(&paths.pending_meta).unwrap();
        db.conn
            .execute(
                "INSERT INTO metadata (key, value) VALUES (?1, ?2)",
                params![K_WRAPPED_PENDING, wrapped],
            )
            .unwrap();
        db.conn
            .pragma_update(None, "rekey", master.db_key().sqlcipher_pragma().as_str())
            .unwrap();
        drop(db);

        let db = VaultDb::open(&paths, "new password 123").unwrap();
        assert_eq!(db.get(&e.id).unwrap().title, "A");
        assert!(!paths.pending_meta.exists());
        drop(db);
        VaultDb::open(&paths, "new password 123").unwrap();
    }

    #[test]
    fn interrupted_change_before_rekey_keeps_old_password() {
        let (_d, paths, db) = fresh();
        db.meta().with_new_salt().save(&paths.pending_meta).unwrap();
        drop(db);
        VaultDb::open(&paths, PW).unwrap();
        assert!(!paths.pending_meta.exists());
    }

    #[test]
    fn sync_pull_push_and_conflicts() {
        let (_d, _p, mut db) = fresh();
        let a = db.insert(note("A"), 1).unwrap();
        let pull = db.pull(0).unwrap();
        assert_eq!(pull.rows.len(), 1);
        assert_eq!(pull.seq, db.seq().unwrap());
        assert!(db.pull(pull.seq).unwrap().rows.is_empty());

        // Phone edits A (base 1 → 2): accepted.
        let mut edited = db.get(&a.id).unwrap();
        edited.title = "A from phone".into();
        edited.updated_at = 50;
        let data = db.encrypt_for_sync(&edited, 2).unwrap();
        let res = db
            .push(&[SyncChange {
                id: a.id,
                base_version: 1,
                version: 2,
                updated_at: 50,
                deleted: false,
                encrypted_data: data.clone(),
            }])
            .unwrap();
        assert_eq!(res.accepted, vec![a.id]);
        assert_eq!(db.get(&a.id).unwrap().title, "A from phone");

        // Stale push (base 1 again): conflict returns the server row.
        let res = db
            .push(&[SyncChange {
                id: a.id,
                base_version: 1,
                version: 2,
                updated_at: 50,
                deleted: false,
                encrypted_data: data,
            }])
            .unwrap();
        assert!(res.accepted.is_empty());
        assert_eq!(res.conflicts[0].version, 2);

        // New entry from the phone.
        let new = VaultEntry::from_input(Uuid::new_v4(), note("Phone note"), 60, 60);
        let data = db.encrypt_for_sync(&new, 1).unwrap();
        let res = db
            .push(&[SyncChange {
                id: new.id,
                base_version: 0,
                version: 1,
                updated_at: 60,
                deleted: false,
                encrypted_data: data,
            }])
            .unwrap();
        assert_eq!(res.accepted, vec![new.id]);

        // Tombstone from the phone.
        let res = db
            .push(&[SyncChange {
                id: new.id,
                base_version: 1,
                version: 2,
                updated_at: 70,
                deleted: true,
                encrypted_data: vec![],
            }])
            .unwrap();
        assert_eq!(res.accepted, vec![new.id]);
        assert!(matches!(db.get(&new.id), Err(DbError::NotFound)));
    }

    #[test]
    fn push_rejects_bad_batches() {
        let (_d, _p, mut db) = fresh();
        let a = db.insert(note("A"), 1).unwrap();
        let e = db.get(&a.id).unwrap();
        // Data encrypted for the wrong version.
        let wrong = db.encrypt_for_sync(&e, 5).unwrap();
        assert!(matches!(
            db.push(&[SyncChange {
                id: a.id,
                base_version: 1,
                version: 2,
                updated_at: 1,
                deleted: false,
                encrypted_data: wrong
            }]),
            Err(DbError::Validation(_))
        ));
        // Version skip.
        assert!(
            db.push(&[SyncChange {
                id: a.id,
                base_version: 1,
                version: 3,
                updated_at: 1,
                deleted: true,
                encrypted_data: vec![]
            }])
            .is_err()
        );
        // Tombstone carrying data.
        assert!(
            db.push(&[SyncChange {
                id: a.id,
                base_version: 1,
                version: 2,
                updated_at: 1,
                deleted: true,
                encrypted_data: vec![1]
            }])
            .is_err()
        );
        assert_eq!(db.get(&a.id).unwrap().title, "A");
    }

    #[test]
    fn bootstrap_lets_a_client_unwrap() {
        let (_d, _p, db) = fresh();
        let b = db.bootstrap().unwrap();
        let master = derive_master_key(PW.as_bytes(), &b.meta.kdf).unwrap();
        let vk =
            crypto::unwrap_vault_key(&master.wrap_key(), &b.wrapped_vault_key, &b.meta.vault_id)
                .unwrap();
        assert_eq!(vk.as_bytes(), db.vault_key.as_bytes());
        assert!(db.verify_password(PW).unwrap());
        assert!(!db.verify_password("nope").unwrap());
    }
}
