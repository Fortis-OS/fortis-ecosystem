use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::UnixListener;
use tokio::sync::Mutex;
use log::{info, error};

use fortis_core::db::Database;
use fortis_core::vault::{VaultEntry, EntryType, SecretString};
use uuid::Uuid;

pub async fn start_server() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let socket_path = "/tmp/fortis_agent.sock";

    // Remove existing socket if any
    let _ = fs::remove_file(socket_path);

    let listener = UnixListener::bind(socket_path)?;
    
    // Secure the socket to the current user only
    let mut perms = fs::metadata(socket_path)?.permissions();
    perms.set_mode(0o600);
    fs::set_permissions(socket_path, perms)?;

    info!("Listening for IPC on {}", socket_path);

    // Initialize DB (simplified for now)
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    let db_dir = PathBuf::from(home).join(".local/share/fortis");
    fs::create_dir_all(&db_dir)?;
    let db_path = db_dir.join("vault.db");
    
    let db = Database::open(db_path, "dev_master_key")?;
    db.init_schema()?;
    let db = Arc::new(Mutex::new(db));

    loop {
        match listener.accept().await {
            Ok((mut socket, _addr)) => {
                let db_clone = Arc::clone(&db);
                tokio::spawn(async move {
                    let mut buf = vec![0; 4096];
                    loop {
                        match socket.read(&mut buf).await {
                            Ok(0) => break, // Connection closed
                            Ok(n) => {
                                let request = String::from_utf8_lossy(&buf[..n]).trim().to_string();
                                info!("Received IPC command: {}", request);
                                
                                let response = if request.starts_with("ADD") {
                                    let title = request.trim_start_matches("ADD").trim().to_string();
                                    
                                    let new_entry = VaultEntry {
                                        id: Uuid::new_v4(),
                                        folder_id: None,
                                        title: title.clone(),
                                        entry_type: EntryType::SecureNote { 
                                            content: SecretString(zeroize::Zeroizing::new("Encrypted secret note via IPC".to_string()))
                                        },
                                        custom_fields: vec![],
                                        notes: "Added via CLI".to_string(),
                                    };
                                    
                                    let db_lock = db_clone.lock().await;
                                    match db_lock.add_entry(&new_entry) {
                                        Ok(_) => format!("Success: Added entry '{}' to database.", title),
                                        Err(e) => format!("Error: Failed to add entry: {}", e)
                                    }
                                } else if request.starts_with("GET") {
                                    let db_lock = db_clone.lock().await;
                                    match db_lock.get_entries() {
                                        Ok(entries) => {
                                            format!("Found {} entries in vault.\n{:#?}", entries.len(), entries)
                                        }
                                        Err(e) => format!("Error: Failed to get entries: {}", e)
                                    }
                                } else if request.starts_with("SYNC") {
                                    "Started P2P Syncing over Tailscale... (stub)".to_string()
                                } else if request.starts_with("AUTH_CHALLENGE") {
                                    // Relays biometric challenge via gRPC
                                    crate::modules::auth::relay_challenge().await
                                } else if request.starts_with("PAIR_START") {
                                    let code = crate::modules::auth::generate_pairing_code();
                                    format!("Pairing Code: {}\nEnter this on your mobile device to complete pairing.", code)
                                } else {
                                    format!("Processed unknown command: {}", request)
                                };
                                
                                if let Err(e) = socket.write_all(response.as_bytes()).await {
                                    error!("Failed to write to socket: {}", e);
                                    break;
                                }
                            }
                            Err(e) => {
                                error!("Socket read error: {}", e);
                                break;
                            }
                        }
                    }
                });
            }
            Err(e) => {
                error!("Accept failed: {}", e);
            }
        }
    }
}
