//! ECDSA P-256 signatures and canonical signed messages
//! (security §4.4 approvals, §5.3 requests, §5.6 sessions).
//!
//! The phone signs with Android Keystore (`SHA256withECDSA`, DER). The agent
//! and `pam_fortis` verify here. The agent also signs its own event messages
//! with an [`AgentKey`].

use base64::{Engine, engine::general_purpose::STANDARD};
use p256::ecdsa::{
    Signature, SigningKey, VerifyingKey,
    signature::{Signer, Verifier},
};
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use zeroize::Zeroizing;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum SignError {
    #[error("invalid public key")]
    PublicKey,
    #[error("invalid private key")]
    PrivateKey,
    #[error("invalid field `{0}`")]
    Field(&'static str),
}

/// A peer's P-256 public key (uncompressed SEC1).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PublicKey(VerifyingKey);

impl PublicKey {
    pub fn from_sec1(bytes: &[u8]) -> Result<Self, SignError> {
        VerifyingKey::from_sec1_bytes(bytes)
            .map(Self)
            .map_err(|_| SignError::PublicKey)
    }

    pub fn from_base64(s: &str) -> Result<Self, SignError> {
        let bytes = STANDARD
            .decode(s.trim())
            .map_err(|_| SignError::PublicKey)?;
        Self::from_sec1(&bytes)
    }

    /// Uncompressed SEC1 bytes (65 bytes, leading 0x04).
    pub fn to_sec1(&self) -> Vec<u8> {
        self.0.to_encoded_point(false).as_bytes().to_vec()
    }

    pub fn to_base64(&self) -> String {
        STANDARD.encode(self.to_sec1())
    }

    /// First 16 hex chars of SHA-256 over the uncompressed SEC1 key.
    pub fn fingerprint(&self) -> String {
        hex::encode(Sha256::digest(self.to_sec1()))[..16].to_string()
    }

    /// Verify a DER-encoded ECDSA-SHA256 signature over `msg`.
    pub fn verify(&self, msg: &[u8], der_sig: &[u8]) -> bool {
        match Signature::from_der(der_sig) {
            Ok(sig) => self.0.verify(msg, &sig).is_ok(),
            Err(_) => false,
        }
    }

    /// Same as [`verify`](Self::verify) with a base64 signature.
    pub fn verify_b64(&self, msg: &[u8], sig_b64: &str) -> bool {
        match STANDARD.decode(sig_b64.trim()) {
            Ok(der) => self.verify(msg, &der),
            Err(_) => false,
        }
    }
}

/// The agent's identity key (security §5.5), stored as 32 raw bytes.
pub struct AgentKey(SigningKey);

impl AgentKey {
    pub fn generate() -> Self {
        Self(SigningKey::random(&mut OsRng))
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, SignError> {
        SigningKey::from_slice(bytes)
            .map(Self)
            .map_err(|_| SignError::PrivateKey)
    }

    pub fn to_bytes(&self) -> Zeroizing<Vec<u8>> {
        Zeroizing::new(self.0.to_bytes().to_vec())
    }

    pub fn public(&self) -> PublicKey {
        PublicKey(*self.0.verifying_key())
    }

    /// DER signature (SHA-256 over `msg`).
    pub fn sign(&self, msg: &[u8]) -> Vec<u8> {
        let sig: Signature = self.0.sign(msg);
        sig.to_der().as_bytes().to_vec()
    }

    pub fn sign_b64(&self, msg: &[u8]) -> String {
        STANDARD.encode(self.sign(msg))
    }
}

impl std::fmt::Debug for AgentKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "AgentKey(pub={})", self.public().fingerprint())
    }
}

fn check_hex(field: &'static str, s: &str, len: usize) -> Result<(), SignError> {
    if s.len() == len && s.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f')) {
        Ok(())
    } else {
        Err(SignError::Field(field))
    }
}

fn check_text(field: &'static str, s: &str) -> Result<(), SignError> {
    if s.len() <= 256 && !s.chars().any(char::is_control) {
        Ok(())
    } else {
        Err(SignError::Field(field))
    }
}

/// Bytes the phone signs with its **request key** (security §5.3).
pub fn canonical_request(
    method: &str,
    path_and_query: &str,
    timestamp: u64,
    nonce_hex: &str,
    body: &[u8],
) -> Vec<u8> {
    format!(
        "fortis/v2/request\n{}\n{}\n{}\n{}\n{}",
        method.to_ascii_uppercase(),
        path_and_query,
        timestamp,
        nonce_hex,
        hex::encode(Sha256::digest(body)),
    )
    .into_bytes()
}

/// A sudo approval challenge created by `pam_fortis` (security §4.3).
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct ApprovalRequest {
    /// 16 bytes, lowercase hex.
    pub request_id: String,
    /// 32 bytes, lowercase hex.
    pub nonce: String,
    pub issued_at: u64,
    pub user: String,
    pub host: String,
    pub service: String,
    pub tty: String,
}

impl ApprovalRequest {
    pub fn new(user: &str, host: &str, service: &str, tty: &str, issued_at: u64) -> Self {
        Self {
            request_id: crate::crypto::random_hex(16),
            nonce: crate::crypto::random_hex(32),
            issued_at,
            user: user.into(),
            host: host.into(),
            service: service.into(),
            tty: tty.into(),
        }
    }

    pub fn validate(&self) -> Result<(), SignError> {
        check_hex("request_id", &self.request_id, 32)?;
        check_hex("nonce", &self.nonce, 64)?;
        check_text("user", &self.user)?;
        check_text("host", &self.host)?;
        check_text("service", &self.service)?;
        check_text("tty", &self.tty)?;
        if self.user.is_empty() || self.service.is_empty() {
            return Err(SignError::Field("user/service"));
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Decision {
    Approve,
    Deny,
}

impl Decision {
    pub fn as_str(self) -> &'static str {
        match self {
            Decision::Approve => "approve",
            Decision::Deny => "deny",
        }
    }
}

/// Bytes the phone signs with its **approval key** (security §4.4).
pub fn canonical_approval(req: &ApprovalRequest, decision: Decision) -> Result<Vec<u8>, SignError> {
    req.validate()?;
    Ok(format!(
        "fortis/v2/sudo-approval\n{}\n{}\n{}\n{}\n{}\n{}\n{}\n{}",
        req.request_id,
        req.nonce,
        req.issued_at,
        req.user,
        req.host,
        req.service,
        req.tty,
        decision.as_str(),
    )
    .into_bytes())
}

/// Bytes the phone signs with its approval key to confirm a session (security §5.6).
pub fn canonical_session(
    session_id: &str,
    nonce: &str,
    issued_at: u64,
    kind: &str,
) -> Result<Vec<u8>, SignError> {
    check_hex("session_id", session_id, 32)?;
    check_hex("nonce", nonce, 64)?;
    if kind.is_empty() || !kind.bytes().all(|b| b.is_ascii_lowercase() || b == b'_') {
        return Err(SignError::Field("kind"));
    }
    Ok(format!("fortis/v2/session\n{session_id}\n{nonce}\n{issued_at}\n{kind}").into_bytes())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn req() -> ApprovalRequest {
        ApprovalRequest::new("ganesh", "laptop", "sudo", "pts/3", 1_731_110_400)
    }

    #[test]
    fn approval_sign_verify() {
        let phone = AgentKey::generate(); // any P-256 key works as a stand-in for the phone
        let pk = PublicKey::from_base64(&phone.public().to_base64()).unwrap();
        let r = req();
        let msg = canonical_approval(&r, Decision::Approve).unwrap();
        let sig = phone.sign(&msg);
        assert!(pk.verify(&msg, &sig));
        // Deny bytes differ, so an approval can't be replayed as anything else.
        let deny = canonical_approval(&r, Decision::Deny).unwrap();
        assert!(!pk.verify(&deny, &sig));
        // Different challenge → invalid.
        let other = canonical_approval(&req(), Decision::Approve).unwrap();
        assert!(!pk.verify(&other, &sig));
        // Wrong key → invalid.
        assert!(!AgentKey::generate().public().verify(&msg, &sig));
        assert!(!pk.verify(&msg, b"garbage"));
    }

    #[test]
    fn approval_rejects_newlines_and_bad_hex() {
        let mut r = req();
        r.tty = "pts/3\nservice".into();
        assert!(canonical_approval(&r, Decision::Approve).is_err());
        let mut r = req();
        r.nonce = "XYZ".into();
        assert!(canonical_approval(&r, Decision::Approve).is_err());
        let mut r = req();
        r.request_id = r.request_id.to_uppercase();
        assert!(canonical_approval(&r, Decision::Approve).is_err());
    }

    #[test]
    fn request_canonical_includes_body_hash() {
        let a = canonical_request("get", "/api/v1/telemetry", 1, "aa", b"");
        let b = canonical_request("GET", "/api/v1/telemetry", 1, "aa", b"x");
        assert_ne!(a, b);
        assert!(
            String::from_utf8(a)
                .unwrap()
                .starts_with("fortis/v2/request\nGET\n")
        );
    }

    #[test]
    fn session_canonical_validates() {
        let sid = crate::crypto::random_hex(16);
        let n = crate::crypto::random_hex(32);
        assert!(canonical_session(&sid, &n, 5, "input").is_ok());
        assert!(canonical_session(&sid, &n, 5, "in\nput").is_err());
        assert!(canonical_session("short", &n, 5, "input").is_err());
    }

    #[test]
    fn agent_key_roundtrip_and_fingerprint() {
        let k = AgentKey::generate();
        let k2 = AgentKey::from_bytes(&k.to_bytes()).unwrap();
        assert_eq!(k.public(), k2.public());
        assert_eq!(k.public().fingerprint().len(), 16);
        assert_eq!(k.public().to_sec1().len(), 65);
        assert!(AgentKey::from_bytes(&[0u8; 32]).is_err());
    }
}
