use std::path::PathBuf;
use std::fs;
use std::sync::RwLock;
use lazy_static::lazy_static;

use fortis_core::crypto::{derive_master_key, MasterKey};
use fortis_core::db::Database;

lazy_static! {
    pub static ref MASTER_KEY: RwLock<Option<MasterKey>> = RwLock::new(None);
}

pub fn unlock_vault(password: &str) -> Result<(), String> {
    let salt = b"fortis_salt_static_for_now"; // In a real app, this should be generated per DB and stored unencrypted.
    let key = derive_master_key(password, salt).map_err(|e| e.to_string())?;
    
    // Test open
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    let db_dir = PathBuf::from(home).join(".local/share/fortis");
    fs::create_dir_all(&db_dir).unwrap_or_default();
    let db_path = db_dir.join("vault.db");
    
    // We try to open to verify the key. If it fails, the key is wrong.
    let _db = Database::open(&db_path, &key).map_err(|e| format!("Failed to unlock: {}", e))?;
    
    let mut lock = MASTER_KEY.write().unwrap();
    *lock = Some(key);
    
    Ok(())
}

pub fn lock_vault() {
    let mut lock = MASTER_KEY.write().unwrap();
    *lock = None;
}

pub fn get_db() -> Result<Database, String> {
    let lock = MASTER_KEY.read().unwrap();
    let key = lock.as_ref().ok_or("Vault is locked")?;
    
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    let db_path = PathBuf::from(home).join(".local/share/fortis/vault.db");
    
    Database::open(db_path, key).map_err(|e| e.to_string())
}
