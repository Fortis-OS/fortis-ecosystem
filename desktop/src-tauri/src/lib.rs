use fortis_core::db::Database;
use fortis_core::vault::VaultEntry;
use std::path::PathBuf;
use std::sync::Mutex;

struct AppState {
    db: Mutex<Database>,
}

#[tauri::command]
fn get_vault_entries(state: tauri::State<AppState>) -> Result<Vec<VaultEntry>, String> {
    let db = state.db.lock().unwrap();
    db.get_entries().map_err(|e| e.to_string())
}

#[tauri::command]
fn generate_password(length: usize) -> String {
    // Call the core fortis_core logic here, stubbed for UI
    format!("SecurePassword-{}", length)
}

#[tauri::command]
fn get_api_key() -> Result<String, String> {
    // First try environment (useful for dev)
    if let Ok(key) = std::env::var("FORTIS_AGENT_KEY") {
        return Ok(key);
    }
    
    // Otherwise read from ~/.config/fortis/env (production daemon layout)
    let home = std::env::var("HOME").map_err(|_| "HOME not set".to_string())?;
    let env_path = std::path::PathBuf::from(home).join(".config/fortis/env");
    
    let content = std::fs::read_to_string(env_path)
        .map_err(|e| format!("Failed to read env file: {}", e))?;
        
    for line in content.lines() {
        if line.starts_with("FORTIS_AGENT_KEY=") {
            return Ok(line.replace("FORTIS_AGENT_KEY=", "").trim().to_string());
        }
    }
    
    Err("FORTIS_AGENT_KEY not found in env file".to_string())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    let db_path = PathBuf::from(home).join(".local/share/fortis/vault.db");
    
    // Connect to the shared global daemon database
    let db = Database::open(db_path, "dev_master_key").expect("Failed to open DB");
    db.init_schema().expect("Failed to init DB schema");

    tauri::Builder::default()
        .manage(AppState { db: Mutex::new(db) })
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![get_vault_entries, generate_password, get_api_key])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
