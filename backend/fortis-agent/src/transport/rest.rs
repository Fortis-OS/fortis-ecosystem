use axum::{
    routing::{get, post},
    Router,
    response::Json,
    middleware,
    http::{StatusCode, Request},
    middleware::Next,
    response::IntoResponse,
    body::Bytes,
};
use log::info;
use serde_json::json;
use serde::Deserialize;

async fn auth_middleware(req: Request<axum::body::Body>, next: Next) -> Result<impl IntoResponse, StatusCode> {
    let api_key = std::env::var("FORTIS_AGENT_KEY").unwrap_or_else(|_| "".to_string());
    if api_key.is_empty() {
        log::error!("FORTIS_AGENT_KEY is not set");
        return Err(StatusCode::INTERNAL_SERVER_ERROR);
    }

    if let Some(auth_header) = req.headers().get("Authorization") {
        if let Ok(auth_str) = auth_header.to_str() {
            if auth_str == format!("Bearer {}", api_key) {
                return Ok(next.run(req).await);
            }
        }
    }

    Err(StatusCode::UNAUTHORIZED)
}

use tower_http::cors::{CorsLayer, Any};
use axum::http::Method;

pub async fn start_server() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let port = 8023;
    info!("Starting REST API on :{}", port);

    let v1_routes = Router::new()
        .route("/telemetry", get(handle_telemetry))
        .route("/capabilities", get(handle_capabilities))
        .route("/clipboard", post(handle_clipboard))
        .route("/action", post(handle_action))
        .route("/media", get(handle_media))
        .route("/files/drop", post(handle_file_drop))
        .route("/files/list", post(handle_file_list))
        .route("/files/download", get(handle_file_download))
        .route("/backup/logs", get(handle_logs))
        .route("/vault/entries", get(handle_vault_entries).post(handle_add_vault_entry))
        .route_layer(middleware::from_fn(auth_middleware));

    let public_routes = Router::new()
        .route("/pair", post(handle_pair))
        .route("/auth/unlock", post(handle_auth_unlock));

    let cors = CorsLayer::new()
        .allow_methods(vec![Method::GET, Method::POST, Method::OPTIONS])
        .allow_headers(Any)
        .allow_origin(Any);

    let app = Router::new()
        .nest("/api/v1", v1_routes)
        .nest("/api/v1", public_routes)
        .layer(cors);

    let listener = tokio::net::TcpListener::bind(format!("0.0.0.0:{}", port)).await?;
    axum::serve(listener, app).await?;

    Ok(())
}

async fn handle_telemetry() -> Json<serde_json::Value> {
    Json(crate::modules::telemetry::get_telemetry())
}

async fn handle_capabilities() -> Json<serde_json::Value> {
    Json(json!({
        "telemetry": true,
        "actions": true,
        "files": true,
        "vault": true,
        "biometric_auth": true,
        "backup": true,
        "terminal": true,
        "clipboard": true,
        "webcam": true,
        "media": true
    }))
}

async fn handle_clipboard(body: Bytes) -> Result<Json<serde_json::Value>, StatusCode> {
    let text = String::from_utf8_lossy(&body).to_string();
    match crate::modules::actions::handle_clipboard(&text) {
        Ok(v) => Ok(Json(v)),
        Err(e) => {
            log::error!("Clipboard error: {}", e);
            Err(StatusCode::INTERNAL_SERVER_ERROR)
        }
    }
}

#[derive(Deserialize)]
struct ActionPayload {
    action: String,
}

async fn handle_action(axum::Json(payload): axum::Json<ActionPayload>) -> Result<Json<serde_json::Value>, StatusCode> {
    match crate::modules::actions::handle_action(&payload.action) {
        Ok(v) => Ok(Json(v)),
        Err(e) => {
            log::error!("Action error: {}", e);
            Err(StatusCode::BAD_REQUEST)
        }
    }
}

async fn handle_media() -> Result<Json<serde_json::Value>, StatusCode> {
    match crate::modules::actions::handle_media() {
        Ok(v) => Ok(Json(v)),
        Err(_) => Err(StatusCode::NOT_FOUND)
    }
}

#[derive(Deserialize)]
struct FileListPayload {
    path: Option<String>,
}

async fn handle_file_list(axum::Json(payload): axum::Json<FileListPayload>) -> Result<Json<serde_json::Value>, StatusCode> {
    match crate::modules::files::list_files(payload.path) {
        Ok((path, files)) => Ok(Json(json!({ "path": path, "files": files }))),
        Err(e) => {
            log::error!("File list error: {}", e);
            Err(StatusCode::INTERNAL_SERVER_ERROR)
        }
    }
}

async fn handle_file_drop(mut multipart: axum::extract::Multipart) -> Result<Json<serde_json::Value>, StatusCode> {
    while let Some(field) = multipart.next_field().await.unwrap_or(None) {
        let name = field.name().unwrap_or("").to_string();
        if name == "file" {
            let file_name = field.file_name().unwrap_or("upload.tmp").to_string();
            let data = field.bytes().await.unwrap_or_default();
            
            if let Err(e) = crate::modules::files::save_file_drop(&file_name, &data).await {
                log::error!("File drop save error: {}", e);
                return Err(StatusCode::INTERNAL_SERVER_ERROR);
            }
            return Ok(Json(json!({ "status": "success", "file": file_name })));
        }
    }
    Err(StatusCode::BAD_REQUEST)
}

#[derive(Deserialize)]
struct DownloadQuery {
    path: String,
}


use axum::body::Body;
use tokio::fs::File;
use tokio_util::io::ReaderStream;

async fn handle_file_download(axum::extract::Query(query): axum::extract::Query<DownloadQuery>) -> Result<impl IntoResponse, StatusCode> {
    let path = std::path::Path::new(&query.path);
    if !path.exists() || path.is_dir() {
        return Err(StatusCode::NOT_FOUND);
    }

    let file = File::open(path).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let stream = ReaderStream::new(file);
    let body = Body::from_stream(stream);
    
    let file_name = path.file_name().unwrap_or_default().to_string_lossy();
    let content_disposition = format!("attachment; filename=\"{}\"", file_name);

    Ok((
        [(axum::http::header::CONTENT_DISPOSITION, content_disposition)],
        body
    ))
}

async fn handle_logs() -> Result<String, StatusCode> {
    match crate::modules::backup::get_logs() {
        Ok(logs) => Ok(logs),
        Err(_) => Err(StatusCode::INTERNAL_SERVER_ERROR),
    }
}

async fn handle_vault_entries() -> Result<Json<serde_json::Value>, StatusCode> {
    // Open the local database directly for simplicity. 
    // In a fully unified system, this might be shared via Arc<Mutex<Database>> from main.
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    let db_path = std::path::PathBuf::from(home).join(".local/share/fortis/vault.db");
    
    if let Ok(db) = fortis_core::db::Database::open(db_path, "dev_master_key") {
        if let Ok(entries) = db.get_entries() {
            // Serialize to JSON, handling SecretString zeroization correctly
            return Ok(Json(serde_json::to_value(entries).unwrap_or(json!([]))));
        }
    }
    
    Err(StatusCode::INTERNAL_SERVER_ERROR)
}

#[derive(Deserialize)]
struct NewVaultEntryPayload {
    title: String,
    notes: String,
    entry_type: String, // "Login", "CreditCard", "SecureNote"
    username: Option<String>,
    password: Option<String>,
    totp_secret: Option<String>,
}

async fn handle_add_vault_entry(axum::Json(payload): axum::Json<NewVaultEntryPayload>) -> Result<Json<serde_json::Value>, StatusCode> {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    let db_path = std::path::PathBuf::from(home).join(".local/share/fortis/vault.db");
    
    if let Ok(db) = fortis_core::db::Database::open(db_path, "dev_master_key") {
        // Map string to enum
        let entry_type = match payload.entry_type.as_str() {
            "Login" => fortis_core::vault::EntryType::Login { 
                username: payload.username.unwrap_or_default(), 
                password: fortis_core::vault::SecretString(zeroize::Zeroizing::new(payload.password.unwrap_or_default())),
                url: None,
                totp_secret: payload.totp_secret,
            },
            "CreditCard" => fortis_core::vault::EntryType::CreditCard { 
                cardholder_name: "".to_string(),
                card_number: fortis_core::vault::SecretString(zeroize::Zeroizing::new("".to_string())), 
                expiration_month: 0,
                expiration_year: 0,
                cvv: fortis_core::vault::SecretString(zeroize::Zeroizing::new("".to_string())),
            },
            _ => fortis_core::vault::EntryType::SecureNote { 
                content: fortis_core::vault::SecretString(zeroize::Zeroizing::new(payload.notes.clone())) 
            }
        };

        let entry = fortis_core::vault::VaultEntry {
            id: uuid::Uuid::new_v4(),
            folder_id: None,
            title: payload.title.clone(),
            entry_type,
            custom_fields: vec![],
            notes: payload.notes,
        };

        if db.add_entry(&entry).is_ok() {
            return Ok(Json(json!({ "status": "success" })));
        }
    }
    
    Err(StatusCode::INTERNAL_SERVER_ERROR)
}

#[derive(Deserialize)]
struct PairingPayload {
    code: String,
    public_key: String,
}

async fn handle_pair(axum::Json(payload): axum::Json<PairingPayload>) -> Result<Json<serde_json::Value>, StatusCode> {
    // Check if code matches
    let mut code_lock = crate::modules::auth::ACTIVE_PAIRING_CODE.write().unwrap();
    
    if let Some(active_code) = code_lock.clone() {
        if active_code == payload.code {
            // Success! Save the device.
            *code_lock = None; // Invalidate the code
            
            let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
            let config_dir = std::path::PathBuf::from(home).join(".config/fortis");
            std::fs::create_dir_all(&config_dir).unwrap_or_default();
            let paired_file = config_dir.join("paired_devices.json");
            
            // Note: Currently only supporting one device for simplicity, this would normally append.
            let device_json = json!({
                "devices": [
                    {
                        "public_key": payload.public_key,
                        "paired_at": std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs()
                    }
                ]
            });
            
            if std::fs::write(&paired_file, serde_json::to_string_pretty(&device_json).unwrap()).is_ok() {
                return Ok(Json(json!({ "status": "success", "message": "Paired successfully." })));
            }
        }
    }
    
    Err(StatusCode::UNAUTHORIZED)
}

#[derive(Deserialize)]
struct AuthUnlockPayload {
    domain: String,
}

async fn handle_auth_unlock(axum::Json(payload): axum::Json<AuthUnlockPayload>) -> Result<Json<serde_json::Value>, StatusCode> {
    log::info!("Received biometric unlock request for domain: {}", payload.domain);
    
    // Relay the biometric challenge to the mobile device
    let result = crate::modules::auth::relay_challenge().await;
    
    if result.contains("Success") {
        Ok(Json(json!({ "status": "success" })))
    } else {
        Err(StatusCode::UNAUTHORIZED)
    }
}
