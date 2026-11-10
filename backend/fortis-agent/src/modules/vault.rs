//! Vault, sync, pairing and device endpoints (API §1–§3).

use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::sync::oneshot;
use uuid::Uuid;
use zeroize::Zeroizing;

use fortis_core::db::SyncChange;
use fortis_core::sign::PublicKey;
use fortis_core::vault::EntryInput;

use crate::devices::{Device, PairCheck, PairingSession, check_code, display_code};
use crate::error::{ApiError, ApiJson, ApiResult};
use crate::state::{Shared, lock};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PasswordBody {
    password: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ChangePasswordBody {
    current_password: String,
    new_password: String,
}

pub async fn status(State(st): State<Shared>) -> axum::Json<Value> {
    axum::Json(json!({
        "state": st.vault_state(),
        "auto_lock_minutes": st.config.vault.auto_lock_minutes,
        "locks_in_secs": st.locks_in_secs(),
    }))
}

pub async fn init(
    State(st): State<Shared>,
    ApiJson(b): ApiJson<PasswordBody>,
) -> ApiResult<impl IntoResponse> {
    st.init(Zeroizing::new(b.password)).await?;
    Ok((
        StatusCode::CREATED,
        axum::Json(json!({ "state": "unlocked" })),
    ))
}

pub async fn unlock(
    State(st): State<Shared>,
    ApiJson(b): ApiJson<PasswordBody>,
) -> ApiResult<axum::Json<Value>> {
    st.unlock(Zeroizing::new(b.password)).await?;
    Ok(axum::Json(json!({ "state": "unlocked" })))
}

pub async fn lock_now(State(st): State<Shared>) -> axum::Json<Value> {
    st.lock_vault("requested");
    axum::Json(json!({ "state": st.vault_state() }))
}

pub async fn change_password(
    State(st): State<Shared>,
    ApiJson(b): ApiJson<ChangePasswordBody>,
) -> ApiResult<axum::Json<Value>> {
    st.change_password(
        Zeroizing::new(b.current_password),
        Zeroizing::new(b.new_password),
    )
    .await?;
    Ok(axum::Json(json!({ "state": "unlocked" })))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ListQuery {
    q: Option<String>,
    #[serde(rename = "type")]
    kind: Option<String>,
}

pub async fn list(
    State(st): State<Shared>,
    Query(q): Query<ListQuery>,
) -> ApiResult<axum::Json<Value>> {
    let res = st.with_vault(|db| db.list())?;
    if !res.corrupt.is_empty() {
        log::error!(
            "{} vault entries failed to decrypt: {:?}",
            res.corrupt.len(),
            res.corrupt
        );
    }
    let out: Vec<_> = res
        .entries
        .iter()
        .filter(|e| q.q.as_deref().is_none_or(|s| e.matches(s)))
        .filter(|e| {
            q.kind
                .as_deref()
                .is_none_or(|k| e.entry_type.type_name() == k)
        })
        .map(|e| e.summary())
        .collect();
    Ok(axum::Json(json!(out)))
}

fn parse_id(s: &str) -> ApiResult<Uuid> {
    Uuid::parse_str(s).map_err(|_| ApiError::not_found())
}

pub async fn get_entry(
    State(st): State<Shared>,
    Path(id): Path<String>,
) -> ApiResult<axum::Json<Value>> {
    let id = parse_id(&id)?;
    let e = st.with_vault(|db| db.get(&id))?;
    Ok(axum::Json(
        serde_json::to_value(&e).map_err(ApiError::internal)?,
    ))
}

pub async fn create_entry(
    State(st): State<Shared>,
    ApiJson(input): ApiJson<EntryInput>,
) -> ApiResult<impl IntoResponse> {
    let e = st.with_vault(|db| db.insert(input, fortis_core::unix_now()))?;
    Ok((StatusCode::CREATED, axum::Json(json!({ "id": e.id }))))
}

pub async fn replace_entry(
    State(st): State<Shared>,
    Path(id): Path<String>,
    ApiJson(input): ApiJson<EntryInput>,
) -> ApiResult<axum::Json<Value>> {
    let id = parse_id(&id)?;
    let e = st.with_vault(|db| db.update(&id, input, fortis_core::unix_now()))?;
    Ok(axum::Json(json!({ "id": e.id })))
}

pub async fn delete_entry(
    State(st): State<Shared>,
    Path(id): Path<String>,
) -> ApiResult<axum::Json<Value>> {
    let id = parse_id(&id)?;
    st.with_vault(|db| db.delete(&id, fortis_core::unix_now()))?;
    Ok(axum::Json(json!({})))
}

pub async fn entry_totp(
    State(st): State<Shared>,
    Path(id): Path<String>,
) -> ApiResult<axum::Json<Value>> {
    let id = parse_id(&id)?;
    let e = st.with_vault(|db| db.get(&id))?;
    let cfg = e.entry_type.totp().ok_or_else(ApiError::not_found)?;
    let now = fortis_core::unix_now().max(0) as u64;
    let code =
        fortis_core::totp::generate(cfg, now).map_err(|e| ApiError::bad_request(e.to_string()))?;
    Ok(axum::Json(
        json!({ "code": code, "remaining_secs": fortis_core::totp::remaining_secs(cfg.period, now) }),
    ))
}

// ---- Sync (tailnet) -----------------------------------------------------------

pub async fn sync_bootstrap(State(st): State<Shared>) -> ApiResult<axum::Json<Value>> {
    let b = st.with_vault(|db| db.bootstrap())?;
    Ok(axum::Json(
        serde_json::to_value(b).map_err(ApiError::internal)?,
    ))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PullQuery {
    #[serde(default)]
    since: i64,
}

pub async fn sync_pull(
    State(st): State<Shared>,
    Query(q): Query<PullQuery>,
) -> ApiResult<axum::Json<Value>> {
    let r = st.with_vault(|db| db.pull(q.since))?;
    Ok(axum::Json(
        serde_json::to_value(r).map_err(ApiError::internal)?,
    ))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PushBody {
    changes: Vec<SyncChange>,
}

pub async fn sync_push(
    State(st): State<Shared>,
    ApiJson(b): ApiJson<PushBody>,
) -> ApiResult<axum::Json<Value>> {
    let r = st.with_vault(|db| db.push(&b.changes))?;
    Ok(axum::Json(
        serde_json::to_value(r).map_err(ApiError::internal)?,
    ))
}

// ---- Pairing (tailnet, unauthenticated) -----------------------------------------

/// Open a pairing session (IPC `pair_start`). Replaces any existing one.
pub fn start_pairing(st: &Shared) -> (Value, oneshot::Receiver<Device>) {
    let (session, rx) = PairingSession::new();
    let code = session.code.clone();
    *lock(&st.pairing) = Some(session);
    let host = st.tailnet_ip().map(|ip| ip.to_string()).unwrap_or_default();
    let fp = st.agent_key.public().fingerprint();
    let data = json!({
        "code": display_code(&code),
        "qr_uri": format!("fortis://pair?host={host}&port={}&code={code}&agent_fp={fp}", st.config.rest.port),
        "expires_at": fortis_core::unix_now() + crate::devices::PAIRING_TTL.as_secs() as i64,
        "agent_fingerprint": fp,
        "tailnet_ip": host,
    });
    log::info!("pairing session opened");
    (data, rx)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PairBody {
    code: String,
    device_name: String,
    request_pubkey: String,
    approval_pubkey: String,
}

pub async fn pair(
    State(st): State<Shared>,
    ApiJson(b): ApiJson<PairBody>,
) -> ApiResult<axum::Json<Value>> {
    let name = b.device_name.trim();
    if name.is_empty() || name.chars().count() > 64 || name.chars().any(char::is_control) {
        return Err(ApiError::bad_request(
            "device_name must be 1-64 printable characters",
        ));
    }
    let req_key = PublicKey::from_base64(&b.request_pubkey)
        .map_err(|_| ApiError::bad_request("invalid request_pubkey"))?;
    let appr_key = PublicKey::from_base64(&b.approval_pubkey)
        .map_err(|_| ApiError::bad_request("invalid approval_pubkey"))?;
    if req_key == appr_key {
        return Err(ApiError::bad_request(
            "request and approval keys must differ",
        ));
    }

    let done = {
        let mut slot = lock(&st.pairing);
        match check_code(&mut slot, &b.code) {
            PairCheck::Ok => slot.take().and_then(|mut s| s.done.take()),
            PairCheck::NoSession | PairCheck::Wrong => {
                log::warn!("pairing attempt with wrong or expired code");
                return Err(ApiError::unauthorized());
            }
            PairCheck::Exhausted => {
                log::warn!("pairing session closed after too many wrong codes");
                return Err(ApiError::rate_limited(1));
            }
        }
    };
    let now = fortis_core::unix_now();
    let device = Device {
        device_id: Uuid::new_v4(),
        name: name.to_string(),
        request_pubkey: req_key.to_base64(),
        approval_pubkey: appr_key.to_base64(),
        paired_at: now,
        last_seen: now,
    };
    st.devices.add(device.clone()).map_err(ApiError::internal)?;
    log::info!("paired device {} ({})", device.device_id, device.name);
    if let Some(tx) = done {
        let _ = tx.send(device.clone());
    }
    Ok(axum::Json(json!({
        "device_id": device.device_id,
        "agent_pubkey": st.agent_key.public().to_base64(),
        "agent_name": st.hostname,
    })))
}

// ---- Devices (loopback) -----------------------------------------------------------

pub fn device_json(st: &Shared, d: &Device) -> Value {
    let fp = |k: &str| {
        PublicKey::from_base64(k)
            .map(|k| k.fingerprint())
            .unwrap_or_default()
    };
    json!({
        "device_id": d.device_id,
        "name": d.name,
        "paired_at": d.paired_at,
        "last_seen": d.last_seen,
        "online": st.events.is_online(&d.device_id),
        "request_key_fp": fp(&d.request_pubkey),
        "approval_key_fp": fp(&d.approval_pubkey),
    })
}

pub async fn devices(State(st): State<Shared>) -> axum::Json<Value> {
    let list: Vec<_> = st
        .devices
        .list()
        .iter()
        .map(|d| device_json(&st, d))
        .collect();
    axum::Json(json!(list))
}

/// Remove a device; true if the root-owned PAM trust file still lists it.
pub fn revoke(st: &Shared, id: &Uuid) -> ApiResult<bool> {
    if !st.devices.remove(id).map_err(ApiError::internal)? {
        return Err(ApiError::not_found());
    }
    log::info!("revoked device {id}");
    let ended: Vec<_> = st
        .sessions
        .active()
        .into_iter()
        .filter(|s| s.device_id == *id)
        .collect();
    for s in ended {
        st.sessions.end(&s.id);
    }
    Ok(crate::util::pam_lists_device(id))
}

pub async fn delete_device(
    State(st): State<Shared>,
    Path(id): Path<String>,
) -> ApiResult<axum::Json<Value>> {
    let id = parse_id(&id)?;
    let reenroll = revoke(&st, &id)?;
    Ok(axum::Json(json!({ "pam_reenroll_required": reenroll })))
}
