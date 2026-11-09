//! Safe, testable core of pam_fortis (security §4.3–§4.7). No FFI here.

use std::io::{Read, Write};
use std::os::unix::fs::MetadataExt;
use std::os::unix::net::UnixStream;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use fortis_core::sign::{ApprovalRequest, Decision, PublicKey, canonical_approval};
use serde::Deserialize;
use serde_json::{Value, json};

pub const MAX_FILE: u64 = 64 * 1024;
pub const MAX_REPLY: usize = 64 * 1024;
pub const DEADLINE_SLACK: Duration = Duration::from_secs(2);

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Config {
    pub timeout: Duration,
    pub debug: bool,
    pub services: Vec<String>,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            timeout: Duration::from_secs(30),
            debug: false,
            services: vec!["sudo".into(), "sudo-i".into()],
        }
    }
}

/// Module arguments from the PAM line. Unknown or invalid ones are ignored
/// (never a reason to block authentication).
pub fn parse_args<S: AsRef<str>>(args: &[S]) -> Config {
    let mut c = Config::default();
    for a in args {
        let a = a.as_ref();
        if a == "debug" {
            c.debug = true;
        } else if let Some(v) = a.strip_prefix("timeout=") {
            if let Ok(n) = v.parse::<u64>() {
                c.timeout = Duration::from_secs(n.clamp(1, 60));
            }
        } else if let Some(v) = a.strip_prefix("services=") {
            let s: Vec<String> = v
                .split(',')
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(String::from)
                .collect();
            if !s.is_empty() {
                c.services = s;
            }
        }
    }
    c
}

/// Where things live; fixed in production, injectable in tests.
#[derive(Clone, Debug)]
pub struct Sys {
    pub trust_dir: PathBuf,
    /// Directories above `trust_dir` that must also be safe (e.g. /etc/fortis, /etc).
    pub trust_parents: Vec<PathBuf>,
    pub owner_uid: u32,
    pub run_user_dir: PathBuf,
}

impl Sys {
    pub fn production() -> Self {
        Self {
            trust_dir: "/etc/fortis/pam".into(),
            trust_parents: vec!["/etc/fortis".into(), "/etc".into()],
            owner_uid: 0,
            run_user_dir: "/run/user".into(),
        }
    }

    pub fn socket(&self, uid: u32) -> PathBuf {
        self.run_user_dir
            .join(uid.to_string())
            .join("fortis/agent.sock")
    }
}

#[derive(Clone, Debug)]
pub struct Context {
    pub user: String,
    pub uid: u32,
    pub service: String,
    pub tty: String,
    pub host: String,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Outcome {
    Approved {
        device_id: String,
    },
    /// Fall through to the next module. The reason only goes to the debug log.
    Ignore(String),
}

fn ignore(r: impl Into<String>) -> Outcome {
    Outcome::Ignore(r.into())
}

pub fn valid_username(u: &str) -> bool {
    !u.is_empty()
        && u.len() <= 32
        && u.bytes()
            .next()
            .is_some_and(|b| b.is_ascii_lowercase() || b == b'_')
        && u.bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_' || b == b'-')
}

/// Owned by `owner`, not group/world writable, and not a symlink.
fn safe_path(p: &Path, owner: u32, want_dir: bool) -> Result<(), String> {
    let m = std::fs::symlink_metadata(p).map_err(|e| format!("{}: {e}", p.display()))?;
    if m.file_type().is_symlink() {
        return Err(format!("{} is a symlink", p.display()));
    }
    if want_dir != m.is_dir() || (!want_dir && !m.is_file()) {
        return Err(format!("{} has the wrong type", p.display()));
    }
    if m.uid() != owner {
        return Err(format!("{} is not owned by uid {owner}", p.display()));
    }
    if m.mode() & 0o022 != 0 {
        return Err(format!("{} is group/world writable", p.display()));
    }
    Ok(())
}

#[derive(Debug, Deserialize)]
struct TrustDevice {
    device_id: String,
    #[allow(dead_code)]
    name: String,
    approval_pubkey: String,
    #[allow(dead_code)]
    enrolled_at: i64,
}

#[derive(Debug, Deserialize)]
struct TrustFile {
    version: u32,
    devices: Vec<TrustDevice>,
}

/// Load `/etc/fortis/pam/<user>.json` after checking the whole chain (security §4.2).
fn load_trust(sys: &Sys, user: &str) -> Result<Vec<(String, PublicKey)>, String> {
    for d in &sys.trust_parents {
        safe_path(d, sys.owner_uid, true)?;
    }
    safe_path(&sys.trust_dir, sys.owner_uid, true)?;
    let file = sys.trust_dir.join(format!("{user}.json"));
    safe_path(&file, sys.owner_uid, false)?;
    let mut s = String::new();
    std::fs::File::open(&file)
        .map_err(|e| e.to_string())?
        .take(MAX_FILE)
        .read_to_string(&mut s)
        .map_err(|e| e.to_string())?;
    let t: TrustFile = serde_json::from_str(&s).map_err(|e| format!("trust file: {e}"))?;
    if t.version != 1 {
        return Err("unsupported trust file version".into());
    }
    Ok(t.devices
        .into_iter()
        .filter_map(|d| {
            PublicKey::from_base64(&d.approval_pubkey)
                .ok()
                .map(|k| (d.device_id, k))
        })
        .collect())
}

fn clean(s: &str) -> String {
    s.chars().filter(|c| !c.is_control()).take(256).collect()
}

fn remaining(deadline: Instant) -> Option<Duration> {
    deadline
        .checked_duration_since(Instant::now())
        .filter(|d| !d.is_zero())
}

/// Read one `\n`-terminated line before the deadline.
fn read_line(s: &mut UnixStream, deadline: Instant) -> Result<Vec<u8>, String> {
    let mut buf = Vec::with_capacity(512);
    let mut chunk = [0u8; 1024];
    loop {
        let left = remaining(deadline).ok_or("deadline reached")?;
        s.set_read_timeout(Some(left)).map_err(|e| e.to_string())?;
        match s.read(&mut chunk) {
            Ok(0) => return Err("agent closed the connection".into()),
            Ok(n) => {
                buf.extend_from_slice(&chunk[..n]);
                if let Some(i) = buf.iter().position(|&b| b == b'\n') {
                    buf.truncate(i);
                    return Ok(buf);
                }
                if buf.len() > MAX_REPLY {
                    return Err("reply too large".into());
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(format!("read: {e}")),
        }
    }
}

/// The whole decision. Returns `Approved` only for a valid signature by an
/// enrolled device over the challenge created here.
pub fn authenticate(cfg: &Config, ctx: &Context, sys: &Sys, info: &mut dyn FnMut(&str)) -> Outcome {
    let deadline = Instant::now() + cfg.timeout + DEADLINE_SLACK;

    if !cfg.services.iter().any(|s| s == &ctx.service) {
        return ignore(format!("service {} not enabled", ctx.service));
    }
    if !valid_username(&ctx.user) {
        return ignore("invalid user name");
    }
    let trusted = match load_trust(sys, &ctx.user) {
        Ok(t) if !t.is_empty() => t,
        Ok(_) => return ignore("no enrolled devices"),
        Err(e) => return ignore(format!("trust file rejected: {e}")),
    };

    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let tty = if ctx.tty.is_empty() {
        "unknown".to_string()
    } else {
        clean(&ctx.tty)
    };
    let req = ApprovalRequest::new(
        &ctx.user,
        &clean(&ctx.host),
        &clean(&ctx.service),
        &tty,
        now,
    );
    if let Err(e) = req.validate() {
        return ignore(format!("challenge invalid: {e}"));
    }

    let mut sock = match UnixStream::connect(sys.socket(ctx.uid)) {
        Ok(s) => s,
        Err(e) => return ignore(format!("agent not reachable: {e}")),
    };
    let line = json!({ "id": 1, "cmd": "auth_request", "args": req }).to_string() + "\n";
    let write_timeout = remaining(deadline).map(|d| d.min(Duration::from_secs(1)));
    if sock.set_write_timeout(write_timeout).is_err() || sock.write_all(line.as_bytes()).is_err() {
        return ignore("could not send challenge");
    }
    info(&format!(
        "Fortis: approve on your phone within {} s (deny or wait to use your password).",
        cfg.timeout.as_secs()
    ));

    let reply = match read_line(&mut sock, deadline) {
        Ok(r) => r,
        Err(e) => return ignore(e),
    };
    let v: Value = match serde_json::from_slice(&reply) {
        Ok(v) => v,
        Err(_) => return ignore("unparseable reply"),
    };
    if v["id"] != json!(1) || v["ok"] != json!(true) {
        return ignore(format!(
            "agent: {}",
            v["error"]["message"].as_str().unwrap_or("error")
        ));
    }
    let d = &v["data"];
    if d["request_id"].as_str() != Some(req.request_id.as_str()) {
        return ignore("reply for another request");
    }
    if d["decision"].as_str() != Some("approve") {
        return ignore("denied on the phone");
    }
    let Some(device_id) = d["device_id"].as_str() else {
        return ignore("no device id");
    };
    let Some(sig) = d["signature"].as_str() else {
        return ignore("no signature");
    };
    let Some((_, key)) = trusted.iter().find(|(id, _)| id == device_id) else {
        return ignore("device not enrolled");
    };
    let Ok(msg) = canonical_approval(&req, Decision::Approve) else {
        return ignore("challenge invalid");
    };
    if !key.verify_b64(&msg, sig) {
        return ignore("bad signature");
    }
    if Instant::now() > deadline {
        return ignore("deadline reached");
    }
    Outcome::Approved {
        device_id: device_id.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use fortis_core::sign::AgentKey;
    use std::io::BufRead;
    use std::os::unix::fs::PermissionsExt;
    use std::os::unix::net::UnixListener;

    struct Env {
        _t: tempfile::TempDir,
        sys: Sys,
        ctx: Context,
        phone: AgentKey,
        device_id: String,
    }

    fn me() -> u32 {
        unsafe { libc::getuid() }
    }

    fn env() -> Env {
        let t = tempfile::tempdir().unwrap();
        let etc = t.path().join("etc");
        let fortis = etc.join("fortis");
        let pam = fortis.join("pam");
        std::fs::create_dir_all(&pam).unwrap();
        for d in [&etc, &fortis, &pam] {
            std::fs::set_permissions(d, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        let run = t.path().join("run");
        std::fs::create_dir_all(run.join(me().to_string()).join("fortis")).unwrap();
        let phone = AgentKey::generate();
        let device_id = "0b7c2f4e-0000-4000-8000-000000000001".to_string();
        let f = json!({"version":1,"devices":[{"device_id":device_id,"name":"P","approval_pubkey":phone.public().to_base64(),"enrolled_at":1}]});
        let file = pam.join("tester.json");
        std::fs::write(&file, f.to_string()).unwrap();
        std::fs::set_permissions(&file, std::fs::Permissions::from_mode(0o644)).unwrap();
        Env {
            sys: Sys {
                trust_dir: pam,
                trust_parents: vec![fortis, etc],
                owner_uid: me(),
                run_user_dir: run,
            },
            ctx: Context {
                user: "tester".into(),
                uid: me(),
                service: "sudo".into(),
                tty: "/dev/pts/1".into(),
                host: "laptop".into(),
            },
            phone,
            device_id,
            _t: t,
        }
    }

    /// Fake agent: reads the challenge and answers with `answer(req)`.
    fn agent(e: &Env, answer: impl FnOnce(ApprovalRequest) -> Option<String> + Send + 'static) {
        let l = UnixListener::bind(e.sys.socket(e.ctx.uid)).unwrap();
        std::thread::spawn(move || {
            let (s, _) = l.accept().unwrap();
            let mut r = std::io::BufReader::new(s.try_clone().unwrap());
            let mut line = String::new();
            r.read_line(&mut line).unwrap();
            let v: Value = serde_json::from_str(&line).unwrap();
            assert_eq!(v["cmd"], "auth_request");
            let req: ApprovalRequest = serde_json::from_value(v["args"].clone()).unwrap();
            match answer(req) {
                Some(reply) => {
                    let mut w = s;
                    let _ = w.write_all(reply.as_bytes());
                    std::thread::sleep(Duration::from_secs(5));
                }
                None => std::thread::sleep(Duration::from_secs(10)), // hang
            }
        });
    }

    fn approval(phone: &AgentKey, device: &str, req: &ApprovalRequest, decision: &str) -> String {
        let d = if decision == "approve" {
            Decision::Approve
        } else {
            Decision::Deny
        };
        let sig = phone.sign_b64(&canonical_approval(req, d).unwrap());
        json!({"id":1,"ok":true,"data":{"request_id":req.request_id,"device_id":device,"decision":decision,"signature":sig}}).to_string() + "\n"
    }

    fn cfg(timeout: u64) -> Config {
        Config {
            timeout: Duration::from_secs(timeout),
            ..Default::default()
        }
    }

    fn run(e: &Env, c: &Config) -> Outcome {
        authenticate(c, &e.ctx, &e.sys, &mut |_| {})
    }

    #[test]
    fn approve() {
        let e = env();
        let (k, id) = (
            AgentKey::from_bytes(&e.phone.to_bytes()).unwrap(),
            e.device_id.clone(),
        );
        agent(&e, move |r| Some(approval(&k, &id, &r, "approve")));
        assert_eq!(
            run(&e, &cfg(5)),
            Outcome::Approved {
                device_id: e.device_id.clone()
            }
        );
    }

    #[test]
    fn deny_is_ignore() {
        let e = env();
        let (k, id) = (
            AgentKey::from_bytes(&e.phone.to_bytes()).unwrap(),
            e.device_id.clone(),
        );
        agent(&e, move |r| Some(approval(&k, &id, &r, "deny")));
        assert!(matches!(run(&e, &cfg(5)), Outcome::Ignore(_)));
    }

    #[test]
    fn deny_signature_relabelled_as_approve() {
        let e = env();
        let (k, id) = (
            AgentKey::from_bytes(&e.phone.to_bytes()).unwrap(),
            e.device_id.clone(),
        );
        agent(&e, move |r| {
            Some(approval(&k, &id, &r, "deny").replace("\"deny\"", "\"approve\""))
        });
        assert_eq!(run(&e, &cfg(5)), Outcome::Ignore("bad signature".into()));
    }

    #[test]
    fn forged_by_compromised_agent() {
        let e = env();
        let id = e.device_id.clone();
        agent(&e, move |r| {
            Some(approval(&AgentKey::generate(), &id, &r, "approve"))
        });
        assert_eq!(run(&e, &cfg(5)), Outcome::Ignore("bad signature".into()));
    }

    #[test]
    fn unknown_device() {
        let e = env();
        let k = AgentKey::from_bytes(&e.phone.to_bytes()).unwrap();
        agent(&e, move |r| {
            Some(approval(&k, "someone-else", &r, "approve"))
        });
        assert_eq!(
            run(&e, &cfg(5)),
            Outcome::Ignore("device not enrolled".into())
        );
    }

    #[test]
    fn replayed_old_approval() {
        let e = env();
        let (k, id) = (
            AgentKey::from_bytes(&e.phone.to_bytes()).unwrap(),
            e.device_id.clone(),
        );
        // Agent answers with a valid approval for a *different* challenge.
        agent(&e, move |_| {
            let old = ApprovalRequest::new("tester", "laptop", "sudo", "/dev/pts/1", 1);
            Some(approval(&k, &id, &old, "approve"))
        });
        assert_eq!(
            run(&e, &cfg(5)),
            Outcome::Ignore("reply for another request".into())
        );
    }

    #[test]
    fn hanging_agent_hits_deadline() {
        let e = env();
        agent(&e, |_| None);
        let start = Instant::now();
        assert!(matches!(run(&e, &cfg(1)), Outcome::Ignore(_)));
        let took = start.elapsed();
        assert!(
            took >= Duration::from_secs(3) - Duration::from_millis(100)
                && took < Duration::from_secs(5),
            "{took:?}"
        );
    }

    #[test]
    fn garbage_and_oversized_replies() {
        let e = env();
        agent(&e, |_| Some("not json\n".into()));
        assert_eq!(
            run(&e, &cfg(5)),
            Outcome::Ignore("unparseable reply".into())
        );
        let e = env();
        agent(&e, |_| Some("x".repeat(200_000)));
        assert_eq!(run(&e, &cfg(5)), Outcome::Ignore("reply too large".into()));
    }

    #[test]
    fn agent_not_running() {
        let e = env();
        assert!(
            matches!(run(&e, &cfg(5)), Outcome::Ignore(r) if r.starts_with("agent not reachable"))
        );
    }

    #[test]
    fn unsafe_trust_files_are_ignored() {
        let e = env();
        let f = e.sys.trust_dir.join("tester.json");
        std::fs::set_permissions(&f, std::fs::Permissions::from_mode(0o666)).unwrap();
        assert!(matches!(run(&e, &cfg(5)), Outcome::Ignore(r) if r.contains("writable")));

        let e = env();
        std::fs::set_permissions(
            &e.sys.trust_parents[0],
            std::fs::Permissions::from_mode(0o777),
        )
        .unwrap();
        assert!(matches!(run(&e, &cfg(5)), Outcome::Ignore(r) if r.contains("writable")));

        let e = env();
        let f = e.sys.trust_dir.join("tester.json");
        let real = e.sys.trust_dir.join("real.json");
        std::fs::rename(&f, &real).unwrap();
        std::os::unix::fs::symlink(&real, &f).unwrap();
        assert!(matches!(run(&e, &cfg(5)), Outcome::Ignore(r) if r.contains("symlink")));

        let mut e = env();
        e.sys.owner_uid = me() + 1;
        assert!(matches!(run(&e, &cfg(5)), Outcome::Ignore(r) if r.contains("not owned")));
    }

    #[test]
    fn other_services_and_bad_users() {
        let mut e = env();
        e.ctx.service = "login".into();
        assert!(matches!(run(&e, &cfg(5)), Outcome::Ignore(r) if r.contains("not enabled")));
        let mut e = env();
        e.ctx.user = "../../tmp/x".into();
        assert_eq!(
            run(&e, &cfg(5)),
            Outcome::Ignore("invalid user name".into())
        );
    }

    #[test]
    fn args() {
        let c = parse_args(&["debug", "timeout=999", "services=sudo,fortis-test", "junk"]);
        assert!(c.debug);
        assert_eq!(c.timeout, Duration::from_secs(60));
        assert_eq!(c.services, vec!["sudo", "fortis-test"]);
        assert_eq!(parse_args(&["timeout=abc", "services="]), Config::default());
    }
}
