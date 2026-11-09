//! Interim vault state on top of the v2 `fortis-core` database.
//!
//! The unlocked vault is held in memory as a `VaultDb` (which owns the vault
//! key); locking drops it. Listener/auth hardening and auto-lock arrive with
//! the full agent rewrite (see docs/README.md implementation status).

use std::path::PathBuf;
use std::sync::{Mutex, MutexGuard};

use fortis_core::db::{DbError, VaultDb, VaultPaths};

static VAULT: Mutex<Option<VaultDb>> = Mutex::new(None);

fn guard() -> MutexGuard<'static, Option<VaultDb>> {
    // A panic while holding the lock must not brick the agent.
    VAULT.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

pub fn data_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    PathBuf::from(home).join(".local/share/fortis")
}

pub fn paths() -> VaultPaths {
    VaultPaths::in_dir(data_dir())
}

fn ensure_dir() -> Result<(), DbError> {
    let dir = data_dir();
    std::fs::create_dir_all(&dir)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700))?;
    }
    Ok(())
}

pub fn state() -> &'static str {
    if guard().is_some() {
        "unlocked"
    } else if paths().exists() {
        "locked"
    } else {
        "uninitialised"
    }
}

/// Create a new vault (min 12-char password) and leave it unlocked.
pub fn init(password: &str) -> Result<(), DbError> {
    if password.chars().count() < 12 {
        return Err(fortis_core::vault::ValidationError("password must be at least 12 characters".into()).into());
    }
    ensure_dir()?;
    let db = VaultDb::create(&paths(), password)?;
    *guard() = Some(db);
    Ok(())
}

pub fn unlock(password: &str) -> Result<(), DbError> {
    let db = VaultDb::open(&paths(), password)?;
    *guard() = Some(db);
    Ok(())
}

pub fn lock() {
    *guard() = None;
}

/// Run `f` against the unlocked vault.
pub fn with_db<T>(f: impl FnOnce(&mut VaultDb) -> Result<T, DbError>) -> Result<T, VaultAccessError> {
    let mut g = guard();
    let db = g.as_mut().ok_or(VaultAccessError::Locked)?;
    f(db).map_err(VaultAccessError::Db)
}

#[derive(Debug)]
pub enum VaultAccessError {
    Locked,
    Db(DbError),
}

impl std::fmt::Display for VaultAccessError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            VaultAccessError::Locked => f.write_str("vault is locked"),
            VaultAccessError::Db(e) => write!(f, "{e}"),
        }
    }
}
