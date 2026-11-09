//! Copy a secret to the clipboard and clear it after 30 s — but only if the
//! clipboard still holds that secret (we compare hashes, never store it).

use std::io::Write;
use std::process::{Command, Stdio};
use std::time::Duration;

use sha2::{Digest, Sha256};

pub const CLEAR_AFTER: Duration = Duration::from_secs(30);

fn on_wayland() -> bool {
    std::env::var_os("WAYLAND_DISPLAY").is_some_and(|v| !v.is_empty())
}

fn hash(s: &[u8]) -> String {
    hex(&Sha256::digest(s))
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn write_clipboard(data: &[u8]) -> std::io::Result<()> {
    let mut cmd = if on_wayland() {
        Command::new("wl-copy")
    } else {
        let mut c = Command::new("xclip");
        c.args(["-selection", "clipboard"]);
        c
    };
    let mut child = cmd
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()?;
    child.stdin.take().expect("piped").write_all(data)?;
    // wl-copy / xclip fork to keep serving the selection.
    child.wait()?;
    Ok(())
}

fn read_clipboard() -> Option<Vec<u8>> {
    let out = if on_wayland() {
        Command::new("wl-paste")
            .arg("--no-newline")
            .stderr(Stdio::null())
            .output()
            .ok()?
    } else {
        Command::new("xclip")
            .args(["-o", "-selection", "clipboard"])
            .stderr(Stdio::null())
            .output()
            .ok()?
    };
    out.status.success().then_some(out.stdout)
}

/// Copy `secret` and start a detached helper that clears it after 30 s.
pub fn copy_secret(secret: &str) -> std::io::Result<()> {
    write_clipboard(secret.as_bytes())?;
    let exe = std::env::current_exe()?;
    Command::new(exe)
        .args(["__clear-clipboard", &hash(secret.as_bytes())])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()?;
    Ok(())
}

/// Hidden subcommand body.
pub fn clear_later(expected_hash: &str) {
    std::thread::sleep(CLEAR_AFTER);
    if read_clipboard().is_some_and(|c| hash(&c) == expected_hash) {
        let _ = if on_wayland() {
            Command::new("wl-copy").arg("--clear").status().map(|_| ())
        } else {
            write_clipboard(b"")
        };
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn hash_is_hex_sha256() {
        assert_eq!(super::hash(b"").len(), 64);
        assert_ne!(super::hash(b"a"), super::hash(b"b"));
    }
}
