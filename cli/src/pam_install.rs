//! `sudo fortis pam-install [--sudo]`, `sudo fortis pam-test`,
//! `sudo fortis pam-uninstall` — the lockout-safe install flow (security §4.7).

use std::ffi::{CStr, CString, c_char, c_int, c_void};
use std::fs;
use std::io::{Read, Write};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

use crate::entries::Res;
use crate::prompt;

pub const FORTIS_LINE: &str = "-auth   sufficient    pam_fortis.so";
const MARK: &str = "pam_fortis.so";

/// Filesystem roots, injectable for tests.
#[derive(Clone, Debug)]
pub struct Layout {
    pub pam_d: PathBuf,
    pub module_dirs: Vec<PathBuf>,
    pub fortis_etc: PathBuf,
}

impl Layout {
    pub fn system() -> Self {
        Self {
            pam_d: "/etc/pam.d".into(),
            module_dirs: [
                "/usr/lib/x86_64-linux-gnu/security",
                "/lib/x86_64-linux-gnu/security",
                "/usr/lib/aarch64-linux-gnu/security",
                "/lib/aarch64-linux-gnu/security",
                "/usr/lib64/security",
                "/lib64/security",
                "/usr/lib/security",
                "/lib/security",
            ]
            .iter()
            .map(PathBuf::from)
            .collect(),
            fortis_etc: "/etc/fortis".into(),
        }
    }
    fn sudo(&self) -> PathBuf {
        self.pam_d.join("sudo")
    }
    fn backup(&self) -> PathBuf {
        self.pam_d.join("sudo.fortis-backup")
    }
    fn test_service(&self) -> PathBuf {
        self.pam_d.join("fortis-test")
    }
    fn test_marker(&self) -> PathBuf {
        self.fortis_etc.join("pam-test.ok")
    }
}

fn require_root() -> Res<()> {
    if unsafe { libc::geteuid() } != 0 {
        return Err("this edits /etc/pam.d and must be run with sudo (or pkexec)".into());
    }
    Ok(())
}

/// The directory libpam loads modules from = the one holding pam_unix.so.
pub fn module_dir(l: &Layout) -> Option<PathBuf> {
    l.module_dirs
        .iter()
        .find(|d| d.join("pam_unix.so").exists())
        .cloned()
}

/// pam_fortis.so must be a regular root-owned file, not group/world writable.
pub fn check_module(l: &Layout, owner: u32) -> Res<PathBuf> {
    let dir =
        module_dir(l).ok_or("cannot find the system PAM module directory (no pam_unix.so)")?;
    let path = dir.join("pam_fortis.so");
    let m = fs::symlink_metadata(&path).map_err(|_| {
        format!("module not found at {} — install it first:\n  sudo install -o root -g root -m 0644 target/release/libfortis_pam.so {}", path.display(), path.display())
    })?;
    if !m.is_file() {
        return Err(format!("{} must be a regular file (not a symlink)", path.display()).into());
    }
    if m.uid() != owner {
        return Err(format!("{} is not owned by root", path.display()).into());
    }
    if m.mode() & 0o022 != 0 {
        return Err(format!(
            "{} is writable by others — reinstall with -m 0644",
            path.display()
        )
        .into());
    }
    Ok(path)
}

pub fn sha256_file(p: &Path) -> Res<String> {
    let mut f = fs::File::open(p)?;
    let mut h = Sha256::new();
    let mut buf = [0u8; 8192];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(h.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

/// Atomic write: temp file in the same dir, fsync, rename; mode 0644.
pub fn write_atomic(path: &Path, content: &str) -> std::io::Result<()> {
    let tmp = path.with_file_name(format!(
        ".{}.fortis-tmp",
        path.file_name().and_then(|n| n.to_str()).unwrap_or("file")
    ));
    {
        let mut f = fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o644)
            .open(&tmp)?;
        f.write_all(content.as_bytes())?;
        f.sync_all()?;
    }
    fs::set_permissions(&tmp, fs::Permissions::from_mode(0o644))?;
    fs::rename(&tmp, path)
}

fn is_auth_line(line: &str) -> bool {
    let t: Vec<&str> = line.split_whitespace().collect();
    match t.as_slice() {
        [first, ..] if first.starts_with('#') => false,
        ["auth" | "-auth", ..] => true,
        ["@include", name, ..] => matches!(*name, "common-auth" | "system-auth" | "password-auth"),
        _ => false,
    }
}

/// Insert the Fortis line directly above the first auth line.
pub fn insert_line(original: &str) -> Res<String> {
    if original.contains(MARK) {
        return Err(
            "pam_fortis is already in this file — run `sudo fortis pam-uninstall` first".into(),
        );
    }
    let lines: Vec<&str> = original.lines().collect();
    let i = lines
        .iter()
        .position(|l| is_auth_line(l))
        .ok_or("no auth line found to insert before")?;
    let mut out: Vec<&str> = lines[..i].to_vec();
    out.push(FORTIS_LINE);
    out.extend(&lines[i..]);
    let mut s = out.join("\n");
    if original.ends_with('\n') || original.is_empty() {
        s.push('\n');
    }
    Ok(s)
}

pub fn remove_lines(content: &str) -> String {
    let mut s = content
        .lines()
        .filter(|l| !l.contains(MARK))
        .collect::<Vec<_>>()
        .join("\n");
    if content.ends_with('\n') {
        s.push('\n');
    }
    s
}

/// The new file must be the original plus exactly our one line, above every auth line.
pub fn verify_inserted(original: &str, new: &str) -> bool {
    let ours: Vec<usize> = new
        .lines()
        .enumerate()
        .filter(|(_, l)| l.contains(MARK))
        .map(|(i, _)| i)
        .collect();
    let [idx] = ours.as_slice() else { return false };
    if new.lines().nth(*idx) != Some(FORTIS_LINE) {
        return false;
    }
    let first_auth = new
        .lines()
        .position(|l| is_auth_line(l) && !l.contains(MARK));
    if first_auth.is_none_or(|a| a < *idx) {
        return false;
    }
    remove_lines(new) == original
}

pub fn test_service_content() -> String {
    format!(
        "#%PAM-1.0\n# Written by `fortis pam-install` for `sudo fortis pam-test`. Safe to delete.\n\
         {FORTIS_LINE} services=fortis-test\nauth    required      pam_deny.so\naccount required      pam_permit.so\n"
    )
}

// ---------------------------------------------------------------------------

pub fn install(l: &Layout, enable_sudo: bool, yes: bool) -> Res<()> {
    let module = check_module(l, 0)?;
    if !enable_sudo {
        write_atomic(&l.test_service(), &test_service_content())?;
        println!(
            "Stage 1 done: wrote {} (sudo is unchanged).",
            l.test_service().display()
        );
        println!("Next: sudo fortis pam-test   (approve on the phone, then run it again and deny)");
        return Ok(());
    }

    // Stage 2 preconditions.
    let marker = fs::read_to_string(l.test_marker())
        .map_err(|_| "run `sudo fortis pam-test` (and approve) before enabling sudo")?;
    let hash = sha256_file(&module)?;
    if !marker
        .lines()
        .any(|line| line.trim() == format!("sha256={hash}"))
    {
        return Err(
            "the installed module changed since the last passing `pam-test` — run it again".into(),
        );
    }
    let sudo = l.sudo();
    let original = fs::read_to_string(&sudo)?;
    if original.lines().any(|x| x.trim() == FORTIS_LINE) {
        println!("Already enabled in {}.", sudo.display());
        return Ok(());
    }
    let new = insert_line(&original)?;
    if !verify_inserted(&original, &new) {
        return Err("internal check failed; nothing was changed".into());
    }
    eprintln!(
        "This adds one line to {} above the first auth line:\n  {FORTIS_LINE}",
        sudo.display()
    );
    eprintln!("Keep a `sudo -s` shell open in another terminal until you've tested it.");
    if !yes && !prompt::confirm("Enable fingerprint sudo?")? {
        return Err("cancelled".into());
    }
    if !l.backup().exists() {
        fs::copy(&sudo, l.backup())?;
        fs::set_permissions(l.backup(), fs::Permissions::from_mode(0o644))?;
    }
    write_atomic(&sudo, &new)?;
    let reread = fs::read_to_string(&sudo)?;
    if !verify_inserted(&original, &reread) {
        write_atomic(&sudo, &original)?;
        return Err("verification after writing failed; the original file was restored".into());
    }
    println!("Enabled. Backup: {}", l.backup().display());
    println!(
        "Test in a NEW terminal:  sudo -k && sudo true   (approve, then try again and deny → password prompt)"
    );
    println!(
        "Undo any time:           sudo fortis pam-uninstall   (or pkexec fortis pam-uninstall)"
    );
    Ok(())
}

pub fn uninstall(l: &Layout) -> Res<()> {
    let sudo = l.sudo();
    let original = fs::read_to_string(&sudo)?;
    if original.contains(MARK) {
        let new = remove_lines(&original);
        write_atomic(&sudo, &new)?;
        if fs::read_to_string(&sudo)?.contains(MARK) {
            return Err(
                "pam_fortis is still in the file after writing — restore the backup manually"
                    .into(),
            );
        }
        println!("Removed pam_fortis from {}.", sudo.display());
    } else {
        println!("pam_fortis was not in {}.", sudo.display());
    }
    for p in [l.test_service(), l.test_marker()] {
        if p.exists() {
            fs::remove_file(&p)?;
            println!("Removed {}.", p.display());
        }
    }
    Ok(())
}

pub fn run_install(enable_sudo: bool, yes: bool) -> Res<()> {
    require_root()?;
    install(&Layout::system(), enable_sudo, yes)
}

pub fn run_uninstall() -> Res<()> {
    require_root()?;
    uninstall(&Layout::system())
}

// ---- pam-test: a real PAM conversation against the fortis-test service ------

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
    fn pam_start(
        service: *const c_char,
        user: *const c_char,
        conv: *const PamConv,
        pamh: *mut *mut c_void,
    ) -> c_int;
    fn pam_authenticate(pamh: *mut c_void, flags: c_int) -> c_int;
    fn pam_end(pamh: *mut c_void, status: c_int) -> c_int;
}

unsafe extern "C" fn print_conv(
    n: c_int,
    msgs: *mut *const PamMessage,
    resp: *mut *mut PamResponse,
    _: *mut c_void,
) -> c_int {
    // SAFETY: libpam passes `n` valid messages; we return a calloc'd response array.
    unsafe {
        for i in 0..n.max(0) as usize {
            let m = &**msgs.add(i);
            if !m.msg.is_null() {
                eprintln!("  {}", CStr::from_ptr(m.msg).to_string_lossy());
            }
        }
        *resp =
            libc::calloc(n.max(1) as usize, std::mem::size_of::<PamResponse>()) as *mut PamResponse;
    }
    0
}

pub fn run_test() -> Res<()> {
    require_root()?;
    let l = Layout::system();
    let module = check_module(&l, 0)?;
    if !l.test_service().exists() {
        return Err(
            "run `sudo fortis pam-install` first (it writes the fortis-test service)".into(),
        );
    }
    let user =
        std::env::var("SUDO_USER").map_err(|_| "run this through sudo from your own account")?;
    let (svc, u) = (CString::new("fortis-test")?, CString::new(user.clone())?);
    let conv = PamConv {
        conv: print_conv,
        appdata: std::ptr::null_mut(),
    };
    let mut h: *mut c_void = std::ptr::null_mut();
    eprintln!(
        "Testing pam_fortis for {user} through the fortis-test service (sudo is not involved)…"
    );
    // SAFETY: standard libpam client calls with valid pointers; handle ended below.
    let rc = unsafe {
        if pam_start(svc.as_ptr(), u.as_ptr(), &conv, &mut h) != 0 {
            return Err("pam_start failed".into());
        }
        let r = pam_authenticate(h, 0);
        pam_end(h, r);
        r
    };
    if rc == 0 {
        fs::create_dir_all(&l.fortis_etc)?;
        let marker = format!(
            "sha256={}\ntested_at={}\nuser={user}\n",
            sha256_file(&module)?,
            fortis_core::unix_now()
        );
        write_atomic(&l.test_marker(), &marker)?;
        println!("PASS — the phone approved and PAM accepted it.");
        println!(
            "Now check the fallback: run `sudo fortis pam-test` again and DENY on the phone (expect FALLBACK OK)."
        );
        println!("Then enable it for sudo:  sudo fortis pam-install --sudo");
    } else {
        println!(
            "FALLBACK OK — Fortis stepped aside and PAM continued to the next module (pam_deny in this test)."
        );
        println!(
            "That's what happens on deny, timeout, or any error. To pass, approve on the phone."
        );
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const UBUNTU: &str = "#%PAM-1.0\n\n# Set up user limits\nsession    required   pam_limits.so\n\n@include common-auth\n@include common-account\n@include common-session-noninteractive\n";
    const FEDORA: &str = "#%PAM-1.0\nauth       include      system-auth\naccount    include      system-auth\npassword   include      system-auth\n";

    #[test]
    fn inserts_above_first_auth_line() {
        let n = insert_line(UBUNTU).unwrap();
        let lines: Vec<&str> = n.lines().collect();
        let f = lines.iter().position(|l| *l == FORTIS_LINE).unwrap();
        assert_eq!(lines[f + 1], "@include common-auth");
        assert!(verify_inserted(UBUNTU, &n));
        assert_eq!(remove_lines(&n), UBUNTU);

        let n = insert_line(FEDORA).unwrap();
        assert_eq!(n.lines().nth(1), Some(FORTIS_LINE));
        assert!(verify_inserted(FEDORA, &n));
    }

    #[test]
    fn refuses_bad_states() {
        assert!(insert_line("#%PAM-1.0\nsession required pam_limits.so\n").is_err());
        let once = insert_line(UBUNTU).unwrap();
        assert!(insert_line(&once).is_err());
        // Wrong form or position is caught.
        assert!(!verify_inserted(
            UBUNTU,
            &once.replace(FORTIS_LINE, "auth sufficient pam_fortis.so")
        ));
        assert!(!verify_inserted(
            UBUNTU,
            &format!("{UBUNTU}{FORTIS_LINE}\n")
        ));
        assert!(!verify_inserted(
            UBUNTU,
            &once.replace("pam_limits", "pam_nope")
        ));
        // A commented-out auth line is not an insertion point.
        let c = "#%PAM-1.0\n# auth sufficient pam_x.so\n@include common-auth\n";
        assert_eq!(insert_line(c).unwrap().lines().nth(2), Some(FORTIS_LINE));
    }

    fn layout(t: &Path) -> Layout {
        let mods = t.join("lib/security");
        fs::create_dir_all(&mods).unwrap();
        fs::write(mods.join("pam_unix.so"), "").unwrap();
        fs::create_dir_all(t.join("pam.d")).unwrap();
        Layout {
            pam_d: t.join("pam.d"),
            module_dirs: vec![t.join("nope"), mods],
            fortis_etc: t.join("fortis"),
        }
    }

    #[test]
    fn full_flow_on_a_fake_root() {
        let t = tempfile::tempdir().unwrap();
        let l = layout(t.path());
        let me = unsafe { libc::getuid() };
        fs::write(l.sudo(), UBUNTU).unwrap();

        // No module yet.
        assert!(
            check_module(&l, me)
                .unwrap_err()
                .to_string()
                .contains("module not found")
        );
        let m = module_dir(&l).unwrap().join("pam_fortis.so");
        fs::write(&m, "elf").unwrap();
        fs::set_permissions(&m, fs::Permissions::from_mode(0o666)).unwrap();
        assert!(
            check_module(&l, me)
                .unwrap_err()
                .to_string()
                .contains("writable")
        );
        fs::set_permissions(&m, fs::Permissions::from_mode(0o644)).unwrap();
        check_module(&l, me).unwrap();
        // (install() itself checks owner 0, so drive the steps it performs.)

        let new = insert_line(UBUNTU).unwrap();
        write_atomic(&l.sudo(), &new).unwrap();
        assert!(verify_inserted(
            UBUNTU,
            &fs::read_to_string(l.sudo()).unwrap()
        ));
        assert_eq!(
            fs::metadata(l.sudo()).unwrap().permissions().mode() & 0o777,
            0o644
        );
        fs::write(l.test_service(), test_service_content()).unwrap();
        uninstall(&l).unwrap();
        assert_eq!(fs::read_to_string(l.sudo()).unwrap(), UBUNTU);
        assert!(!l.test_service().exists());
    }

    #[test]
    fn test_service_uses_dash_and_deny() {
        let s = test_service_content();
        assert!(s.contains("-auth   sufficient    pam_fortis.so services=fortis-test"));
        assert!(s.contains("pam_deny.so"));
    }
}
