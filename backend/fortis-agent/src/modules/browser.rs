//! IPC commands for the fortis-browser extension (API §6, security §7.7).
//!
//! The extension never decides what matches: every command takes the page URL
//! and the agent applies `fortis_core::urlmatch` (security §3.6) itself.

use fortis_core::urlmatch::{self, Target};
use fortis_core::vault::{EntryInput, EntryType, SecretString, VaultEntry};
use serde_json::{Value, json};
use uuid::Uuid;

use crate::error::ApiError;
use crate::state::Shared;

const MAX_URL: usize = 2048;
const MAX_USERNAME: usize = 512;
const MAX_PASSWORD: usize = 4096;

fn s<'a>(a: &'a Value, k: &str) -> Result<&'a str, ApiError> {
    a.get(k)
        .and_then(Value::as_str)
        .ok_or_else(|| ApiError::bad_request(format!("`{k}` must be a string")))
}

fn page(a: &Value) -> Result<String, ApiError> {
    let url = s(a, "url")?;
    if url.len() > MAX_URL {
        return Err(ApiError::bad_request("url too long"));
    }
    if !urlmatch::fillable_page(url) {
        return Err(ApiError::forbidden(
            "only https pages (or http on localhost) can be filled",
        ));
    }
    Ok(url.to_string())
}

fn id(a: &Value) -> Result<Uuid, ApiError> {
    Uuid::parse_str(s(a, "id")?).map_err(|_| ApiError::bad_request("`id` must be a UUID"))
}

fn login_urls(e: &VaultEntry) -> Option<&[String]> {
    match &e.entry_type {
        EntryType::Login { urls, .. } => Some(urls),
        _ => None,
    }
}

fn matching(st: &Shared, url: &str) -> Result<Vec<VaultEntry>, ApiError> {
    let t = Target::Web(url.to_string());
    let all = st.with_vault(|db| db.list())?;
    Ok(all
        .entries
        .into_iter()
        .filter(|e| login_urls(e).is_some_and(|u| urlmatch::matches(u, &t)))
        .collect())
}

/// The entry `id`, only if it is a Login matching `url`.
fn matched_entry(st: &Shared, id: &Uuid, url: &str) -> Result<VaultEntry, ApiError> {
    let e = st.with_vault(|db| db.get(id))?;
    let ok = login_urls(&e).is_some_and(|u| urlmatch::matches(u, &Target::Web(url.into())));
    if ok {
        Ok(e)
    } else {
        Err(ApiError::forbidden("this login is not saved for this site"))
    }
}

fn username_of(e: &VaultEntry) -> &str {
    match &e.entry_type {
        EntryType::Login { username, .. } => username,
        _ => "",
    }
}

pub fn handle(st: &Shared, cmd: &str, a: &Value) -> Result<Value, ApiError> {
    match cmd {
        "browser_match" => {
            let url = page(a)?;
            let mut list: Vec<_> = matching(st, &url)?
                .iter()
                .map(|e| json!({ "id": e.id, "title": e.title, "username": username_of(e) }))
                .collect();
            list.sort_by(|x, y| x["title"].as_str().cmp(&y["title"].as_str()));
            Ok(json!({ "host": urlmatch::page_host(&url), "entries": list }))
        }
        "browser_fill" => {
            let url = page(a)?;
            let e = matched_entry(st, &id(a)?, &url)?;
            match &e.entry_type {
                EntryType::Login {
                    username, password, ..
                } => Ok(json!({ "username": username, "password": password.expose() })),
                _ => Err(ApiError::not_found()),
            }
        }
        "browser_totp" => {
            let url = page(a)?;
            let e = matched_entry(st, &id(a)?, &url)?;
            let cfg = e.entry_type.totp().ok_or_else(ApiError::not_found)?;
            let now = fortis_core::unix_now().max(0) as u64;
            let code = fortis_core::totp::generate(cfg, now)
                .map_err(|e| ApiError::bad_request(e.to_string()))?;
            Ok(
                json!({ "code": code, "remaining_secs": fortis_core::totp::remaining_secs(cfg.period, now) }),
            )
        }
        "browser_save_check" => {
            let url = page(a)?;
            let (user, pass) = (s(a, "username")?, s(a, "password")?);
            let found = matching(st, &url)?
                .into_iter()
                .find(|e| username_of(e) == user);
            Ok(match found {
                None => json!({ "status": "new" }),
                Some(e) => {
                    let same = matches!(&e.entry_type,
                        EntryType::Login { password, .. } if fortis_core::crypto::ct_eq(password.expose().as_bytes(), pass.as_bytes()));
                    json!({ "status": if same { "same" } else { "changed" }, "id": e.id })
                }
            })
        }
        "browser_save" => {
            let url = page(a)?;
            let (user, pass) = (s(a, "username")?, s(a, "password")?);
            if pass.is_empty() || pass.len() > MAX_PASSWORD || user.len() > MAX_USERNAME {
                return Err(ApiError::bad_request("invalid username or password length"));
            }
            let now = fortis_core::unix_now();
            if a.get("id").is_some_and(|v| !v.is_null()) {
                let e = matched_entry(st, &id(a)?, &url)?;
                let EntryType::Login {
                    username,
                    urls,
                    totp,
                    ..
                } = e.entry_type
                else {
                    return Err(ApiError::not_found());
                };
                let input = EntryInput {
                    folder: e.folder,
                    title: e.title,
                    entry_type: EntryType::Login {
                        username,
                        password: SecretString::new(pass),
                        urls,
                        totp,
                    },
                    custom_fields: e.custom_fields,
                    notes: e.notes,
                };
                let saved = st.with_vault(|db| db.update(&e.id, input, now))?;
                Ok(json!({ "id": saved.id, "action": "updated" }))
            } else {
                let target = Target::Web(url.clone());
                let save_url = urlmatch::url_for_new_login(&target)
                    .ok_or_else(|| ApiError::forbidden("this page can't be saved"))?;
                let input = EntryInput {
                    folder: None,
                    title: urlmatch::page_host(&url).unwrap_or_else(|| "Login".into()),
                    entry_type: EntryType::Login {
                        username: user.to_string(),
                        password: SecretString::new(pass),
                        urls: vec![save_url],
                        totp: None,
                    },
                    custom_fields: vec![],
                    notes: SecretString::default(),
                };
                let saved = st.with_vault(|db| db.insert(input, now))?;
                Ok(json!({ "id": saved.id, "action": "created" }))
            }
        }
        _ => Err(ApiError::bad_request(format!("unknown command `{cmd}`"))),
    }
}
