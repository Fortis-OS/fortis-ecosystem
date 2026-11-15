//! `fortis doctor`: one screen that says whether every part of Fortis is working, and what to
//! do when something isn't. Works even when the agent is down (that is one of the checks).

use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::{Value, json};

use crate::entries::Res;
use crate::ipc::Client;

#[derive(Clone, Copy, PartialEq)]
enum Level {
    Ok,
    Warn,
    Fail,
}

struct Report {
    rows: Vec<(Level, &'static str, String)>,
}

impl Report {
    fn add(&mut self, l: Level, what: &'static str, msg: impl Into<String>) {
        self.rows.push((l, what, msg.into()));
    }
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

pub fn ago(secs: i64) -> String {
    match secs {
        s if s < 90 => "just now".into(),
        s if s < 90 * 60 => format!("{} min ago", s / 60),
        s if s < 48 * 3600 => format!("{} h ago", s / 3600),
        s => format!("{} days ago", s / 86400),
    }
}

fn home() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_default()
}

fn sh(prog: &str, args: &[&str]) -> Option<String> {
    let out = Command::new(prog).args(args).output().ok()?;
    out.status
        .success()
        .then(|| String::from_utf8_lossy(&out.stdout).into_owned())
}

/// `key=value` lines from `systemctl show`.
fn systemd_props(unit: &str, props: &[&str]) -> Vec<(String, String)> {
    let mut args = vec!["--user", "show", unit];
    for p in props {
        args.push("-p");
        args.push(p);
    }
    sh("systemctl", &args)
        .unwrap_or_default()
        .lines()
        .filter_map(|l| {
            l.split_once('=')
                .map(|(k, v)| (k.to_string(), v.to_string()))
        })
        .collect()
}

/// Value of `key = "…"` inside `[section]` of a small TOML file (enough for agent.toml).
pub fn toml_value(text: &str, section: &str, key: &str) -> Option<String> {
    let mut in_section = false;
    for line in text.lines() {
        let l = line.trim();
        if l.starts_with('[') {
            in_section = l == format!("[{section}]");
            continue;
        }
        if !in_section {
            continue;
        }
        if let Some((k, v)) = l.split_once('=')
            && k.trim() == key
        {
            let v = v.split('#').next().unwrap_or("").trim().trim_matches('"');
            return Some(v.to_string());
        }
    }
    None
}

/// `NAME="value"` from a shell config file.
pub fn shell_value(text: &str, name: &str) -> Option<String> {
    text.lines().find_map(|l| {
        let v = l.trim().strip_prefix(&format!("{name}="))?;
        Some(v.trim().trim_matches('"').to_string())
    })
}

fn expand(p: &str) -> PathBuf {
    match p.strip_prefix("~/") {
        Some(rest) => home().join(rest),
        None => PathBuf::from(p),
    }
}

fn agent_checks(r: &mut Report) {
    let mut c = match Client::connect() {
        Ok(c) => c,
        Err(_) => {
            r.add(
                Level::Fail,
                "Agent",
                "not running — systemctl --user start fortis-agent",
            );
            return;
        }
    };
    let s = c.call("status", json!({})).unwrap_or(Value::Null);
    let state = s["state"].as_str().unwrap_or("?");
    let lock = s["locks_in_secs"]
        .as_u64()
        .map(|n| format!(", locks in {} min", n / 60))
        .unwrap_or_default();
    r.add(Level::Ok, "Agent", format!("running (vault {state}{lock})"));

    match s["tailnet_ip"].as_str() {
        Some(ip) => r.add(Level::Ok, "Tailscale", ip.to_string()),
        None => r.add(
            Level::Warn,
            "Tailscale",
            "no Tailscale address — the phone can't reach the laptop (tailscale up)",
        ),
    }

    let devices = c.call("devices_list", json!({})).unwrap_or(Value::Null);
    let list = devices.as_array().cloned().unwrap_or_default();
    if list.is_empty() {
        r.add(Level::Fail, "Phone", "no phone paired — fortis pair");
    }
    for d in &list {
        let name = d["name"].as_str().unwrap_or("phone");
        if d["online"].as_bool() == Some(true) {
            r.add(Level::Ok, "Phone", format!("{name}: connected"));
        } else {
            let seen = d["last_seen"].as_i64().unwrap_or(0);
            let when = if seen > 1 {
                ago(now() - seen)
            } else {
                "not since the agent started".into()
            };
            r.add(
                Level::Warn,
                "Phone",
                format!(
                    "{name}: not connected (last seen {when}) — open the Fortis app on the phone"
                ),
            );
        }
    }

    let caps = c.call("capabilities", json!({})).unwrap_or(Value::Null);
    if caps.is_null() {
        r.add(
            Level::Warn,
            "Capabilities",
            "agent too old to report them — rebuild fortis-agent",
        );
        return;
    }
    let pam_line = std::fs::read_to_string("/etc/pam.d/sudo")
        .map(|t| t.lines().any(pam_line_enables_fortis))
        .unwrap_or(false);
    match (caps["sudo_approval"].as_bool() == Some(true), pam_line) {
        (true, true) => r.add(Level::Ok, "Sudo approval", "fingerprint sudo enabled"),
        (true, false) => r.add(
            Level::Warn,
            "Sudo approval",
            "phone enrolled, but sudo doesn't use Fortis — sudo fortis pam-install --sudo",
        ),
        (false, _) => r.add(
            Level::Warn,
            "Sudo approval",
            "no phone enrolled — sudo fortis pam-enroll",
        ),
    }
    match caps["input"].as_str() {
        Some(b) => r.add(Level::Ok, "Remote input", b.to_string()),
        None => r.add(
            Level::Warn,
            "Remote input",
            "no backend — Wayland needs ydotool + ydotoold (systemctl --user enable --now ydotool)",
        ),
    }
    match caps["clipboard"].as_str() {
        Some(t) => r.add(Level::Ok, "Clipboard", t.to_string()),
        None => r.add(
            Level::Warn,
            "Clipboard",
            "no tool — install wl-clipboard (Wayland) or xclip",
        ),
    }
}

/// `auth … pam_fortis.so` (a leading `-` just means "skip if the module is missing").
pub fn pam_line_enables_fortis(line: &str) -> bool {
    let l = line.trim_start().trim_start_matches('-');
    l.starts_with("auth") && l.contains("pam_fortis")
}

/// Where the vault would be inside the backup mirror (either naming scheme).
pub fn vault_in_mirror(mirror_secrets: &Path) -> Option<PathBuf> {
    [".local__share__fortis", "fortis"]
        .iter()
        .map(|n| mirror_secrets.join(n).join("vault.db"))
        .find(|p| p.is_file())
}

fn backup_checks(r: &mut Report) {
    let props = systemd_props(
        "fortis-backup.service",
        &[
            "Result",
            "ExecMainStatus",
            "ExecMainExitTimestamp",
            "ActiveState",
        ],
    );
    let get = |k: &str| {
        props
            .iter()
            .find(|(p, _)| p == k)
            .map(|(_, v)| v.as_str())
            .unwrap_or("")
    };
    if props.is_empty() {
        r.add(Level::Warn, "Backup", "fortis-backup.service not installed");
    } else if get("ActiveState") == "activating" {
        r.add(Level::Ok, "Backup", "running now");
    } else if get("ExecMainExitTimestamp").is_empty() {
        r.add(Level::Warn, "Backup", "has never run — fortis backup run");
    } else if get("Result") == "success" {
        r.add(
            Level::Ok,
            "Backup",
            format!("last run OK ({})", get("ExecMainExitTimestamp")),
        );
    } else {
        r.add(
            Level::Warn,
            "Backup",
            format!(
                "last run failed (exit {}, {}) — journalctl --user -u fortis-backup",
                get("ExecMainStatus"),
                get("ExecMainExitTimestamp")
            ),
        );
    }

    // Is the vault actually in the backup?
    let toml =
        std::fs::read_to_string(home().join(".config/fortis/agent.toml")).unwrap_or_default();
    let script = toml_value(&toml, "backup", "script").map(|s| expand(&s));
    let conf = script
        .as_deref()
        .and_then(Path::parent)
        .map(|d| d.join("backup.conf"))
        .and_then(|p| std::fs::read_to_string(p).ok());
    let Some(root) = conf.as_deref().and_then(|c| shell_value(c, "BACKUP_ROOT")) else {
        r.add(
            Level::Warn,
            "Vault backup",
            "can't find backup.conf (agent.toml [backup] script)",
        );
        return;
    };
    let live = home().join(".local/share/fortis/vault.db");
    match vault_in_mirror(&Path::new(&root).join("Mirror/Secrets")) {
        None => r.add(
            Level::Fail,
            "Vault backup",
            format!("vault.db is NOT in {root}/Mirror/Secrets — add ~/.local/share/fortis to SECRETS in backup.conf"),
        ),
        Some(copy) => {
            let mtime = |p: &Path| std::fs::metadata(p).and_then(|m| m.modified()).ok();
            if mtime(&live) > mtime(&copy) {
                r.add(Level::Warn, "Vault backup", "the vault changed since the last backup (next run copies it)");
            } else {
                r.add(Level::Ok, "Vault backup", copy.display().to_string());
            }
        }
    }
}

fn browser_checks(r: &mut Report) {
    let h = home();
    let candidates = [
        ".config/BraveSoftware/Brave-Browser/NativeMessagingHosts",
        ".config/google-chrome/NativeMessagingHosts",
        ".config/chromium/NativeMessagingHosts",
        ".mozilla/native-messaging-hosts",
    ];
    let found: Vec<&str> = candidates
        .iter()
        .filter(|d| h.join(d).join("fortis_browser_host.json").is_file())
        .map(|d| match d.split('/').nth(1).unwrap_or(d) {
            "BraveSoftware" => "Brave",
            "google-chrome" => "Chrome",
            "chromium" => "Chromium",
            _ => "Firefox",
        })
        .collect();
    if found.is_empty() {
        r.add(
            Level::Warn,
            "Browser autofill",
            "native host not registered — browser/install.sh",
        );
    } else {
        r.add(
            Level::Ok,
            "Browser autofill",
            format!("host registered for {}", found.join(", ")),
        );
    }
}

pub fn run() -> Res<()> {
    let mut r = Report { rows: Vec::new() };
    agent_checks(&mut r);
    backup_checks(&mut r);
    browser_checks(&mut r);

    println!("Fortis doctor");
    for (l, what, msg) in &r.rows {
        let icon = match l {
            Level::Ok => "✔",
            Level::Warn => "⚠",
            Level::Fail => "✖",
        };
        println!("  {icon} {what:<17} {msg}");
    }
    let fails = r.rows.iter().filter(|(l, ..)| *l == Level::Fail).count();
    let warns = r.rows.iter().filter(|(l, ..)| *l == Level::Warn).count();
    println!();
    if fails > 0 {
        return Err(format!("{fails} problem(s), {warns} warning(s)").into());
    }
    println!(
        "{}",
        if warns == 0 {
            "All good."
        } else {
            "No problems; see the warnings above."
        }
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_toml_and_shell_values() {
        let t = "[rest]\nport = 8023\n[backup]\nscript = \"~/x/fortis-backup.sh\" # comment\n";
        assert_eq!(
            toml_value(t, "backup", "script").as_deref(),
            Some("~/x/fortis-backup.sh")
        );
        assert_eq!(toml_value(t, "rest", "script"), None);
        let c = "# c\nBACKUP_ROOT=\"/mnt/FortisVault\"\n";
        assert_eq!(
            shell_value(c, "BACKUP_ROOT").as_deref(),
            Some("/mnt/FortisVault")
        );
    }

    #[test]
    fn pam_lines() {
        assert!(pam_line_enables_fortis(
            "-auth   sufficient    pam_fortis.so"
        ));
        assert!(pam_line_enables_fortis("auth sufficient pam_fortis.so"));
        assert!(!pam_line_enables_fortis("# auth sufficient pam_fortis.so"));
        assert!(!pam_line_enables_fortis("@include common-auth"));
    }

    #[test]
    fn ago_text() {
        assert_eq!(ago(30), "just now");
        assert_eq!(ago(600), "10 min ago");
        assert_eq!(ago(5 * 3600), "5 h ago");
        assert_eq!(ago(3 * 86400), "3 days ago");
    }

    #[test]
    fn finds_vault_under_either_name() {
        let d = std::env::temp_dir().join(format!("fortis-doctor-{}", std::process::id()));
        std::fs::create_dir_all(d.join(".local__share__fortis")).unwrap();
        assert!(vault_in_mirror(&d).is_none());
        std::fs::write(d.join(".local__share__fortis/vault.db"), b"x").unwrap();
        assert!(vault_in_mirror(&d).is_some());
        std::fs::remove_dir_all(&d).unwrap();
    }
}
