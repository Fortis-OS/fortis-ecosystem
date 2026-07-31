use serde::{Deserialize, Deserializer, Serialize, Serializer};
use uuid::Uuid;

#[derive(Clone, Debug)]
pub struct SecretString(pub zeroize::Zeroizing<String>);

impl Serialize for SecretString {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        serializer.serialize_str(&self.0)
    }
}

impl<'de> Deserialize<'de> for SecretString {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let s = String::deserialize(deserializer)?;
        Ok(SecretString(zeroize::Zeroizing::new(s)))
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Vault {
    pub id: Uuid,
    pub entries: Vec<VaultEntry>,
    pub folders: Vec<VaultFolder>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct VaultFolder {
    pub id: Uuid,
    pub name: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub enum FieldType {
    Text,
    Hidden,
    Email,
    Phone,
    Date,
    URL,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct CustomField {
    pub name: String,
    pub value: SecretString,
    pub field_type: FieldType,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub enum EntryType {
    Login {
        username: String,
        password: SecretString,
        url: Option<String>,
        totp_secret: Option<String>,
    },
    Identity {
        first_name: String,
        last_name: String,
        email: Option<String>,
        phone: Option<String>,
        address: Option<String>,
    },
    CreditCard {
        cardholder_name: String,
        card_number: SecretString,
        expiration_month: u8,
        expiration_year: u16,
        cvv: SecretString,
    },
    SecureNote {
        content: SecretString,
    },
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct VaultEntry {
    pub id: Uuid,
    pub folder_id: Option<Uuid>,
    pub title: String,
    pub entry_type: EntryType,
    pub custom_fields: Vec<CustomField>,
    pub notes: String,
}

impl Vault {
    pub fn new() -> Self {
        Self {
            id: Uuid::new_v4(),
            entries: Vec::new(),
            folders: Vec::new(),
        }
    }
}
