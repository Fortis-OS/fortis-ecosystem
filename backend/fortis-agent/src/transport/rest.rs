//! HTTP routers and listeners (API endpoint summary §7, security §5.1).
//!
//! Two separate routers: an endpoint that only belongs on one listener
//! simply doesn't exist on the other (→ 404).

use std::net::{Ipv4Addr, SocketAddr};
use std::time::Duration;

use axum::Router;
use axum::extract::DefaultBodyLimit;
use axum::middleware::from_fn_with_state;
use axum::routing::{delete, get, post};

use crate::error::ApiError;
use crate::modules::{files, phone, system, vault};
use crate::state::Shared;
use crate::transport::auth::{JSON_LIMIT, is_tailnet, loopback_auth, tailnet_auth};

async fn not_found() -> ApiError {
    ApiError::not_found()
}

/// Endpoints available on both listeners.
fn system_routes(st: &Shared) -> Router<Shared> {
    Router::new()
        .route("/vault/status", get(vault::status))
        .route("/vault/lock", post(vault::lock_now))
        .route("/telemetry", get(system::telemetry))
        .route("/capabilities", get(system::capabilities))
        .route("/actions", post(system::actions))
        .route("/media", get(system::media))
        .route("/clipboard", post(system::clipboard))
        .route("/files/roots", get(files::list_roots))
        .route("/files/list", get(files::list))
        .route("/files/download", get(files::download))
        .route(
            "/files/drop",
            post(files::drop_file).layer(DefaultBodyLimit::max(
                st.config.max_upload_bytes() + JSON_LIMIT,
            )),
        )
        .route("/backup/logs", get(system::backup_logs))
        .route("/tools/generate-password", post(system::generate_password))
}

pub fn loopback_router(st: Shared) -> Router {
    let api = system_routes(&st)
        .route("/vault/init", post(vault::init))
        .route("/vault/unlock", post(vault::unlock))
        .route("/vault/change-password", post(vault::change_password))
        .route("/vault/entries", get(vault::list).post(vault::create_entry))
        .route(
            "/vault/entries/{id}",
            get(vault::get_entry)
                .put(vault::replace_entry)
                .delete(vault::delete_entry),
        )
        .route("/vault/entries/{id}/totp", get(vault::entry_totp))
        .route("/devices", get(vault::devices))
        .route("/devices/{id}", delete(vault::delete_device));
    Router::new()
        .nest("/api/v1", api)
        .fallback(not_found)
        .layer(DefaultBodyLimit::max(JSON_LIMIT))
        .layer(from_fn_with_state(st.clone(), loopback_auth))
        .with_state(st)
}

pub fn tailnet_router(st: Shared) -> Router {
    let api = system_routes(&st)
        .route("/sync/bootstrap", get(vault::sync_bootstrap))
        .route("/sync/pull", get(vault::sync_pull))
        .route("/sync/push", post(vault::sync_push))
        .route("/pair", post(vault::pair))
        .route("/events", get(phone::events))
        .route("/auth/respond", post(phone::auth_respond))
        .route("/notify", post(system::notify))
        .route("/sessions", post(phone::session_create))
        .route("/sessions/{id}/confirm", post(phone::session_confirm))
        .route("/sessions/{id}", delete(phone::session_end))
        .route("/input", get(phone::input_ws))
        .route("/webcam/start", post(phone::webcam_start))
        .route("/webcam/stop", post(phone::webcam_stop));
    Router::new()
        .nest("/api/v1", api)
        .fallback(not_found)
        .layer(DefaultBodyLimit::max(JSON_LIMIT))
        .layer(from_fn_with_state(st.clone(), tailnet_auth))
        .with_state(st)
}

pub async fn bind_loopback(st: &Shared) -> std::io::Result<tokio::net::TcpListener> {
    tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, st.config.rest.port)).await
}

pub async fn serve(listener: tokio::net::TcpListener, app: Router) -> std::io::Result<()> {
    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .await
}

/// This machine's Tailscale IPv4 address, via `tailscale ip -4`.
pub async fn discover_tailnet_ip() -> Option<Ipv4Addr> {
    let out = crate::util::run("tailscale", &["ip", "-4"]).await.ok()?;
    let ip: Ipv4Addr = out.stdout.lines().next()?.trim().parse().ok()?;
    is_tailnet(ip.into()).then_some(ip)
}

/// Keep the tailnet listener up: (re)discover the address every 30 s.
pub async fn tailnet_supervisor(st: Shared) {
    loop {
        match discover_tailnet_ip().await {
            Some(ip) => match tokio::net::TcpListener::bind((ip, st.config.rest.port)).await {
                Ok(l) => {
                    *st.tailnet_ip.write().unwrap_or_else(|p| p.into_inner()) = Some(ip);
                    log::info!("tailnet REST listening on {ip}:{}", st.config.rest.port);
                    if let Err(e) = serve(l, tailnet_router(st.clone())).await {
                        log::error!("tailnet listener stopped: {e}");
                    }
                    *st.tailnet_ip.write().unwrap_or_else(|p| p.into_inner()) = None;
                }
                Err(e) => log::warn!("cannot bind tailnet listener on {ip}: {e}"),
            },
            None => log::debug!("no Tailscale IPv4 address yet"),
        }
        tokio::time::sleep(Duration::from_secs(30)).await;
    }
}
