//! fortis-browser-host — the browser starts this for the Fortis extension
//! (native messaging) and it forwards a fixed set of messages to the agent's
//! IPC socket (docs/api §6, docs/security §7.7).
//!
//! Framing (browser ↔ host): 4-byte little-endian length + UTF-8 JSON.
//! Framing (host ↔ agent): one JSON object per line.

use serde_json::{Value, json};
use std::io::{self, BufRead, BufReader, Read, Write};
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::time::Duration;

const MAX_FRAME: usize = 64 * 1024;

/// Extension command → agent IPC command. Anything else is refused.
fn ipc_command(cmd: &str) -> Option<&'static str> {
    Some(match cmd {
        "status" => "status",
        "unlock" => "unlock",
        "lock" => "lock",
        "match" => "browser_match",
        "fill" => "browser_fill",
        "totp" => "browser_totp",
        "save_check" => "browser_save_check",
        "save" => "browser_save",
        _ => return None,
    })
}

fn socket_path() -> PathBuf {
    let dir = std::env::var_os("XDG_RUNTIME_DIR")
        .filter(|d| !d.is_empty())
        .map(PathBuf::from)
        // SAFETY: getuid has no preconditions.
        .unwrap_or_else(|| PathBuf::from(format!("/run/user/{}", unsafe { libc_getuid() })));
    dir.join("fortis").join("agent.sock")
}

unsafe extern "C" {
    #[link_name = "getuid"]
    fn libc_getuid() -> u32;
}

fn error(id: &Value, code: &str, message: &str) -> Value {
    json!({ "id": id, "ok": false, "error": { "code": code, "message": message } })
}

/// Handle one request from the extension.
fn handle(req: &Value, sock: &PathBuf) -> Value {
    let id = req.get("id").cloned().unwrap_or(Value::Null);
    let Some(cmd) = req.get("cmd").and_then(Value::as_str).and_then(ipc_command) else {
        return error(&id, "bad_request", "unknown command");
    };
    let args = req.get("args").cloned().unwrap_or_else(|| json!({}));
    if !args.is_object() {
        return error(&id, "bad_request", "args must be an object");
    }
    match call_agent(sock, cmd, args) {
        Ok(mut resp) => {
            resp["id"] = id; // the extension's id, not ours
            resp
        }
        Err(_) => error(&id, "unavailable", "The Fortis agent isn't running"),
    }
}

fn call_agent(sock: &PathBuf, cmd: &str, args: Value) -> io::Result<Value> {
    let mut s = UnixStream::connect(sock)?;
    s.set_read_timeout(Some(Duration::from_secs(10)))?;
    s.set_write_timeout(Some(Duration::from_secs(5)))?;
    let line = json!({ "id": 1, "cmd": cmd, "args": args }).to_string();
    s.write_all(line.as_bytes())?;
    s.write_all(b"\n")?;
    let mut r = BufReader::new(s).take(MAX_FRAME as u64);
    let mut out = String::new();
    r.read_line(&mut out)?;
    serde_json::from_str(&out).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
}

fn read_frame(r: &mut impl Read) -> io::Result<Option<Vec<u8>>> {
    let mut len = [0u8; 4];
    match r.read_exact(&mut len) {
        Ok(()) => {}
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let n = u32::from_le_bytes(len) as usize;
    if n > MAX_FRAME {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "frame too large"));
    }
    let mut buf = vec![0u8; n];
    r.read_exact(&mut buf)?;
    Ok(Some(buf))
}

fn write_frame(w: &mut impl Write, v: &Value) -> io::Result<()> {
    let b = v.to_string();
    w.write_all(&(b.len() as u32).to_le_bytes())?;
    w.write_all(b.as_bytes())?;
    w.flush()
}

fn run(input: &mut impl Read, output: &mut impl Write, sock: &PathBuf) -> io::Result<()> {
    while let Some(frame) = read_frame(input)? {
        let resp = match serde_json::from_slice::<Value>(&frame) {
            Ok(req) => handle(&req, sock),
            Err(_) => error(&Value::Null, "bad_request", "invalid JSON"),
        };
        write_frame(output, &resp)?;
    }
    Ok(())
}

fn main() {
    // Browsers pass the extension origin / manifest path as arguments; the
    // browser itself enforces allowed_origins / allowed_extensions.
    let sock = socket_path();
    let (mut i, mut o) = (io::stdin().lock(), io::stdout().lock());
    if run(&mut i, &mut o, &sock).is_err() {
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::net::UnixListener;

    fn frame(v: &Value) -> Vec<u8> {
        let b = v.to_string();
        let mut out = (b.len() as u32).to_le_bytes().to_vec();
        out.extend_from_slice(b.as_bytes());
        out
    }

    fn frames(mut b: &[u8]) -> Vec<Value> {
        let mut out = vec![];
        while let Some(f) = read_frame(&mut b).unwrap() {
            out.push(serde_json::from_slice(&f).unwrap());
        }
        out
    }

    /// A fake agent that echoes the command it received.
    fn fake_agent(dir: &std::path::Path) -> PathBuf {
        let p = dir.join("agent.sock");
        let l = UnixListener::bind(&p).unwrap();
        std::thread::spawn(move || {
            for s in l.incoming().flatten() {
                let mut line = String::new();
                BufReader::new(&s).read_line(&mut line).unwrap();
                let req: Value = serde_json::from_str(&line).unwrap();
                let resp = json!({"id": req["id"], "ok": true, "data": {"cmd": req["cmd"], "args": req["args"]}});
                (&s).write_all(format!("{resp}\n").as_bytes()).unwrap();
            }
        });
        p
    }

    #[test]
    fn maps_allowed_commands_and_keeps_extension_ids() {
        let d = std::env::temp_dir().join(format!("fbh-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        let sock = fake_agent(&d);
        let mut input = frame(&json!({"id": 7, "cmd": "match", "args": {"url": "https://github.com/"}}));
        input.extend(frame(&json!({"id": 8, "cmd": "fill", "args": {"id": "x", "url": "https://github.com/"}})));
        input.extend(frame(&json!({"id": 9, "cmd": "get", "args": {"title": "Bank"}})));
        input.extend(frame(&json!({"id": 10, "cmd": "devices_revoke", "args": {}})));
        input.extend(frame(&json!({"id": 11, "cmd": "status", "args": []})));
        let mut out = vec![];
        run(&mut input.as_slice(), &mut out, &sock).unwrap();
        let r = frames(&out);
        assert_eq!(r[0]["id"], 7);
        assert_eq!(r[0]["data"]["cmd"], "browser_match");
        assert_eq!(r[1]["data"]["cmd"], "browser_fill");
        for (i, x) in r[2..].iter().enumerate() {
            assert_eq!(x["ok"], false, "#{i}");
            assert_eq!(x["error"]["code"], "bad_request");
        }
        std::fs::remove_dir_all(&d).ok();
    }

    #[test]
    fn agent_down_and_bad_frames() {
        let sock = PathBuf::from("/nonexistent/fortis/agent.sock");
        let mut out = vec![];
        run(&mut frame(&json!({"id": 1, "cmd": "status"})).as_slice(), &mut out, &sock).unwrap();
        assert_eq!(frames(&out)[0]["error"]["code"], "unavailable");

        let mut big = ((MAX_FRAME + 1) as u32).to_le_bytes().to_vec();
        big.extend(vec![b' '; 16]);
        assert!(run(&mut big.as_slice(), &mut vec![], &sock).is_err(), "oversized frame is refused");

        let mut out = vec![];
        let junk = { let mut v = 3u32.to_le_bytes().to_vec(); v.extend(b"{x}"); v };
        run(&mut junk.as_slice(), &mut out, &sock).unwrap();
        assert_eq!(frames(&out)[0]["error"]["code"], "bad_request");
    }
}
