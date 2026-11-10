//! Per-listener authentication (security §5.1–§5.3).
//!
//! - Loopback: `Host` allow-list, `Origin` allow-list, bearer token (constant time).
//! - Tailnet: peer must be in 100.64.0.0/10; every request except `/pair`
//!   carries a P-256 signature from a paired device, a fresh timestamp and an
//!   unused nonce.

use std::net::{IpAddr, SocketAddr};

use axum::body::{Body, to_bytes};
use axum::extract::{ConnectInfo, Request, State};
use axum::http::header;
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};

use fortis_core::crypto::ct_eq;
use fortis_core::sign::canonical_request;

use crate::devices::Device;
use crate::error::ApiError;
use crate::state::Shared;

pub const JSON_LIMIT: usize = 1024 * 1024;
pub const MAX_CLOCK_SKEW_SECS: i64 = 60;
const ALLOWED_ORIGINS: [&str; 2] = ["tauri://localhost", "http://tauri.localhost"];

/// The paired phone that signed this request.
#[derive(Clone, Debug)]
pub struct AuthedDevice(pub Device);

/// Tailscale CGNAT range 100.64.0.0/10.
pub fn is_tailnet(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let o = v4.octets();
            o[0] == 100 && (o[1] & 0xC0) == 64
        }
        IpAddr::V6(v6) => v6
            .to_ipv4_mapped()
            .is_some_and(|v4| is_tailnet(IpAddr::V4(v4))),
    }
}

pub async fn loopback_auth(State(st): State<Shared>, req: Request, next: Next) -> Response {
    let port = st.config.rest.port;
    let host_ok = req
        .headers()
        .get(header::HOST)
        .and_then(|h| h.to_str().ok())
        .is_some_and(|h| h == format!("127.0.0.1:{port}") || h == format!("localhost:{port}"));
    if !host_ok {
        return ApiError::forbidden("bad Host header").into_response();
    }
    if let Some(origin) = req.headers().get(header::ORIGIN)
        && !origin.to_str().is_ok_and(|o| ALLOWED_ORIGINS.contains(&o))
    {
        return ApiError::forbidden("bad Origin header").into_response();
    }
    let token_ok = req
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|h| h.to_str().ok())
        .and_then(|h| h.strip_prefix("Bearer "))
        .is_some_and(|t| ct_eq(t.trim().as_bytes(), st.token.as_bytes()));
    if !token_ok {
        return ApiError::unauthorized().into_response();
    }
    next.run(req).await
}

fn header_str<'a>(req: &'a axum::http::request::Parts, name: &str) -> Option<&'a str> {
    req.headers.get(name).and_then(|v| v.to_str().ok())
}

fn is_hex(s: &str, len: usize) -> bool {
    s.len() == len && s.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

pub async fn tailnet_auth(State(st): State<Shared>, req: Request, next: Next) -> Response {
    let peer_ok = req
        .extensions()
        .get::<ConnectInfo<SocketAddr>>()
        .is_some_and(|ConnectInfo(a)| is_tailnet(a.ip()));
    if !peer_ok {
        return ApiError::forbidden("not a tailnet peer").into_response();
    }
    // The only unauthenticated endpoint; the handler checks the pairing code.
    if req.uri().path() == "/api/v1/pair" {
        return next.run(req).await;
    }

    let limit = if req.uri().path() == "/api/v1/files/drop" {
        st.config.max_upload_bytes() + JSON_LIMIT
    } else {
        JSON_LIMIT
    };
    let (mut parts, body) = req.into_parts();
    let unauthorized = || ApiError::unauthorized().into_response();

    let Some(device_id) =
        header_str(&parts, "x-fortis-device").and_then(|s| uuid::Uuid::parse_str(s).ok())
    else {
        return unauthorized();
    };
    let Some(device) = st.devices.get(&device_id) else {
        log::warn!("rejected request from unknown device {device_id}");
        return unauthorized();
    };
    let Some(ts) = header_str(&parts, "x-fortis-timestamp").and_then(|s| s.parse::<i64>().ok())
    else {
        return unauthorized();
    };
    let now = fortis_core::unix_now();
    if (now - ts).abs() > MAX_CLOCK_SKEW_SECS {
        log::warn!("rejected request from {device_id}: stale timestamp");
        return unauthorized();
    }
    let Some(nonce) = header_str(&parts, "x-fortis-nonce")
        .filter(|n| is_hex(n, 32))
        .map(str::to_owned)
    else {
        return unauthorized();
    };
    let Some(sig) = header_str(&parts, "x-fortis-signature").map(str::to_owned) else {
        return unauthorized();
    };
    let bytes = match to_bytes(body, limit).await {
        Ok(b) => b,
        Err(_) => return ApiError::too_large().into_response(),
    };
    let path_q = parts
        .uri
        .path_and_query()
        .map(|p| p.as_str())
        .unwrap_or("/");
    let msg = canonical_request(parts.method.as_str(), path_q, ts as u64, &nonce, &bytes);
    let Some(key) = device.request_key() else {
        return unauthorized();
    };
    if !key.verify_b64(&msg, &sig) {
        log::warn!("rejected request from {device_id}: bad signature");
        return unauthorized();
    }
    // Only consume the nonce once the signature is known to be good.
    if !st.check_nonce(device_id, &nonce) {
        log::warn!("rejected request from {device_id}: replayed nonce");
        return unauthorized();
    }
    st.devices.touch(&device_id, now);
    parts.extensions.insert(AuthedDevice(device));
    next.run(Request::from_parts(parts, Body::from(bytes)))
        .await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cgnat_range() {
        assert!(is_tailnet("100.64.0.1".parse().unwrap()));
        assert!(is_tailnet("100.127.255.254".parse().unwrap()));
        assert!(!is_tailnet("100.128.0.1".parse().unwrap()));
        assert!(!is_tailnet("100.63.0.1".parse().unwrap()));
        assert!(!is_tailnet("192.168.1.5".parse().unwrap()));
        assert!(!is_tailnet("127.0.0.1".parse().unwrap()));
        assert!(is_tailnet("::ffff:100.100.1.1".parse().unwrap()));
    }
}
