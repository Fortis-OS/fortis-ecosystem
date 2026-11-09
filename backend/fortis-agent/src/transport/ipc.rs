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

    loop {
        match listener.accept().await {
            Ok((mut socket, _addr)) => {
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
                                    
                                    match crate::modules::vault::get_db() {
                                        Ok(db) => {
                                            match db.add_entry(&new_entry) {
                                                Ok(_) => format!("Success: Added entry '{}' to database.", title),
                                                Err(e) => format!("Error: Failed to add entry: {}", e)
                                            }
                                        }
                                        Err(e) => format!("Error: {}", e)
                                    }
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
