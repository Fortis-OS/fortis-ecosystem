use std::path::PathBuf;
use std::fs;
use std::sync::Arc;
use tokio::sync::Mutex;
use uuid::Uuid;

use fortis_core::db::Database;
use fortis_core::vault::{VaultEntry, EntryType, SecretString};

pub struct VaultManager {
    db: Arc<Mutex<Database>>,
}

impl VaultManager {
    pub fn new() -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
        let db_dir = PathBuf::from(home).join(".local/share/fortis");
        fs::create_dir_all(&db_dir)?;
        let db_path = db_dir.join("vault.db");
        
        let db = Database::open(db_path, "dev_master_key")?;
        db.init_schema()?;
        
        Ok(VaultManager {
            db: Arc::new(Mutex::new(db)),
        })
    }

    pub async fn add_secure_note(&self, title: &str, content: &str) -> Result<(), String> {
        let new_entry = VaultEntry {
            id: Uuid::new_v4(),
            folder_id: None,
            title: title.to_string(),
            entry_type: EntryType::SecureNote { 
                content: SecretString(zeroize::Zeroizing::new(content.to_string()))
            },
            custom_fields: vec![],
            notes: "Added via Agent".to_string(),
        };
        
        let db_lock = self.db.lock().await;
        db_lock.add_entry(&new_entry).map_err(|e| e.to_string())
    }

    pub async fn list_entries_summary(&self) -> Result<String, String> {
        let db_lock = self.db.lock().await;
        let entries = db_lock.get_entries().map_err(|e| e.to_string())?;
        Ok(format!("Found {} entries in vault.\n{:#?}", entries.len(), entries))
    }
}
