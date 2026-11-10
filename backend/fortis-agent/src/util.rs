//! Small helpers: running external tools safely, user lookup, PAM trust file.

use std::path::PathBuf;
use std::process::Stdio;
use std::time::Duration;

use tokio::process::Command;

/// Find an executable on `PATH` (no shell).
pub fn which(cmd: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path).map(|d| d.join(cmd)).find(|p| {
        use std::os::unix::fs::PermissionsExt;
        std::fs::metadata(p).is_ok_and(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
    })
}

#[derive(Debug)]
pub struct Output {
    pub status_ok: bool,
    pub stdout: String,
}

/// Run `program args…` with a fixed argument vector (never a shell),
/// no stdin, 10 s timeout.
pub async fn run(program: &str, args: &[&str]) -> std::io::Result<Output> {
    let child = Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()?;
    let out = tokio::time::timeout(Duration::from_secs(10), child.wait_with_output())
        .await
        .map_err(|_| {
            std::io::Error::new(std::io::ErrorKind::TimedOut, format!("{program} timed out"))
        })??;
    Ok(Output {
        status_ok: out.status.success(),
        stdout: String::from_utf8_lossy(&out.stdout).into_owned(),
    })
}

/// Start a detached process (fixed args, no shell, output discarded).
pub fn spawn_detached(program: &str, args: &[&str]) -> std::io::Result<()> {
    Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map(|_| ())
}

/// The agent user's login name.
pub fn username() -> String {
    // SAFETY: getpwuid returns a pointer into static storage or null; we copy
    // the name out immediately and don't hold the pointer.
    unsafe {
        let pw = libc::getpwuid(libc::getuid());
        if !pw.is_null()
            && !(*pw).pw_name.is_null()
            && let Ok(s) = std::ffi::CStr::from_ptr((*pw).pw_name).to_str()
        {
            return s.to_string();
        }
    }
    std::env::var("USER").unwrap_or_else(|_| "user".into())
}

pub fn pam_trust_file() -> PathBuf {
    PathBuf::from("/etc/fortis/pam").join(format!("{}.json", username()))
}

/// Read the root-owned PAM trust file (security §4.2), if present and parseable.
pub fn pam_trust_devices() -> Vec<String> {
    let Ok(s) = std::fs::read_to_string(pam_trust_file()) else {
        return vec![];
    };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&s) else {
        return vec![];
    };
    v["devices"]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(|d| d["device_id"].as_str().map(String::from))
                .collect()
        })
        .unwrap_or_default()
}

pub fn pam_lists_device(id: &uuid::Uuid) -> bool {
    pam_trust_devices().iter().any(|d| d == &id.to_string())
}

/// Remove control characters and escape notify-send markup.
pub fn sanitize_display(s: &str, max_chars: usize) -> String {
    s.chars()
        .filter(|c| !c.is_control())
        .take(max_chars)
        .collect::<String>()
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

/// Show a desktop notification (best effort).
pub async fn notify(title: &str, body: &str, critical: bool) -> bool {
    if which("notify-send").is_none() {
        return false;
    }
    let title = sanitize_display(title, 120);
    let body = sanitize_display(body, 500);
    let urgency = if critical {
        "--urgency=critical"
    } else {
        "--urgency=normal"
    };
    run(
        "notify-send",
        &["--app-name=Fortis", urgency, "--", &title, &body],
    )
    .await
    .is_ok_and(|o| o.status_ok)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitize() {
        assert_eq!(sanitize_display("a<b>&\x07\nc", 100), "a&lt;b&gt;&amp;c");
        assert_eq!(sanitize_display("abcdef", 3), "abc");
    }

    #[test]
    fn which_finds_sh() {
        assert!(which("sh").is_some());
        assert!(which("definitely-not-a-command-xyz").is_none());
    }
}
