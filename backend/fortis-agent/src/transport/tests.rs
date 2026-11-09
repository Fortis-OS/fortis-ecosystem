//! Router-level tests: real HTTP requests through both listeners' auth layers.

use std::net::SocketAddr;

use axum::Router;
use axum::body::Body;
use axum::extract::ConnectInfo;
use axum::http::{Request, StatusCode, header};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use tower::ServiceExt;
use zeroize::Zeroizing;

use fortis_core::sign::{AgentKey, canonical_request};

use crate::modules::vault::start_pairing;
use crate::state::{Shared, test_support};

const PHONE_IP: &str = "100.100.1.2:50000";

struct Phone {
    id: String,
    request: AgentKey,
}

async fn send(app: &Router, req: Request<Body>) -> (StatusCode, Value) {
    let resp = app.clone().oneshot(req).await.unwrap();
    let status = resp.status();
    let bytes = resp.into_body().collect().await.unwrap().to_bytes();
    let v = serde_json::from_slice(&bytes)
        .unwrap_or(Value::String(String::from_utf8_lossy(&bytes).into()));
    (status, v)
}

fn loopback(method: &str, path: &str, body: Option<Value>) -> axum::http::request::Builder {
    let _ = body;
    Request::builder()
        .method(method)
        .uri(path)
        .header(header::HOST, "127.0.0.1:8023")
        .header(
            header::AUTHORIZATION,
            format!("Bearer {}", test_support::TOKEN),
        )
        .header(header::CONTENT_TYPE, "application/json")
}

fn json_body(v: Value) -> Body {
    Body::from(v.to_string())
}

fn tailnet_raw(method: &str, path: &str, body: Vec<u8>, ctype: &str) -> Request<Body> {
    let mut r = Request::builder()
        .method(method)
        .uri(path)
        .header(header::CONTENT_TYPE, ctype)
        .body(Body::from(body))
        .unwrap();
    r.extensions_mut()
        .insert(ConnectInfo::<SocketAddr>(PHONE_IP.parse().unwrap()));
    r
}

fn signed_at(
    phone: &Phone,
    method: &str,
    path: &str,
    body: Vec<u8>,
    ctype: &str,
    ts: i64,
    nonce: &str,
) -> Request<Body> {
    let msg = canonical_request(method, path, ts as u64, nonce, &body);
    let mut r = tailnet_raw(method, path, body, ctype);
    let h = r.headers_mut();
    h.insert("x-fortis-device", phone.id.parse().unwrap());
    h.insert("x-fortis-timestamp", ts.to_string().parse().unwrap());
    h.insert("x-fortis-nonce", nonce.parse().unwrap());
    h.insert(
        "x-fortis-signature",
        phone.request.sign_b64(&msg).parse().unwrap(),
    );
    r
}

fn signed(phone: &Phone, method: &str, path: &str, body: Option<Value>) -> Request<Body> {
    let b = body.map(|v| v.to_string().into_bytes()).unwrap_or_default();
    signed_at(
        phone,
        method,
        path,
        b,
        "application/json",
        fortis_core::unix_now(),
        &fortis_core::crypto::random_hex(16),
    )
}

async fn pair(st: &Shared, app: &Router) -> Phone {
    let (data, _rx) = start_pairing(st);
    let request = AgentKey::generate();
    let approval = AgentKey::generate();
    let (s, v) = send(
        app,
        tailnet_raw(
            "POST",
            "/api/v1/pair",
            json!({
                "code": data["code"],
                "device_name": "Pixel 8",
                "request_pubkey": request.public().to_base64(),
                "approval_pubkey": approval.public().to_base64(),
            })
            .to_string()
            .into_bytes(),
            "application/json",
        ),
    )
    .await;
    assert_eq!(s, StatusCode::OK, "{v}");
    assert_eq!(v["agent_pubkey"], st.agent_key.public().to_base64());
    Phone {
        id: v["device_id"].as_str().unwrap().into(),
        request,
    }
}

#[tokio::test]
async fn loopback_auth_rules() {
    let t = tempfile::tempdir().unwrap();
    let st = test_support::state(t.path(), |_| {});
    let app = super::rest::loopback_router(st.clone());

    let (s, v) = send(
        &app,
        loopback("GET", "/api/v1/vault/status", None)
            .body(Body::empty())
            .unwrap(),
    )
    .await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(v["state"], "uninitialised");

    // No token.
    let r = Request::get("/api/v1/vault/status")
        .header(header::HOST, "127.0.0.1:8023")
        .body(Body::empty())
        .unwrap();
    let (s, v) = send(&app, r).await;
    assert_eq!(s, StatusCode::UNAUTHORIZED);
    assert_eq!(v["error"]["code"], "unauthorized");
    // Wrong token.
    let r = Request::get("/api/v1/vault/status")
        .header(header::HOST, "127.0.0.1:8023")
        .header(header::AUTHORIZATION, "Bearer nope")
        .body(Body::empty())
        .unwrap();
    assert_eq!(send(&app, r).await.0, StatusCode::UNAUTHORIZED);
    // DNS rebinding: foreign Host.
    let r = Request::get("/api/v1/vault/status")
        .header(header::HOST, "evil.example:8023")
        .header(
            header::AUTHORIZATION,
            format!("Bearer {}", test_support::TOKEN),
        )
        .body(Body::empty())
        .unwrap();
    assert_eq!(send(&app, r).await.0, StatusCode::FORBIDDEN);
    // Web page origin.
    let r = loopback("GET", "/api/v1/vault/status", None)
        .header(header::ORIGIN, "https://evil.example")
        .body(Body::empty())
        .unwrap();
    assert_eq!(send(&app, r).await.0, StatusCode::FORBIDDEN);
    // Tauri origin is fine.
    let r = loopback("GET", "/api/v1/vault/status", None)
        .header(header::ORIGIN, "tauri://localhost")
        .body(Body::empty())
        .unwrap();
    assert_eq!(send(&app, r).await.0, StatusCode::OK);
    // No CORS headers anywhere.
    let resp = app
        .clone()
        .oneshot(
            loopback("GET", "/api/v1/vault/status", None)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert!(resp.headers().get("access-control-allow-origin").is_none());
    // Tailnet-only endpoints don't exist here.
    assert_eq!(
        send(
            &app,
            loopback("GET", "/api/v1/sync/pull", None)
                .body(Body::empty())
                .unwrap()
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        send(
            &app,
            loopback("POST", "/api/v1/pair", None)
                .body(Body::empty())
                .unwrap()
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn loopback_vault_flow() {
    let t = tempfile::tempdir().unwrap();
    let st = test_support::state(t.path(), |_| {});
    let app = super::rest::loopback_router(st.clone());
    let req = |m: &str, p: &str, b: Value| loopback(m, p, None).body(json_body(b)).unwrap();

    assert_eq!(
        send(&app, req("GET", "/api/v1/vault/entries", json!(null)))
            .await
            .1["error"]["code"],
        "vault_uninitialised"
    );
    assert_eq!(
        send(
            &app,
            req("POST", "/api/v1/vault/init", json!({"password":"short"}))
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        send(
            &app,
            req(
                "POST",
                "/api/v1/vault/init",
                json!({"password":"a long password"})
            )
        )
        .await
        .0,
        StatusCode::CREATED
    );
    let entry = json!({"title":"GitHub","entry_type":{"Login":{"username":"me","password":"hunter2","urls":[],
                       "totp":{"secret_base32":"JBSWY3DPEHPK3PXP"}}}});
    let (s, v) = send(&app, req("POST", "/api/v1/vault/entries", entry.clone())).await;
    assert_eq!(s, StatusCode::CREATED);
    let id = v["id"].as_str().unwrap().to_string();
    let (_, list) = send(&app, req("GET", "/api/v1/vault/entries?q=git", json!(null))).await;
    assert_eq!(list.as_array().unwrap().len(), 1);
    assert!(!list.to_string().contains("hunter2"));
    let (_, full) = send(
        &app,
        req("GET", &format!("/api/v1/vault/entries/{id}"), json!(null)),
    )
    .await;
    assert_eq!(full["entry_type"]["Login"]["password"], "hunter2");
    let (s, totp) = send(
        &app,
        req(
            "GET",
            &format!("/api/v1/vault/entries/{id}/totp"),
            json!(null),
        ),
    )
    .await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(totp["code"].as_str().unwrap().len(), 6);
    let bad = json!({"title":"x","entry_type":{"SecureNote":{"content":"c"}},"evil":1});
    assert_eq!(
        send(&app, req("POST", "/api/v1/vault/entries", bad))
            .await
            .0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        send(
            &app,
            req(
                "DELETE",
                &format!("/api/v1/vault/entries/{id}"),
                json!(null)
            )
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        send(
            &app,
            req("GET", &format!("/api/v1/vault/entries/{id}"), json!(null))
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        send(&app, req("POST", "/api/v1/vault/lock", json!(null)))
            .await
            .1["state"],
        "locked"
    );
    let (s, v) = send(
        &app,
        req(
            "POST",
            "/api/v1/vault/unlock",
            json!({"password":"wrong password"}),
        ),
    )
    .await;
    assert_eq!(
        (s, v["error"]["code"].as_str()),
        (StatusCode::UNAUTHORIZED, Some("invalid_password"))
    );
    assert_eq!(
        send(&app, req("GET", "/api/v1/vault/entries", json!(null)))
            .await
            .0,
        StatusCode::LOCKED
    );
}

#[tokio::test]
async fn tailnet_signed_requests() {
    let t = tempfile::tempdir().unwrap();
    let st = test_support::state(t.path(), |_| {});
    let app = super::rest::tailnet_router(st.clone());

    // LAN peer is refused outright.
    let mut r = tailnet_raw("GET", "/api/v1/vault/status", vec![], "application/json");
    r.extensions_mut().insert(ConnectInfo::<SocketAddr>(
        "192.168.1.9:4000".parse().unwrap(),
    ));
    assert_eq!(send(&app, r).await.0, StatusCode::FORBIDDEN);
    // Unsigned.
    assert_eq!(
        send(
            &app,
            tailnet_raw("GET", "/api/v1/vault/status", vec![], "application/json")
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );

    let phone = pair(&st, &app).await;
    assert_eq!(
        send(&app, signed(&phone, "GET", "/api/v1/vault/status", None))
            .await
            .0,
        StatusCode::OK
    );
    assert_eq!(st.devices.list().len(), 1);

    // Replay of the exact same request.
    let nonce = fortis_core::crypto::random_hex(16);
    let now = fortis_core::unix_now();
    let r1 = signed_at(
        &phone,
        "GET",
        "/api/v1/telemetry",
        vec![],
        "application/json",
        now,
        &nonce,
    );
    let r2 = signed_at(
        &phone,
        "GET",
        "/api/v1/telemetry",
        vec![],
        "application/json",
        now,
        &nonce,
    );
    assert_eq!(send(&app, r1).await.0, StatusCode::OK);
    assert_eq!(send(&app, r2).await.0, StatusCode::UNAUTHORIZED);
    // Stale timestamp.
    let r = signed_at(
        &phone,
        "GET",
        "/api/v1/vault/status",
        vec![],
        "application/json",
        now - 120,
        &fortis_core::crypto::random_hex(16),
    );
    assert_eq!(send(&app, r).await.0, StatusCode::UNAUTHORIZED);
    // Signed by some other key.
    let imposter = Phone {
        id: phone.id.clone(),
        request: AgentKey::generate(),
    };
    assert_eq!(
        send(&app, signed(&imposter, "GET", "/api/v1/vault/status", None))
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
    // Body tampering: signature covers the body hash.
    let mut r = signed(
        &phone,
        "POST",
        "/api/v1/clipboard",
        Some(json!({"text":"a"})),
    );
    *r.body_mut() = Body::from(json!({"text":"b"}).to_string());
    assert_eq!(send(&app, r).await.0, StatusCode::UNAUTHORIZED);
    // Loopback-only endpoints don't exist on the tailnet.
    assert_eq!(
        send(&app, signed(&phone, "GET", "/api/v1/vault/entries", None))
            .await
            .0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        send(
            &app,
            signed(
                &phone,
                "POST",
                "/api/v1/vault/unlock",
                Some(json!({"password":"x"}))
            )
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
    // No shell endpoint.
    assert_eq!(
        send(
            &app,
            signed(&phone, "POST", "/api/v1/terminal", Some(json!({})))
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
    // Remote input is off by default.
    let (s, v) = send(
        &app,
        signed(
            &phone,
            "POST",
            "/api/v1/sessions",
            Some(json!({"kind":"input"})),
        ),
    )
    .await;
    assert_eq!(
        (s, v["error"]["code"].as_str()),
        (StatusCode::FORBIDDEN, Some("forbidden"))
    );

    // Revoked device is locked out.
    crate::modules::vault::revoke(&st, &phone.id.parse().unwrap()).unwrap();
    assert_eq!(
        send(&app, signed(&phone, "GET", "/api/v1/vault/status", None))
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
}

#[tokio::test]
async fn pairing_rules() {
    let t = tempfile::tempdir().unwrap();
    let st = test_support::state(t.path(), |_| {});
    let app = super::rest::tailnet_router(st.clone());
    let k1 = AgentKey::generate().public().to_base64();
    let k2 = AgentKey::generate().public().to_base64();
    let body = |code: &str| {
        json!({"code": code, "device_name": "P", "request_pubkey": k1, "approval_pubkey": k2})
            .to_string()
            .into_bytes()
    };

    // No session open.
    assert_eq!(
        send(
            &app,
            tailnet_raw(
                "POST",
                "/api/v1/pair",
                body("AAAA-AAAA-AA"),
                "application/json"
            )
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );
    let (data, _rx) = start_pairing(&st);
    for _ in 0..4 {
        assert_eq!(
            send(
                &app,
                tailnet_raw(
                    "POST",
                    "/api/v1/pair",
                    body("WRONG-CODE"),
                    "application/json"
                )
            )
            .await
            .0,
            StatusCode::UNAUTHORIZED
        );
    }
    assert_eq!(
        send(
            &app,
            tailnet_raw(
                "POST",
                "/api/v1/pair",
                body("WRONG-CODE"),
                "application/json"
            )
        )
        .await
        .0,
        StatusCode::TOO_MANY_REQUESTS
    );
    // Session is gone even with the right code now.
    let code = data["code"].as_str().unwrap();
    assert_eq!(
        send(
            &app,
            tailnet_raw("POST", "/api/v1/pair", body(code), "application/json")
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );
    assert!(st.devices.list().is_empty());
}

#[tokio::test]
async fn phone_sync_and_upload() {
    let t = tempfile::tempdir().unwrap();
    let st = test_support::state(t.path(), |_| {});
    let app = super::rest::tailnet_router(st.clone());
    let phone = pair(&st, &app).await;

    assert_eq!(
        send(&app, signed(&phone, "GET", "/api/v1/sync/bootstrap", None))
            .await
            .1["error"]["code"],
        "vault_uninitialised"
    );
    st.init(Zeroizing::new("a long password".into()))
        .await
        .unwrap();
    let (s, b) = send(&app, signed(&phone, "GET", "/api/v1/sync/bootstrap", None)).await;
    assert_eq!(s, StatusCode::OK);
    assert_eq!(b["meta"]["kdf"]["alg"], "argon2id");
    assert!(b["wrapped_vault_key"].as_str().unwrap().len() > 40);
    let (s, p) = send(
        &app,
        signed(&phone, "GET", "/api/v1/sync/pull?since=0", None),
    )
    .await;
    assert_eq!(
        (s, p["rows"].as_array().unwrap().len()),
        (StatusCode::OK, 0)
    );

    // Multipart upload with a hostile filename.
    let boundary = "XBOUNDARYX";
    let mut body = Vec::new();
    body.extend_from_slice(format!("--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"../../.bashrc\"\r\nContent-Type: text/plain\r\n\r\nhello\r\n--{boundary}--\r\n").as_bytes());
    let r = signed_at(
        &phone,
        "POST",
        "/api/v1/files/drop?dest=drop",
        body,
        &format!("multipart/form-data; boundary={boundary}"),
        fortis_core::unix_now(),
        &fortis_core::crypto::random_hex(16),
    );
    let (s, v) = send(&app, r).await;
    assert_eq!(s, StatusCode::OK, "{v}");
    assert_eq!(v["saved_as"], "bashrc");
    assert_eq!(
        std::fs::read_to_string(st.paths.drop_dir().join("bashrc")).unwrap(),
        "hello"
    );
}
