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
        .route("/vault/unlock", post(handle_vault_unlock))
        .route("/vault/init", post(handle_vault_init))
        .route("/vault/lock", post(handle_vault_lock))
        .route("/vault/status", get(handle_vault_status))
        .route_layer(middleware::from_fn(auth_middleware));

    let cors = CorsLayer::new()
        .allow_methods(vec![Method::GET, Method::POST, Method::OPTIONS])
        .allow_headers(Any)
        .allow_origin(Any);

    let app = Router::new()
        .nest("/api/v1", v1_routes)
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

// ---- Vault (interim, on fortis-core v2) -------------------------------------

use fortis_core::db::DbError;
use fortis_core::vault::EntryInput;
use crate::modules::vault::{self as vault_mod, VaultAccessError};

fn vault_error(e: VaultAccessError) -> StatusCode {
    match e {
        VaultAccessError::Locked => StatusCode::LOCKED,
        VaultAccessError::Db(DbError::NotFound) => StatusCode::NOT_FOUND,
        VaultAccessError::Db(DbError::Validation(_)) => StatusCode::BAD_REQUEST,
        VaultAccessError::Db(e) => {
            log::error!("vault error: {e}");
            StatusCode::INTERNAL_SERVER_ERROR
        }
    }
}

async fn handle_vault_entries() -> Result<Json<serde_json::Value>, StatusCode> {
    let list = vault_mod::with_db(|db| db.list()).map_err(vault_error)?;
    if !list.corrupt.is_empty() {
        log::error!("{} vault entries failed to decrypt", list.corrupt.len());
    }
    let summaries: Vec<_> = list.entries.iter().map(|e| e.summary()).collect();
    Ok(Json(json!(summaries)))
}

async fn handle_add_vault_entry(axum::Json(input): axum::Json<EntryInput>) -> Result<Json<serde_json::Value>, StatusCode> {
    let entry = vault_mod::with_db(|db| db.insert(input, fortis_core::unix_now())).map_err(vault_error)?;
    Ok(Json(json!({ "id": entry.id })))
}

#[derive(Deserialize)]
struct UnlockPayload {
    password: String,
}

async fn handle_vault_unlock(axum::Json(payload): axum::Json<UnlockPayload>) -> Result<Json<serde_json::Value>, StatusCode> {
    // Argon2id takes ~0.5 s of CPU; keep it off the async workers.
    let res = tokio::task::spawn_blocking(move || vault_mod::unlock(&payload.password))
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    match res {
        Ok(()) => Ok(Json(json!({ "state": "unlocked" }))),
        Err(DbError::InvalidPassword) => Err(StatusCode::UNAUTHORIZED),
        Err(DbError::Uninitialised) => Err(StatusCode::TOO_EARLY),
        Err(e) => {
            log::error!("unlock failed: {e}");
            Err(StatusCode::INTERNAL_SERVER_ERROR)
        }
    }
}

async fn handle_vault_init(axum::Json(payload): axum::Json<UnlockPayload>) -> Result<Json<serde_json::Value>, StatusCode> {
    let res = tokio::task::spawn_blocking(move || vault_mod::init(&payload.password))
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    match res {
        Ok(()) => Ok(Json(json!({ "state": "unlocked" }))),
        Err(DbError::AlreadyExists) => Err(StatusCode::CONFLICT),
        Err(DbError::Validation(_)) => Err(StatusCode::BAD_REQUEST),
        Err(e) => {
            log::error!("init failed: {e}");
            Err(StatusCode::INTERNAL_SERVER_ERROR)
        }
    }
}

async fn handle_vault_lock() -> Json<serde_json::Value> {
    vault_mod::lock();
    Json(json!({ "state": "locked" }))
}

async fn handle_vault_status() -> Json<serde_json::Value> {
    Json(json!({ "state": vault_mod::state() }))
}
