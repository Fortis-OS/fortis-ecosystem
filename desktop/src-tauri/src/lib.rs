//! fortis-desktop — Tauri commands (docs/development §4).
//!
//! All agent calls happen here, in Rust. The webview never sees the agent
//! token and never talks to the agent directly.

use std::sync::Mutex;
use std::time::Duration;

use fortis_desktop_agent::{check_id, clip_hash, entry_field, Agent, Paths, UiError, UiResult};
use serde_json::{json, Value};
use tauri::{AppHandle, State};
use tauri_plugin_clipboard_manager::ClipboardExt;
use zeroize::Zeroizing;

const CLEAR_AFTER: Duration = Duration::from_secs(30);

struct AppState {
    agent: Agent,
    /// Hash of the last secret we put on the clipboard.
    last_clip: Mutex<Option<[u8; 32]>>,
}

fn q(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

#[tauri::command]
async fn vault_status(st: State<'_, AppState>) -> UiResult<Value> {
    st.agent.get("/vault/status").await
}

#[tauri::command]
async fn init_vault(st: State<'_, AppState>, password: String) -> UiResult<Value> {
    let p = Zeroizing::new(password);
    st.agent.post("/vault/init", json!({ "password": p.as_str() })).await
}

#[tauri::command]
async fn unlock(st: State<'_, AppState>, password: String) -> UiResult<Value> {
    let p = Zeroizing::new(password);
    st.agent.post("/vault/unlock", json!({ "password": p.as_str() })).await
}

#[tauri::command]
async fn lock(st: State<'_, AppState>) -> UiResult<Value> {
    st.agent.post("/vault/lock", json!({})).await
}

#[tauri::command]
async fn change_password(st: State<'_, AppState>, current: String, new: String) -> UiResult<Value> {
    let (c, n) = (Zeroizing::new(current), Zeroizing::new(new));
    st.agent
        .post("/vault/change-password", json!({ "current_password": c.as_str(), "new_password": n.as_str() }))
        .await
}

#[tauri::command]
async fn list_entries(st: State<'_, AppState>, query: Option<String>, kind: Option<String>) -> UiResult<Value> {
    let mut params = Vec::new();
    if let Some(s) = query.filter(|s| !s.is_empty()) {
        params.push(format!("q={}", q(&s)));
    }
    if let Some(k) = kind.filter(|k| !k.is_empty()) {
        params.push(format!("type={}", q(&k)));
    }
    let path = if params.is_empty() { "/vault/entries".into() } else { format!("/vault/entries?{}", params.join("&")) };
    st.agent.get(&path).await
}

#[tauri::command]
async fn get_entry(st: State<'_, AppState>, id: String) -> UiResult<Value> {
    st.agent.get(&format!("/vault/entries/{}", check_id(&id)?)).await
}

#[tauri::command]
async fn save_entry(st: State<'_, AppState>, id: Option<String>, entry: Value) -> UiResult<Value> {
    match id {
        Some(id) => st.agent.put(&format!("/vault/entries/{}", check_id(&id)?), entry).await,
        None => st.agent.post("/vault/entries", entry).await,
    }
}

#[tauri::command]
async fn delete_entry(st: State<'_, AppState>, id: String) -> UiResult<Value> {
    st.agent.delete(&format!("/vault/entries/{}", check_id(&id)?)).await
}

#[tauri::command]
async fn totp(st: State<'_, AppState>, id: String) -> UiResult<Value> {
    st.agent.get(&format!("/vault/entries/{}/totp", check_id(&id)?)).await
}

#[tauri::command]
async fn generate_password(st: State<'_, AppState>, length: u32, uppercase: bool, numbers: bool, symbols: bool) -> UiResult<Value> {
    st.agent
        .post("/tools/generate-password", json!({ "length": length, "uppercase": uppercase, "numbers": numbers, "symbols": symbols }))
        .await
}

/// Put `secret` on the clipboard and clear it after 30 s if it's still there.
fn copy_and_schedule_clear(app: &AppHandle, st: &AppState, secret: Zeroizing<String>) -> UiResult<()> {
    app.clipboard()
        .write_text(secret.as_str())
        .map_err(|e| UiError::new("clipboard", e.to_string()))?;
    let h = clip_hash(&secret);
    if let Ok(mut g) = st.last_clip.lock() {
        *g = Some(h);
    }
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(CLEAR_AFTER).await;
        let still_ours = app.clipboard().read_text().map(|t| clip_hash(&t) == h).unwrap_or(false);
        if still_ours {
            let _ = app.clipboard().write_text("");
        }
    });
    Ok(())
}

/// Copy a field of an entry. The secret goes agent → Rust → clipboard and
/// never passes through the webview.
#[tauri::command]
async fn copy_secret(app: AppHandle, st: State<'_, AppState>, id: String, field: String) -> UiResult<()> {
    let value = if field == "totp" {
        let t = st.agent.get(&format!("/vault/entries/{}/totp", check_id(&id)?)).await?;
        t["code"].as_str().unwrap_or_default().to_string()
    } else {
        let e = st.agent.get(&format!("/vault/entries/{}", check_id(&id)?)).await?;
        entry_field(&e, &field).ok_or_else(|| UiError::new("not_found", format!("this entry has no {field}")))?
    };
    copy_and_schedule_clear(&app, &st, Zeroizing::new(value))
}

/// Copy text the UI already shows (a generated password).
#[tauri::command]
async fn copy_text(app: AppHandle, st: State<'_, AppState>, text: String) -> UiResult<()> {
    copy_and_schedule_clear(&app, &st, Zeroizing::new(text))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let paths = match Paths::from_env() {
        Ok(p) => p,
        Err(e) => {
            eprintln!("fortis-desktop: {e}");
            std::process::exit(1);
        }
    };
    tauri::Builder::default()
        .plugin(tauri_plugin_clipboard_manager::init())
        .manage(AppState { agent: Agent::new(paths), last_clip: Mutex::new(None) })
        .invoke_handler(tauri::generate_handler![
            vault_status,
            init_vault,
            unlock,
            lock,
            change_password,
            list_entries,
            get_entry,
            save_entry,
            delete_entry,
            totp,
            generate_password,
            copy_secret,
            copy_text,
        ])
        .run(tauri::generate_context!())
        .expect("error while running fortis-desktop");
}
