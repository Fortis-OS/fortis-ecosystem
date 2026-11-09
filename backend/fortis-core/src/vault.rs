//! Vault entry model (architecture §4.1). This is the plaintext that gets
//! encrypted per entry; nothing here is stored unencrypted.

use serde::{Deserialize, Deserializer, Serialize, Serializer};
use uuid::Uuid;
use zeroize::Zeroizing;

/// A secret string: zeroised on drop, redacted in `Debug`.
#[derive(Clone, Default, PartialEq, Eq)]
pub struct SecretString(Zeroizing<String>);

impl SecretString {
    pub fn new(s: impl Into<String>) -> Self {
        Self(Zeroizing::new(s.into()))
    }

    pub fn expose(&self) -> &str {
        &self.0
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    pub fn len(&self) -> usize {
        self.0.len()
    }
}

impl From<&str> for SecretString {
    fn from(s: &str) -> Self {
        Self::new(s)
    }
}

impl std::fmt::Debug for SecretString {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("\"[redacted]\"")
    }
}

impl Serialize for SecretString {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&self.0)
    }
}

impl<'de> Deserialize<'de> for SecretString {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        Ok(Self::new(String::deserialize(d)?))
    }
}

#[derive(Clone, Copy, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
pub enum TotpAlg {
    #[default]
    Sha1,
    Sha256,
    Sha512,
}

fn default_digits() -> u8 {
    6
}
fn default_period() -> u32 {
    30
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct TotpConfig {
    pub secret_base32: SecretString,
    #[serde(default = "default_digits")]
    pub digits: u8,
    #[serde(default = "default_period")]
    pub period: u32,
    #[serde(default)]
    pub algorithm: TotpAlg,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub enum FieldType {
    Text,
    Hidden,
    Email,
    Phone,
    Date,
    Url,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct CustomField {
    pub name: String,
    pub value: SecretString,
    pub field_type: FieldType,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub enum EntryType {
    Login {
        username: String,
        password: SecretString,
        #[serde(default)]
        urls: Vec<String>,
        #[serde(default)]
        totp: Option<TotpConfig>,
    },
    CreditCard {
        cardholder_name: String,
        number: SecretString,
        exp_month: u8,
        exp_year: u16,
        cvv: SecretString,
    },
    Identity {
        first_name: String,
        last_name: String,
        email: Option<String>,
        phone: Option<String>,
        address: Option<String>,
    },
    SecureNote {
        content: SecretString,
    },
    Totp {
        issuer: Option<String>,
        account: Option<String>,
        totp: TotpConfig,
    },
}

impl EntryType {
    pub fn type_name(&self) -> &'static str {
        match self {
            EntryType::Login { .. } => "Login",
            EntryType::CreditCard { .. } => "CreditCard",
            EntryType::Identity { .. } => "Identity",
            EntryType::SecureNote { .. } => "SecureNote",
            EntryType::Totp { .. } => "Totp",
        }
    }

    pub fn totp(&self) -> Option<&TotpConfig> {
        match self {
            EntryType::Login { totp, .. } => totp.as_ref(),
            EntryType::Totp { totp, .. } => Some(totp),
            _ => None,
        }
    }
}

/// What a client sends to create or replace an entry (no ID, no timestamps).
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct EntryInput {
    #[serde(default)]
    pub folder: Option<String>,
    pub title: String,
    pub entry_type: EntryType,
    #[serde(default)]
    pub custom_fields: Vec<CustomField>,
    #[serde(default)]
    pub notes: SecretString,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct VaultEntry {
    pub id: Uuid,
    pub folder: Option<String>,
    pub title: String,
    pub entry_type: EntryType,
    pub custom_fields: Vec<CustomField>,
    pub notes: SecretString,
    pub created_at: i64,
    pub updated_at: i64,
}

/// List view without secrets (API `GET /vault/entries`).
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct EntrySummary {
    pub id: Uuid,
    pub title: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub folder: Option<String>,
    pub username: Option<String>,
    pub urls: Vec<String>,
    pub has_totp: bool,
    pub updated_at: i64,
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
#[error("invalid entry: {0}")]
pub struct ValidationError(pub String);

fn check_len(field: &str, s: &str, max: usize) -> Result<(), ValidationError> {
    if s.chars().count() > max {
        return Err(ValidationError(format!(
            "{field} longer than {max} characters"
        )));
    }
    Ok(())
}

impl TotpConfig {
    pub fn validate(&self) -> Result<(), ValidationError> {
        if !(6..=8).contains(&self.digits) {
            return Err(ValidationError("totp digits must be 6-8".into()));
        }
        if !(15..=120).contains(&self.period) {
            return Err(ValidationError("totp period must be 15-120 s".into()));
        }
        crate::totp::generate(self, 0)
            .map(|_| ())
            .map_err(|e| ValidationError(e.to_string()))
    }
}

impl EntryInput {
    pub fn validate(&self) -> Result<(), ValidationError> {
        if self.title.trim().is_empty() {
            return Err(ValidationError("title is required".into()));
        }
        check_len("title", &self.title, 256)?;
        if let Some(f) = &self.folder {
            check_len("folder", f, 128)?;
        }
        check_len("notes", self.notes.expose(), 65_536)?;
        if self.custom_fields.len() > 64 {
            return Err(ValidationError("too many custom fields".into()));
        }
        for cf in &self.custom_fields {
            check_len("custom field name", &cf.name, 128)?;
            check_len("custom field value", cf.value.expose(), 8192)?;
        }
        match &self.entry_type {
            EntryType::Login {
                username,
                password,
                urls,
                totp,
            } => {
                check_len("username", username, 512)?;
                check_len("password", password.expose(), 4096)?;
                if urls.len() > 32 {
                    return Err(ValidationError("too many urls".into()));
                }
                for u in urls {
                    check_len("url", u, 2048)?;
                }
                if let Some(t) = totp {
                    t.validate()?;
                }
            }
            EntryType::CreditCard {
                cardholder_name,
                number,
                exp_month,
                cvv,
                ..
            } => {
                check_len("cardholder_name", cardholder_name, 256)?;
                check_len("number", number.expose(), 32)?;
                check_len("cvv", cvv.expose(), 8)?;
                if *exp_month > 12 {
                    return Err(ValidationError("exp_month must be 0-12".into()));
                }
            }
            EntryType::Identity {
                first_name,
                last_name,
                email,
                phone,
                address,
            } => {
                check_len("first_name", first_name, 256)?;
                check_len("last_name", last_name, 256)?;
                for (n, v) in [("email", email), ("phone", phone), ("address", address)] {
                    if let Some(v) = v {
                        check_len(n, v, 1024)?;
                    }
                }
            }
            EntryType::SecureNote { content } => check_len("content", content.expose(), 65_536)?,
            EntryType::Totp {
                issuer,
                account,
                totp,
            } => {
                for (n, v) in [("issuer", issuer), ("account", account)] {
                    if let Some(v) = v {
                        check_len(n, v, 256)?;
                    }
                }
                totp.validate()?;
            }
        }
        Ok(())
    }
}

impl VaultEntry {
    pub fn from_input(id: Uuid, input: EntryInput, created_at: i64, updated_at: i64) -> Self {
        Self {
            id,
            folder: input.folder,
            title: input.title,
            entry_type: input.entry_type,
            custom_fields: input.custom_fields,
            notes: input.notes,
            created_at,
            updated_at,
        }
    }

    pub fn summary(&self) -> EntrySummary {
        let (username, urls) = match &self.entry_type {
            EntryType::Login { username, urls, .. } => (Some(username.clone()), urls.clone()),
            EntryType::Identity { email, .. } => (email.clone(), vec![]),
            EntryType::Totp { account, .. } => (account.clone(), vec![]),
            _ => (None, vec![]),
        };
        EntrySummary {
            id: self.id,
            title: self.title.clone(),
            kind: self.entry_type.type_name().into(),
            folder: self.folder.clone(),
            username,
            urls,
            has_totp: self.entry_type.totp().is_some(),
            updated_at: self.updated_at,
        }
    }

    /// Case-insensitive match on title, username, URLs, folder (API `?q=`).
    pub fn matches(&self, query: &str) -> bool {
        let q = query.to_lowercase();
        let s = self.summary();
        s.title.to_lowercase().contains(&q)
            || s.username.is_some_and(|u| u.to_lowercase().contains(&q))
            || s.urls.iter().any(|u| u.to_lowercase().contains(&q))
            || s.folder.is_some_and(|f| f.to_lowercase().contains(&q))
    }

    pub fn to_json(&self) -> Zeroizing<Vec<u8>> {
        Zeroizing::new(serde_json::to_vec(self).expect("VaultEntry always serialises"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    pub(crate) fn login(title: &str) -> EntryInput {
        EntryInput {
            folder: Some("Work".into()),
            title: title.into(),
            entry_type: EntryType::Login {
                username: "me@example.com".into(),
                password: "hunter2".into(),
                urls: vec!["https://github.com".into()],
                totp: Some(TotpConfig {
                    secret_base32: "JBSWY3DPEHPK3PXP".into(),
                    digits: 6,
                    period: 30,
                    algorithm: TotpAlg::Sha1,
                }),
            },
            custom_fields: vec![],
            notes: SecretString::default(),
        }
    }

    #[test]
    fn json_shape_matches_api() {
        let e = VaultEntry::from_input(Uuid::nil(), login("GitHub"), 1, 2);
        let v: serde_json::Value = serde_json::from_slice(&e.to_json()).unwrap();
        assert_eq!(v["entry_type"]["Login"]["password"], "hunter2");
        assert_eq!(v["entry_type"]["Login"]["totp"]["algorithm"], "Sha1");
        let back: VaultEntry = serde_json::from_value(v).unwrap();
        assert_eq!(back, e);
    }

    #[test]
    fn debug_hides_secrets() {
        let e = VaultEntry::from_input(Uuid::nil(), login("GitHub"), 1, 2);
        assert!(!format!("{e:?}").contains("hunter2"));
    }

    #[test]
    fn summary_has_no_secret() {
        let e = VaultEntry::from_input(Uuid::nil(), login("GitHub"), 1, 2);
        let s = serde_json::to_string(&e.summary()).unwrap();
        assert!(!s.contains("hunter2") && !s.contains("JBSW"));
        assert!(s.contains("\"type\":\"Login\"") && s.contains("\"has_totp\":true"));
        assert!(e.matches("git") && e.matches("WORK") && !e.matches("gitlab"));
    }

    #[test]
    fn validation() {
        assert!(login("GitHub").validate().is_ok());
        assert!(login("  ").validate().is_err());
        let mut bad = login("x");
        if let EntryType::Login { totp: Some(t), .. } = &mut bad.entry_type {
            t.secret_base32 = "not base32!!".into();
        }
        assert!(bad.validate().is_err());
        let unknown = r#"{"title":"x","entry_type":{"SecureNote":{"content":"c"}},"evil":1}"#;
        assert!(serde_json::from_str::<EntryInput>(unknown).is_err());
        let minimal = r#"{"title":"x","entry_type":{"SecureNote":{"content":"c"}}}"#;
        assert!(
            serde_json::from_str::<EntryInput>(minimal)
                .unwrap()
                .validate()
                .is_ok()
        );
    }
}
