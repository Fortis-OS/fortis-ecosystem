//! JSON-lines IPC socket (API §6, security §7.2).
//!
//! `$XDG_RUNTIME_DIR/fortis/agent.sock`, mode 0600. The peer UID (SO_PEERCRED)
//! must be the agent's own UID or 0. UID 0 (pam_fortis, `sudo fortis
//! pam-enroll`) may only call `auth_request` and `pam_devices`.

use std::time::Duration;

use serde::Deserialize;
use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::{mpsc, watch};
use uuid::Uuid;
use zeroize::Zeroizing;

use fortis_core::password_gen::{self, PasswordOptions};
use fortis_core::sign::ApprovalRequest;
use fortis_core::vault::EntryInput;

use crate::error::ApiError;
use crate::modules::{browser_lock, system, vault};
use crate::state::Shared;
use crate::util;

pub const MAX_LINE: u64 = 64 * 1024;

pub fn bind(st: &Shared) -> std::io::Result<UnixListener> {
    let path = st.paths.socket();
    let _ = std::fs::remove_file(&path);
    let l = UnixListener::bind(&path)?;
    crate::paths::tighten(&path, 0o600)?;
    Ok(l)
}

pub async fn serve(st: Shared, listener: UnixListener) {
    log::info!("IPC listening on {}", st.paths.socket().display());
    loop {
        match listener.accept().await {
            Ok((stream, _)) => {
                let st = st.clone();
                tokio::spawn(async move { handle_conn(st, stream).await });
            }
            Err(e) => {
                log::error!("IPC accept failed: {e}");
                tokio::time::sleep(Duration::from_millis(200)).await;
            }
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Caller {
    Owner,
    Root,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct IpcRequest {
    id: Value,
    cmd: String,
    #[serde(default)]
    args: Value,
}

fn ok(id: &Value, data: Value) -> String {
    json!({ "id": id, "ok": true, "data": data }).to_string()
}

fn err(id: &Value, e: ApiError) -> String {
    json!({ "id": id, "ok": false, "error": { "code": e.code, "message": e.message } }).to_string()
}

async fn handle_conn(st: Shared, stream: UnixStream) {
    let own = unsafe { libc::getuid() };
    let caller = match stream.peer_cred().map(|c| c.uid()) {
        Ok(uid) if uid == own => Caller::Owner,
        Ok(0) => Caller::Root,
        Ok(uid) => {
            log::warn!("IPC connection from foreign uid {uid} refused");
            return;
        }
        Err(_) => return,
    };

    let (rd, mut wr) = stream.into_split();
    let (out_tx, mut out_rx) = mpsc::channel::<String>(16);
    let writer = tokio::spawn(async move {
        while let Some(line) = out_rx.recv().await {
            if wr.write_all(line.as_bytes()).await.is_err() || wr.write_all(b"\n").await.is_err() {
                break;
            }
        }
    });
    let (closed_tx, closed_rx) = watch::channel(false);
    let mut reader = BufReader::new(rd);

    loop {
        let mut buf = Vec::new();
        match (&mut reader)
            .take(MAX_LINE + 1)
            .read_until(b'\n', &mut buf)
            .await
        {
            Ok(0) => break,
            Ok(_) if buf.len() as u64 > MAX_LINE => {
                let _ = out_tx.send(err(&Value::Null, ApiError::too_large())).await;
                break;
            }
            Ok(_) => {}
            Err(_) => break,
        }
        let req: IpcRequest = match serde_json::from_slice(&buf) {
            Ok(r) => r,
            Err(e) => {
                let _ = out_tx
                    .send(err(&Value::Null, ApiError::bad_request(e.to_string())))
                    .await;
                continue;
            }
        };
        // Long-running commands answer from their own task.
        let st2 = st.clone();
        let tx2 = out_tx.clone();
        let closed = closed_rx.clone();
        tokio::spawn(async move {
            let line = match dispatch(&st2, caller, &req, &tx2, closed).await {
                Ok(data) => ok(&req.id, data),
                Err(e) => err(&req.id, e),
            };
            let _ = tx2.send(line).await;
        });
    }
    let _ = closed_tx.send(true);
    drop(out_tx);
    let _ = writer.await;
}

fn arg<'a>(args: &'a Value, key: &str) -> Result<&'a Value, ApiError> {
    args.get(key)
        .ok_or_else(|| ApiError::bad_request(format!("missing `{key}`")))
}

fn arg_str<'a>(args: &'a Value, key: &str) -> Result<&'a str, ApiError> {
    arg(args, key)?
        .as_str()
        .ok_or_else(|| ApiError::bad_request(format!("`{key}` must be a string")))
}

fn arg_uuid(args: &Value, key: &str) -> Result<Uuid, ApiError> {
    Uuid::parse_str(arg_str(args, key)?)
        .map_err(|_| ApiError::bad_request(format!("`{key}` must be a UUID")))
}

fn from_args<T: serde::de::DeserializeOwned>(v: &Value) -> Result<T, ApiError> {
    serde_json::from_value(v.clone()).map_err(|e| ApiError::bad_request(e.to_string()))
}

async fn dispatch(
    st: &Shared,
    caller: Caller,
    req: &IpcRequest,
    out: &mpsc::Sender<String>,
    closed: watch::Receiver<bool>,
) -> Result<Value, ApiError> {
    let a = &req.args;
    let root_allowed = matches!(req.cmd.as_str(), "auth_request" | "pam_devices");
    match caller {
        Caller::Root if !root_allowed => {
            return Err(ApiError::forbidden(
                "root may only call auth_request and pam_devices",
            ));
        }
        Caller::Owner if req.cmd == "auth_request" => {
            return Err(ApiError::forbidden("auth_request is only for pam_fortis"));
        }
        _ => {}
    }
    match req.cmd.as_str() {
        "status" => Ok(json!({
            "state": st.vault_state(),
            "locks_in_secs": st.locks_in_secs(),
            "tailnet_ip": st.tailnet_ip(),
            "paired_devices": st.devices.list().len(),
            "online_devices": st.events.online_count(),
            "active_sessions": st.sessions.active().len(),
            "agent_fingerprint": st.agent_key.public().fingerprint(),
        })),
        "capabilities" => Ok(crate::modules::system::capabilities_json(st)),
        "init" => {
            st.init(Zeroizing::new(arg_str(a, "password")?.to_string()))
                .await?;
            Ok(json!({ "state": "unlocked" }))
        }
        "unlock" => {
            st.unlock(Zeroizing::new(arg_str(a, "password")?.to_string()))
                .await?;
            Ok(json!({ "state": "unlocked" }))
        }
        "lock" => {
            st.lock_vault("requested via IPC");
            Ok(json!({ "state": st.vault_state() }))
        }
        "list" => {
            let q = a.get("query").and_then(Value::as_str).map(str::to_owned);
            let kind = a.get("type").and_then(Value::as_str).map(str::to_owned);
            let res = st.with_vault(|db| db.list())?;
            let out: Vec<_> = res
                .entries
                .iter()
                .filter(|e| q.as_deref().is_none_or(|s| e.matches(s)))
                .filter(|e| {
                    kind.as_deref()
                        .is_none_or(|k| e.entry_type.type_name() == k)
                })
                .map(|e| e.summary())
                .collect();
            Ok(json!(out))
        }
        "get" => {
            let entry = if a.get("id").is_some() {
                let id = arg_uuid(a, "id")?;
                st.with_vault(|db| db.get(&id))?
            } else {
                let title = arg_str(a, "title")?;
                let list = st.with_vault(|db| db.list())?;
                let mut matches: Vec<_> = list
                    .entries
                    .into_iter()
                    .filter(|e| e.title == title)
                    .collect();
                match matches.len() {
                    0 => return Err(ApiError::not_found()),
                    1 => matches.remove(0),
                    _ => {
                        let c: Vec<_> = matches
                            .iter()
                            .map(|e| format!("{} ({})", e.title, e.id))
                            .collect();
                        return Err(ApiError::conflict(format!(
                            "several entries match: {}",
                            c.join(", ")
                        )));
                    }
                }
            };
            serde_json::to_value(&entry).map_err(ApiError::internal)
        }
        "add" => {
            let input: EntryInput = from_args(arg(a, "entry")?)?;
            let e = st.with_vault(|db| db.insert(input, fortis_core::unix_now()))?;
            Ok(json!({ "id": e.id }))
        }
        "edit" => {
            let id = arg_uuid(a, "id")?;
            let input: EntryInput = from_args(arg(a, "entry")?)?;
            let e = st.with_vault(|db| db.update(&id, input, fortis_core::unix_now()))?;
            Ok(json!({ "id": e.id }))
        }
        "delete" => {
            let id = arg_uuid(a, "id")?;
            st.with_vault(|db| db.delete(&id, fortis_core::unix_now()))?;
            Ok(json!({}))
        }
        "totp" => {
            let id = arg_uuid(a, "id")?;
            let e = st.with_vault(|db| db.get(&id))?;
            let cfg = e.entry_type.totp().ok_or_else(ApiError::not_found)?;
            let now = fortis_core::unix_now().max(0) as u64;
            let code = fortis_core::totp::generate(cfg, now)
                .map_err(|e| ApiError::bad_request(e.to_string()))?;
            Ok(
                json!({ "code": code, "remaining_secs": fortis_core::totp::remaining_secs(cfg.period, now) }),
            )
        }
        "generate" => {
            let o: PasswordOptions = if a.is_null() {
                PasswordOptions::default()
            } else {
                from_args(a)?
            };
            let p = password_gen::generate(&o).map_err(|e| ApiError::bad_request(e.to_string()))?;
            Ok(json!({ "password": p.as_str() }))
        }
        "pair_start" => {
            let (data, done) = vault::start_pairing(st);
            // Completion is sent as a second line on this connection.
            let id = req.id.clone();
            let out = out.clone();
            let st2 = st.clone();
            tokio::spawn(async move {
                if let Ok(Ok(d)) = tokio::time::timeout(crate::devices::PAIRING_TTL, done).await {
                    let mut dev = vault::device_json(&st2, &d);
                    dev["event"] = json!("paired");
                    let _ = out
                        .send(ok(&id, json!({ "event": "paired", "device": dev })))
                        .await;
                }
            });
            Ok(data)
        }
        "devices_list" => Ok(json!(
            st.devices
                .list()
                .iter()
                .map(|d| vault::device_json(st, d))
                .collect::<Vec<_>>()
        )),
        "devices_revoke" => {
            let id = arg_uuid(a, "device_id")?;
            Ok(json!({ "pam_reenroll_required": vault::revoke(st, &id)? }))
        }
        "pam_devices" => {
            let user = arg_str(a, "user")?;
            if user != util::username() {
                return Err(ApiError::forbidden("this agent belongs to another user"));
            }
            let list: Vec<_> = st
                .devices
                .list()
                .iter()
                .map(|d| {
                    json!({
                        "device_id": d.device_id,
                        "name": d.name,
                        "approval_pubkey": d.approval_pubkey,
                        "approval_key_fp": d.approval_key().map(|k| k.fingerprint()),
                    })
                })
                .collect();
            Ok(json!(list))
        }
        "auth_request" => auth_request(st, a, closed).await,
        "backup_run" => {
            let r = system::run_action(st, "run_backup").await?;
            Ok(json!({ "started_at": r["started_at"] }))
        }
        "backup_ask_phone" => backup_ask_phone(st, a),
        "lock_status" => Ok(st.browser_lock.status()),
        "lock_on" => browser_lock::lock_on(st, "laptop"),
        "lock_unlock" => browser_lock::unlock_with_password(st, a).await,
        "lock_unlock_phone" => lock_unlock_phone(st, closed).await,
        "lock_sites" => {
            if st.browser_lock.is_locked() {
                return Err(ApiError::forbidden("the browser lock is on"));
            }
            let sites = browser_lock::parse_sites(a.get("sites"))?;
            browser_lock::set_sites(st, sites, "laptop")
        }
        "lock_password" => browser_lock::set_password(st, a).await,
        "lock_watch" => lock_watch(st, &req.id, out, closed).await,
        "sessions_list" => Ok(json!(
            st.sessions
                .active()
                .iter()
                .map(
                    |s| json!({ "session_id": s.id, "device_id": s.device_id, "kind": s.kind,
                                 "started_secs_ago": s.started.map(|t| t.elapsed().as_secs()) })
                )
                .collect::<Vec<_>>()
        )),
        "sessions_end" => {
            let n = match a.get("session_id").and_then(Value::as_str) {
                Some(id) => st.sessions.end(id).map(|_| 1).unwrap_or(0),
                None => st.sessions.end_all(),
            };
            Ok(json!({ "ended": n }))
        }
        c if c.starts_with("browser_") => crate::modules::browser::handle(st, c, a),
        _ => Err(ApiError::bad_request(format!(
            "unknown command `{}`",
            req.cmd
        ))),
    }
}

/// fortis-backup is waiting for the phone: push a signed `backup_request` so the
/// app shows a "Backup waiting" notification (API §4). The phone only starts
/// Termux backup mode when the user taps it; no reply comes back.
fn backup_ask_phone(st: &Shared, a: &Value) -> Result<Value, ApiError> {
    let wait = match a.get("wait_secs") {
        None | Some(Value::Null) => 900,
        Some(v) => v
            .as_u64()
            .filter(|s| (60..=3600).contains(s))
            .ok_or_else(|| ApiError::bad_request("wait_secs must be 60–3600"))?,
    };
    let phones = st.events.online_count();
    if phones == 0 {
        return Err(ApiError::unavailable("no phone online"));
    }
    let now = fortis_core::unix_now().max(0) as u64;
    let request_id = fortis_core::crypto::random_hex(16);
    log::info!("backup request {request_id} sent to {phones} phone(s)");
    st.events.publish(
        &st.agent_key,
        json!({
            "type": "backup_request",
            "request_id": request_id,
            "issued_at": now,
            "expires_at": now + wait,
        }),
    );
    Ok(json!({ "phones": phones, "expires_at": now + wait }))
}

/// Relay a pam_fortis challenge to the phones and wait for one answer
/// (security §4.3). The agent only relays; pam_fortis verifies.
async fn auth_request(
    st: &Shared,
    a: &Value,
    closed: watch::Receiver<bool>,
) -> Result<Value, ApiError> {
    let req: ApprovalRequest = from_args(a)?;
    req.validate()
        .map_err(|e| ApiError::bad_request(e.to_string()))?;
    let now = fortis_core::unix_now().max(0) as u64;
    if now.abs_diff(req.issued_at) > 30 {
        return Err(ApiError::bad_request("stale challenge"));
    }
    let r = ask_phones(st, req, closed).await?;
    serde_json::to_value(r).map_err(ApiError::internal)
}

/// Turn the browser lock off with a fingerprint on a paired phone (security §7.8). The
/// phone signs the same approval bytes as for sudo; `Approvals::respond` has verified that
/// signature against the phone's approval key before the reply reaches us.
async fn lock_unlock_phone(st: &Shared, closed: watch::Receiver<bool>) -> Result<Value, ApiError> {
    if !st.browser_lock.is_locked() {
        return Ok(st.browser_lock.status());
    }
    let now = fortis_core::unix_now().max(0) as u64;
    let req = ApprovalRequest::new(
        &util::username(),
        &st.hostname,
        "fortis-lock",
        "browser",
        now,
    );
    let r = ask_phones(st, req, closed).await?;
    match r.decision {
        fortis_core::sign::Decision::Approve => {
            browser_lock::unlock(st, &format!("fingerprint on {}", r.device_id))
        }
        fortis_core::sign::Decision::Deny => Err(ApiError::forbidden("denied on the phone")),
    }
}

/// `lock_watch`: send the browser-lock state now and after every change, until the
/// caller hangs up. Answers on the same connection with the request's `id`.
async fn lock_watch(
    st: &Shared,
    id: &Value,
    out: &mpsc::Sender<String>,
    mut closed: watch::Receiver<bool>,
) -> Result<Value, ApiError> {
    let mut rx = st.browser_lock.subscribe();
    loop {
        let now = rx.borrow_and_update().clone();
        if out.send(ok(id, now)).await.is_err() {
            break;
        }
        tokio::select! {
            r = rx.changed() => if r.is_err() { break },
            _ = closed.changed() => break,
        }
    }
    Err(ApiError::unavailable("watch ended"))
}

/// Push a `sudo_request` for `req` to every online phone and wait for one signed answer.
async fn ask_phones(
    st: &Shared,
    req: ApprovalRequest,
    mut closed: watch::Receiver<bool>,
) -> Result<crate::hub::ApprovalReply, ApiError> {
    if st.events.online_count() == 0 {
        return Err(ApiError::unavailable("no phone online"));
    }
    let timeout = Duration::from_secs(st.config.auth.approval_timeout_secs);
    let rx = st.approvals.register(req.clone());
    log::info!(
        "sudo request {} for {}@{} ({})",
        req.request_id,
        req.user,
        req.host,
        req.service
    );
    st.events.publish(
        &st.agent_key,
        json!({
            "type": "sudo_request",
            "request_id": req.request_id,
            "nonce": req.nonce,
            "issued_at": req.issued_at,
            "user": req.user,
            "host": req.host,
            "service": req.service,
            "tty": req.tty,
            "expires_at": req.issued_at + timeout.as_secs(),
        }),
    );

    let cancelled = |reason: &str| {
        st.approvals.cancel(&req.request_id);
        st.events.publish(
            &st.agent_key,
            json!({ "type": "sudo_cancelled", "request_id": req.request_id, "reason": reason }),
        );
    };
    tokio::select! {
        reply = rx => match reply {
            Ok(r) => Ok(r),
            Err(_) => Err(ApiError::unavailable("request dropped")),
        },
        _ = tokio::time::sleep(timeout) => {
            cancelled("timeout");
            log::info!("sudo request {} timed out", req.request_id);
            Err(ApiError::unavailable("timeout"))
        }
        _ = closed.changed() => {
            cancelled("pam_closed");
            Err(ApiError::unavailable("pam closed the connection"))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::devices::Device;
    use crate::state::test_support;
    use fortis_core::sign::{AgentKey, Decision, canonical_approval};

    fn req(cmd: &str, args: Value) -> IpcRequest {
        IpcRequest {
            id: json!(1),
            cmd: cmd.into(),
            args,
        }
    }

    async fn call(st: &Shared, caller: Caller, r: IpcRequest) -> Result<Value, ApiError> {
        let (tx, _rx) = mpsc::channel(4);
        let (_c, closed) = watch::channel(false);
        dispatch(st, caller, &r, &tx, closed).await
    }

    #[tokio::test]
    async fn socket_round_trip() {
        let t = tempfile::tempdir().unwrap();
        let st = test_support::state(t.path(), |_| {});
        let l = bind(&st).unwrap();
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(
            std::fs::metadata(st.paths.socket())
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o600
        );
        tokio::spawn(serve(st.clone(), l));
        let mut s = UnixStream::connect(st.paths.socket()).await.unwrap();
        s.write_all(b"{\"id\":7,\"cmd\":\"status\"}\nnot json\n")
            .await
            .unwrap();
        let mut r = BufReader::new(s);
        let mut lines = Vec::new();
        for _ in 0..2 {
            let mut l = String::new();
            r.read_line(&mut l).await.unwrap();
            lines.push(serde_json::from_str::<Value>(&l).unwrap());
        }
        let status = lines.iter().find(|v| v["id"] == 7).unwrap();
        assert_eq!(status["data"]["state"], "uninitialised");
        assert!(
            lines
                .iter()
                .any(|v| v["ok"] == false && v["error"]["code"] == "bad_request")
        );
    }

    #[tokio::test]
    async fn caller_restrictions() {
        let t = tempfile::tempdir().unwrap();
        let st = test_support::state(t.path(), |_| {});
        assert_eq!(
            call(&st, Caller::Root, req("status", json!({})))
                .await
                .unwrap_err()
                .code,
            "forbidden"
        );
        assert_eq!(
            call(&st, Caller::Root, req("unlock", json!({"password":"x"})))
                .await
                .unwrap_err()
                .code,
            "forbidden"
        );
        assert_eq!(
            call(&st, Caller::Owner, req("auth_request", json!({})))
                .await
                .unwrap_err()
                .code,
            "forbidden"
        );
        let user = util::username();
        assert!(
            call(&st, Caller::Root, req("pam_devices", json!({"user": user})))
                .await
                .is_ok()
        );
        assert_eq!(
            call(
                &st,
                Caller::Root,
                req("pam_devices", json!({"user": "someone-else"}))
            )
            .await
            .unwrap_err()
            .code,
            "forbidden"
        );
    }

    #[tokio::test]
    async fn browser_commands_enforce_matching() {
        let t = tempfile::tempdir().unwrap();
        let st = test_support::state(t.path(), |_| {});
        // Locked/uninitialised → no data.
        assert!(
            call(
                &st,
                Caller::Owner,
                req("browser_match", json!({"url":"https://github.com/"}))
            )
            .await
            .is_err()
        );
        call(
            &st,
            Caller::Owner,
            req("init", json!({"password":"a long password"})),
        )
        .await
        .unwrap();
        let gh = json!({"title":"GitHub","entry_type":{"Login":{"username":"me","password":"pw1","urls":["https://github.com"],
            "totp":{"secret_base32":"JBSWY3DPEHPK3PXP","digits":6,"period":30,"algorithm":"Sha1"}}}});
        let id = call(&st, Caller::Owner, req("add", json!({"entry": gh})))
            .await
            .unwrap()["id"]
            .as_str()
            .unwrap()
            .to_string();
        let note = json!({"title":"github.com","entry_type":{"SecureNote":{"content":"x"}}});
        call(&st, Caller::Owner, req("add", json!({"entry": note})))
            .await
            .unwrap();

        let m = call(
            &st,
            Caller::Owner,
            req("browser_match", json!({"url":"https://gist.github.com/x"})),
        )
        .await
        .unwrap();
        assert_eq!(m["host"], "gist.github.com");
        assert_eq!(
            m["entries"].as_array().unwrap().len(),
            1,
            "notes never match, even by title"
        );
        assert!(
            m["entries"][0].get("password").is_none(),
            "match returns no secrets"
        );
        let none = call(
            &st,
            Caller::Owner,
            req(
                "browser_match",
                json!({"url":"https://github.com.evil.io/"}),
            ),
        )
        .await
        .unwrap();
        assert!(none["entries"].as_array().unwrap().is_empty());

        let f = call(
            &st,
            Caller::Owner,
            req(
                "browser_fill",
                json!({"id": id, "url":"https://github.com/login"}),
            ),
        )
        .await
        .unwrap();
        assert_eq!(f["password"], "pw1");
        // The agent re-checks the URL: a known id on the wrong site is refused.
        for bad in ["https://evil.io/", "http://github.com/"] {
            let e = call(
                &st,
                Caller::Owner,
                req("browser_fill", json!({"id": id, "url": bad})),
            )
            .await
            .unwrap_err();
            assert_eq!(e.code, "forbidden", "{bad}");
        }
        assert_eq!(
            call(
                &st,
                Caller::Owner,
                req(
                    "browser_totp",
                    json!({"id": id, "url":"https://github.com/"})
                )
            )
            .await
            .unwrap()["code"]
                .as_str()
                .unwrap()
                .len(),
            6
        );
        assert_eq!(
            call(
                &st,
                Caller::Owner,
                req("browser_totp", json!({"id": id, "url":"https://evil.io/"}))
            )
            .await
            .unwrap_err()
            .code,
            "forbidden"
        );

        // Save: same / changed / new.
        let chk = |u: &str, p: &str| {
            req(
                "browser_save_check",
                json!({"url":"https://github.com/login","username":u,"password":p}),
            )
        };
        assert_eq!(
            call(&st, Caller::Owner, chk("me", "pw1")).await.unwrap()["status"],
            "same"
        );
        let c = call(&st, Caller::Owner, chk("me", "pw2")).await.unwrap();
        assert_eq!(c["status"], "changed");
        assert_eq!(c["id"].as_str().unwrap(), id);
        assert_eq!(
            call(&st, Caller::Owner, chk("other", "pw")).await.unwrap()["status"],
            "new"
        );

        let up = call(
            &st,
            Caller::Owner,
            req(
                "browser_save",
                json!({"url":"https://github.com/","username":"me","password":"pw2","id": id}),
            ),
        )
        .await
        .unwrap();
        assert_eq!(up["action"], "updated");
        let g = call(&st, Caller::Owner, req("get", json!({"id": id})))
            .await
            .unwrap();
        assert_eq!(g["entry_type"]["Login"]["password"], "pw2");
        assert!(
            g["entry_type"]["Login"]["totp"].is_object(),
            "update keeps TOTP"
        );
        // Updating via an unrelated site is refused.
        assert_eq!(
            call(
                &st,
                Caller::Owner,
                req(
                    "browser_save",
                    json!({"url":"https://evil.io/","username":"me","password":"x","id": id})
                )
            )
            .await
            .unwrap_err()
            .code,
            "forbidden"
        );

        let cr = call(&st, Caller::Owner, req("browser_save", json!({"url":"https://news.ycombinator.com/login?goto=x","username":"pg","password":"s"}))).await.unwrap();
        assert_eq!(cr["action"], "created");
        let n = call(&st, Caller::Owner, req("get", json!({"id": cr["id"]})))
            .await
            .unwrap();
        assert_eq!(n["title"], "news.ycombinator.com");
        assert_eq!(
            n["entry_type"]["Login"]["urls"][0],
            "https://news.ycombinator.com"
        );
        assert_eq!(
            call(
                &st,
                Caller::Owner,
                req(
                    "browser_save",
                    json!({"url":"http://plain.com/","username":"a","password":"b"})
                )
            )
            .await
            .unwrap_err()
            .code,
            "forbidden"
        );
        // Root (pam) may not use browser commands.
        assert_eq!(
            call(
                &st,
                Caller::Root,
                req("browser_match", json!({"url":"https://github.com/"}))
            )
            .await
            .unwrap_err()
            .code,
            "forbidden"
        );
    }

    #[tokio::test]
    async fn vault_commands() {
        let t = tempfile::tempdir().unwrap();
        let st = test_support::state(t.path(), |_| {});
        call(
            &st,
            Caller::Owner,
            req("init", json!({"password":"a long password"})),
        )
        .await
        .unwrap();
        let e = json!({"title":"Bank","entry_type":{"SecureNote":{"content":"1234"}}});
        call(&st, Caller::Owner, req("add", json!({"entry": e.clone()})))
            .await
            .unwrap();
        call(&st, Caller::Owner, req("add", json!({"entry": e})))
            .await
            .unwrap();
        // Two entries share the title → conflict, not a guess.
        assert_eq!(
            call(&st, Caller::Owner, req("get", json!({"title":"Bank"})))
                .await
                .unwrap_err()
                .code,
            "conflict"
        );
        let list = call(&st, Caller::Owner, req("list", json!({})))
            .await
            .unwrap();
        assert_eq!(list.as_array().unwrap().len(), 2);
        let g = call(&st, Caller::Owner, req("generate", json!({"length": 30})))
            .await
            .unwrap();
        assert_eq!(g["password"].as_str().unwrap().len(), 30);
        call(&st, Caller::Owner, req("lock", json!({})))
            .await
            .unwrap();
        assert_eq!(
            call(&st, Caller::Owner, req("list", json!({})))
                .await
                .unwrap_err()
                .code,
            "vault_locked"
        );
    }

    #[tokio::test]
    async fn browser_lock_watch_and_phone_unlock() {
        let t = tempfile::tempdir().unwrap();
        let st = test_support::state(t.path(), |c| c.auth.approval_timeout_secs = 5);
        let l = bind(&st).unwrap();
        tokio::spawn(serve(st.clone(), l));

        // A watcher gets the state at once and after every change.
        let mut s = UnixStream::connect(st.paths.socket()).await.unwrap();
        s.write_all(b"{\"id\":3,\"cmd\":\"lock_watch\"}\n")
            .await
            .unwrap();
        let mut r = BufReader::new(s);
        let mut next = async || {
            let mut l = String::new();
            r.read_line(&mut l).await.unwrap();
            serde_json::from_str::<Value>(&l).unwrap()
        };
        let first = next().await;
        assert_eq!(
            (first["id"].clone(), first["data"]["locked"].clone()),
            (json!(3), json!(false))
        );

        let r = call(
            &st,
            Caller::Owner,
            req(
                "lock_sites",
                json!({"sites": ["https://web.whatsapp.com/"]}),
            ),
        )
        .await
        .unwrap();
        assert_eq!(r["sites"], json!(["web.whatsapp.com"]));
        assert_eq!(next().await["data"]["sites"], json!(["web.whatsapp.com"]));
        call(&st, Caller::Owner, req("lock_on", json!({})))
            .await
            .unwrap();
        assert_eq!(next().await["data"]["locked"], true);

        // Locked: the laptop can't change the sites; root can't touch the lock at all.
        let e = call(&st, Caller::Owner, req("lock_sites", json!({"sites": []})))
            .await
            .unwrap_err();
        assert_eq!(e.code, "forbidden");
        assert!(
            call(&st, Caller::Root, req("lock_status", json!({})))
                .await
                .is_err()
        );

        // Fingerprint unlock: the phone gets a fortis-lock approval request.
        let approval = AgentKey::generate();
        let d = Device {
            device_id: uuid::Uuid::new_v4(),
            name: "Phone".into(),
            request_pubkey: AgentKey::generate().public().to_base64(),
            approval_pubkey: approval.public().to_base64(),
            paired_at: 0,
            last_seen: 0,
        };
        st.devices.add(d.clone()).unwrap();
        st.events.connected(d.device_id);
        for decision in [Decision::Deny, Decision::Approve] {
            let mut events = st.events.subscribe();
            let st2 = st.clone();
            let waiting = tokio::spawn(async move {
                call(&st2, Caller::Owner, req("lock_unlock_phone", json!({}))).await
            });
            let msg = loop {
                let f: Value = serde_json::from_str(&events.recv().await.unwrap()).unwrap();
                let m: Value = serde_json::from_str(f["msg"].as_str().unwrap()).unwrap();
                if m["type"] == "sudo_request" {
                    break m;
                }
            };
            assert_eq!(
                (msg["service"].as_str(), msg["tty"].as_str()),
                (Some("fortis-lock"), Some("browser"))
            );
            let challenge: ApprovalRequest = serde_json::from_value(json!({
                "request_id": msg["request_id"], "nonce": msg["nonce"], "issued_at": msg["issued_at"],
                "user": msg["user"], "host": msg["host"], "service": msg["service"], "tty": msg["tty"],
            })).unwrap();
            // A signature by some other key is refused and the request stays open.
            let bad =
                AgentKey::generate().sign_b64(&canonical_approval(&challenge, decision).unwrap());
            assert!(
                st.approvals
                    .respond(&challenge.request_id, &d, decision, &bad)
                    .is_err()
            );
            let sig = approval.sign_b64(&canonical_approval(&challenge, decision).unwrap());
            st.approvals
                .respond(&challenge.request_id, &d, decision, &sig)
                .unwrap();
            let res = waiting.await.unwrap();
            if decision == Decision::Deny {
                assert_eq!(res.unwrap_err().code, "forbidden");
                assert!(st.browser_lock.is_locked());
            } else {
                assert_eq!(res.unwrap()["locked"], false);
            }
        }
        assert_eq!(next().await["data"]["locked"], false);
    }

    #[tokio::test]
    async fn sudo_relay() {
        let t = tempfile::tempdir().unwrap();
        let st = test_support::state(t.path(), |c| c.auth.approval_timeout_secs = 5);
        let approval = AgentKey::generate();
        let d = Device {
            device_id: uuid::Uuid::new_v4(),
            name: "Phone".into(),
            request_pubkey: AgentKey::generate().public().to_base64(),
            approval_pubkey: approval.public().to_base64(),
            paired_at: 0,
            last_seen: 0,
        };
        st.devices.add(d.clone()).unwrap();
        let challenge = ApprovalRequest::new(
            "ganesh",
            "laptop",
            "sudo",
            "pts/1",
            fortis_core::unix_now() as u64,
        );

        // No phone online → immediate fallback.
        let r = call(
            &st,
            Caller::Root,
            req("auth_request", serde_json::to_value(&challenge).unwrap()),
        )
        .await;
        assert_eq!(r.unwrap_err().message, "no phone online");

        st.events.connected(d.device_id);
        let mut events = st.events.subscribe();
        let st2 = st.clone();
        let c2 = challenge.clone();
        let waiting = tokio::spawn(async move {
            call(
                &st2,
                Caller::Root,
                req("auth_request", serde_json::to_value(&c2).unwrap()),
            )
            .await
        });

        // The phone sees a signed sudo_request…
        let frame: Value = serde_json::from_str(&events.recv().await.unwrap()).unwrap();
        let msg: Value = serde_json::from_str(frame["msg"].as_str().unwrap()).unwrap();
        assert_eq!(msg["type"], "sudo_request");
        assert!(st.agent_key.public().verify_b64(
            frame["msg"].as_str().unwrap().as_bytes(),
            frame["agent_sig"].as_str().unwrap()
        ));
        // …and approves.
        let sig = approval.sign_b64(&canonical_approval(&challenge, Decision::Approve).unwrap());
        st.approvals
            .respond(&challenge.request_id, &d, Decision::Approve, &sig)
            .unwrap();
        let reply = waiting.await.unwrap().unwrap();
        assert_eq!(reply["decision"], "approve");
        assert_eq!(reply["signature"], sig);
        assert_eq!(reply["device_id"], d.device_id.to_string());
    }

    #[tokio::test]
    async fn backup_ask_phone_pushes_signed_request() {
        let t = tempfile::tempdir().unwrap();
        let st = test_support::state(t.path(), |_| {});

        // No phone online → 503, nothing sent.
        let r = call(&st, Caller::Owner, req("backup_ask_phone", json!({}))).await;
        assert_eq!(r.unwrap_err().message, "no phone online");

        st.events.connected(uuid::Uuid::new_v4());
        let mut events = st.events.subscribe();
        let r = call(
            &st,
            Caller::Owner,
            req("backup_ask_phone", json!({ "wait_secs": 600 })),
        )
        .await
        .unwrap();
        assert_eq!(r["phones"], 1);

        let frame: Value = serde_json::from_str(&events.recv().await.unwrap()).unwrap();
        let raw = frame["msg"].as_str().unwrap();
        assert!(
            st.agent_key
                .public()
                .verify_b64(raw.as_bytes(), frame["agent_sig"].as_str().unwrap())
        );
        let msg: Value = serde_json::from_str(raw).unwrap();
        assert_eq!(msg["type"], "backup_request");
        assert_eq!(msg["request_id"].as_str().unwrap().len(), 32);
        assert_eq!(
            msg["expires_at"].as_u64().unwrap() - msg["issued_at"].as_u64().unwrap(),
            600
        );
        assert_eq!(msg["expires_at"], r["expires_at"]);

        // Out-of-range wait and root callers are refused.
        let r = call(
            &st,
            Caller::Owner,
            req("backup_ask_phone", json!({ "wait_secs": 5 })),
        )
        .await;
        assert_eq!(r.unwrap_err().code, "bad_request");
        let r = call(&st, Caller::Root, req("backup_ask_phone", json!({}))).await;
        assert_eq!(r.unwrap_err().code, "forbidden");
    }

    #[tokio::test]
    async fn sudo_timeout() {
        let t = tempfile::tempdir().unwrap();
        let st = test_support::state(t.path(), |c| c.auth.approval_timeout_secs = 1);
        st.events.connected(uuid::Uuid::new_v4());
        let challenge =
            ApprovalRequest::new("u", "h", "sudo", "pts/1", fortis_core::unix_now() as u64);
        let r = call(
            &st,
            Caller::Root,
            req("auth_request", serde_json::to_value(&challenge).unwrap()),
        )
        .await;
        assert_eq!(r.unwrap_err().message, "timeout");
        // Stale challenges are refused.
        let old = ApprovalRequest::new("u", "h", "sudo", "pts/1", 1000);
        assert_eq!(
            call(
                &st,
                Caller::Root,
                req("auth_request", serde_json::to_value(&old).unwrap())
            )
            .await
            .unwrap_err()
            .code,
            "bad_request"
        );
    }
}
