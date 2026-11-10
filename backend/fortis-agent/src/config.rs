//! `~/.config/fortis/agent.toml` (architecture §3.2).
//!
//! Unknown keys are an error. There is deliberately no bind-address option:
//! the agent only ever listens on 127.0.0.1 and the Tailscale IPv4 address.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields, default)]
#[derive(Default)]
pub struct Config {
    pub rest: RestConfig,
    pub vault: VaultConfig,
    pub auth: AuthConfig,
    pub files: FilesConfig,
    pub input: InputConfig,
    pub webcam: WebcamConfig,
    pub backup: BackupConfig,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields, default)]
pub struct RestConfig {
    pub port: u16,
    pub tailnet: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields, default)]
pub struct VaultConfig {
    pub auto_lock_minutes: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields, default)]
pub struct AuthConfig {
    pub approval_timeout_secs: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields, default)]
pub struct FilesConfig {
    pub roots: Vec<String>,
    pub max_upload_mb: u64,
    pub camera_dir: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields, default)]
pub struct InputConfig {
    pub enabled: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields, default)]
pub struct WebcamConfig {
    pub enabled: bool,
    pub device: String,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields, default)]
pub struct BackupConfig {
    pub script: String,
    pub log: String,
}

impl Default for RestConfig {
    fn default() -> Self {
        Self {
            port: 8023,
            tailnet: true,
        }
    }
}

impl Default for VaultConfig {
    fn default() -> Self {
        Self {
            auto_lock_minutes: 15,
        }
    }
}

impl Default for AuthConfig {
    fn default() -> Self {
        Self {
            approval_timeout_secs: 30,
        }
    }
}

impl Default for FilesConfig {
    fn default() -> Self {
        Self {
            roots: vec!["~/Downloads".into(), "~/Documents".into()],
            max_upload_mb: 100,
            camera_dir: "~/Pictures/FortisCameraRoll".into(),
        }
    }
}

impl Default for WebcamConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            device: "/dev/video10".into(),
        }
    }
}

impl Default for BackupConfig {
    fn default() -> Self {
        Self {
            script: "~/dev/Fortis/Fortis-Ecosystem/fortis-backup/fortis-backup.sh".into(),
            log: "/mnt/FortisVault/Logs/mirror/mirror.log".into(),
        }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error("cannot read {0}: {1}")]
    Io(PathBuf, std::io::Error),
    #[error("invalid {0}: {1}")]
    Parse(PathBuf, toml::de::Error),
    #[error("invalid setting: {0}")]
    Invalid(String),
}

impl Config {
    pub fn validate(&self) -> Result<(), ConfigError> {
        if self.rest.port < 1024 {
            return Err(ConfigError::Invalid("rest.port must be >= 1024".into()));
        }
        if !(1..=24 * 60).contains(&self.vault.auto_lock_minutes) {
            return Err(ConfigError::Invalid(
                "vault.auto_lock_minutes must be 1-1440".into(),
            ));
        }
        if !(1..=60).contains(&self.auth.approval_timeout_secs) {
            return Err(ConfigError::Invalid(
                "auth.approval_timeout_secs must be 1-60".into(),
            ));
        }
        if !(1..=4096).contains(&self.files.max_upload_mb) {
            return Err(ConfigError::Invalid(
                "files.max_upload_mb must be 1-4096".into(),
            ));
        }
        if self.files.roots.is_empty() {
            return Err(ConfigError::Invalid("files.roots must not be empty".into()));
        }
        Ok(())
    }

    pub fn from_toml(path: &Path, s: &str) -> Result<Self, ConfigError> {
        let c: Config = toml::from_str(s).map_err(|e| ConfigError::Parse(path.into(), e))?;
        c.validate()?;
        Ok(c)
    }

    /// Load, or write the defaults (mode 0600) if the file doesn't exist.
    pub fn load_or_create(path: &Path) -> Result<Self, ConfigError> {
        match std::fs::read_to_string(path) {
            Ok(s) => Self::from_toml(path, &s),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                let c = Config::default();
                let text = format!(
                    "# Fortis agent configuration — see docs/architecture/README.md §3.2\n{}",
                    toml::to_string_pretty(&c).expect("default config serialises")
                );
                fortis_core::meta::write_private_atomic(path, text.as_bytes())
                    .map_err(|e| ConfigError::Io(path.into(), e))?;
                Ok(c)
            }
            Err(e) => Err(ConfigError::Io(path.into(), e)),
        }
    }

    pub fn max_upload_bytes(&self) -> usize {
        (self.files.max_upload_mb as usize) * 1024 * 1024
    }
}

/// Expand a leading `~/` against `home`.
pub fn expand(home: &Path, p: &str) -> PathBuf {
    if p == "~" {
        home.to_path_buf()
    } else if let Some(rest) = p.strip_prefix("~/") {
        home.join(rest)
    } else {
        PathBuf::from(p)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_roundtrip_and_validate() {
        let c = Config::default();
        c.validate().unwrap();
        let s = toml::to_string_pretty(&c).unwrap();
        assert_eq!(Config::from_toml(Path::new("x"), &s).unwrap(), c);
    }

    #[test]
    fn unknown_keys_and_bad_values_rejected() {
        assert!(Config::from_toml(Path::new("x"), "[rest]\nbind = \"0.0.0.0\"\n").is_err());
        assert!(
            Config::from_toml(Path::new("x"), "[auth]\napproval_timeout_secs = 120\n").is_err()
        );
        assert!(Config::from_toml(Path::new("x"), "[rest]\nport = 80\n").is_err());
        let c = Config::from_toml(Path::new("x"), "[input]\nenabled = true\n").unwrap();
        assert!(c.input.enabled && c.rest.port == 8023);
    }

    #[test]
    fn tilde_expansion() {
        let h = Path::new("/home/u");
        assert_eq!(expand(h, "~/Downloads"), PathBuf::from("/home/u/Downloads"));
        assert_eq!(expand(h, "/mnt/x"), PathBuf::from("/mnt/x"));
    }
}
