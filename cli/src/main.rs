//! `fortis` — terminal client for fortis-agent (docs/development §3).
//!
//! Talks to the agent over its Unix socket only. Secrets are printed only
//! when explicitly asked for with `--field` (or `--json`).

use std::process::ExitCode;
use std::time::Duration;

use clap::{Parser, Subcommand};
use serde_json::{Value, json};

use fortis_core::password_gen::{self, PasswordOptions};
use fortis_core::vault::{EntryInput, VaultEntry};

mod clipboard;
mod doctor;
mod entries;
mod ipc;
mod pam;
mod pam_install;
mod prompt;

use entries::{Kind, Res};
use ipc::Client;

const MIN_PASSWORD_CHARS: usize = 12;

#[derive(Parser)]
#[command(
    name = "fortis",
    version,
    about = "Fortis password vault and agent control"
)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Agent and vault status
    Status,
    /// Check every part of Fortis and say what to fix
    Doctor,
    /// Create a new vault
    Init {
        /// Read the password from stdin instead of prompting
        #[arg(long)]
        password_stdin: bool,
    },
    /// Unlock the vault
    Unlock {
        #[arg(long)]
        password_stdin: bool,
    },
    /// Lock the vault now
    Lock,
    /// List entries (no secrets)
    #[command(alias = "ls")]
    List {
        /// Filter by title, username, URL or folder
        query: Option<String>,
        #[arg(long = "type", value_enum)]
        kind: Option<Kind>,
        #[arg(long)]
        json: bool,
    },
    /// Show an entry; secrets only with --field
    Get {
        /// Title, ID, or the 8-character ID prefix shown by `list`
        name: String,
        /// password | username | url | totp | notes | number | cvv | content | email
        #[arg(long, short)]
        field: Option<String>,
        /// Copy the field (default: password) to the clipboard; cleared after 30 s
        #[arg(long, short)]
        copy: bool,
        /// Print the full entry as JSON, including secrets
        #[arg(long, conflicts_with_all = ["field", "copy"])]
        json: bool,
    },
    /// Add an entry (interactive)
    Add {
        #[arg(long = "type", value_enum, default_value = "login")]
        kind: Kind,
        /// Read an EntryInput JSON object from a file, or `-` for stdin
        #[arg(long)]
        from_json: Option<String>,
    },
    /// Edit an entry (interactive; Enter keeps the current value)
    Edit { name: String },
    /// Delete an entry
    #[command(alias = "delete")]
    Rm {
        name: String,
        #[arg(long, short)]
        yes: bool,
    },
    /// Generate a password (works without the agent)
    Generate {
        #[arg(long, short, default_value_t = 20)]
        length: usize,
        #[arg(long)]
        no_uppercase: bool,
        #[arg(long)]
        no_numbers: bool,
        #[arg(long)]
        no_symbols: bool,
        #[arg(long, short)]
        copy: bool,
    },
    /// Pair a phone (shows a QR code)
    Pair,
    /// Paired phones
    #[command(subcommand)]
    Devices(DevicesCmd),
    /// Remote-control sessions started from a phone
    #[command(subcommand)]
    Sessions(SessionsCmd),
    /// Backups
    #[command(subcommand)]
    Backup(BackupCmd),
    /// Allow paired phones to approve sudo (run with sudo)
    PamEnroll {
        #[arg(long, short)]
        yes: bool,
    },
    /// Stage 1: write the fortis-test PAM service; with --sudo, stage 2: enable for sudo (run with sudo)
    PamInstall {
        /// Edit /etc/pam.d/sudo (only after a passing `pam-test`)
        #[arg(long)]
        sudo: bool,
        #[arg(long, short)]
        yes: bool,
    },
    /// Run a real PAM conversation against the fortis-test service (run with sudo)
    PamTest,
    /// Remove pam_fortis from /etc/pam.d/sudo and the test service (sudo or pkexec)
    PamUninstall,
    #[command(name = "__clear-clipboard", hide = true)]
    ClearClipboard { hash: String },
}

#[derive(Subcommand)]
enum DevicesCmd {
    #[command(alias = "ls")]
    List,
    Revoke {
        device_id: String,
        #[arg(long, short)]
        yes: bool,
    },
}

#[derive(Subcommand)]
enum SessionsCmd {
    #[command(alias = "ls")]
    List,
    /// End one session, or all
    End { session_id: Option<String> },
}

#[derive(Subcommand)]
enum BackupCmd {
    /// Start a backup now
    Run,
}

fn fmt_age(secs: i64) -> String {
    let d = fortis_core::unix_now() - secs;
    match d {
        i64::MIN..=59 => "just now".into(),
        60..=3599 => format!("{} min ago", d / 60),
        3600..=86_399 => format!("{} h ago", d / 3600),
        _ => format!("{} days ago", d / 86_400),
    }
}

/// Title, full ID, or unique ID prefix → entry.
fn resolve(c: &mut Client, name: &str) -> Res<VaultEntry> {
    let by_id = |c: &mut Client, id: &str| -> Res<VaultEntry> {
        Ok(serde_json::from_value(c.call("get", json!({ "id": id }))?)?)
    };
    if uuid::Uuid::parse_str(name).is_ok() {
        return by_id(c, name);
    }
    if name.len() >= 6 && name.bytes().all(|b| b.is_ascii_hexdigit()) {
        let list = c.call("list", json!({}))?;
        let hits: Vec<String> = list
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|e| e["id"].as_str())
            .filter(|id| id.starts_with(&name.to_lowercase()))
            .map(String::from)
            .collect();
        if hits.len() == 1 {
            return by_id(c, &hits[0]);
        }
    }
    match c.call("get", json!({ "title": name })) {
        Ok(v) => Ok(serde_json::from_value(v)?),
        Err(ipc::IpcError::Agent { code, .. }) if code == "not_found" => {
            // Fall back to a unique case-insensitive title match.
            let list = c.call("list", json!({}))?;
            let hits: Vec<String> = list
                .as_array()
                .into_iter()
                .flatten()
                .filter(|e| {
                    e["title"]
                        .as_str()
                        .is_some_and(|t| t.eq_ignore_ascii_case(name))
                })
                .filter_map(|e| e["id"].as_str().map(String::from))
                .collect();
            match hits.as_slice() {
                [id] => by_id(c, id),
                [] => Err(format!("no entry named \"{name}\" — try `fortis list {name}`").into()),
                _ => Err(format!(
                    "several entries are named \"{name}\" — use the ID from `fortis list`"
                )
                .into()),
            }
        }
        Err(e) => Err(e.into()),
    }
}

fn read_master(stdin: bool, new: bool) -> Res<zeroize::Zeroizing<String>> {
    let p = match (stdin, new) {
        (true, _) => prompt::secret("")?,
        (false, true) => prompt::new_secret("New master password: ")?,
        (false, false) => prompt::secret("Master password: ")?,
    };
    if new && p.chars().count() < MIN_PASSWORD_CHARS {
        return Err(format!(
            "the master password must be at least {MIN_PASSWORD_CHARS} characters"
        )
        .into());
    }
    Ok(p)
}

fn status(c: &mut Client) -> Res<()> {
    let s = c.call("status", json!({}))?;
    println!("vault        {}", s["state"].as_str().unwrap_or("?"));
    if let Some(n) = s["locks_in_secs"].as_u64() {
        println!("auto-lock    in {} min {} s", n / 60, n % 60);
    }
    println!(
        "tailnet      {}",
        s["tailnet_ip"].as_str().unwrap_or("not connected")
    );
    println!(
        "phones       {} paired, {} online",
        s["paired_devices"], s["online_devices"]
    );
    println!("sessions     {} active", s["active_sessions"]);
    println!(
        "agent key    {}",
        s["agent_fingerprint"].as_str().unwrap_or("?")
    );
    Ok(())
}

fn pair(c: &mut Client) -> Res<()> {
    let (id, d) = c.call_streaming("pair_start", json!({}))?;
    let uri = d["qr_uri"].as_str().unwrap_or("");
    if d["tailnet_ip"].as_str().unwrap_or("").is_empty() {
        eprintln!(
            "warning: the agent has no Tailscale address yet — the phone won't be able to connect."
        );
    }
    if let Ok(code) = qrcode::QrCode::new(uri.as_bytes()) {
        use qrcode::render::unicode::Dense1x2;
        let img = code
            .render::<Dense1x2>()
            .dark_color(Dense1x2::Light)
            .light_color(Dense1x2::Dark)
            .quiet_zone(true)
            .build();
        println!("{img}");
    }
    println!("Scan with the Fortis app, or enter:");
    println!("  code       {}", d["code"].as_str().unwrap_or("?"));
    println!("  laptop     {}", d["tailnet_ip"].as_str().unwrap_or("?"));
    println!(
        "  agent key  {}",
        d["agent_fingerprint"].as_str().unwrap_or("?")
    );
    println!("Valid for 2 minutes, one use. Waiting for the phone…");
    c.set_timeout(Duration::from_secs(125))?;
    match c.read_reply(id) {
        Ok(ev) if ev["event"] == "paired" => {
            let dev = &ev["device"];
            println!();
            println!("Paired: {}", dev["name"].as_str().unwrap_or("?"));
            println!(
                "  request key   {}",
                dev["request_key_fp"].as_str().unwrap_or("?")
            );
            println!(
                "  approval key  {}",
                dev["approval_key_fp"].as_str().unwrap_or("?")
            );
            println!(
                "Check these match the phone. If they don't, revoke it:  fortis devices revoke {}",
                dev["device_id"].as_str().unwrap_or("")
            );
            println!("To approve sudo with this phone:  sudo fortis pam-enroll");
            Ok(())
        }
        Ok(_) => Err("unexpected pairing reply".into()),
        Err(ipc::IpcError::Io(e))
            if matches!(
                e.kind(),
                std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
            ) =>
        {
            Err("the pairing code expired — run `fortis pair` again".into())
        }
        Err(e) => Err(e.into()),
    }
}

fn run(cli: Cli) -> Res<()> {
    match cli.command {
        Command::ClearClipboard { hash } => {
            clipboard::clear_later(&hash);
            return Ok(());
        }
        Command::PamEnroll { yes } => return pam::run(yes),
        Command::PamInstall { sudo, yes } => return pam_install::run_install(sudo, yes),
        Command::PamTest => return pam_install::run_test(),
        Command::Doctor => return doctor::run(),
        Command::PamUninstall => return pam_install::run_uninstall(),
        Command::Generate {
            length,
            no_uppercase,
            no_numbers,
            no_symbols,
            copy,
        } => {
            let o = PasswordOptions {
                length,
                uppercase: !no_uppercase,
                numbers: !no_numbers,
                symbols: !no_symbols,
            };
            let p = password_gen::generate(&o)?;
            if copy {
                clipboard::copy_secret(&p)?;
                eprintln!("Copied to the clipboard (cleared in 30 s).");
            } else {
                println!("{}", p.as_str());
            }
            return Ok(());
        }
        _ => {}
    }

    let mut c = Client::connect()?;
    match cli.command {
        Command::Status => status(&mut c)?,
        Command::Init { password_stdin } => {
            let p = read_master(password_stdin, true)?;
            c.call("init", json!({ "password": p.as_str() }))?;
            println!("Vault created and unlocked.");
        }
        Command::Unlock { password_stdin } => {
            let p = read_master(password_stdin, false)?;
            c.call("unlock", json!({ "password": p.as_str() }))?;
            println!("Unlocked.");
        }
        Command::Lock => {
            c.call("lock", json!({}))?;
            println!("Locked.");
        }
        Command::List { query, kind, json } => {
            let mut args = json!({});
            if let Some(q) = query {
                args["query"] = json!(q);
            }
            if let Some(k) = kind {
                args["type"] = json!(match k {
                    Kind::Login => "Login",
                    Kind::Note => "SecureNote",
                    Kind::Card => "CreditCard",
                    Kind::Identity => "Identity",
                    Kind::Totp => "Totp",
                });
            }
            let rows = c.call("list", args)?;
            if json {
                println!("{}", serde_json::to_string_pretty(&rows)?);
            } else {
                println!(
                    "{}",
                    entries::table(rows.as_array().map(Vec::as_slice).unwrap_or(&[]))
                );
            }
        }
        Command::Get {
            name,
            field,
            copy,
            json,
        } => {
            let e = resolve(&mut c, &name)?;
            if json {
                println!("{}", serde_json::to_string_pretty(&e)?);
                return Ok(());
            }
            let field = match (field, copy) {
                (Some(f), _) => Some(f),
                (None, true) => Some("password".into()),
                (None, false) => None,
            };
            let Some(f) = field else {
                println!("{}", entries::describe(&e));
                return Ok(());
            };
            let value = if f == "totp" {
                let t = c.call("totp", json!({ "id": e.id }))?;
                if !copy {
                    eprintln!("({} s left)", t["remaining_secs"]);
                }
                t["code"].as_str().unwrap_or("").to_string()
            } else {
                entries::field(&e, &f).ok_or_else(|| format!("`{}` has no field `{f}`", e.title))?
            };
            let value = zeroize::Zeroizing::new(value);
            if copy {
                clipboard::copy_secret(&value)?;
                eprintln!("Copied {f} of \"{}\" (cleared in 30 s).", e.title);
            } else {
                println!("{}", value.as_str());
            }
        }
        Command::Add { kind, from_json } => {
            let input: EntryInput = match from_json {
                Some(p) => {
                    let text = if p == "-" {
                        std::io::read_to_string(std::io::stdin())?
                    } else {
                        std::fs::read_to_string(&p)?
                    };
                    let i: EntryInput = serde_json::from_str(&text)?;
                    i.validate()?;
                    i
                }
                None => entries::prompt_entry(kind, None)?,
            };
            let r = c.call("add", json!({ "entry": input }))?;
            println!("Added {}.", r["id"].as_str().unwrap_or(""));
        }
        Command::Edit { name } => {
            let e = resolve(&mut c, &name)?;
            let input = entries::prompt_entry(entries::kind_of(&e), Some(&e))?;
            c.call("edit", json!({ "id": e.id, "entry": input }))?;
            println!("Saved.");
        }
        Command::Rm { name, yes } => {
            let e = resolve(&mut c, &name)?;
            if !yes && !prompt::confirm(&format!("Delete \"{}\" ({})?", e.title, e.id))? {
                return Err("cancelled".into());
            }
            c.call("delete", json!({ "id": e.id }))?;
            println!("Deleted.");
        }
        Command::Pair => pair(&mut c)?,
        Command::Devices(DevicesCmd::List) => {
            let list = c.call("devices_list", json!({}))?;
            let list = list.as_array().cloned().unwrap_or_default();
            if list.is_empty() {
                println!("(no paired phones — run `fortis pair`)");
            }
            for d in &list {
                println!(
                    "{}  {}  {}  last seen {}\n    request key {}  approval key {}",
                    &d["device_id"].as_str().unwrap_or("")
                        [..8.min(d["device_id"].as_str().unwrap_or("").len())],
                    d["name"].as_str().unwrap_or("?"),
                    if d["online"] == json!(true) {
                        "online"
                    } else {
                        "offline"
                    },
                    fmt_age(d["last_seen"].as_i64().unwrap_or(0)),
                    d["request_key_fp"].as_str().unwrap_or("?"),
                    d["approval_key_fp"].as_str().unwrap_or("?"),
                );
            }
        }
        Command::Devices(DevicesCmd::Revoke { device_id, yes }) => {
            let list = c.call("devices_list", json!({}))?;
            let hits: Vec<Value> = list
                .as_array()
                .into_iter()
                .flatten()
                .filter(|d| {
                    d["device_id"]
                        .as_str()
                        .is_some_and(|id| id.starts_with(&device_id))
                })
                .cloned()
                .collect();
            let [d] = hits.as_slice() else {
                return Err(if hits.is_empty() {
                    "no such device".into()
                } else {
                    "that prefix matches several devices".into()
                });
            };
            let id = d["device_id"].as_str().unwrap_or("");
            if !yes
                && !prompt::confirm(&format!(
                    "Revoke {} ({id})?",
                    d["name"].as_str().unwrap_or("?")
                ))?
            {
                return Err("cancelled".into());
            }
            let r = c.call("devices_revoke", json!({ "device_id": id }))?;
            println!("Revoked.");
            if r["pam_reenroll_required"] == json!(true) {
                println!("It can still approve sudo until you run:  sudo fortis pam-enroll");
            }
        }
        Command::Sessions(SessionsCmd::List) => {
            let list = c.call("sessions_list", json!({}))?;
            let list = list.as_array().cloned().unwrap_or_default();
            if list.is_empty() {
                println!("(no active sessions)");
            }
            for s in list {
                println!(
                    "{}  {}  device {}  started {} s ago",
                    s["session_id"].as_str().unwrap_or(""),
                    s["kind"].as_str().unwrap_or(""),
                    s["device_id"].as_str().unwrap_or(""),
                    s["started_secs_ago"]
                );
            }
        }
        Command::Sessions(SessionsCmd::End { session_id }) => {
            let args = session_id
                .map(|s| json!({ "session_id": s }))
                .unwrap_or(json!({}));
            let r = c.call("sessions_end", args)?;
            println!("Ended {} session(s).", r["ended"]);
        }
        Command::Backup(BackupCmd::Run) => {
            c.call("backup_run", json!({}))?;
            println!("Backup started. Follow it with:  journalctl --user -f | grep -i backup");
        }
        Command::PamEnroll { .. }
        | Command::PamInstall { .. }
        | Command::PamTest
        | Command::Doctor
        | Command::PamUninstall
        | Command::Generate { .. }
        | Command::ClearClipboard { .. } => {
            unreachable!()
        }
    }
    Ok(())
}

fn main() -> ExitCode {
    match run(Cli::parse()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("fortis: {e}");
            ExitCode::FAILURE
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::CommandFactory;

    #[test]
    fn cli_definition_is_valid() {
        Cli::command().debug_assert();
    }

    #[test]
    fn parses_common_commands() {
        assert!(
            Cli::try_parse_from(["fortis", "get", "GitHub", "--field", "password", "--copy"])
                .is_ok()
        );
        assert!(Cli::try_parse_from(["fortis", "get", "x", "--json", "--copy"]).is_err());
        assert!(Cli::try_parse_from(["fortis", "add", "--type", "card"]).is_ok());
        assert!(Cli::try_parse_from(["fortis", "devices", "revoke", "abcd1234", "-y"]).is_ok());
        assert!(Cli::try_parse_from(["fortis", "pam-enroll"]).is_ok());
        assert!(Cli::try_parse_from(["fortis", "pam-install", "--sudo", "-y"]).is_ok());
        assert!(Cli::try_parse_from(["fortis", "pam-test"]).is_ok());
        assert!(Cli::try_parse_from(["fortis", "pam-uninstall"]).is_ok());
        assert!(Cli::try_parse_from(["fortis", "ls", "--type", "totp"]).is_ok());
    }
}
