//! UniFFI bindings used by fortis-mobile (development guide §5).
//!
//! The phone never sends secrets to Rust for signing: Android Keystore signs.
//! Rust derives keys, encrypts/decrypts entries, and builds the exact bytes
//! that must be signed so both sides agree byte-for-byte.

uniffi::setup_scaffolding!();

use fortis_core::crypto::{self, VaultKey, WrapKey};
use fortis_core::meta::VaultMeta;
use fortis_core::password_gen::{self, PasswordOptions};
use fortis_core::sign::{self, ApprovalRequest, Decision};
use fortis_core::totp;
use fortis_core::vault::{TotpConfig, VaultEntry};
use uuid::Uuid;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum BindingError {
    #[error("invalid input: {msg}")]
    InvalidInput { msg: String },
    #[error("crypto error")]
    Crypto,
}

type Result<T> = std::result::Result<T, BindingError>;

fn invalid(msg: impl ToString) -> BindingError {
    BindingError::InvalidInput {
        msg: msg.to_string(),
    }
}

fn parse_uuid(s: &str) -> Result<Uuid> {
    Uuid::parse_str(s).map_err(invalid)
}

fn key32(bytes: &[u8]) -> Result<[u8; 32]> {
    bytes
        .try_into()
        .map_err(|_| invalid("key must be 32 bytes"))
}

/// Argon2id + HKDF from the `meta` JSON returned by `/sync/bootstrap`.
/// Returns the 32-byte wrap key; the caller must wipe it after use.
#[uniffi::export]
pub fn derive_wrap_key(password: String, meta_json: String) -> Result<Vec<u8>> {
    let meta = VaultMeta::from_json(&meta_json).map_err(invalid)?;
    let master = crypto::derive_master_key(password.as_bytes(), &meta.kdf)
        .map_err(|_| BindingError::Crypto)?;
    Ok(master.wrap_key().as_bytes().to_vec())
}

#[uniffi::export]
pub fn unwrap_vault_key(wrap_key: Vec<u8>, wrapped: Vec<u8>, vault_id: String) -> Result<Vec<u8>> {
    let wrap = WrapKey::from_bytes(key32(&wrap_key)?);
    let vk = crypto::unwrap_vault_key(&wrap, &wrapped, &parse_uuid(&vault_id)?)
        .map_err(|_| BindingError::Crypto)?;
    Ok(vk.as_bytes().to_vec())
}

/// Returns the entry as JSON (`VaultEntry`).
#[uniffi::export]
pub fn decrypt_entry(
    vault_key: Vec<u8>,
    entry_id: String,
    version: u64,
    blob: Vec<u8>,
) -> Result<String> {
    let vk = VaultKey::from_bytes(key32(&vault_key)?);
    let id = parse_uuid(&entry_id)?;
    let pt = crypto::decrypt_entry(&vk, &id, version, &blob).map_err(|_| BindingError::Crypto)?;
    let entry: VaultEntry = serde_json::from_slice(&pt).map_err(invalid)?;
    if entry.id != id {
        return Err(BindingError::Crypto);
    }
    serde_json::to_string(&entry).map_err(invalid)
}

/// Encrypts entry JSON for upload; validates that its `id` matches.
#[uniffi::export]
pub fn encrypt_entry(
    vault_key: Vec<u8>,
    entry_id: String,
    version: u64,
    entry_json: String,
) -> Result<Vec<u8>> {
    let vk = VaultKey::from_bytes(key32(&vault_key)?);
    let id = parse_uuid(&entry_id)?;
    let entry: VaultEntry = serde_json::from_str(&entry_json).map_err(invalid)?;
    if entry.id != id {
        return Err(invalid("entry id does not match"));
    }
    crypto::encrypt_entry(&vk, &id, version, &entry.to_json()).map_err(|_| BindingError::Crypto)
}

/// `config_json` is a `TotpConfig`.
#[uniffi::export]
pub fn generate_totp(config_json: String, unix_time: u64) -> Result<String> {
    let cfg: TotpConfig = serde_json::from_str(&config_json).map_err(invalid)?;
    totp::generate(&cfg, unix_time).map_err(invalid)
}

#[uniffi::export]
pub fn totp_remaining_secs(period: u32, unix_time: u64) -> u64 {
    totp::remaining_secs(period, unix_time)
}

#[uniffi::export]
pub fn generate_password(
    length: u32,
    uppercase: bool,
    numbers: bool,
    symbols: bool,
) -> Result<String> {
    let opts = PasswordOptions {
        length: length as usize,
        uppercase,
        numbers,
        symbols,
    };
    password_gen::generate(&opts)
        .map(|p| p.to_string())
        .map_err(invalid)
}

/// Bytes to sign with the request key.
#[uniffi::export]
pub fn canonical_request(
    method: String,
    path_and_query: String,
    timestamp: u64,
    nonce_hex: String,
    body: Vec<u8>,
) -> Vec<u8> {
    sign::canonical_request(&method, &path_and_query, timestamp, &nonce_hex, &body)
}

/// Bytes to sign with the approval key. `request_json` is the `sudo_request`
/// event payload's challenge fields; `decision` is "approve" or "deny".
#[uniffi::export]
pub fn canonical_approval(request_json: String, decision: String) -> Result<Vec<u8>> {
    let req: ApprovalRequest = serde_json::from_str(&request_json).map_err(invalid)?;
    let d = match decision.as_str() {
        "approve" => Decision::Approve,
        "deny" => Decision::Deny,
        _ => return Err(invalid("decision must be approve or deny")),
    };
    sign::canonical_approval(&req, d).map_err(invalid)
}

/// Bytes to sign with the approval key to confirm a session.
#[uniffi::export]
pub fn canonical_session(
    session_id: String,
    nonce: String,
    issued_at: u64,
    kind: String,
) -> Result<Vec<u8>> {
    sign::canonical_session(&session_id, &nonce, issued_at, &kind).map_err(invalid)
}

/// Verify an agent event signature against the pinned agent key.
#[uniffi::export]
pub fn verify_agent_signature(
    agent_pubkey_b64: String,
    message: Vec<u8>,
    signature_b64: String,
) -> bool {
    match sign::PublicKey::from_base64(&agent_pubkey_b64) {
        Ok(pk) => pk.verify_b64(&message, &signature_b64),
        Err(_) => false,
    }
}

/// Fingerprint shown during pairing (first 16 hex of SHA-256 of the key).
#[uniffi::export]
pub fn key_fingerprint(pubkey_b64: String) -> Result<String> {
    Ok(sign::PublicKey::from_base64(&pubkey_b64)
        .map_err(invalid)?
        .fingerprint())
}
