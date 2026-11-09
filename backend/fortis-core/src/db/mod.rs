use rusqlite::{Connection, Result as SqlResult, params};
use std::path::Path;
use crate::vault::VaultEntry;
use crate::crypto::{MasterKey, VaultKey, generate_vault_key, encrypt_vault_key, decrypt_vault_key, encrypt_entry, decrypt_entry};

pub struct Database {
    conn: Connection,
    vault_key: VaultKey,
}

impl Database {
    pub fn open<P: AsRef<Path>>(path: P, master_key: &MasterKey) -> SqlResult<Self> {
        let conn = Connection::open(path)?;
        
        // SQLCipher PRAGMAs for AES-256 encryption
        let hex_key = hex::encode(&master_key.0);
        conn.pragma_update(None, "key", format!("x'{}'", hex_key))?;
        conn.pragma_update(None, "cipher_page_size", 4096)?;
        conn.pragma_update(None, "kdf_iter", 256000)?;
        
        // Verify key by trying to read the schema
        let _count: i64 = conn.query_row("SELECT count(*) FROM sqlite_master", [], |row| row.get(0))?;
        
        // Init schema
        conn.execute(
            "CREATE TABLE IF NOT EXISTS metadata (
                key TEXT PRIMARY KEY,
                value BLOB NOT NULL
            )",
            [],
        )?;
        
        conn.execute(
            "CREATE TABLE IF NOT EXISTS entries (
                id TEXT PRIMARY KEY,
                folder_id TEXT,
                encrypted_data BLOB NOT NULL,
                last_updated INTEGER NOT NULL
            )",
            [],
        )?;
        
        conn.execute(
            "CREATE TABLE IF NOT EXISTS folders (
                id TEXT PRIMARY KEY,
                encrypted_data BLOB NOT NULL,
                last_updated INTEGER NOT NULL
            )",
            [],
        )?;
        
        conn.execute(
            "CREATE TABLE IF NOT EXISTS sync_clock (
                device_id TEXT PRIMARY KEY,
                timestamp INTEGER NOT NULL
            )",
            [],
        )?;
        
        // Load or create vault key
        let encrypted_vk_opt: Option<Vec<u8>> = {
            let mut stmt = conn.prepare("SELECT value FROM metadata WHERE key = 'encrypted_vault_key'")?;
            let mut rows = stmt.query([])?;
            if let Some(row) = rows.next()? {
                Some(row.get(0)?)
            } else {
                None
            }
        };
        
        let vault_key = if let Some(encrypted_vault_key) = encrypted_vk_opt {
            decrypt_vault_key(master_key, &encrypted_vault_key)
                .map_err(|_| rusqlite::Error::ToSqlConversionFailure(Box::new(std::io::Error::new(std::io::ErrorKind::Other, "Crypto Error"))))?
        } else {
            let vk = generate_vault_key();
            let encrypted_vk = encrypt_vault_key(master_key, &vk)
                .map_err(|_| rusqlite::Error::ToSqlConversionFailure(Box::new(std::io::Error::new(std::io::ErrorKind::Other, "Crypto Error"))))?;
            conn.execute("INSERT INTO metadata (key, value) VALUES ('encrypted_vault_key', ?1)", params![encrypted_vk])?;
            vk
        };
        
        Ok(Self { conn, vault_key })
    }

    pub fn init_schema(&self) -> SqlResult<()> {
        // Schema is initialized in open
        Ok(())
    }

    pub fn add_entry(&self, entry: &VaultEntry) -> SqlResult<()> {
        let serialized = serde_json::to_vec(entry).expect("Serialization failed");
        let encrypted = encrypt_entry(&self.vault_key, &serialized)
            .map_err(|_| rusqlite::Error::ToSqlConversionFailure(Box::new(std::io::Error::new(std::io::ErrorKind::Other, "Crypto Error"))))?;
        
        self.conn.execute(
            "INSERT OR REPLACE INTO entries (id, folder_id, encrypted_data, last_updated)
             VALUES (?1, ?2, ?3, strftime('%s', 'now'))",
            params![
                entry.id.to_string(),
                entry.folder_id.map(|id| id.to_string()),
                encrypted
            ],
        )?;
        Ok(())
    }

    pub fn get_entries(&self) -> SqlResult<Vec<VaultEntry>> {
        let mut stmt = self.conn.prepare("SELECT encrypted_data FROM entries")?;
        let rows = stmt.query_map([], |row| {
            let data: Vec<u8> = row.get(0)?;
            Ok(data)
        })?;

        let mut entries = Vec::new();
        for data in rows {
            if let Ok(bytes) = data {
                if let Ok(decrypted) = decrypt_entry(&self.vault_key, &bytes) {
                    if let Ok(entry) = serde_json::from_slice::<VaultEntry>(&decrypted) {
                        entries.push(entry);
                    }
                }
            }
        }
        Ok(entries)
    }
}
