//! pam_fortis — fingerprint-approved `sudo` (docs/security §4, §4.7).
//!
//! Returns only `PAM_SUCCESS` (valid phone signature over our own challenge)
//! or `PAM_IGNORE` (anything else), so the next module — your password —
//! always runs on failure. All logic is in [`logic`]; this file is the thin
//! FFI layer and the only place with `unsafe`.

use std::ffi::{CStr, CString, c_char, c_int, c_void};
use std::panic::{AssertUnwindSafe, catch_unwind};

pub mod logic;

pub const PAM_SUCCESS: c_int = 0;
pub const PAM_IGNORE: c_int = 25;
const PAM_SILENT: c_int = 0x8000;
const PAM_SERVICE: c_int = 1;
const PAM_USER: c_int = 2;
const PAM_TTY: c_int = 3;
const PAM_CONV: c_int = 5;
const PAM_TEXT_INFO: c_int = 4;
const LOG_NOTICE: c_int = 5;

#[repr(C)]
pub struct PamHandle {
    _private: [u8; 0],
}

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

type ConvFn = unsafe extern "C" fn(
    c_int,
    *mut *const PamMessage,
    *mut *mut PamResponse,
    *mut c_void,
) -> c_int;

#[repr(C)]
struct PamConv {
    conv: Option<ConvFn>,
    appdata_ptr: *mut c_void,
}

#[link(name = "pam")]
unsafe extern "C" {
    fn pam_get_item(pamh: *const PamHandle, item_type: c_int, item: *mut *const c_void) -> c_int;
    fn pam_syslog(pamh: *const PamHandle, priority: c_int, fmt: *const c_char, ...);
}

/// A string PAM item, or empty.
unsafe fn item_str(pamh: *const PamHandle, kind: c_int) -> String {
    let mut p: *const c_void = std::ptr::null();
    // SAFETY: pamh comes from libpam; pam_get_item writes a pointer owned by libpam.
    if unsafe { pam_get_item(pamh, kind, &mut p) } != PAM_SUCCESS || p.is_null() {
        return String::new();
    }
    // SAFETY: string items are NUL-terminated C strings owned by libpam.
    unsafe { CStr::from_ptr(p as *const c_char) }
        .to_string_lossy()
        .into_owned()
}

fn uid_of(user: &str) -> Option<u32> {
    let c = CString::new(user).ok()?;
    let mut pw: libc::passwd = unsafe { std::mem::zeroed() };
    let mut buf = vec![0 as c_char; 16 * 1024];
    let mut out: *mut libc::passwd = std::ptr::null_mut();
    // SAFETY: all pointers are valid for the call; buf outlives the result use.
    let rc =
        unsafe { libc::getpwnam_r(c.as_ptr(), &mut pw, buf.as_mut_ptr(), buf.len(), &mut out) };
    (rc == 0 && !out.is_null()).then_some(pw.pw_uid)
}

fn hostname() -> String {
    let mut buf = [0 as c_char; 256];
    // SAFETY: buf is writable for its full length.
    if unsafe { libc::gethostname(buf.as_mut_ptr(), buf.len() - 1) } != 0 {
        return "unknown".into();
    }
    // SAFETY: gethostname NUL-terminates within the length we passed.
    unsafe { CStr::from_ptr(buf.as_ptr()) }
        .to_string_lossy()
        .into_owned()
}

/// Show an informational line through the application's conversation.
unsafe fn tell(pamh: *const PamHandle, text: &str) {
    let mut p: *const c_void = std::ptr::null();
    // SAFETY: pamh from libpam.
    if unsafe { pam_get_item(pamh, PAM_CONV, &mut p) } != PAM_SUCCESS || p.is_null() {
        return;
    }
    // SAFETY: PAM_CONV is a `struct pam_conv *` owned by the application.
    let conv = unsafe { &*(p as *const PamConv) };
    let Some(f) = conv.conv else { return };
    let Ok(c) = CString::new(text) else { return };
    let msg = PamMessage {
        msg_style: PAM_TEXT_INFO,
        msg: c.as_ptr(),
    };
    let mut msgp: *const PamMessage = &msg;
    let mut resp: *mut PamResponse = std::ptr::null_mut();
    // SAFETY: Linux-PAM calling convention; we free any response the app allocated.
    unsafe {
        f(1, &mut msgp, &mut resp, conv.appdata_ptr);
        if !resp.is_null() {
            if !(*resp).resp.is_null() {
                libc::free((*resp).resp as *mut c_void);
            }
            libc::free(resp as *mut c_void);
        }
    }
}

unsafe fn log(pamh: *const PamHandle, msg: &str) {
    if let Ok(c) = CString::new(msg.replace('%', "%%")) {
        // SAFETY: "%s" with one valid C string argument.
        unsafe { pam_syslog(pamh, LOG_NOTICE, c"%s".as_ptr(), c.as_ptr()) };
    }
}

unsafe fn args(argc: c_int, argv: *const *const c_char) -> Vec<String> {
    if argv.is_null() || argc <= 0 {
        return vec![];
    }
    (0..argc as usize)
        .filter_map(|i| {
            // SAFETY: libpam passes argc valid C strings.
            let p = unsafe { *argv.add(i) };
            (!p.is_null()).then(|| unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned())
        })
        .collect()
}

unsafe fn run(
    pamh: *const PamHandle,
    flags: c_int,
    argc: c_int,
    argv: *const *const c_char,
) -> c_int {
    if pamh.is_null() {
        return PAM_IGNORE;
    }
    let cfg = logic::parse_args(&unsafe { args(argc, argv) });
    let user = unsafe { item_str(pamh, PAM_USER) };
    let service = unsafe { item_str(pamh, PAM_SERVICE) };
    let Some(uid) = uid_of(&user) else {
        if cfg.debug {
            unsafe { log(pamh, "pam_fortis: unknown user, ignoring") };
        }
        return PAM_IGNORE;
    };
    let ctx = logic::Context {
        user,
        uid,
        service,
        tty: unsafe { item_str(pamh, PAM_TTY) },
        host: hostname(),
    };
    let silent = flags & PAM_SILENT != 0;
    let outcome = logic::authenticate(&cfg, &ctx, &logic::Sys::production(), &mut |m| {
        if !silent {
            unsafe { tell(pamh, m) }
        }
    });
    match outcome {
        logic::Outcome::Approved { device_id } => {
            unsafe { log(pamh, &format!("pam_fortis: approved by device {device_id}")) };
            PAM_SUCCESS
        }
        logic::Outcome::Ignore(reason) => {
            if cfg.debug {
                unsafe { log(pamh, &format!("pam_fortis: ignoring ({reason})")) };
            }
            PAM_IGNORE
        }
    }
}

/// # Safety
/// Called by libpam with a valid handle and argument vector.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pam_sm_authenticate(
    pamh: *mut PamHandle,
    flags: c_int,
    argc: c_int,
    argv: *const *const c_char,
) -> c_int {
    catch_unwind(AssertUnwindSafe(|| unsafe { run(pamh, flags, argc, argv) })).unwrap_or(PAM_IGNORE)
}

/// # Safety
/// Called by libpam. Fortis sets no credentials.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pam_sm_setcred(
    _pamh: *mut PamHandle,
    _flags: c_int,
    _argc: c_int,
    _argv: *const *const c_char,
) -> c_int {
    PAM_IGNORE
}
