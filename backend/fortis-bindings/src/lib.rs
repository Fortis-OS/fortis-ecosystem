uniffi::setup_scaffolding!();

use fortis_core::crypto::{VaultKey, decrypt_entry};
use fortis_core::totp::{generate_totp_code, get_remaining_seconds};

#[derive(uniffi::Error, Debug)]
pub enum BindingError {
    CryptoError { msg: String },
    TotpError { msg: String },
}

impl std::fmt::Display for BindingError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            BindingError::CryptoError { msg } => write!(f, "Crypto error: {}", msg),
            BindingError::TotpError { msg } => write!(f, "TOTP error: {}", msg),
        }
    }
}

impl std::error::Error for BindingError {}

#[uniffi::export]
pub fn fortis_generate_totp(secret_base32: String) -> Result<String, BindingError> {
    generate_totp_code(&secret_base32).map_err(|e| BindingError::TotpError { msg: e })
}

#[uniffi::export]
pub fn fortis_totp_remaining_seconds() -> u64 {
    get_remaining_seconds()
}

#[uniffi::export]
pub fn fortis_decrypt_entry(vault_key_bytes: Vec<u8>, encrypted_entry: Vec<u8>) -> Result<Vec<u8>, BindingError> {
    if vault_key_bytes.len() != 32 {
        return Err(BindingError::CryptoError { msg: "Invalid vault key length".into() });
    }
    let mut key_arr = [0u8; 32];
    key_arr.copy_from_slice(&vault_key_bytes);
    let vk = VaultKey(key_arr);

    match decrypt_entry(&vk, &encrypted_entry) {
        Ok(plaintext) => Ok(plaintext.to_vec()),
        Err(_) => Err(BindingError::CryptoError { msg: "Decryption failed".into() }),
    }
}
