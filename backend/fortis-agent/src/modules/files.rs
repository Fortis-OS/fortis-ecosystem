use std::fs;
use std::path::PathBuf;
use serde::{Deserialize, Serialize};

#[derive(Serialize)]
pub struct FileInfo {
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
}

pub fn list_files(path: Option<String>) -> Result<(String, Vec<FileInfo>), String> {
    let target_path = if let Some(p) = path {
        if p.is_empty() {
            std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string())
        } else {
            p
        }
    } else {
        std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string())
    };

    let entries = fs::read_dir(&target_path).map_err(|e| format!("Failed to read directory: {}", e))?;
    let mut files = Vec::new();

    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let is_dir = entry.file_type().map(|t| t.is_dir()).unwrap_or(false);
        let size = entry.metadata().map(|m| m.len()).unwrap_or(0);

        files.push(FileInfo { name, is_dir, size });
    }

    Ok((target_path, files))
}

pub async fn save_file_drop(filename: &str, data: &[u8]) -> Result<(), String> {
    let home_dir = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    let drop_dir = PathBuf::from(home_dir).join("Downloads").join("FortisMobileDrop");
    
    fs::create_dir_all(&drop_dir).map_err(|e| format!("Failed to create drop directory: {}", e))?;
    
    let dest_path = drop_dir.join(filename);
    fs::write(&dest_path, data).map_err(|e| format!("Failed to write file: {}", e))?;
    
    Ok(())
}
