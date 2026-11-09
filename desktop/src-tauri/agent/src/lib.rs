//! fortis-desktop ↔ fortis-agent, over the loopback REST API (docs/api).
//!
//! The bearer token is read here, in Rust, from `~/.config/fortis/agent.token`
//! and never handed to the webview (security §5.2).

use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::Serialize;
use serde_json::Value;
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

/// Error shape sent to the UI.
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub struct UiError {
    pub code: String,
    pub message: String,
}

impl UiError {
    pub fn new(code: &str, message: impl Into<String>) -> Self {
        Self { code: code.into(), message: message.into() }
    }
}

impl std::fmt::Display for UiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{} ({})", self.message, self.code)
    }
}

pub type UiResult<T> = Result<T, UiError>;

#[derive(Clone, Debug)]
pub struct Paths {
    pub config_dir: PathBuf,
}

impl Paths {
    pub fn from_env() -> UiResult<Self> {
        let base = std::env::var_os("XDG_CONFIG_HOME")
            .filter(|v| !v.is_empty())
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config")))
            .ok_or_else(|| UiError::new("config", "HOME is not set"))?;
        Ok(Self { config_dir: base.join("fortis") })
    }
}

/// `rest.port` from agent.toml, default 8023.
pub fn read_port(config_dir: &Path) -> u16 {
    std::fs::read_to_string(config_dir.join("agent.toml"))
        .ok()
        .and_then(|s| s.parse::<toml::Table>().ok())
        .and_then(|t| t.get("rest")?.get("port")?.as_integer())
        .and_then(|p| u16::try_from(p).ok())
        .unwrap_or(8023)
}

pub fn read_token(config_dir: &Path) -> UiResult<Zeroizing<String>> {
    let s = std::fs::read_to_string(config_dir.join("agent.token")).map_err(|_| {
        UiError::new("agent_unreachable", "Fortis agent token not found — is fortis-agent installed and started?")
    })?;
    let t = s.trim();
    if t.len() < 32 {
        return Err(UiError::new("agent_unreachable", "agent.token is invalid"));
    }
    Ok(Zeroizing::new(t.to_string()))
}

pub struct Agent {
    http: reqwest::Client,
    paths: Paths,
}

pub enum Method {
    Get,
    Post,
    Put,
    Delete,
}

impl Agent {
    pub fn new(paths: Paths) -> Self {
        let http = reqwest::Client::builder()
            .no_proxy() // loopback only; never route through a proxy
            .connect_timeout(Duration::from_secs(2))
            .timeout(Duration::from_secs(30))
            .build()
            .expect("reqwest client");
        Self { http, paths }
    }

    fn base(&self) -> String {
        format!("http://127.0.0.1:{}/api/v1", read_port(&self.paths.config_dir))
    }

    async fn send(&self, method: &Method, path: &str, body: Option<&Value>) -> UiResult<(u16, Value)> {
        // Re-read every time so a rotated token works without restarting the app.
        let token = read_token(&self.paths.config_dir)?;
        let url = format!("{}{}", self.base(), path);
        let mut req = match method {
            Method::Get => self.http.get(&url),
            Method::Post => self.http.post(&url),
            Method::Put => self.http.put(&url),
            Method::Delete => self.http.delete(&url),
        }
        .bearer_auth(token.as_str());
        if let Some(b) = body {
            req = req.json(b);
        }
        let resp = req.send().await.map_err(|e| {
            if e.is_connect() || e.is_timeout() {
                UiError::new("agent_unreachable", "Can't reach the Fortis agent. Start it with: systemctl --user start fortis-agent")
            } else {
                UiError::new("network", e.to_string())
            }
        })?;
        let status = resp.status().as_u16();
        let text = resp.text().await.map_err(|e| UiError::new("network", e.to_string()))?;
        let v: Value = if text.is_empty() { Value::Null } else { serde_json::from_str(&text).unwrap_or(Value::String(text)) };
        Ok((status, v))
    }

    /// Call the agent; non-2xx becomes a `UiError` with the agent's code.
    pub async fn call(&self, method: Method, path: &str, body: Option<Value>) -> UiResult<Value> {
        let (status, v) = self.send(&method, path, body.as_ref()).await?;
        if (200..300).contains(&status) {
            return Ok(v);
        }
        Err(to_ui_error(status, &v))
    }

    pub async fn get(&self, path: &str) -> UiResult<Value> {
        self.call(Method::Get, path, None).await
    }
    pub async fn post(&self, path: &str, body: Value) -> UiResult<Value> {
        self.call(Method::Post, path, Some(body)).await
    }
    pub async fn put(&self, path: &str, body: Value) -> UiResult<Value> {
        self.call(Method::Put, path, Some(body)).await
    }
    pub async fn delete(&self, path: &str) -> UiResult<Value> {
        self.call(Method::Delete, path, None).await
    }
}

pub fn to_ui_error(status: u16, v: &Value) -> UiError {
    let code = v["error"]["code"].as_str().unwrap_or(match status {
        401 => "unauthorized",
        404 => "not_found",
        423 => "vault_locked",
        _ => "error",
    });
    let message = match code {
        "unauthorized" => "The agent rejected the desktop token. Restart the app; if it persists, restart fortis-agent.".to_string(),
        "invalid_password" => "Wrong master password.".to_string(),
        "rate_limited" => "Too many attempts — wait a moment and try again.".to_string(),
        "vault_locked" => "The vault is locked.".to_string(),
        _ => v["error"]["message"].as_str().unwrap_or("Request failed").to_string(),
    };
    UiError::new(code, message)
}

/// Only path-safe IDs go into URLs.
pub fn check_id(id: &str) -> UiResult<&str> {
    if id.len() == 36 && id.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-') {
        Ok(id)
    } else {
        Err(UiError::new("bad_request", "invalid entry id"))
    }
}

/// One copyable field of a full entry (as returned by `GET /vault/entries/{id}`).
pub fn entry_field(entry: &Value, field: &str) -> Option<String> {
    let et = &entry["entry_type"];
    let (kind, inner) = et.as_object()?.iter().next()?;
    let s = |k: &str| inner[k].as_str().map(String::from);
    match (kind.as_str(), field) {
        (_, "notes") => entry["notes"].as_str().map(String::from),
        ("Login", "username" | "password") => s(field),
        ("Login", "url") => inner["urls"].get(0)?.as_str().map(String::from),
        ("CreditCard", "number" | "cvv" | "cardholder_name") => s(field),
        ("SecureNote", "content") => s("content"),
        ("Identity", "email" | "phone" | "address") => s(field),
        ("Totp" | "Login", "totp_secret") => inner["totp"]["secret_base32"].as_str().map(String::from),
        _ => None,
    }
}

/// Hash used to check "is our secret still on the clipboard?" without keeping it.
pub fn clip_hash(s: &str) -> [u8; 32] {
    Sha256::digest(s.as_bytes()).into()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    fn token_dir(port: u16) -> tempfile::TempDir {
        let t = tempfile::tempdir().unwrap();
        let d = t.path().join("fortis");
        std::fs::create_dir_all(&d).unwrap();
        std::fs::write(d.join("agent.token"), "a".repeat(64) + "\n").unwrap();
        std::fs::write(d.join("agent.toml"), format!("[rest]\nport = {port}\n")).unwrap();
        t
    }

    /// Minimal HTTP server: records the request, replies with `status` + `body`.
    async fn fake(status: u16, body: &'static str) -> (u16, tokio::task::JoinHandle<String>) {
        let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = l.local_addr().unwrap().port();
        let h = tokio::spawn(async move {
            let (mut s, _) = l.accept().await.unwrap();
            let mut buf = vec![0u8; 8192];
            let n = s.read(&mut buf).await.unwrap();
            let req = String::from_utf8_lossy(&buf[..n]).to_string();
            let resp = format!("HTTP/1.1 {status} X\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}", body.len());
            s.write_all(resp.as_bytes()).await.unwrap();
            req
        });
        (port, h)
    }

    #[tokio::test]
    async fn sends_token_and_host_and_no_origin() {
        let (port, h) = fake(200, r#"{"state":"locked"}"#).await;
        let t = token_dir(port);
        let a = Agent::new(Paths { config_dir: t.path().join("fortis") });
        assert_eq!(a.get("/vault/status").await.unwrap()["state"], "locked");
        let req = h.await.unwrap().to_lowercase();
        assert!(req.starts_with("get /api/v1/vault/status "));
        assert!(req.contains(&format!("authorization: bearer {}", "a".repeat(64))));
        assert!(req.contains(&format!("host: 127.0.0.1:{port}")));
        assert!(!req.contains("origin:"));
    }

    #[tokio::test]
    async fn maps_agent_errors() {
        let (port, _h) = fake(401, r#"{"error":{"code":"invalid_password","message":"Invalid master password"}}"#).await;
        let t = token_dir(port);
        let a = Agent::new(Paths { config_dir: t.path().join("fortis") });
        let e = a.post("/vault/unlock", json!({"password":"x"})).await.unwrap_err();
        assert_eq!(e.code, "invalid_password");
        assert_eq!(e.message, "Wrong master password.");
    }

    #[tokio::test]
    async fn agent_down_and_missing_token() {
        let t = token_dir(1); // nothing listens on port 1
        let a = Agent::new(Paths { config_dir: t.path().join("fortis") });
        assert_eq!(a.get("/vault/status").await.unwrap_err().code, "agent_unreachable");
        let empty = tempfile::tempdir().unwrap();
        let a = Agent::new(Paths { config_dir: empty.path().to_path_buf() });
        assert_eq!(a.get("/vault/status").await.unwrap_err().code, "agent_unreachable");
    }

    #[test]
    fn ids_and_port() {
        assert!(check_id("550e8400-e29b-41d4-a716-446655440000").is_ok());
        assert!(check_id("../../devices").is_err());
        assert!(check_id("550e8400-e29b-41d4-a716-44665544000/").is_err());
        let t = tempfile::tempdir().unwrap();
        assert_eq!(read_port(t.path()), 8023);
        std::fs::write(t.path().join("agent.toml"), "[rest]\nport = 9000\n").unwrap();
        assert_eq!(read_port(t.path()), 9000);
    }

    #[test]
    fn fields() {
        let e = json!({"notes":"n","entry_type":{"Login":{"username":"me","password":"pw","urls":["https://x"],"totp":{"secret_base32":"JBSW"}}}});
        assert_eq!(entry_field(&e, "password").as_deref(), Some("pw"));
        assert_eq!(entry_field(&e, "url").as_deref(), Some("https://x"));
        assert_eq!(entry_field(&e, "totp_secret").as_deref(), Some("JBSW"));
        assert_eq!(entry_field(&e, "notes").as_deref(), Some("n"));
        assert_eq!(entry_field(&e, "cvv"), None);
        let c = json!({"notes":"","entry_type":{"CreditCard":{"cardholder_name":"J","number":"4111","exp_month":1,"exp_year":2030,"cvv":"123"}}});
        assert_eq!(entry_field(&c, "number").as_deref(), Some("4111"));
    }
}
