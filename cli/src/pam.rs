//! `sudo fortis pam-enroll` — write `/etc/fortis/pam/<user>.json`, the only
//! keys pam_fortis trusts (security §4.2, §4.5).

use std::fs;
use std::io::Write;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};

use fortis_core::sign::PublicKey;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::entries::Res;
use crate::ipc::Client;
use crate::prompt;

pub const PAM_DIR: &str = "/etc/fortis/pam";

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct TrustDevice {
    pub device_id: String,
    pub name: String,
    pub approval_pubkey: String,
    pub enrolled_at: i64,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct TrustFile {
    pub version: u32,
    pub devices: Vec<TrustDevice>,
}

/// POSIX-ish login names only — the name becomes part of a path under /etc.
pub fn valid_username(u: &str) -> bool {
    !u.is_empty()
        && u.len() <= 32
        && u.bytes()
            .next()
            .is_some_and(|b| b.is_ascii_lowercase() || b == b'_')
        && u.bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_' || b == b'-')
}

/// Build the new trust file from the agent's devices. Keys are re-parsed
/// here; nothing from the agent is trusted blindly.
pub fn build(existing: Option<&TrustFile>, agent_devices: &[Value], now: i64) -> Res<TrustFile> {
    let mut devices = Vec::new();
    for d in agent_devices {
        let id = d["device_id"].as_str().ok_or("device without id")?;
        uuid::Uuid::parse_str(id).map_err(|_| "invalid device id")?;
        let name: String = d["name"]
            .as_str()
            .unwrap_or("phone")
            .chars()
            .filter(|c| !c.is_control())
            .take(64)
            .collect();
        let key = PublicKey::from_base64(
            d["approval_pubkey"]
                .as_str()
                .ok_or("device without approval key")?,
        )
        .map_err(|_| format!("device {id} has an invalid approval key"))?;
        let key_b64 = key.to_base64();
        let enrolled_at = existing
            .and_then(|f| {
                f.devices
                    .iter()
                    .find(|o| o.device_id == id && o.approval_pubkey == key_b64)
            })
            .map(|o| o.enrolled_at)
            .unwrap_or(now);
        devices.push(TrustDevice {
            device_id: id.into(),
            name,
            approval_pubkey: key_b64,
            enrolled_at,
        });
    }
    Ok(TrustFile {
        version: 1,
        devices,
    })
}

fn ensure_root_dir(dir: &Path) -> std::io::Result<()> {
    fs::create_dir_all(dir)?;
    fs::set_permissions(dir, fs::Permissions::from_mode(0o755))
}

/// Atomic write: `<dir>/<user>.json`, mode 0644, owned by whoever runs this (root).
pub fn write(dir: &Path, user: &str, file: &TrustFile) -> std::io::Result<PathBuf> {
    if let Some(parent) = dir.parent() {
        ensure_root_dir(parent)?;
    }
    ensure_root_dir(dir)?;
    let path = dir.join(format!("{user}.json"));
    let tmp = dir.join(format!(".{user}.json.tmp"));
    {
        let mut f = fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o644)
            .open(&tmp)?;
        f.write_all(
            serde_json::to_string_pretty(file)
                .expect("serialises")
                .as_bytes(),
        )?;
        f.sync_all()?;
    }
    fs::set_permissions(&tmp, fs::Permissions::from_mode(0o644))?;
    fs::rename(&tmp, &path)?;
    Ok(path)
}

pub fn run(yes: bool) -> Res<()> {
    if unsafe { libc::geteuid() } != 0 {
        return Err(
            "pam-enroll writes to /etc and must be run with sudo:  sudo fortis pam-enroll".into(),
        );
    }
    let user = std::env::var("SUDO_USER")
        .map_err(|_| "run this through sudo from your own account (SUDO_USER is not set)")?;
    if !valid_username(&user) || user == "root" {
        return Err(format!("refusing to enroll for user {user:?}").into());
    }

    let mut client = Client::connect()?;
    let devices = client.call("pam_devices", json!({ "user": user }))?;
    let devices = devices.as_array().cloned().unwrap_or_default();

    let path = Path::new(PAM_DIR).join(format!("{user}.json"));
    let existing: Option<TrustFile> = fs::read_to_string(&path)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok());
    let file = build(existing.as_ref(), &devices, fortis_core::unix_now())?;

    if file.devices.is_empty() {
        eprintln!(
            "No paired phones. Fingerprint sudo will be disabled for {user} (password only)."
        );
    } else {
        eprintln!("Phones that will be able to approve sudo for {user}:");
        for d in &file.devices {
            let fp = PublicKey::from_base64(&d.approval_pubkey)
                .map(|k| k.fingerprint())
                .unwrap_or_default();
            eprintln!("  {}  {}  approval key {}", &d.device_id[..8], d.name, fp);
        }
        eprintln!("Check each fingerprint matches the one shown in the Fortis app.");
    }
    if !yes && !prompt::confirm(&format!("Write {}?", path.display()))? {
        return Err("cancelled".into());
    }
    let written = write(Path::new(PAM_DIR), &user, &file)?;
    println!(
        "Wrote {} ({} device(s)).",
        written.display(),
        file.devices.len()
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use fortis_core::sign::AgentKey;

    #[test]
    fn usernames() {
        assert!(valid_username("ganesh"));
        assert!(valid_username("_svc-1"));
        assert!(!valid_username("../etc"));
        assert!(!valid_username("Ganesh"));
        assert!(!valid_username(""));
        assert!(!valid_username("a/b"));
    }

    #[test]
    fn build_validates_keys_and_keeps_dates() {
        let k = AgentKey::generate().public().to_base64();
        let id = uuid::Uuid::new_v4().to_string();
        let devs = vec![json!({"device_id": id, "name": "Pixel\n8", "approval_pubkey": k})];
        let f1 = build(None, &devs, 100).unwrap();
        assert_eq!(f1.devices[0].name, "Pixel8");
        assert_eq!(f1.devices[0].enrolled_at, 100);
        let f2 = build(Some(&f1), &devs, 200).unwrap();
        assert_eq!(f2.devices[0].enrolled_at, 100);
        let bad = vec![json!({"device_id": id, "name": "x", "approval_pubkey": "bm90IGEga2V5"})];
        assert!(build(None, &bad, 1).is_err());
        let bad_id = vec![json!({"device_id": "../x", "name": "x", "approval_pubkey": k})];
        assert!(build(None, &bad_id, 1).is_err());
    }

    #[test]
    fn write_is_atomic_and_readable() {
        let t = tempfile::tempdir().unwrap();
        let dir = t.path().join("fortis/pam");
        let f = TrustFile {
            version: 1,
            devices: vec![],
        };
        let p = write(&dir, "ganesh", &f).unwrap();
        assert_eq!(
            fs::metadata(&p).unwrap().permissions().mode() & 0o777,
            0o644
        );
        assert_eq!(
            fs::metadata(&dir).unwrap().permissions().mode() & 0o777,
            0o755
        );
        assert_eq!(
            serde_json::from_str::<TrustFile>(&fs::read_to_string(&p).unwrap()).unwrap(),
            f
        );
        assert!(!dir.join(".ganesh.json.tmp").exists());
    }
}
