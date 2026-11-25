//! Phone-only features: events stream, sudo replies, fingerprint sessions,
//! remote input and webcam (API §4, §5b; security §5.6, §7.5, §7.6).

use std::net::SocketAddr;
use std::time::{Duration, Instant};

use axum::Extension;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{ConnectInfo, Path, Query, State};
use axum::response::Response;
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::sync::broadcast::error::RecvError;

use fortis_core::sign::Decision;

use crate::error::{ApiError, ApiJson, ApiResult};
use crate::hub::{SESSION_IDLE, SESSION_MAX, sign_frame};
use crate::modules::browser_lock;
use crate::modules::input::{self, InputEvent, MAX_EVENTS_PER_SEC};
use crate::modules::system::webcam_available;
use crate::state::Shared;
use crate::transport::auth::AuthedDevice;
use crate::util;

// ---- Events stream -------------------------------------------------------------

pub async fn events(
    ws: WebSocketUpgrade,
    State(st): State<Shared>,
    Extension(AuthedDevice(d)): Extension<AuthedDevice>,
) -> Response {
    ws.on_upgrade(move |socket| events_loop(st, d.device_id, socket))
}

async fn events_loop(st: Shared, device_id: uuid::Uuid, socket: WebSocket) {
    let (mut tx, mut rx) = socket.split();
    let mut events = st.events.subscribe();
    st.events.connected(device_id);
    log::info!("device {device_id} connected to events");
    let hello = sign_frame(
        &st.agent_key,
        &json!({ "type": "hello", "agent_name": st.hostname, "vault_state": st.vault_state() }),
    );
    let mut ping = tokio::time::interval(Duration::from_secs(25));
    ping.tick().await;

    let started = std::time::Instant::now();
    // Why the stream ended, for the log (flaky mobile links are otherwise invisible).
    let why: String = if tx.send(Message::Text(hello.into())).await.is_err() {
        "could not send hello".into()
    } else {
        loop {
            tokio::select! {
                ev = events.recv() => match ev {
                    Ok(frame) => if let Err(e) = tx.send(Message::Text(frame.into())).await { break format!("send failed: {e}") },
                    Err(RecvError::Lagged(_)) => continue,
                    Err(RecvError::Closed) => break "event hub closed".into(),
                },
                incoming = rx.next() => match incoming {
                    Some(Ok(Message::Close(c))) => break match c {
                        Some(c) => format!("phone closed ({} {})", c.code, c.reason),
                        None => "phone closed".into(),
                    },
                    None => break "connection ended".into(),
                    Some(Err(e)) => break format!("receive failed: {e}"),
                    _ => {} // pongs and anything else are ignored
                },
                _ = ping.tick() => {
                    // A revoked device is disconnected at the next ping.
                    if st.devices.get(&device_id).is_none() { break "device revoked".into() }
                    let f = sign_frame(&st.agent_key, &json!({ "type": "ping", "t": fortis_core::unix_now() }));
                    if let Err(e) = tx.send(Message::Text(f.into())).await { break format!("ping failed: {e}") }
                }
            }
        }
    };
    st.events.disconnected(device_id);
    log::info!(
        "device {device_id} disconnected from events after {}s: {why}",
        started.elapsed().as_secs()
    );
}

// ---- Sudo approval reply ------------------------------------------------------------

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RespondBody {
    request_id: String,
    decision: Decision,
    signature: String,
}

pub async fn auth_respond(
    State(st): State<Shared>,
    Extension(AuthedDevice(d)): Extension<AuthedDevice>,
    ApiJson(b): ApiJson<RespondBody>,
) -> ApiResult<axum::Json<Value>> {
    st.approvals
        .respond(&b.request_id, &d, b.decision, &b.signature)?;
    log::info!(
        "sudo request {} answered by {}: {}",
        b.request_id,
        d.device_id,
        b.decision.as_str()
    );
    st.events.publish(
        &st.agent_key,
        json!({ "type": "sudo_cancelled", "request_id": b.request_id, "reason": "answered_elsewhere" }),
    );
    Ok(axum::Json(json!({ "relayed": true })))
}

// ---- Fingerprint-confirmed sessions ---------------------------------------------------

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SessionBody {
    kind: String,
}

pub async fn session_create(
    State(st): State<Shared>,
    Extension(AuthedDevice(d)): Extension<AuthedDevice>,
    ApiJson(b): ApiJson<SessionBody>,
) -> ApiResult<axum::Json<Value>> {
    if b.kind == "browser_unlock" {
        let s = st
            .sessions
            .create(d.device_id, &b.kind, fortis_core::unix_now().max(0) as u64);
        return Ok(axum::Json(json!({
            "session_id": s.id,
            "nonce": s.nonce,
            "issued_at": s.issued_at,
            "expires_at": s.issued_at + crate::hub::SESSION_CONFIRM_TTL.as_secs(),
        })));
    }
    if b.kind != "input" {
        return Err(ApiError::bad_request("unknown session kind"));
    }
    if !st.config.input.enabled {
        return Err(ApiError::forbidden(
            "remote input is disabled in agent.toml",
        ));
    }
    if input::backend().is_none() {
        return Err(ApiError::unavailable("no input backend (xdotool/ydotool)"));
    }
    let s = st
        .sessions
        .create(d.device_id, &b.kind, fortis_core::unix_now().max(0) as u64);
    Ok(axum::Json(json!({
        "session_id": s.id,
        "nonce": s.nonce,
        "issued_at": s.issued_at,
        "expires_at": s.issued_at + crate::hub::SESSION_CONFIRM_TTL.as_secs(),
    })))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ConfirmBody {
    signature: String,
}

pub async fn session_confirm(
    State(st): State<Shared>,
    Extension(AuthedDevice(d)): Extension<AuthedDevice>,
    Path(id): Path<String>,
    ApiJson(b): ApiJson<ConfirmBody>,
) -> ApiResult<axum::Json<Value>> {
    let s = st.sessions.confirm(&id, &d, &b.signature)?;
    if s.kind == "browser_unlock" {
        // A fingerprint on the phone: unlock the browser now; nothing stays open.
        st.sessions.end(&s.id);
        let status = browser_lock::unlock(&st, &format!("fingerprint on {}", d.name))?;
        return Ok(axum::Json(
            json!({ "active": false, "browser_lock": status }),
        ));
    }
    log::info!("{} session started by {}", s.kind, d.device_id);
    util::notify(
        "Fortis",
        &format!("📱 {} is controlling keyboard and mouse", d.name),
        true,
    )
    .await;
    Ok(axum::Json(json!({
        "active": true,
        "max_duration_secs": SESSION_MAX.as_secs(),
        "idle_timeout_secs": SESSION_IDLE.as_secs(),
    })))
}

pub async fn session_end(
    State(st): State<Shared>,
    Extension(AuthedDevice(d)): Extension<AuthedDevice>,
    Path(id): Path<String>,
) -> ApiResult<axum::Json<Value>> {
    match st
        .sessions
        .active()
        .into_iter()
        .find(|s| s.id == id && s.device_id == d.device_id)
    {
        Some(_) => {
            st.sessions.end(&id);
            util::notify(
                "Fortis",
                &format!("📱 {} stopped controlling keyboard and mouse", d.name),
                false,
            )
            .await;
            Ok(axum::Json(json!({ "active": false })))
        }
        None => Err(ApiError::not_found()),
    }
}

// ---- Remote input WebSocket --------------------------------------------------------------

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InputQuery {
    session: String,
}

pub async fn input_ws(
    ws: WebSocketUpgrade,
    State(st): State<Shared>,
    Extension(AuthedDevice(d)): Extension<AuthedDevice>,
    Query(q): Query<InputQuery>,
) -> ApiResult<Response> {
    if !st.config.input.enabled {
        return Err(ApiError::forbidden(
            "remote input is disabled in agent.toml",
        ));
    }
    if !st.sessions.touch(&q.session, &d.device_id, "input") {
        return Err(ApiError::unauthorized());
    }
    let backend = input::backend().ok_or_else(|| ApiError::unavailable("no input backend"))?;
    Ok(ws.on_upgrade(move |socket| input_loop(st, d.device_id, q.session, backend, socket)))
}

async fn input_loop(
    st: Shared,
    device_id: uuid::Uuid,
    session: String,
    backend: input::Backend,
    mut socket: WebSocket,
) {
    let mut window = Instant::now();
    let mut count = 0u32;
    let mut check = tokio::time::interval(Duration::from_secs(5));
    loop {
        tokio::select! {
            msg = socket.recv() => {
                let text = match msg {
                    Some(Ok(Message::Text(t))) => t,
                    Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                    _ => continue,
                };
                if !st.sessions.touch(&session, &device_id, "input") {
                    break;
                }
                if window.elapsed() >= Duration::from_secs(1) {
                    window = Instant::now();
                    count = 0;
                }
                count += 1;
                if count > MAX_EVENTS_PER_SEC {
                    continue; // excess is dropped
                }
                let Some(ev) = serde_json::from_str::<InputEvent>(&text).ok().and_then(InputEvent::validated) else {
                    let _ = socket.send(Message::Text(r#"{"type":"error","code":"bad_request"}"#.into())).await;
                    continue;
                };
                let args = ev.command(backend);
                let argv: Vec<&str> = args.iter().map(String::as_str).collect();
                if let Err(e) = util::run(backend.name(), &argv).await {
                    log::warn!("input backend failed: {e}");
                }
            }
            _ = check.tick() => {
                if !st.sessions.is_active(&session, &device_id, "input") { break }
            }
        }
    }
    let _ = socket.send(Message::Close(None)).await;
    log::info!("input session for {device_id} closed");
}

// ---- Webcam ----------------------------------------------------------------------------

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct WebcamBody {
    port: u16,
    token: String,
}

pub async fn webcam_start(
    State(st): State<Shared>,
    Extension(AuthedDevice(d)): Extension<AuthedDevice>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    ApiJson(b): ApiJson<WebcamBody>,
) -> ApiResult<axum::Json<Value>> {
    if !st.config.webcam.enabled {
        return Err(ApiError::forbidden("webcam is disabled in agent.toml"));
    }
    if !webcam_available(&st) {
        return Err(ApiError::unavailable(
            "ffmpeg or the v4l2loopback device is missing",
        ));
    }
    if b.port < 1024 || b.token.len() != 32 || !b.token.bytes().all(|c| c.is_ascii_hexdigit()) {
        return Err(ApiError::bad_request("invalid port or token"));
    }
    let mut slot = st.webcam.lock().await;
    if let Some(child) = slot.as_mut()
        && matches!(child.try_wait(), Ok(None))
    {
        return Err(ApiError::conflict("a webcam stream is already running"));
    }
    // The address comes from the authenticated connection, never the body.
    let url = format!("http://{}:{}/{}/stream", peer.ip(), b.port, b.token);
    let device = st.config.webcam.device.clone();
    let child = tokio::process::Command::new("ffmpeg")
        .args([
            "-nostdin",
            "-loglevel",
            "error",
            "-rw_timeout",
            "10000000",
            "-f",
            "mjpeg",
            "-i",
            &url,
        ])
        .args(["-vf", "format=yuv420p", "-f", "v4l2", &device])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .map_err(ApiError::internal)?;
    let pid = child.id();
    *slot = Some(child);
    drop(slot);
    log::info!("webcam stream started by {}", d.device_id);

    // Hard stop after one hour.
    let st2 = st.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_secs(3600)).await;
        let mut slot = st2.webcam.lock().await;
        if slot.as_ref().and_then(|c| c.id()) == pid {
            if let Some(mut c) = slot.take() {
                let _ = c.kill().await;
            }
            log::info!("webcam stream stopped after 1 hour");
        }
    });
    Ok(axum::Json(json!({ "device": device })))
}

pub async fn webcam_stop(State(st): State<Shared>) -> axum::Json<Value> {
    if let Some(mut c) = st.webcam.lock().await.take() {
        let _ = c.kill().await;
        log::info!("webcam stream stopped");
    }
    axum::Json(json!({ "ok": true }))
}

// ---- Browser lock (security §7.8) ---------------------------------------------------------

pub async fn browser_lock_status(State(st): State<Shared>) -> axum::Json<Value> {
    axum::Json(st.browser_lock.status())
}

pub async fn browser_lock_on(
    State(st): State<Shared>,
    Extension(AuthedDevice(d)): Extension<AuthedDevice>,
) -> ApiResult<axum::Json<Value>> {
    Ok(axum::Json(browser_lock::lock_on(
        &st,
        &format!("phone {}", d.name),
    )?))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SitesBody {
    sites: Value,
}

pub async fn browser_lock_sites(
    State(st): State<Shared>,
    Extension(AuthedDevice(d)): Extension<AuthedDevice>,
    ApiJson(b): ApiJson<SitesBody>,
) -> ApiResult<axum::Json<Value>> {
    let sites = browser_lock::parse_sites(Some(&b.sites))?;
    Ok(axum::Json(browser_lock::set_sites(
        &st,
        sites,
        &format!("phone {}", d.name),
    )?))
}
