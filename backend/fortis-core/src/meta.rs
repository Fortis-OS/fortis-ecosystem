//! `vault.meta.json` — the plaintext vault header (security §3.1).

use std::fs;
use std::io::Write;
use std::path::Path;

use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::crypto::{CryptoError, KdfParams};

pub const FORMAT_VERSION: u32 = 2;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct VaultMeta {
    pub format_version: u32,
    pub kdf: KdfParams,
    pub vault_id: Uuid,
}

#[derive(Debug, thiserror::Error)]
pub enum MetaError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("invalid vault.meta.json: {0}")]
    Parse(#[from] serde_json::Error),
    #[error("unsupported vault format version {0}")]
    Version(u32),
    #[error(transparent)]
    Kdf(#[from] CryptoError),
}

impl VaultMeta {
    /// New vault header: fresh vault ID and random salt.
    pub fn new() -> Self {
        Self {
            format_version: FORMAT_VERSION,
            kdf: KdfParams::new_random(),
            vault_id: Uuid::new_v4(),
        }
    }

    /// Same vault, new random salt (used when changing the master password).
    pub fn with_new_salt(&self) -> Self {
        Self {
            format_version: FORMAT_VERSION,
            kdf: KdfParams::new_random(),
            vault_id: self.vault_id,
        }
    }

    pub fn from_json(s: &str) -> Result<Self, MetaError> {
        let m: Self = serde_json::from_str(s)?;
        m.validate()?;
        Ok(m)
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string_pretty(self).expect("VaultMeta always serialises")
    }

    pub fn validate(&self) -> Result<(), MetaError> {
        if self.format_version != FORMAT_VERSION {
            return Err(MetaError::Version(self.format_version));
        }
        self.kdf.validate()?;
        Ok(())
    }

    pub fn load(path: &Path) -> Result<Self, MetaError> {
        Self::from_json(&fs::read_to_string(path)?)
    }

    /// Atomic write: temp file (mode 0600) → fsync → rename.
    pub fn save(&self, path: &Path) -> Result<(), MetaError> {
        write_private_atomic(path, self.to_json().as_bytes())?;
        Ok(())
    }
}

impl Default for VaultMeta {
    fn default() -> Self {
        Self::new()
    }
}

/// Write `data` to `path` atomically with mode 0600.
pub fn write_private_atomic(path: &Path, data: &[u8]) -> std::io::Result<()> {
    let tmp = path.with_extension("tmp");
    {
        let mut opts = fs::OpenOptions::new();
        opts.write(true).create(true).truncate(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            opts.mode(0o600);
        }
        let mut f = opts.open(&tmp)?;
        f.write_all(data)?;
        f.sync_all()?;
    }
    fs::rename(&tmp, path)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_roundtrip() {
        let m = VaultMeta::new();
        assert_eq!(VaultMeta::from_json(&m.to_json()).unwrap(), m);
    }

    #[test]
    fn rejects_unknown_fields_and_bad_version() {
        let m = VaultMeta::new();
        let mut v: serde_json::Value = serde_json::from_str(&m.to_json()).unwrap();
        v["extra"] = 1.into();
        assert!(VaultMeta::from_json(&v.to_string()).is_err());
        let mut v: serde_json::Value = serde_json::from_str(&m.to_json()).unwrap();
        v["format_version"] = 1.into();
        assert!(matches!(
            VaultMeta::from_json(&v.to_string()),
            Err(MetaError::Version(1))
        ));
    }

    #[test]
    fn save_is_private() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("vault.meta.json");
        let m = VaultMeta::new();
        m.save(&p).unwrap();
        assert_eq!(VaultMeta::load(&p).unwrap(), m);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(&p).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
    }
}
