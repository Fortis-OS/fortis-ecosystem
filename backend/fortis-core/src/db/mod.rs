use rusqlite::{Connection, Result as SqlResult, params};
use std::path::Path;
use crate::vault::VaultEntry;

pub struct Database {
    conn: Connection,
}

impl Database {
    pub fn open<P: AsRef<Path>>(path: P, key: &str) -> SqlResult<Self> {
        let conn = Connection::open(path)?;
        
        // SQLCipher PRAGMAs for AES-256 encryption
        conn.pragma_update(None, "key", key)?;
        conn.pragma_update(None, "cipher_page_size", 4096)?;
        conn.pragma_update(None, "kdf_iter", 256000)?;
        
        // Verify key by trying to read the schema
        let _count: i64 = conn.query_row("SELECT count(*) FROM sqlite_master", [], |row| row.get(0))?;
        
        Ok(Self { conn })
    }

    pub fn init_schema(&self) -> SqlResult<()> {
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS entries (
                id TEXT PRIMARY KEY,
                folder_id TEXT,
                encrypted_data BLOB NOT NULL,
                last_updated INTEGER NOT NULL
            )",
            [],
        )?;
        
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS folders (
                id TEXT PRIMARY KEY,
                encrypted_data BLOB NOT NULL,
                last_updated INTEGER NOT NULL
            )",
            [],
        )?;
        
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS sync_clock (
                device_id TEXT PRIMARY KEY,
                timestamp INTEGER NOT NULL
            )",
            [],
        )?;
        
        Ok(())
    }

    pub fn add_entry(&self, entry: &VaultEntry) -> SqlResult<()> {
        let serialized = serde_json::to_vec(entry).expect("Serialization failed");
        
        self.conn.execute(
            "INSERT OR REPLACE INTO entries (id, folder_id, encrypted_data, last_updated)
             VALUES (?1, ?2, ?3, strftime('%s', 'now'))",
            params![
                entry.id.to_string(),
                entry.folder_id.map(|id| id.to_string()),
                serialized
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
                if let Ok(entry) = serde_json::from_slice::<VaultEntry>(&bytes) {
                    entries.push(entry);
                }
            }
        }
        Ok(entries)
    }
}
