//! Small helpers: running external tools safely, user lookup, PAM trust file.

use std::ffi::OsString;
use std::path::{Path, PathBuf};
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

/// The graphical session's display variables, looked up at call time.
///
/// The agent starts at boot, before the desktop exists, so its own
/// environment normally has neither `WAYLAND_DISPLAY` nor `DISPLAY`. Use its
/// own value when set, otherwise find the sockets the compositor / X server
/// create in `$XDG_RUNTIME_DIR` and `/tmp/.X11-unix`.
pub fn display_env() -> Vec<(&'static str, OsString)> {
    let own = |k: &str| std::env::var_os(k).filter(|v| !v.is_empty());
    let runtime = std::env::var_os("XDG_RUNTIME_DIR").map(PathBuf::from);
    display_env_in(&own, runtime.as_deref(), Path::new("/tmp/.X11-unix"))
}

fn display_env_in(
    own: &dyn Fn(&str) -> Option<OsString>,
    runtime: Option<&Path>,
    x11_dir: &Path,
) -> Vec<(&'static str, OsString)> {
    use std::os::unix::fs::FileTypeExt;
    // `prefix<digits>` sockets in `dir`, lowest number first.
    let sockets = |dir: &Path, prefix: &str| -> Vec<(u32, String)> {
        let mut v: Vec<(u32, String)> = std::fs::read_dir(dir)
            .into_iter()
            .flatten()
            .flatten()
            .filter(|e| e.file_type().is_ok_and(|t| t.is_socket()))
            .filter_map(|e| {
                let name = e.file_name().into_string().ok()?;
                let n = name.strip_prefix(prefix)?.parse::<u32>().ok()?;
                Some((n, name))
            })
            .collect();
        v.sort();
        v
    };
    let mut out = Vec::new();
    let wayland = own("WAYLAND_DISPLAY").or_else(|| {
        let dir = runtime?;
        sockets(dir, "wayland-")
            .into_iter()
            .next()
            .map(|(_, n)| n.into())
    });
    if let Some(w) = wayland {
        out.push(("WAYLAND_DISPLAY", w));
    }
    let display = own("DISPLAY").or_else(|| {
        sockets(x11_dir, "X")
            .into_iter()
            .next()
            .map(|(n, _)| format!(":{n}").into())
    });
    if let Some(d) = display {
        out.push(("DISPLAY", d));
        // KDE/GNOME keep the X cookie in the runtime dir as xauth_*.
        let xauth = own("XAUTHORITY").or_else(|| {
            std::fs::read_dir(runtime?)
                .ok()?
                .flatten()
                .filter(|e| e.file_name().to_string_lossy().starts_with("xauth_"))
                .max_by_key(|e| e.metadata().and_then(|m| m.modified()).ok())
                .map(|e| e.path().into_os_string())
        });
        if let Some(x) = xauth {
            out.push(("XAUTHORITY", x));
        }
    }
    out
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
        .envs(display_env())
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
        .envs(display_env())
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
    fn display_env_found_after_boot() {
        use std::os::unix::net::UnixListener;
        let rt = tempfile::tempdir().unwrap();
        let x11 = tempfile::tempdir().unwrap();
        let none = |_: &str| None;
        let get = |v: &[(&str, OsString)], k: &str| {
            v.iter().find(|(n, _)| *n == k).map(|(_, x)| x.clone())
        };
        // Nothing running yet: nothing found.
        assert!(display_env_in(&none, Some(rt.path()), x11.path()).is_empty());
        // The desktop has started: its sockets are found; lock files and
        // plain files with the same names are ignored.
        let _w1 = UnixListener::bind(rt.path().join("wayland-1")).unwrap();
        let _w0 = UnixListener::bind(rt.path().join("wayland-0")).unwrap();
        std::fs::write(rt.path().join("wayland-0.lock"), "").unwrap();
        std::fs::write(x11.path().join("X5"), "").unwrap();
        let _x = UnixListener::bind(x11.path().join("X1")).unwrap();
        std::fs::write(rt.path().join("xauth_abc"), "").unwrap();
        let v = display_env_in(&none, Some(rt.path()), x11.path());
        assert_eq!(get(&v, "WAYLAND_DISPLAY"), Some("wayland-0".into()));
        assert_eq!(get(&v, "DISPLAY"), Some(":1".into()));
        assert_eq!(
            get(&v, "XAUTHORITY"),
            Some(rt.path().join("xauth_abc").into())
        );
        // The agent's own values win when set.
        let own = |k: &str| (k == "WAYLAND_DISPLAY").then(|| OsString::from("wayland-9"));
        let v = display_env_in(&own, Some(rt.path()), x11.path());
        assert_eq!(get(&v, "WAYLAND_DISPLAY"), Some("wayland-9".into()));
    }

    #[test]
    fn which_finds_sh() {
        assert!(which("sh").is_some());
        assert!(which("definitely-not-a-command-xyz").is_none());
    }
}
