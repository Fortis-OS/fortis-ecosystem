//! JSON-lines client for the agent socket (docs/api §6).

use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::time::Duration;

use serde_json::{Value, json};

#[derive(Debug)]
pub enum IpcError {
    /// Agent not running / socket missing.
    NotRunning(PathBuf, std::io::Error),
    Io(std::io::Error),
    /// The agent answered with an error.
    Agent {
        code: String,
        message: String,
    },
    Protocol(String),
}

impl std::fmt::Display for IpcError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            IpcError::NotRunning(p, e) => write!(
                f,
                "cannot reach fortis-agent at {} ({e}).\n  Is it running?  systemctl --user status fortis-agent",
                p.display()
            ),
            IpcError::Io(e) => write!(f, "agent connection failed: {e}"),
            IpcError::Agent { code, message } => match code.as_str() {
                "vault_locked" => write!(f, "the vault is locked — run `fortis unlock`"),
                "vault_uninitialised" => write!(f, "no vault yet — run `fortis init`"),
                "invalid_password" => write!(f, "wrong master password"),
                _ => write!(f, "{message} ({code})"),
            },
            IpcError::Protocol(m) => write!(f, "unexpected reply from agent: {m}"),
        }
    }
}

impl std::error::Error for IpcError {}

impl From<std::io::Error> for IpcError {
    fn from(e: std::io::Error) -> Self {
        IpcError::Io(e)
    }
}

/// `$XDG_RUNTIME_DIR/fortis/agent.sock`, or `/run/user/<uid>/…`.
/// Under `sudo`, the invoking user's socket (`SUDO_UID`).
pub fn socket_path() -> PathBuf {
    socket_path_from(
        std::env::var("XDG_RUNTIME_DIR").ok(),
        std::env::var("SUDO_UID").ok(),
        unsafe { libc::geteuid() },
        unsafe { libc::getuid() },
    )
}

pub fn socket_path_from(
    xdg: Option<String>,
    sudo_uid: Option<String>,
    euid: u32,
    uid: u32,
) -> PathBuf {
    if euid == 0
        && let Some(u) = sudo_uid.as_deref().and_then(|s| s.parse::<u32>().ok())
    {
        return PathBuf::from(format!("/run/user/{u}/fortis/agent.sock"));
    }
    match xdg.filter(|s| !s.is_empty()) {
        Some(d) => PathBuf::from(d).join("fortis/agent.sock"),
        None => PathBuf::from(format!("/run/user/{uid}/fortis/agent.sock")),
    }
}

pub struct Client {
    reader: BufReader<UnixStream>,
    writer: UnixStream,
    next_id: u64,
}

impl Client {
    pub fn connect() -> Result<Self, IpcError> {
        let path = socket_path();
        let s = UnixStream::connect(&path).map_err(|e| IpcError::NotRunning(path.clone(), e))?;
        // Argon2 unlock takes ~1 s; pairing waits up to 120 s on its own call.
        s.set_read_timeout(Some(Duration::from_secs(30)))?;
        Ok(Self {
            reader: BufReader::new(s.try_clone()?),
            writer: s,
            next_id: 1,
        })
    }

    fn send(&mut self, cmd: &str, args: Value) -> Result<u64, IpcError> {
        let id = self.next_id;
        self.next_id += 1;
        let line = json!({ "id": id, "cmd": cmd, "args": args }).to_string();
        self.writer.write_all(line.as_bytes())?;
        self.writer.write_all(b"\n")?;
        Ok(id)
    }

    /// Read the next line addressed to `id`.
    pub fn read_reply(&mut self, id: u64) -> Result<Value, IpcError> {
        loop {
            let mut line = String::new();
            if self.reader.read_line(&mut line)? == 0 {
                return Err(IpcError::Protocol("agent closed the connection".into()));
            }
            let v: Value =
                serde_json::from_str(&line).map_err(|e| IpcError::Protocol(e.to_string()))?;
            if v["id"] != json!(id) {
                continue;
            }
            if v["ok"] == json!(true) {
                return Ok(v["data"].clone());
            }
            return Err(IpcError::Agent {
                code: v["error"]["code"].as_str().unwrap_or("unknown").into(),
                message: v["error"]["message"].as_str().unwrap_or("").into(),
            });
        }
    }

    pub fn call(&mut self, cmd: &str, args: Value) -> Result<Value, IpcError> {
        let id = self.send(cmd, args)?;
        self.read_reply(id)
    }

    /// Send and return the id, for commands that answer more than once.
    pub fn call_streaming(&mut self, cmd: &str, args: Value) -> Result<(u64, Value), IpcError> {
        let id = self.send(cmd, args)?;
        let first = self.read_reply(id)?;
        Ok((id, first))
    }

    pub fn set_timeout(&self, d: Duration) -> Result<(), IpcError> {
        self.writer.set_read_timeout(Some(d))?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn socket_paths() {
        assert_eq!(
            socket_path_from(Some("/run/user/1000".into()), None, 1000, 1000),
            PathBuf::from("/run/user/1000/fortis/agent.sock")
        );
        assert_eq!(
            socket_path_from(None, None, 1000, 1000),
            PathBuf::from("/run/user/1000/fortis/agent.sock")
        );
        // sudo: the invoking user's agent, not root's.
        assert_eq!(
            socket_path_from(Some("/run/user/0".into()), Some("1000".into()), 0, 0),
            PathBuf::from("/run/user/1000/fortis/agent.sock")
        );
        // SUDO_UID is ignored for non-root (can't be used to point elsewhere).
        assert_eq!(
            socket_path_from(
                Some("/run/user/1000".into()),
                Some("1001".into()),
                1000,
                1000
            ),
            PathBuf::from("/run/user/1000/fortis/agent.sock")
        );
    }

    #[test]
    fn talks_json_lines() {
        let t = tempfile::tempdir().unwrap();
        let p = t.path().join("s.sock");
        let l = std::os::unix::net::UnixListener::bind(&p).unwrap();
        std::thread::spawn(move || {
            let (s, _) = l.accept().unwrap();
            let mut r = BufReader::new(s.try_clone().unwrap());
            let mut w = s;
            let mut line = String::new();
            r.read_line(&mut line).unwrap();
            let req: Value = serde_json::from_str(&line).unwrap();
            assert_eq!(req["cmd"], "status");
            // An unrelated line first, then the answer.
            writeln!(w, "{}", json!({"id": 99, "ok": true, "data": {}})).unwrap();
            writeln!(w, "{}", json!({"id": req["id"], "ok": false, "error": {"code":"vault_locked","message":"Vault is locked"}})).unwrap();
        });
        let s = UnixStream::connect(&p).unwrap();
        let mut c = Client {
            reader: BufReader::new(s.try_clone().unwrap()),
            writer: s,
            next_id: 1,
        };
        let e = c.call("status", json!({})).unwrap_err();
        assert!(e.to_string().contains("fortis unlock"));
    }
}
