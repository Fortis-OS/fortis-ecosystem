//! Real-libpam test harness (docs/development "Testing pam_fortis").
//!
//! Run only inside the sandbox prepared by `tests/real_pam.sh`
//! (`unshare -rm`, overlay on /etc, tmpfs on /run/user). It writes the trust
//! file, starts a fake agent, and runs `pam_authenticate` through libpam with
//! a private config dir (`pam_start_confdir`).
//!
//! usage: pam_harness <module.so|missing> <user> <agent: approve|deny|forge|hang|none> <next: deny|permit> <dash: yes|no> [module args…]
//!        pam_harness serve  <user> <agent>          trust file + fake agent only (for `fortis pam-test`)
//!        pam_harness system <user> <agent>          run the real /etc/pam.d/sudo stack

use std::ffi::{CStr, CString, c_char, c_int, c_void};
use std::io::{BufRead, BufReader, Write};
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::UnixListener;
use std::time::{Duration, Instant};

use fortis_core::sign::{AgentKey, ApprovalRequest, Decision, canonical_approval};
use serde_json::{Value, json};

#[repr(C)]
struct PamMessage {
    msg_style: c_int,
    msg: *const c_char,
}
#[repr(C)]
struct PamResponse {
    resp: *mut c_char,
    resp_retcode: c_int,
}
#[repr(C)]
struct PamConv {
    conv: unsafe extern "C" fn(
        c_int,
        *mut *const PamMessage,
        *mut *mut PamResponse,
        *mut c_void,
    ) -> c_int,
    appdata: *mut c_void,
}

#[link(name = "pam")]
unsafe extern "C" {
    fn pam_start_confdir(
        service: *const c_char,
        user: *const c_char,
        conv: *const PamConv,
        confdir: *const c_char,
        pamh: *mut *mut c_void,
    ) -> c_int;
    fn pam_authenticate(pamh: *mut c_void, flags: c_int) -> c_int;
    fn pam_end(pamh: *mut c_void, status: c_int) -> c_int;
    fn pam_strerror(pamh: *mut c_void, errnum: c_int) -> *const c_char;
}

unsafe extern "C" fn conv(
    n: c_int,
    msgs: *mut *const PamMessage,
    resp: *mut *mut PamResponse,
    _: *mut c_void,
) -> c_int {
    unsafe {
        for i in 0..n as usize {
            let m = &**msgs.add(i);
            println!(
                "[conv {}] {}",
                m.msg_style,
                CStr::from_ptr(m.msg).to_string_lossy()
            );
        }
        *resp = libc::calloc(n as usize, std::mem::size_of::<PamResponse>()) as *mut PamResponse;
    }
    0
}

fn uid_of(user: &str) -> u32 {
    let c = CString::new(user).unwrap();
    unsafe { (*libc::getpwnam(c.as_ptr())).pw_uid }
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let mode = a[1].as_str();
    let (module, user, agent) = (&a[1], &a[2], a[3].as_str());
    let (next, dash) = if a.len() > 5 {
        (a[4].clone(), a[5] == "yes")
    } else {
        (String::new(), true)
    };
    let extra = a.get(6..).map(|x| x.join(" ")).unwrap_or_default();

    // Trust file, as `sudo fortis pam-enroll` would write it.
    let phone = AgentKey::generate();
    let device = "0b7c2f4e-0000-4000-8000-0000000000aa";
    std::fs::create_dir_all("/etc/fortis/pam").unwrap();
    for d in ["/etc/fortis", "/etc/fortis/pam"] {
        std::fs::set_permissions(d, std::fs::Permissions::from_mode(0o755)).unwrap();
    }
    let trust = json!({"version":1,"devices":[{"device_id":device,"name":"Test phone","approval_pubkey":phone.public().to_base64(),"enrolled_at":1}]});
    let tf = format!("/etc/fortis/pam/{user}.json");
    std::fs::write(&tf, trust.to_string()).unwrap();
    std::fs::set_permissions(&tf, std::fs::Permissions::from_mode(0o644)).unwrap();

    // Fake agent on the user's socket.
    let sock_dir = format!("/run/user/{}/fortis", uid_of(user));
    std::fs::create_dir_all(&sock_dir).unwrap();
    let sock = format!("{sock_dir}/agent.sock");
    let _ = std::fs::remove_file(&sock);
    if agent != "none" {
        let l = UnixListener::bind(&sock).unwrap();
        let mode = agent.to_string();
        let key = AgentKey::from_bytes(&phone.to_bytes()).unwrap();
        std::thread::spawn(move || {
            let Ok((s, _)) = l.accept() else { return };
            let mut r = BufReader::new(s.try_clone().unwrap());
            let mut line = String::new();
            r.read_line(&mut line).unwrap();
            let v: Value = serde_json::from_str(&line).unwrap();
            let req: ApprovalRequest = serde_json::from_value(v["args"].clone()).unwrap();
            let (signer, decision, dec) = match mode.as_str() {
                "approve" => (key, Decision::Approve, "approve"),
                "deny" => (key, Decision::Deny, "deny"),
                "forge" => (AgentKey::generate(), Decision::Approve, "approve"),
                _ => {
                    std::thread::sleep(Duration::from_secs(120));
                    return;
                }
            };
            let sig = signer.sign_b64(&canonical_approval(&req, decision).unwrap());
            let reply = json!({"id":1,"ok":true,"data":{"request_id":req.request_id,"device_id":device,"decision":dec,"signature":sig}});
            let mut w = s;
            let _ = writeln!(w, "{reply}");
            std::thread::sleep(Duration::from_secs(2));
        });
    }

    if mode == "serve" {
        std::thread::sleep(Duration::from_secs(25));
        return;
    }
    let (service_name, confdir) = if mode == "system" {
        ("sudo", "/etc/pam.d".to_string())
    } else {
        ("fortis-test", tempfile_dir())
    };
    if mode != "system" {
        let prefix = if dash { "-auth" } else { "auth" };
        let module_path = if module == "missing" {
            "/nonexistent/pam_fortis.so".to_string()
        } else {
            std::fs::canonicalize(module).unwrap().display().to_string()
        };
        let svc = format!(
            "{prefix} sufficient {module_path} services=fortis-test {extra}\nauth required pam_{next}.so\n"
        );
        std::fs::write(format!("{confdir}/fortis-test"), &svc).unwrap();
    }

    let c = PamConv {
        conv,
        appdata: std::ptr::null_mut(),
    };
    let (service, u, cd) = (
        CString::new(service_name).unwrap(),
        CString::new(user.as_str()).unwrap(),
        CString::new(confdir.clone()).unwrap(),
    );
    let mut h: *mut c_void = std::ptr::null_mut();
    let start = Instant::now();
    unsafe {
        let rc = pam_start_confdir(service.as_ptr(), u.as_ptr(), &c, cd.as_ptr(), &mut h);
        assert_eq!(rc, 0, "pam_start_confdir failed");
        let r = pam_authenticate(h, 0);
        let msg = CStr::from_ptr(pam_strerror(h, r))
            .to_string_lossy()
            .into_owned();
        println!(
            "RESULT {r} \"{msg}\" in {:.1}s",
            start.elapsed().as_secs_f64()
        );
        pam_end(h, r);
    }
    if mode != "system" {
        let _ = std::fs::remove_dir_all(&confdir);
    }
}

fn tempfile_dir() -> String {
    let d = format!("/tmp/fortis-pamconf-{}", std::process::id());
    std::fs::create_dir_all(&d).unwrap();
    d
}
