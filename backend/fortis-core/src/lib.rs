//! Fortis core library.
//!
//! Pure crypto, data models and (with the `db` feature) the SQLCipher vault
//! database. No networking. The rules implemented here are specified in
//! `docs/security/README.md`; section numbers in comments refer to it.

pub mod crypto;
pub mod meta;
pub mod password_gen;
pub mod sign;
pub mod totp;
pub mod vault;

#[cfg(feature = "db")]
pub mod db;

/// Current unix time in seconds. Returns 0 if the clock is before 1970.
pub fn unix_now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}
