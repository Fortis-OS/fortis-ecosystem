//! Browser lock, "Fortis Lock" (security §7.8).
//!
//! The agent owns the state: whether the chosen sites are hidden in the browser, which
//! sites, and the optional unlock password (argon2id). The browser extension follows it
//! through `lock_watch`, the phone through `/browser-lock` and the `browser_lock` event.

use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::sync::watch;
use zeroize::Zeroizing;

use fortis_core::crypto::{KdfParams, ct_eq, derive_master_key};

use crate::error::{ApiError, ApiResult};
use crate::state::Shared;

pub const MAX_SITES: usize = 100;
pub const MIN_PASSWORD: usize = 4;
const MAX_PASSWORD: usize = 1024;

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct PasswordHash {
    kdf: KdfParams,
    /// Argon2id output, hex.
    hash: String,
}

#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct LockFile {
    locked: bool,
    #[serde(default)]
    locked_at: Option<u64>,
    #[serde(default)]
    sites: Vec<String>,
    #[serde(default)]
    password: Option<PasswordHash>,
}

/// 5 wrong passwords → 30 s, then doubling up to 15 min.
#[derive(Default)]
struct Limiter {
    failures: u32,
    next_allowed: Option<Instant>,
}

impl Limiter {
    fn check(&self) -> Result<(), u64> {
        match self.next_allowed {
            Some(t) if Instant::now() < t => Err((t - Instant::now()).as_secs() + 1),
            _ => Ok(()),
        }
    }
    fn failed(&mut self) {
        self.failures += 1;
        if self.failures >= 5 {
            let secs = 30u64
                .saturating_mul(2u64.saturating_pow(self.failures - 5))
                .min(15 * 60);
            self.next_allowed = Some(Instant::now() + Duration::from_secs(secs));
        }
    }
}

pub struct BrowserLock {
    path: PathBuf,
    file: Mutex<LockFile>,
    limiter: Mutex<Limiter>,
    tx: watch::Sender<Value>,
}

impl BrowserLock {
    /// Load `browser-lock.json`. A missing file is an unlocked lock with no sites; an
    /// unreadable one is logged and treated the same (the file is only replaced on change).
    pub fn load(path: PathBuf) -> Self {
        let file = match std::fs::read(&path) {
            Ok(b) => serde_json::from_slice(&b).unwrap_or_else(|e| {
                log::error!(
                    "{} is invalid ({e}); starting with an empty browser lock",
                    path.display()
                );
                LockFile::default()
            }),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => LockFile::default(),
            Err(e) => {
                log::error!("cannot read {}: {e}", path.display());
                LockFile::default()
            }
        };
        let (tx, _) = watch::channel(status_of(&file));
        Self {
            path,
            file: Mutex::new(file),
            limiter: Mutex::new(Limiter::default()),
            tx,
        }
    }

    pub fn status(&self) -> Value {
        status_of(&lock(&self.file))
    }

    pub fn is_locked(&self) -> bool {
        lock(&self.file).locked
    }

    pub fn subscribe(&self) -> watch::Receiver<Value> {
        self.tx.subscribe()
    }

    /// Apply `f` to a copy, write it, then make it current. Nothing changes if the write fails.
    fn update(&self, f: impl FnOnce(&mut LockFile)) -> ApiResult<Value> {
        let mut g = lock(&self.file);
        let mut next = g.clone();
        f(&mut next);
        let bytes = serde_json::to_vec_pretty(&next).map_err(ApiError::internal)?;
        fortis_core::meta::write_private_atomic(&self.path, &bytes).map_err(ApiError::internal)?;
        *g = next;
        let st = status_of(&g);
        drop(g);
        self.tx.send_replace(st.clone());
        Ok(st)
    }
}

fn status_of(f: &LockFile) -> Value {
    json!({
        "locked": f.locked,
        "locked_at": f.locked_at,
        "sites": f.sites,
        "has_password": f.password.is_some(),
    })
}

/// A host name from what the user typed: `https://web.whatsapp.com/x` → `web.whatsapp.com`,
/// `www.Gmail.com` → `gmail.com`. `None` if it isn't a plausible host name.
pub fn normalize_site(s: &str) -> Option<String> {
    let s = s.trim().to_ascii_lowercase();
    let rest = s.split_once("://").map_or(s.as_str(), |(_, r)| r);
    let host = rest.split(['/', '?', '#']).next()?;
    let host = host.rsplit_once('@').map_or(host, |(_, h)| h);
    let host = match host.rsplit_once(':') {
        Some((h, port)) if port.chars().all(|c| c.is_ascii_digit()) => h,
        _ => host,
    };
    let host = host.trim_end_matches('.');
    let host = host.strip_prefix("www.").unwrap_or(host);
    let ok = (3..=253).contains(&host.len())
        && host.contains('.')
        && host.split('.').all(|l| {
            !l.is_empty()
                && l.len() <= 63
                && !l.starts_with('-')
                && !l.ends_with('-')
                && l.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
        });
    ok.then(|| host.to_string())
}

pub fn parse_sites(v: Option<&Value>) -> ApiResult<Vec<String>> {
    let arr = v
        .and_then(Value::as_array)
        .ok_or_else(|| ApiError::bad_request("`sites` must be a list of host names"))?;
    let mut out: Vec<String> = Vec::new();
    for x in arr {
        let raw = x
            .as_str()
            .ok_or_else(|| ApiError::bad_request("`sites` must be a list of host names"))?;
        let h = normalize_site(raw).ok_or_else(|| {
            ApiError::bad_request(format!(
                "not a site: {}",
                crate::util::sanitize_display(raw, 80)
            ))
        })?;
        if !out.contains(&h) {
            out.push(h);
        }
    }
    if out.len() > MAX_SITES {
        return Err(ApiError::bad_request(format!("at most {MAX_SITES} sites")));
    }
    Ok(out)
}

fn password_arg(a: &Value, key: &str) -> ApiResult<Option<Zeroizing<String>>> {
    match a.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(s)) if s.len() <= MAX_PASSWORD => Ok(Some(Zeroizing::new(s.clone()))),
        _ => Err(ApiError::bad_request(format!("`{key}` must be a string"))),
    }
}

async fn hash(password: Zeroizing<String>, kdf: KdfParams) -> ApiResult<String> {
    tokio::task::spawn_blocking(move || derive_master_key(password.as_bytes(), &kdf))
        .await
        .map_err(ApiError::internal)?
        .map(|k| hex::encode(k.as_bytes()))
        .map_err(ApiError::internal)
}

async fn verify(password: Zeroizing<String>, stored: &PasswordHash) -> ApiResult<bool> {
    let got = hash(password, stored.kdf.clone()).await?;
    Ok(ct_eq(got.as_bytes(), stored.hash.as_bytes()))
}

fn publish(st: &Shared, status: &Value) {
    st.events.publish(
        &st.agent_key,
        json!({ "type": "browser_lock", "locked": status["locked"], "sites": status["sites"] }),
    );
}

/// Turn the lock on (idempotent) and lock the vault.
pub fn lock_on(st: &Shared, by: &str) -> ApiResult<Value> {
    st.lock_vault("browser lock");
    let bl = &st.browser_lock;
    if bl.is_locked() {
        return Ok(bl.status());
    }
    let now = fortis_core::unix_now().max(0) as u64;
    let s = bl.update(|f| {
        f.locked = true;
        f.locked_at = Some(now);
    })?;
    log::info!("browser lock on ({by})");
    publish(st, &s);
    Ok(s)
}

/// Turn the lock off. Callers have already checked the password or the phone's signature.
pub fn unlock(st: &Shared, by: &str) -> ApiResult<Value> {
    let bl = &st.browser_lock;
    if !bl.is_locked() {
        return Ok(bl.status());
    }
    let s = bl.update(|f| {
        f.locked = false;
        f.locked_at = None;
    })?;
    lock(&bl.limiter).failures = 0;
    log::info!("browser lock off ({by})");
    publish(st, &s);
    Ok(s)
}

pub fn set_sites(st: &Shared, sites: Vec<String>, by: &str) -> ApiResult<Value> {
    let s = st.browser_lock.update(|f| f.sites = sites)?;
    log::info!("browser lock sites changed ({by}): {}", s["sites"]);
    publish(st, &s);
    Ok(s)
}

/// IPC `lock_unlock`.
pub async fn unlock_with_password(st: &Shared, a: &Value) -> ApiResult<Value> {
    let bl = &st.browser_lock;
    let pw =
        password_arg(a, "password")?.ok_or_else(|| ApiError::bad_request("missing `password`"))?;
    if !bl.is_locked() {
        return Ok(bl.status());
    }
    if let Err(secs) = lock(&bl.limiter).check() {
        return Err(ApiError::rate_limited(secs));
    }
    let stored = lock(&bl.file).password.clone();
    let Some(stored) = stored else {
        return Err(ApiError::conflict(
            "no lock password is set; unlock from the phone",
        ));
    };
    if verify(pw, &stored).await? {
        unlock(st, "password")
    } else {
        lock(&bl.limiter).failed();
        log::warn!("browser lock: wrong password");
        Err(ApiError::invalid_password())
    }
}

/// IPC `lock_password`: set or change the unlock password (only while unlocked).
pub async fn set_password(st: &Shared, a: &Value) -> ApiResult<Value> {
    let bl = &st.browser_lock;
    if bl.is_locked() {
        return Err(ApiError::forbidden("the browser lock is on"));
    }
    let new =
        password_arg(a, "password")?.ok_or_else(|| ApiError::bad_request("missing `password`"))?;
    if new.chars().count() < MIN_PASSWORD {
        return Err(ApiError::bad_request(format!(
            "the lock password needs at least {MIN_PASSWORD} characters"
        )));
    }
    let current = lock(&bl.file).password.clone();
    if let Some(cur) = current {
        if let Err(secs) = lock(&bl.limiter).check() {
            return Err(ApiError::rate_limited(secs));
        }
        let old = password_arg(a, "old_password")?
            .ok_or_else(|| ApiError::bad_request("enter the current lock password"))?;
        if !verify(old, &cur).await? {
            lock(&bl.limiter).failed();
            return Err(ApiError::invalid_password());
        }
    }
    let kdf = KdfParams::new_random();
    let h = hash(new, kdf.clone()).await?;
    let s = bl.update(|f| f.password = Some(PasswordHash { kdf, hash: h }))?;
    log::info!("browser lock password changed");
    Ok(s)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sites_are_normalised() {
        assert_eq!(
            normalize_site("https://web.whatsapp.com/").as_deref(),
            Some("web.whatsapp.com")
        );
        assert_eq!(
            normalize_site(" WWW.Gmail.com ").as_deref(),
            Some("gmail.com")
        );
        assert_eq!(
            normalize_site("mail.google.com:443/mail?x").as_deref(),
            Some("mail.google.com")
        );
        assert_eq!(
            normalize_site("https://user@x.com.").as_deref(),
            Some("x.com")
        );
        for bad in [
            "",
            "localhost",
            "a b.com",
            "-x.com",
            "x..com",
            "http://",
            "<script>.com",
        ] {
            assert_eq!(normalize_site(bad), None, "{bad}");
        }
        let s = parse_sites(Some(&json!([
            "whatsapp.com",
            "https://whatsapp.com/",
            "gmail.com"
        ])))
        .unwrap();
        assert_eq!(s, vec!["whatsapp.com", "gmail.com"]);
        assert!(parse_sites(Some(&json!("x.com"))).is_err());
        assert!(parse_sites(Some(&json!(["nope"]))).is_err());
    }

    #[test]
    fn limiter_backs_off_after_five() {
        let mut l = Limiter::default();
        for _ in 0..4 {
            l.failed();
        }
        assert!(l.check().is_ok());
        l.failed();
        let wait = l.check().unwrap_err();
        assert!((29..=31).contains(&wait), "{wait}");
    }

    #[tokio::test]
    async fn lock_password_and_persistence() {
        let t = tempfile::tempdir().unwrap();
        let st = crate::state::test_support::state(t.path(), |_| {});
        let mut rx = st.browser_lock.subscribe();
        set_sites(&st, vec!["whatsapp.com".into()], "test").unwrap();
        assert!(rx.has_changed().unwrap());
        rx.borrow_and_update();

        // No password yet: password unlock is refused, phone unlock works.
        lock_on(&st, "test").unwrap();
        assert!(rx.has_changed().unwrap());
        assert_eq!(
            unlock_with_password(&st, &json!({"password": "x"}))
                .await
                .unwrap_err()
                .code,
            "conflict"
        );
        // Can't change the sites or password from the laptop while locked.
        assert_eq!(
            set_password(&st, &json!({"password": "1234"}))
                .await
                .unwrap_err()
                .code,
            "forbidden"
        );
        unlock(&st, "test").unwrap();

        assert!(set_password(&st, &json!({"password": "12"})).await.is_err());
        set_password(&st, &json!({"password": "hunter2"}))
            .await
            .unwrap();
        // Changing it needs the old one.
        assert!(
            set_password(&st, &json!({"password": "next1"}))
                .await
                .is_err()
        );
        assert_eq!(
            set_password(&st, &json!({"password": "next1", "old_password": "nope"}))
                .await
                .unwrap_err()
                .code,
            "invalid_password"
        );

        lock_on(&st, "test").unwrap();
        assert_eq!(st.vault_state(), "uninitialised"); // lock_vault was called; no vault here
        assert_eq!(
            unlock_with_password(&st, &json!({"password": "wrong"}))
                .await
                .unwrap_err()
                .code,
            "invalid_password"
        );
        // Survives a restart (file reload).
        let again = BrowserLock::load(t.path().join(".config/fortis/browser-lock.json"));
        assert_eq!(again.status()["locked"], true);
        assert_eq!(again.status()["sites"], json!(["whatsapp.com"]));
        assert_eq!(again.status()["has_password"], true);

        let s = unlock_with_password(&st, &json!({"password": "hunter2"}))
            .await
            .unwrap();
        assert_eq!(s["locked"], false);
    }
}
