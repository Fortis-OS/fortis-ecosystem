use argon2::{
    password_hash::Error as Argon2Error,
    Argon2, Params,
};
use chacha20poly1305::{
    aead::{Aead, AeadCore, KeyInit, Error as AeadError},
    XChaCha20Poly1305,
};
use rand::{rngs::OsRng, RngCore};
use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

#[derive(Zeroize, ZeroizeOnDrop)]
pub struct MasterKey(pub [u8; 32]);

#[derive(Zeroize, ZeroizeOnDrop)]
pub struct VaultKey(pub [u8; 32]);

pub fn derive_master_key(passphrase: &str, salt: &[u8]) -> Result<MasterKey, Argon2Error> {
    let params = Params::new(65536, 3, 4, Some(32))?;
    let argon2 = Argon2::new(
        argon2::Algorithm::Argon2id,
        argon2::Version::V0x13,
        params,
    );
    let mut key = [0u8; 32];
    argon2.hash_password_into(passphrase.as_bytes(), salt, &mut key)?;
    Ok(MasterKey(key))
}

pub fn generate_vault_key() -> VaultKey {
    let mut key = [0u8; 32];
    OsRng.fill_bytes(&mut key);
    VaultKey(key)
}

pub fn encrypt_vault_key(master_key: &MasterKey, vault_key: &VaultKey) -> Result<Vec<u8>, AeadError> {
    let cipher = XChaCha20Poly1305::new(master_key.0.as_ref().into());
    let nonce = XChaCha20Poly1305::generate_nonce(&mut OsRng); // 24 bytes
    let ciphertext = cipher.encrypt(&nonce, vault_key.0.as_ref())?;
    
    let mut result = Vec::with_capacity(nonce.len() + ciphertext.len());
    result.extend_from_slice(&nonce);
    result.extend_from_slice(&ciphertext);
    Ok(result)
}

pub fn decrypt_vault_key(master_key: &MasterKey, encrypted_vault_key: &[u8]) -> Result<VaultKey, AeadError> {
    if encrypted_vault_key.len() < 24 {
        return Err(AeadError);
    }
    let (nonce, ciphertext) = encrypted_vault_key.split_at(24);
    let cipher = XChaCha20Poly1305::new(master_key.0.as_ref().into());
    let plaintext = cipher.decrypt(nonce.into(), ciphertext)?;
    
    let mut key = [0u8; 32];
    if plaintext.len() != 32 {
        return Err(AeadError);
    }
    key.copy_from_slice(&plaintext);
    Ok(VaultKey(key))
}

pub fn encrypt_entry(vault_key: &VaultKey, plaintext: &[u8]) -> Result<Vec<u8>, AeadError> {
    let cipher = XChaCha20Poly1305::new(vault_key.0.as_ref().into());
    let nonce = XChaCha20Poly1305::generate_nonce(&mut OsRng);
    let ciphertext = cipher.encrypt(&nonce, plaintext)?;
    
    let mut result = Vec::with_capacity(nonce.len() + ciphertext.len());
    result.extend_from_slice(&nonce);
    result.extend_from_slice(&ciphertext);
    Ok(result)
}

pub fn decrypt_entry(vault_key: &VaultKey, encrypted_entry: &[u8]) -> Result<Zeroizing<Vec<u8>>, AeadError> {
    if encrypted_entry.len() < 24 {
        return Err(AeadError);
    }
    let (nonce, ciphertext) = encrypted_entry.split_at(24);
    let cipher = XChaCha20Poly1305::new(vault_key.0.as_ref().into());
    let plaintext = cipher.decrypt(nonce.into(), ciphertext)?;
    Ok(Zeroizing::new(plaintext))
}
