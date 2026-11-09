//! Building, editing and displaying vault entries.

use fortis_core::password_gen::{self, PasswordOptions};
use fortis_core::vault::{EntryInput, EntryType, SecretString, TotpAlg, TotpConfig, VaultEntry};
use serde_json::Value;

use crate::prompt;

pub type Res<T> = Result<T, Box<dyn std::error::Error>>;

#[derive(Clone, Copy, Debug, PartialEq, Eq, clap::ValueEnum)]
pub enum Kind {
    Login,
    Note,
    Card,
    Identity,
    Totp,
}

fn generated() -> SecretString {
    let p = password_gen::generate(&PasswordOptions::default()).expect("default length is valid");
    SecretString::new(p.as_str())
}

/// Hidden prompt. Empty keeps `current`; `g` generates (when `can_generate`).
fn secret_field(
    label: &str,
    current: Option<&SecretString>,
    can_generate: bool,
) -> Res<SecretString> {
    let hint = match (current.is_some_and(|c| !c.is_empty()), can_generate) {
        (true, true) => " (Enter keeps current, g generates)",
        (true, false) => " (Enter keeps current)",
        (false, true) => " (Enter or g generates)",
        (false, false) => "",
    };
    let v = prompt::secret(&format!("{label}{hint}: "))?;
    Ok(match (v.as_str(), current) {
        ("", Some(c)) if !c.is_empty() => c.clone(),
        ("" | "g", _) if can_generate => {
            eprintln!("  generated a 20-character password");
            generated()
        }
        (s, _) => SecretString::new(s),
    })
}

fn opt(s: String) -> Option<String> {
    if s.is_empty() { None } else { Some(s) }
}

fn totp_field(current: Option<&TotpConfig>, required: bool) -> Res<Option<TotpConfig>> {
    let label = if required {
        "TOTP secret (base32)"
    } else {
        "TOTP secret (base32, optional)"
    };
    let secret = secret_field(label, current.map(|t| &t.secret_base32), false)?;
    if secret.is_empty() {
        return Ok(None);
    }
    let base = current.cloned().unwrap_or(TotpConfig {
        secret_base32: SecretString::default(),
        digits: 6,
        period: 30,
        algorithm: TotpAlg::Sha1,
    });
    Ok(Some(TotpConfig {
        secret_base32: secret,
        ..base
    }))
}

/// Prompt for every field; `existing` supplies defaults when editing.
pub fn prompt_entry(kind: Kind, existing: Option<&VaultEntry>) -> Res<EntryInput> {
    let title = prompt::ask("Title", existing.map(|e| e.title.as_str()))?;
    let folder = prompt::ask(
        "Folder (optional)",
        existing.and_then(|e| e.folder.as_deref()),
    )?;
    let old = existing.map(|e| &e.entry_type);
    let entry_type = match kind {
        Kind::Login => {
            let (u0, p0, urls0, t0) = match old {
                Some(EntryType::Login {
                    username,
                    password,
                    urls,
                    totp,
                }) => (
                    Some(username.as_str()),
                    Some(password),
                    urls.join(", "),
                    totp.as_ref(),
                ),
                _ => (None, None, String::new(), None),
            };
            let username = prompt::ask("Username", u0)?;
            let password = secret_field("Password", p0, true)?;
            let urls = prompt::ask("URLs (comma-separated, optional)", Some(&urls0))?;
            let urls = urls
                .split(',')
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(String::from)
                .collect();
            EntryType::Login {
                username,
                password,
                urls,
                totp: totp_field(t0, false)?,
            }
        }
        Kind::Note => {
            let c0 = match old {
                Some(EntryType::SecureNote { content }) => Some(content),
                _ => None,
            };
            EntryType::SecureNote {
                content: secret_field("Note text (one line)", c0, false)?,
            }
        }
        Kind::Card => {
            let (h0, n0, m0, y0, c0) = match old {
                Some(EntryType::CreditCard {
                    cardholder_name,
                    number,
                    exp_month,
                    exp_year,
                    cvv,
                }) => (
                    Some(cardholder_name.as_str()),
                    Some(number),
                    exp_month.to_string(),
                    exp_year.to_string(),
                    Some(cvv),
                ),
                _ => (None, None, String::new(), String::new(), None),
            };
            let cardholder_name = prompt::ask("Cardholder name", h0)?;
            let number = secret_field("Card number", n0, false)?;
            let exp_month = prompt::ask("Expiry month (1-12)", Some(&m0))?
                .parse()
                .unwrap_or(0);
            let exp_year = prompt::ask("Expiry year (e.g. 2029)", Some(&y0))?
                .parse()
                .unwrap_or(0);
            let cvv = secret_field("CVV", c0, false)?;
            EntryType::CreditCard {
                cardholder_name,
                number,
                exp_month,
                exp_year,
                cvv,
            }
        }
        Kind::Identity => {
            let (f0, l0, e0, p0, a0) = match old {
                Some(EntryType::Identity {
                    first_name,
                    last_name,
                    email,
                    phone,
                    address,
                }) => (
                    Some(first_name.as_str()),
                    Some(last_name.as_str()),
                    email.as_deref(),
                    phone.as_deref(),
                    address.as_deref(),
                ),
                _ => (None, None, None, None, None),
            };
            EntryType::Identity {
                first_name: prompt::ask("First name", f0)?,
                last_name: prompt::ask("Last name", l0)?,
                email: opt(prompt::ask("Email (optional)", e0)?),
                phone: opt(prompt::ask("Phone (optional)", p0)?),
                address: opt(prompt::ask("Address (optional)", a0)?),
            }
        }
        Kind::Totp => {
            let (i0, a0, t0) = match old {
                Some(EntryType::Totp {
                    issuer,
                    account,
                    totp,
                }) => (issuer.as_deref(), account.as_deref(), Some(totp)),
                _ => (None, None, None),
            };
            let issuer = opt(prompt::ask("Issuer (optional)", i0)?);
            let account = opt(prompt::ask("Account (optional)", a0)?);
            let totp = totp_field(t0, true)?.ok_or("a TOTP secret is required")?;
            EntryType::Totp {
                issuer,
                account,
                totp,
            }
        }
    };
    let notes = secret_field(
        "Notes (one line, optional)",
        existing.map(|e| &e.notes),
        false,
    )?;
    let input = EntryInput {
        folder: opt(folder),
        title,
        entry_type,
        custom_fields: existing
            .map(|e| e.custom_fields.clone())
            .unwrap_or_default(),
        notes,
    };
    input.validate()?;
    Ok(input)
}

pub fn kind_of(e: &VaultEntry) -> Kind {
    match e.entry_type {
        EntryType::Login { .. } => Kind::Login,
        EntryType::SecureNote { .. } => Kind::Note,
        EntryType::CreditCard { .. } => Kind::Card,
        EntryType::Identity { .. } => Kind::Identity,
        EntryType::Totp { .. } => Kind::Totp,
    }
}

/// A single field for `get --field`. `totp` is handled by the agent.
pub fn field(e: &VaultEntry, name: &str) -> Option<String> {
    let s = |v: &SecretString| Some(v.expose().to_string());
    match (name, &e.entry_type) {
        ("notes", _) => s(&e.notes),
        ("title", _) => Some(e.title.clone()),
        ("password", EntryType::Login { password, .. }) => s(password),
        ("username", EntryType::Login { username, .. }) => Some(username.clone()),
        ("url", EntryType::Login { urls, .. }) => urls.first().cloned(),
        ("content" | "password", EntryType::SecureNote { content }) => s(content),
        ("number" | "password", EntryType::CreditCard { number, .. }) => s(number),
        ("cvv", EntryType::CreditCard { cvv, .. }) => s(cvv),
        ("email" | "username", EntryType::Identity { email, .. }) => email.clone(),
        ("username", EntryType::Totp { account, .. }) => account.clone(),
        _ => None,
    }
}

/// Everything except secrets.
pub fn describe(e: &VaultEntry) -> String {
    let mut out = vec![
        format!("{}  ({})", e.title, e.entry_type.type_name()),
        format!("  id        {}", e.id),
    ];
    if let Some(f) = &e.folder {
        out.push(format!("  folder    {f}"));
    }
    let hidden = "•••••• (use --field)";
    match &e.entry_type {
        EntryType::Login {
            username,
            urls,
            totp,
            ..
        } => {
            out.push(format!("  username  {username}"));
            out.push(format!("  password  {hidden}"));
            for u in urls {
                out.push(format!("  url       {u}"));
            }
            if totp.is_some() {
                out.push("  totp      yes (--field totp)".into());
            }
        }
        EntryType::CreditCard {
            cardholder_name,
            number,
            exp_month,
            exp_year,
            ..
        } => {
            let n = number.expose();
            let last4 = if n.len() >= 4 { &n[n.len() - 4..] } else { "" };
            out.push(format!("  holder    {cardholder_name}"));
            out.push(format!("  number    •••• {last4}"));
            out.push(format!("  expires   {exp_month:02}/{exp_year}"));
        }
        EntryType::Identity {
            first_name,
            last_name,
            email,
            phone,
            address,
        } => {
            out.push(format!("  name      {first_name} {last_name}"));
            for (k, v) in [("email", email), ("phone", phone), ("address", address)] {
                if let Some(v) = v {
                    out.push(format!("  {k:<9} {v}"));
                }
            }
        }
        EntryType::SecureNote { .. } => out.push(format!("  content   {hidden}")),
        EntryType::Totp {
            issuer, account, ..
        } => {
            out.push(format!("  issuer    {}", issuer.as_deref().unwrap_or("-")));
            out.push(format!("  account   {}", account.as_deref().unwrap_or("-")));
            out.push("  code      --field totp".into());
        }
    }
    if !e.notes.is_empty() {
        out.push(format!("  notes     {hidden}"));
    }
    out.push(format!("  updated   {}", e.updated_at));
    out.join("\n")
}

/// Aligned table of entry summaries.
pub fn table(rows: &[Value]) -> String {
    if rows.is_empty() {
        return "(no entries)".into();
    }
    let cell = |v: &Value, k: &str| v[k].as_str().unwrap_or("").to_string();
    let data: Vec<[String; 5]> = rows
        .iter()
        .map(|r| {
            [
                cell(r, "title"),
                cell(r, "type"),
                cell(r, "username"),
                cell(r, "folder"),
                cell(r, "id").chars().take(8).collect(),
            ]
        })
        .collect();
    let head = ["TITLE", "TYPE", "USERNAME", "FOLDER", "ID"];
    let mut w = head.map(str::len);
    for row in &data {
        for (i, c) in row.iter().enumerate() {
            w[i] = w[i].max(c.chars().count()).min(40);
        }
    }
    let fmt = |r: [&str; 5]| {
        r.iter()
            .enumerate()
            .map(|(i, c)| {
                let c: String = c.chars().take(40).collect();
                format!("{c:<width$}", width = w[i])
            })
            .collect::<Vec<_>>()
            .join("  ")
            .trim_end()
            .to_string()
    };
    let mut out = vec![fmt(head)];
    out.extend(
        data.iter()
            .map(|r| fmt([&r[0], &r[1], &r[2], &r[3], &r[4]])),
    );
    out.join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;
    use uuid::Uuid;

    fn login() -> VaultEntry {
        VaultEntry {
            id: Uuid::nil(),
            folder: Some("Work".into()),
            title: "GitHub".into(),
            entry_type: EntryType::Login {
                username: "me".into(),
                password: "hunter2".into(),
                urls: vec!["https://github.com".into()],
                totp: None,
            },
            custom_fields: vec![],
            notes: "secret note".into(),
            created_at: 1,
            updated_at: 2,
        }
    }

    #[test]
    fn describe_hides_secrets() {
        let d = describe(&login());
        assert!(d.contains("GitHub") && d.contains("me") && d.contains("github.com"));
        assert!(!d.contains("hunter2") && !d.contains("secret note"));
    }

    #[test]
    fn fields() {
        let e = login();
        assert_eq!(field(&e, "password").as_deref(), Some("hunter2"));
        assert_eq!(field(&e, "username").as_deref(), Some("me"));
        assert_eq!(field(&e, "notes").as_deref(), Some("secret note"));
        assert_eq!(field(&e, "cvv"), None);
    }

    #[test]
    fn table_layout() {
        let rows = vec![
            serde_json::json!({"title":"GitHub","type":"Login","username":"me","folder":null,"id":"0123456789"}),
        ];
        let t = table(&rows);
        assert!(t.lines().next().unwrap().starts_with("TITLE"));
        assert!(t.contains("01234567") && !t.contains("0123456789"));
    }
}
