//! Filesystem layout and permissions (security §7.1).

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use log::warn;

#[derive(Clone, Debug)]
pub struct Paths {
    pub home: PathBuf,
    /// `~/.config/fortis`
    pub config_dir: PathBuf,
    /// `~/.local/share/fortis`
    pub data_dir: PathBuf,
    /// `$XDG_RUNTIME_DIR/fortis`
    pub runtime_dir: PathBuf,
}

impl Paths {
    /// Resolve from the environment. `XDG_RUNTIME_DIR` is required: the IPC
    /// socket must never live in `/tmp` (security §7.2).
    pub fn from_env() -> Result<Self, String> {
        let home = std::env::var_os("HOME")
            .map(PathBuf::from)
            .ok_or("HOME is not set")?;
        let xdg = |var: &str, fallback: &str| {
            std::env::var_os(var)
                .filter(|v| !v.is_empty())
                .map(PathBuf::from)
                .unwrap_or_else(|| home.join(fallback))
        };
        let runtime = std::env::var_os("XDG_RUNTIME_DIR")
            .filter(|v| !v.is_empty())
            .map(PathBuf::from)
            .ok_or("XDG_RUNTIME_DIR is not set (run the agent as a systemd user service)")?;
        Ok(Self {
            config_dir: xdg("XDG_CONFIG_HOME", ".config").join("fortis"),
            data_dir: xdg("XDG_DATA_HOME", ".local/share").join("fortis"),
            runtime_dir: runtime.join("fortis"),
            home,
        })
    }

    /// Everything under one directory (tests).
    #[cfg(test)]
    pub fn under(root: &Path) -> Self {
        Self {
            home: root.to_path_buf(),
            config_dir: root.join(".config/fortis"),
            data_dir: root.join(".local/share/fortis"),
            runtime_dir: root.join("run/fortis"),
        }
    }

    pub fn config_file(&self) -> PathBuf {
        self.config_dir.join("agent.toml")
    }
    pub fn token_file(&self) -> PathBuf {
        self.config_dir.join("agent.token")
    }
    pub fn key_file(&self) -> PathBuf {
        self.config_dir.join("agent.key")
    }
    pub fn devices_file(&self) -> PathBuf {
        self.config_dir.join("paired_devices.json")
    }
    pub fn browser_lock_file(&self) -> PathBuf {
        self.config_dir.join("browser-lock.json")
    }
    pub fn socket(&self) -> PathBuf {
        self.runtime_dir.join("agent.sock")
    }
    pub fn drop_dir(&self) -> PathBuf {
        self.home.join("Downloads/FortisMobileDrop")
    }

    /// Create directories with 0700 and tighten anything too open.
    pub fn prepare(&self) -> io::Result<()> {
        for d in [&self.config_dir, &self.data_dir, &self.runtime_dir] {
            fs::create_dir_all(d)?;
            tighten(d, 0o700)?;
        }
        for dir in [&self.config_dir, &self.data_dir] {
            for entry in fs::read_dir(dir)? {
                let p = entry?.path();
                if p.is_file() {
                    tighten(&p, 0o600)?;
                }
            }
        }
        Ok(())
    }
}

/// Set `mode` if the current mode allows more than that.
pub fn tighten(path: &Path, mode: u32) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let cur = fs::metadata(path)?.permissions().mode() & 0o777;
    if cur & !mode != 0 {
        warn!(
            "tightening permissions of {} from {:o} to {:o}",
            path.display(),
            cur,
            mode
        );
        fs::set_permissions(path, fs::Permissions::from_mode(mode))?;
    }
    Ok(())
}

/// Read a secret file or create it with `gen()` (mode 0600).
pub fn read_or_create(path: &Path, generate: impl FnOnce() -> Vec<u8>) -> io::Result<Vec<u8>> {
    match fs::read(path) {
        Ok(b) => Ok(b),
        Err(e) if e.kind() == io::ErrorKind::NotFound => {
            let b = generate();
            fortis_core::meta::write_private_atomic(path, &b)?;
            Ok(b)
        }
        Err(e) => Err(e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    #[test]
    fn prepare_creates_private_dirs_and_tightens_files() {
        let t = tempfile::tempdir().unwrap();
        let p = Paths::under(t.path());
        fs::create_dir_all(&p.config_dir).unwrap();
        let f = p.config_dir.join("paired_devices.json");
        fs::write(&f, "{}").unwrap();
        fs::set_permissions(&f, fs::Permissions::from_mode(0o644)).unwrap();
        p.prepare().unwrap();
        assert_eq!(
            fs::metadata(&p.runtime_dir).unwrap().permissions().mode() & 0o777,
            0o700
        );
        assert_eq!(
            fs::metadata(&f).unwrap().permissions().mode() & 0o777,
            0o600
        );
    }

    #[test]
    fn read_or_create_is_stable() {
        let t = tempfile::tempdir().unwrap();
        let f = t.path().join("agent.token");
        let a = read_or_create(&f, || b"one".to_vec()).unwrap();
        let b = read_or_create(&f, || b"two".to_vec()).unwrap();
        assert_eq!(a, b);
    }
}
