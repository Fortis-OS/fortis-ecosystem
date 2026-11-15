//! Paired phones and pairing sessions (security §5.4).

use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use rand::{Rng, rngs::OsRng};
use serde::{Deserialize, Serialize};
use tokio::sync::oneshot;
use uuid::Uuid;

use fortis_core::crypto::ct_eq;
use fortis_core::sign::PublicKey;

pub const MAX_DEVICES: usize = 16;
pub const PAIRING_TTL: Duration = Duration::from_secs(120);
pub const PAIRING_ATTEMPTS: u8 = 5;
const CROCKFORD: &[u8] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct Device {
    pub device_id: Uuid,
    pub name: String,
    /// base64 SEC1 P-256
    pub request_pubkey: String,
    /// base64 SEC1 P-256
    pub approval_pubkey: String,
    pub paired_at: i64,
    pub last_seen: i64,
}

impl Device {
    pub fn request_key(&self) -> Option<PublicKey> {
        PublicKey::from_base64(&self.request_pubkey).ok()
    }
    pub fn approval_key(&self) -> Option<PublicKey> {
        PublicKey::from_base64(&self.approval_pubkey).ok()
    }
}

#[derive(Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct DeviceFile {
    version: u32,
    devices: Vec<Device>,
}

/// Minimum time between disk writes of a device's `last_seen`.
const SEEN_PERSIST_SECS: i64 = 600;

pub struct DeviceStore {
    path: PathBuf,
    devices: Mutex<Vec<Device>>,
}

impl DeviceStore {
    pub fn load(path: PathBuf) -> std::io::Result<Self> {
        let devices = match std::fs::read_to_string(&path) {
            Ok(s) => {
                serde_json::from_str::<DeviceFile>(&s)
                    .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))?
                    .devices
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => vec![],
            Err(e) => return Err(e),
        };
        Ok(Self {
            path,
            devices: Mutex::new(devices),
        })
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Vec<Device>> {
        self.devices.lock().unwrap_or_else(|p| p.into_inner())
    }

    fn save(&self, devices: &[Device]) -> std::io::Result<()> {
        let f = DeviceFile {
            version: 1,
            devices: devices.to_vec(),
        };
        let s = serde_json::to_string_pretty(&f).expect("devices serialise");
        fortis_core::meta::write_private_atomic(&self.path, s.as_bytes())
    }

    pub fn list(&self) -> Vec<Device> {
        self.lock().clone()
    }

    pub fn get(&self, id: &Uuid) -> Option<Device> {
        self.lock().iter().find(|d| d.device_id == *id).cloned()
    }

    pub fn add(&self, d: Device) -> std::io::Result<()> {
        let mut g = self.lock();
        if g.len() >= MAX_DEVICES {
            return Err(std::io::Error::other("too many paired devices"));
        }
        g.push(d);
        self.save(&g)
    }

    pub fn remove(&self, id: &Uuid) -> std::io::Result<bool> {
        let mut g = self.lock();
        let before = g.len();
        g.retain(|d| d.device_id != *id);
        if g.len() == before {
            return Ok(false);
        }
        self.save(&g)?;
        Ok(true)
    }

    /// Record that a device was seen. Written to disk at most once per
    /// `SEEN_PERSIST_SECS` per device, so "last seen" survives agent restarts.
    pub fn touch(&self, id: &Uuid, now: i64) {
        let mut g = self.lock();
        let Some(d) = g.iter_mut().find(|d| d.device_id == *id) else {
            return;
        };
        let persist = now - d.last_seen >= SEEN_PERSIST_SECS;
        d.last_seen = now;
        if persist && let Err(e) = self.save(&g) {
            log::warn!("could not save device last_seen: {e}");
        }
    }
}

/// Generate a 10-character Crockford base32 code (50 bits).
pub fn new_code() -> String {
    let mut rng = OsRng;
    (0..10)
        .map(|_| CROCKFORD[rng.gen_range(0..CROCKFORD.len())] as char)
        .collect()
}

/// `7KQ2M9XD4H` → `7KQ2-M9XD-4H`
pub fn display_code(code: &str) -> String {
    format!("{}-{}-{}", &code[..4], &code[4..8], &code[8..])
}

/// Crockford normalisation: uppercase, drop separators, O→0, I/L→1.
pub fn normalize_code(input: &str) -> String {
    input
        .chars()
        .filter(|c| !matches!(c, '-' | ' '))
        .map(|c| match c.to_ascii_uppercase() {
            'O' => '0',
            'I' | 'L' => '1',
            c => c,
        })
        .collect()
}

pub struct PairingSession {
    pub code: String,
    pub expires_at: Instant,
    pub attempts_left: u8,
    pub done: Option<oneshot::Sender<Device>>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum PairCheck {
    Ok,
    NoSession,
    Wrong,
    Exhausted,
}

impl PairingSession {
    pub fn new() -> (Self, oneshot::Receiver<Device>) {
        let (tx, rx) = oneshot::channel();
        (
            Self {
                code: new_code(),
                expires_at: Instant::now() + PAIRING_TTL,
                attempts_left: PAIRING_ATTEMPTS,
                done: Some(tx),
            },
            rx,
        )
    }
}

/// Check a submitted code against the open session (if any), consuming attempts.
pub fn check_code(slot: &mut Option<PairingSession>, submitted: &str) -> PairCheck {
    let Some(s) = slot.as_mut() else {
        return PairCheck::NoSession;
    };
    if Instant::now() > s.expires_at {
        *slot = None;
        return PairCheck::NoSession;
    }
    if ct_eq(normalize_code(submitted).as_bytes(), s.code.as_bytes()) {
        return PairCheck::Ok;
    }
    s.attempts_left = s.attempts_left.saturating_sub(1);
    if s.attempts_left == 0 {
        *slot = None;
        return PairCheck::Exhausted;
    }
    PairCheck::Wrong
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn code_format() {
        let c = new_code();
        assert_eq!(c.len(), 10);
        assert!(c.bytes().all(|b| CROCKFORD.contains(&b)));
        assert_eq!(normalize_code(&display_code(&c).to_lowercase()), c);
        assert_eq!(normalize_code("o1l-i"), "0111");
    }

    #[test]
    fn attempts_are_limited() {
        let (s, _rx) = PairingSession::new();
        let code = s.code.clone();
        let mut slot = Some(s);
        for _ in 0..4 {
            assert_eq!(check_code(&mut slot, "WRONGWRONG"), PairCheck::Wrong);
        }
        assert_eq!(check_code(&mut slot, "WRONGWRONG"), PairCheck::Exhausted);
        assert_eq!(check_code(&mut slot, &code), PairCheck::NoSession);
    }

    #[test]
    fn expired_session() {
        let (mut s, _rx) = PairingSession::new();
        s.expires_at = Instant::now() - Duration::from_secs(1);
        let code = s.code.clone();
        let mut slot = Some(s);
        assert_eq!(check_code(&mut slot, &code), PairCheck::NoSession);
        assert!(slot.is_none());
    }

    #[test]
    fn store_persists() {
        let t = tempfile::tempdir().unwrap();
        let p = t.path().join("paired_devices.json");
        let s = DeviceStore::load(p.clone()).unwrap();
        let d = Device {
            device_id: Uuid::new_v4(),
            name: "Pixel".into(),
            request_pubkey: "a".into(),
            approval_pubkey: "b".into(),
            paired_at: 1,
            last_seen: 1,
        };
        s.add(d.clone()).unwrap();
        assert_eq!(
            DeviceStore::load(p.clone()).unwrap().list(),
            vec![d.clone()]
        );
        assert!(s.remove(&d.device_id).unwrap());
        assert!(DeviceStore::load(p).unwrap().list().is_empty());
    }

    #[test]
    fn last_seen_reaches_disk_at_most_every_10_min() {
        let t = tempfile::tempdir().unwrap();
        let p = t.path().join("paired_devices.json");
        let s = DeviceStore::load(p.clone()).unwrap();
        let id = Uuid::new_v4();
        s.add(Device {
            device_id: id,
            name: "Pixel".into(),
            request_pubkey: "a".into(),
            approval_pubkey: "b".into(),
            paired_at: 1,
            last_seen: 1_000,
        })
        .unwrap();
        let on_disk = || {
            DeviceStore::load(p.clone())
                .unwrap()
                .get(&id)
                .unwrap()
                .last_seen
        };
        s.touch(&id, 1_000 + SEEN_PERSIST_SECS); // 10 min later: written
        assert_eq!(on_disk(), 1_000 + SEEN_PERSIST_SECS);
        s.touch(&id, 1_000 + SEEN_PERSIST_SECS + 60); // 1 min after that: memory only
        assert_eq!(on_disk(), 1_000 + SEEN_PERSIST_SECS);
        assert_eq!(
            s.get(&id).unwrap().last_seen,
            1_000 + SEEN_PERSIST_SECS + 60
        );
    }
}
