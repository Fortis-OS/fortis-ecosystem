//! Key derivation and authenticated encryption (security §2, §3).
//!
//! ```text
//! master password ──Argon2id(salt)──► master key
//!     master key ──HKDF "fortis/v2/db-key"──► db key   (SQLCipher raw key)
//!     master key ──HKDF "fortis/v2/wrap-key"─► wrap key (wraps the vault key)
//! vault key (random) ──XChaCha20-Poly1305──► each entry
//! ```

use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::{
    XChaCha20Poly1305, XNonce,
    aead::{Aead, KeyInit, Payload},
};
use hkdf::Hkdf;
use rand::{RngCore, rngs::OsRng};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use uuid::Uuid;
use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

pub const KEY_LEN: usize = 32;
pub const SALT_LEN: usize = 16;
pub const NONCE_LEN: usize = 24;
pub const TAG_LEN: usize = 16;

/// Spec values for Argon2id (security §2). Stored params may be stronger, never weaker.
pub const ARGON2_M_KIB: u32 = 65_536;
pub const ARGON2_T: u32 = 3;
pub const ARGON2_P: u32 = 4;

const INFO_DB_KEY: &[u8] = b"fortis/v2/db-key";
const INFO_WRAP_KEY: &[u8] = b"fortis/v2/wrap-key";
const AAD_VAULT_KEY: &[u8] = b"fortis/v2/vault-key";
const AAD_ENTRY: &[u8] = b"fortis/v2/entry";

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum CryptoError {
    #[error("invalid KDF parameters: {0}")]
    InvalidParams(&'static str),
    #[error("key derivation failed")]
    Kdf,
    #[error("decryption failed")]
    Decrypt,
    #[error("encryption failed")]
    Encrypt,
}

macro_rules! key_type {
    ($(#[$doc:meta])* $name:ident) => {
        $(#[$doc])*
        #[derive(Zeroize, ZeroizeOnDrop)]
        pub struct $name([u8; KEY_LEN]);

        impl $name {
            pub fn from_bytes(bytes: [u8; KEY_LEN]) -> Self {
                Self(bytes)
            }

            /// Build from a slice; returns `None` unless it is exactly 32 bytes.
            pub fn from_slice(bytes: &[u8]) -> Option<Self> {
                let arr: [u8; KEY_LEN] = bytes.try_into().ok()?;
                Some(Self(arr))
            }

            pub fn as_bytes(&self) -> &[u8; KEY_LEN] {
                &self.0
            }
        }

        impl std::fmt::Debug for $name {
            fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                f.write_str(concat!(stringify!($name), "([redacted])"))
            }
        }
    };
}

key_type!(
    /// Argon2id output. Never stored; only fed to HKDF.
    MasterKey
);
key_type!(
    /// SQLCipher raw key.
    DbKey
);
key_type!(
    /// Encrypts / decrypts the vault key.
    WrapKey
);
key_type!(
    /// Random per-vault key that encrypts entries.
    VaultKey
);

impl VaultKey {
    pub fn generate() -> Self {
        let mut k = [0u8; KEY_LEN];
        OsRng.fill_bytes(&mut k);
        Self(k)
    }
}

impl DbKey {
    /// SQLCipher pragma value: `x'<64 hex chars>'`.
    pub fn sqlcipher_pragma(&self) -> Zeroizing<String> {
        Zeroizing::new(format!("x'{}'", hex::encode(self.0)))
    }
}

/// Argon2id parameters, stored in `vault.meta.json` (security §3.1).
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct KdfParams {
    pub alg: String,
    pub m_kib: u32,
    pub t: u32,
    pub p: u32,
    #[serde(with = "b64")]
    pub salt: Vec<u8>,
}

impl KdfParams {
    /// Spec parameters with a fresh random salt.
    pub fn new_random() -> Self {
        let mut salt = vec![0u8; SALT_LEN];
        OsRng.fill_bytes(&mut salt);
        Self {
            alg: "argon2id".into(),
            m_kib: ARGON2_M_KIB,
            t: ARGON2_T,
            p: ARGON2_P,
            salt,
        }
    }

    /// Reject anything weaker than the spec (or absurdly large).
    pub fn validate(&self) -> Result<(), CryptoError> {
        if self.alg != "argon2id" {
            return Err(CryptoError::InvalidParams("alg must be argon2id"));
        }
        if !(ARGON2_M_KIB..=4 * 1024 * 1024).contains(&self.m_kib) {
            return Err(CryptoError::InvalidParams("m_kib out of range"));
        }
        if !(ARGON2_T..=20).contains(&self.t) {
            return Err(CryptoError::InvalidParams("t out of range"));
        }
        if !(1..=16).contains(&self.p) {
            return Err(CryptoError::InvalidParams("p out of range"));
        }
        if self.salt.len() != SALT_LEN {
            return Err(CryptoError::InvalidParams("salt must be 16 bytes"));
        }
        Ok(())
    }
}

/// Argon2id(password, salt) → master key.
pub fn derive_master_key(password: &[u8], params: &KdfParams) -> Result<MasterKey, CryptoError> {
    params.validate()?;
    let p = Params::new(params.m_kib, params.t, params.p, Some(KEY_LEN))
        .map_err(|_| CryptoError::InvalidParams("argon2 rejected params"))?;
    let argon = Argon2::new(Algorithm::Argon2id, Version::V0x13, p);
    let mut out = [0u8; KEY_LEN];
    argon
        .hash_password_into(password, &params.salt, &mut out)
        .map_err(|_| CryptoError::Kdf)?;
    Ok(MasterKey(out))
}

fn hkdf_expand(ikm: &[u8; KEY_LEN], info: &[u8]) -> [u8; KEY_LEN] {
    let hk = Hkdf::<Sha256>::new(None, ikm);
    let mut out = [0u8; KEY_LEN];
    // 32 bytes is always a valid HKDF-SHA256 output length.
    hk.expand(info, &mut out)
        .expect("32-byte HKDF-SHA256 output is always valid");
    out
}

impl MasterKey {
    pub fn db_key(&self) -> DbKey {
        DbKey(hkdf_expand(&self.0, INFO_DB_KEY))
    }

    pub fn wrap_key(&self) -> WrapKey {
        WrapKey(hkdf_expand(&self.0, INFO_WRAP_KEY))
    }
}

fn seal(key: &[u8; KEY_LEN], plaintext: &[u8], aad: &[u8]) -> Result<Vec<u8>, CryptoError> {
    let cipher = XChaCha20Poly1305::new(key.into());
    let mut nonce = [0u8; NONCE_LEN];
    OsRng.fill_bytes(&mut nonce);
    let ct = cipher
        .encrypt(
            XNonce::from_slice(&nonce),
            Payload {
                msg: plaintext,
                aad,
            },
        )
        .map_err(|_| CryptoError::Encrypt)?;
    let mut out = Vec::with_capacity(NONCE_LEN + ct.len());
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&ct);
    Ok(out)
}

fn open(key: &[u8; KEY_LEN], blob: &[u8], aad: &[u8]) -> Result<Zeroizing<Vec<u8>>, CryptoError> {
    if blob.len() < NONCE_LEN + TAG_LEN {
        return Err(CryptoError::Decrypt);
    }
    let (nonce, ct) = blob.split_at(NONCE_LEN);
    let cipher = XChaCha20Poly1305::new(key.into());
    cipher
        .decrypt(XNonce::from_slice(nonce), Payload { msg: ct, aad })
        .map(Zeroizing::new)
        .map_err(|_| CryptoError::Decrypt)
}

fn vault_key_aad(vault_id: &Uuid) -> Vec<u8> {
    let mut aad = AAD_VAULT_KEY.to_vec();
    aad.extend_from_slice(vault_id.as_bytes());
    aad
}

/// `nonce ‖ XChaCha20-Poly1305(wrap key, vault key, AAD = label ‖ vault_id)`
pub fn wrap_vault_key(
    wrap: &WrapKey,
    vault_key: &VaultKey,
    vault_id: &Uuid,
) -> Result<Vec<u8>, CryptoError> {
    seal(&wrap.0, &vault_key.0, &vault_key_aad(vault_id))
}

pub fn unwrap_vault_key(
    wrap: &WrapKey,
    wrapped: &[u8],
    vault_id: &Uuid,
) -> Result<VaultKey, CryptoError> {
    let pt = open(&wrap.0, wrapped, &vault_key_aad(vault_id))?;
    VaultKey::from_slice(&pt).ok_or(CryptoError::Decrypt)
}

fn entry_aad(entry_id: &Uuid, version: u64) -> Vec<u8> {
    let mut aad = AAD_ENTRY.to_vec();
    aad.extend_from_slice(entry_id.as_bytes());
    aad.extend_from_slice(&version.to_be_bytes());
    aad
}

/// Encrypt one entry, bound to its ID and version (security §3.3).
pub fn encrypt_entry(
    vault_key: &VaultKey,
    entry_id: &Uuid,
    version: u64,
    plaintext: &[u8],
) -> Result<Vec<u8>, CryptoError> {
    seal(&vault_key.0, plaintext, &entry_aad(entry_id, version))
}

pub fn decrypt_entry(
    vault_key: &VaultKey,
    entry_id: &Uuid,
    version: u64,
    blob: &[u8],
) -> Result<Zeroizing<Vec<u8>>, CryptoError> {
    open(&vault_key.0, blob, &entry_aad(entry_id, version))
}

/// 16 random bytes as hex — request IDs, nonces, tokens.
pub fn random_hex(n_bytes: usize) -> String {
    let mut b = vec![0u8; n_bytes];
    OsRng.fill_bytes(&mut b);
    hex::encode(b)
}

/// Constant-time byte comparison.
pub fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    use subtle::ConstantTimeEq;
    a.len() == b.len() && bool::from(a.ct_eq(b))
}

pub(crate) mod b64 {
    use base64::{Engine, engine::general_purpose::STANDARD};
    use serde::{Deserialize, Deserializer, Serializer};

    pub fn serialize<S: Serializer>(v: &[u8], s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&STANDARD.encode(v))
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<u8>, D::Error> {
        let s = String::deserialize(d)?;
        STANDARD.decode(s).map_err(serde::de::Error::custom)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn params() -> KdfParams {
        KdfParams::new_random()
    }

    #[test]
    fn rejects_weak_params() {
        let mut p = params();
        p.m_kib = 1024;
        assert!(derive_master_key(b"pw", &p).is_err());
        let mut p = params();
        p.salt = vec![0; 8];
        assert!(p.validate().is_err());
        let mut p = params();
        p.alg = "argon2i".into();
        assert!(p.validate().is_err());
    }

    #[test]
    fn random_salt_differs() {
        assert_ne!(params().salt, params().salt);
    }

    #[test]
    fn same_password_same_key_and_split_keys_differ() {
        let p = params();
        let a = derive_master_key(b"correct horse", &p).unwrap();
        let b = derive_master_key(b"correct horse", &p).unwrap();
        assert_eq!(a.as_bytes(), b.as_bytes());
        assert_ne!(a.db_key().as_bytes(), a.wrap_key().as_bytes());
        assert_ne!(a.as_bytes(), a.db_key().as_bytes());
        let c = derive_master_key(b"wrong horse", &p).unwrap();
        assert_ne!(a.as_bytes(), c.as_bytes());
    }

    #[test]
    fn vault_key_wrap_roundtrip_and_binding() {
        let wrap = WrapKey::from_bytes([7; 32]);
        let vk = VaultKey::generate();
        let id = Uuid::new_v4();
        let wrapped = wrap_vault_key(&wrap, &vk, &id).unwrap();
        assert_eq!(
            unwrap_vault_key(&wrap, &wrapped, &id).unwrap().as_bytes(),
            vk.as_bytes()
        );
        assert!(unwrap_vault_key(&wrap, &wrapped, &Uuid::new_v4()).is_err());
        assert!(unwrap_vault_key(&WrapKey::from_bytes([8; 32]), &wrapped, &id).is_err());
    }

    #[test]
    fn entry_bound_to_id_and_version() {
        let vk = VaultKey::generate();
        let id = Uuid::new_v4();
        let blob = encrypt_entry(&vk, &id, 3, b"secret").unwrap();
        assert_eq!(&*decrypt_entry(&vk, &id, 3, &blob).unwrap(), b"secret");
        assert_eq!(decrypt_entry(&vk, &id, 2, &blob), Err(CryptoError::Decrypt));
        assert_eq!(
            decrypt_entry(&vk, &Uuid::new_v4(), 3, &blob),
            Err(CryptoError::Decrypt)
        );
        let mut tampered = blob.clone();
        *tampered.last_mut().unwrap() ^= 1;
        assert!(decrypt_entry(&vk, &id, 3, &tampered).is_err());
        assert!(decrypt_entry(&vk, &id, 3, &blob[..10]).is_err());
    }

    #[test]
    fn debug_is_redacted() {
        let k = VaultKey::from_bytes([0xAB; 32]);
        assert!(!format!("{k:?}").to_lowercase().contains("ab"));
    }

    #[test]
    fn ct_eq_works() {
        assert!(ct_eq(b"abc", b"abc"));
        assert!(!ct_eq(b"abc", b"abd"));
        assert!(!ct_eq(b"abc", b"ab"));
    }
}
