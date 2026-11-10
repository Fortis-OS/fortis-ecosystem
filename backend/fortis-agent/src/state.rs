//! Shared agent state and vault lifecycle (security §3.4, §3.5).

use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::sync::{Arc, Mutex, MutexGuard, RwLock};
use std::time::{Duration, Instant};

use serde_json::json;
use uuid::Uuid;
use zeroize::Zeroizing;

use fortis_core::db::{DbError, VaultDb, VaultPaths};
use fortis_core::sign::AgentKey;

use crate::config::Config;
use crate::devices::{DeviceStore, PairingSession};
use crate::error::{ApiError, ApiResult};
use crate::hub::{Approvals, EventHub, Sessions};
use crate::paths::Paths;

pub const MIN_PASSWORD_CHARS: usize = 12;

pub fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

#[derive(Default)]
struct VaultSlot {
    db: Option<VaultDb>,
    last_activity: Option<Instant>,
    /// Bumped on every lock; lets long operations detect a lock that
    /// happened while they had the database checked out.
    generation: u64,
}

/// Unlock back-off: after 5 consecutive failures, wait 2^(n-5) s (cap 300 s).
#[derive(Default)]
struct UnlockLimiter {
    failures: u32,
    next_allowed: Option<Instant>,
}

impl UnlockLimiter {
    fn check(&self) -> Result<(), u64> {
        match self.next_allowed {
            Some(t) if Instant::now() < t => Err((t - Instant::now()).as_secs() + 1),
            _ => Ok(()),
        }
    }

    fn failed(&mut self) {
        self.failures += 1;
        if self.failures >= 5 {
            let secs = 2u64.saturating_pow(self.failures - 5).min(300);
            self.next_allowed = Some(Instant::now() + Duration::from_secs(secs));
        }
    }

    fn succeeded(&mut self) {
        *self = Self::default();
    }
}

pub struct AppState {
    pub config: Config,
    pub paths: Paths,
    /// Local bearer token (hex), compared in constant time.
    pub token: Zeroizing<String>,
    pub agent_key: AgentKey,
    pub hostname: String,
    pub devices: DeviceStore,
    pub pairing: Mutex<Option<PairingSession>>,
    pub events: EventHub,
    pub approvals: Approvals,
    pub sessions: Sessions,
    pub tailnet_ip: RwLock<Option<Ipv4Addr>>,
    pub webcam: tokio::sync::Mutex<Option<tokio::process::Child>>,
    vault: Mutex<VaultSlot>,
    limiter: Mutex<UnlockLimiter>,
    nonces: Mutex<HashMap<Uuid, HashMap<String, Instant>>>,
    notify_last: Mutex<HashMap<Uuid, Instant>>,
}

pub type Shared = Arc<AppState>;

impl AppState {
    pub fn new(
        config: Config,
        paths: Paths,
        token: String,
        agent_key: AgentKey,
    ) -> std::io::Result<Self> {
        let hostname = std::fs::read_to_string("/proc/sys/kernel/hostname")
            .map(|s| s.trim().to_string())
            .unwrap_or_else(|_| "laptop".into());
        Ok(Self {
            devices: DeviceStore::load(paths.devices_file())?,
            config,
            paths,
            token: Zeroizing::new(token),
            agent_key,
            hostname,
            pairing: Mutex::new(None),
            events: EventHub::default(),
            approvals: Approvals::default(),
            sessions: Sessions::default(),
            tailnet_ip: RwLock::new(None),
            webcam: tokio::sync::Mutex::new(None),
            vault: Mutex::new(VaultSlot::default()),
            limiter: Mutex::new(UnlockLimiter::default()),
            nonces: Mutex::new(HashMap::new()),
            notify_last: Mutex::new(HashMap::new()),
        })
    }

    pub fn vault_paths(&self) -> VaultPaths {
        VaultPaths::in_dir(&self.paths.data_dir)
    }

    pub fn vault_state(&self) -> &'static str {
        if lock(&self.vault).db.is_some() {
            "unlocked"
        } else if self.vault_paths().exists() {
            "locked"
        } else {
            "uninitialised"
        }
    }

    pub fn locks_in_secs(&self) -> Option<u64> {
        let g = lock(&self.vault);
        g.db.as_ref()?;
        let idle = g.last_activity.map(|t| t.elapsed()).unwrap_or_default();
        Some(self.auto_lock_after().saturating_sub(idle).as_secs())
    }

    fn auto_lock_after(&self) -> Duration {
        Duration::from_secs(self.config.vault.auto_lock_minutes * 60)
    }

    fn broadcast_vault_state(&self) {
        self.events.publish(
            &self.agent_key,
            json!({ "type": "vault_state", "state": self.vault_state() }),
        );
    }

    /// Run `f` on the unlocked vault; counts as activity for auto-lock.
    pub fn with_vault<T>(
        &self,
        f: impl FnOnce(&mut VaultDb) -> Result<T, DbError>,
    ) -> ApiResult<T> {
        let mut g = lock(&self.vault);
        if g.db.is_none() {
            drop(g);
            return Err(if self.vault_paths().exists() {
                ApiError::locked()
            } else {
                ApiError::uninitialised()
            });
        }
        g.last_activity = Some(Instant::now());
        let db = g.db.as_mut().expect("checked");
        Ok(f(db)?)
    }

    /// Drop (and so zeroise) the vault key and connection.
    pub fn lock_vault(&self, reason: &str) -> bool {
        let was = {
            let mut g = lock(&self.vault);
            g.generation += 1;
            g.last_activity = None;
            g.db.take().is_some()
        };
        if was {
            log::info!("vault locked ({reason})");
            self.broadcast_vault_state();
        }
        was
    }

    /// Lock if idle for longer than `vault.auto_lock_minutes`.
    pub fn auto_lock_tick(&self) {
        let idle = {
            let g = lock(&self.vault);
            g.db.is_some()
                && g.last_activity
                    .is_some_and(|t| t.elapsed() >= self.auto_lock_after())
        };
        if idle {
            self.lock_vault("idle timeout");
        }
    }

    fn store_unlocked(&self, db: VaultDb) {
        {
            let mut g = lock(&self.vault);
            g.db = Some(db);
            g.last_activity = Some(Instant::now());
        }
        self.broadcast_vault_state();
    }

    pub async fn unlock(self: &Arc<Self>, password: Zeroizing<String>) -> ApiResult<()> {
        lock(&self.limiter)
            .check()
            .map_err(ApiError::rate_limited)?;
        if lock(&self.vault).db.is_some() {
            return Ok(());
        }
        let paths = self.vault_paths();
        let res = tokio::task::spawn_blocking(move || VaultDb::open(&paths, &password))
            .await
            .map_err(ApiError::internal)?;
        match res {
            Ok(db) => {
                lock(&self.limiter).succeeded();
                self.store_unlocked(db);
                log::info!("vault unlocked");
                Ok(())
            }
            Err(DbError::InvalidPassword) => {
                lock(&self.limiter).failed();
                log::warn!("vault unlock failed: wrong password");
                Err(ApiError::invalid_password())
            }
            Err(e) => Err(e.into()),
        }
    }

    pub async fn init(self: &Arc<Self>, password: Zeroizing<String>) -> ApiResult<()> {
        if password.chars().count() < MIN_PASSWORD_CHARS {
            return Err(ApiError::bad_request(format!(
                "password must be at least {MIN_PASSWORD_CHARS} characters"
            )));
        }
        let paths = self.vault_paths();
        let db = tokio::task::spawn_blocking(move || VaultDb::create(&paths, &password))
            .await
            .map_err(ApiError::internal)??;
        self.store_unlocked(db);
        log::info!("vault created");
        Ok(())
    }

    pub async fn change_password(
        self: &Arc<Self>,
        current: Zeroizing<String>,
        new: Zeroizing<String>,
    ) -> ApiResult<()> {
        if new.chars().count() < MIN_PASSWORD_CHARS {
            return Err(ApiError::bad_request(format!(
                "password must be at least {MIN_PASSWORD_CHARS} characters"
            )));
        }
        lock(&self.limiter)
            .check()
            .map_err(ApiError::rate_limited)?;
        // Check the database out so Argon2 doesn't run under the mutex.
        let (mut db, generation) = {
            let mut g = lock(&self.vault);
            let db = g.db.take().ok_or_else(ApiError::locked)?;
            (db, g.generation)
        };
        let paths = self.vault_paths();
        let (db, res) = tokio::task::spawn_blocking(move || {
            let r = db.change_password(&paths, &current, &new);
            (db, r)
        })
        .await
        .map_err(ApiError::internal)?;
        {
            let mut g = lock(&self.vault);
            if g.generation == generation {
                g.db = Some(db);
                g.last_activity = Some(Instant::now());
            }
        }
        match res {
            Ok(()) => {
                lock(&self.limiter).succeeded();
                log::info!("master password changed");
                Ok(())
            }
            Err(DbError::InvalidPassword) => {
                lock(&self.limiter).failed();
                Err(ApiError::invalid_password())
            }
            Err(e) => Err(e.into()),
        }
    }

    /// Replay protection for signed requests: a nonce may be used once per
    /// device within the 120 s window (security §5.3).
    pub fn check_nonce(&self, device: Uuid, nonce: &str) -> bool {
        let mut g = lock(&self.nonces);
        let window = Duration::from_secs(120);
        let per = g.entry(device).or_default();
        per.retain(|_, t| t.elapsed() < window);
        if per.len() > 10_000 {
            return false;
        }
        per.insert(nonce.to_string(), Instant::now()).is_none()
    }

    /// Max one mirrored notification per second per device.
    pub fn notify_allowed(&self, device: Uuid) -> bool {
        let mut g = lock(&self.notify_last);
        match g.get(&device) {
            Some(t) if t.elapsed() < Duration::from_secs(1) => false,
            _ => {
                g.insert(device, Instant::now());
                true
            }
        }
    }

    pub fn tailnet_ip(&self) -> Option<Ipv4Addr> {
        *self.tailnet_ip.read().unwrap_or_else(|p| p.into_inner())
    }
}

#[cfg(test)]
pub mod test_support {
    use super::*;

    pub const TOKEN: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    pub fn state(dir: &std::path::Path, tweak: impl FnOnce(&mut Config)) -> Shared {
        let paths = Paths::under(dir);
        paths.prepare().unwrap();
        let mut cfg = Config::default();
        tweak(&mut cfg);
        Arc::new(AppState::new(cfg, paths, TOKEN.into(), AgentKey::generate()).unwrap())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn limiter_backoff() {
        let mut l = UnlockLimiter::default();
        for _ in 0..4 {
            l.failed();
            assert!(l.check().is_ok());
        }
        l.failed();
        assert!(l.check().is_err());
        l.succeeded();
        assert!(l.check().is_ok());
    }

    #[test]
    fn nonce_replay() {
        let t = tempfile::tempdir().unwrap();
        let s = test_support::state(t.path(), |_| {});
        let d = Uuid::new_v4();
        assert!(s.check_nonce(d, "aa"));
        assert!(!s.check_nonce(d, "aa"));
        assert!(s.check_nonce(Uuid::new_v4(), "aa"));
    }

    #[tokio::test]
    async fn vault_lifecycle_and_auto_lock() {
        let t = tempfile::tempdir().unwrap();
        let s = test_support::state(t.path(), |_| {});
        assert_eq!(s.vault_state(), "uninitialised");
        assert!(s.init(Zeroizing::new("short".into())).await.is_err());
        s.init(Zeroizing::new("long enough password".into()))
            .await
            .unwrap();
        assert_eq!(s.vault_state(), "unlocked");
        assert!(s.locks_in_secs().unwrap() > 800);
        s.lock_vault("test");
        assert_eq!(s.vault_state(), "locked");
        assert_eq!(
            s.with_vault(|db| db.seq()).unwrap_err().code,
            "vault_locked"
        );
        assert_eq!(
            s.unlock(Zeroizing::new("wrong".into()))
                .await
                .unwrap_err()
                .code,
            "invalid_password"
        );
        s.unlock(Zeroizing::new("long enough password".into()))
            .await
            .unwrap();
        // Force idle.
        lock(&s.vault).last_activity = Some(Instant::now() - Duration::from_secs(16 * 60));
        s.auto_lock_tick();
        assert_eq!(s.vault_state(), "locked");
    }

    #[tokio::test]
    async fn change_password_keeps_vault_unlocked() {
        let t = tempfile::tempdir().unwrap();
        let s = test_support::state(t.path(), |_| {});
        s.init(Zeroizing::new("long enough password".into()))
            .await
            .unwrap();
        let wrong = s
            .change_password(
                Zeroizing::new("nope".into()),
                Zeroizing::new("another long password".into()),
            )
            .await;
        assert_eq!(wrong.unwrap_err().code, "invalid_password");
        s.change_password(
            Zeroizing::new("long enough password".into()),
            Zeroizing::new("another long password".into()),
        )
        .await
        .unwrap();
        assert_eq!(s.vault_state(), "unlocked");
        s.lock_vault("test");
        s.unlock(Zeroizing::new("another long password".into()))
            .await
            .unwrap();
    }
}
