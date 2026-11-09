//! RFC 6238 TOTP codes.

use totp_rs::{Algorithm, Secret, TOTP};

use crate::vault::{TotpAlg, TotpConfig};

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum TotpError {
    #[error("invalid TOTP secret (expected base32)")]
    Secret,
    #[error("invalid TOTP parameters")]
    Params,
}

/// Code for `unix_time`. Accepts secrets with spaces and lowercase letters.
pub fn generate(cfg: &TotpConfig, unix_time: u64) -> Result<String, TotpError> {
    let cleaned: String = cfg
        .secret_base32
        .expose()
        .chars()
        .filter(|c| !c.is_whitespace() && *c != '-')
        .map(|c| c.to_ascii_uppercase())
        .collect();
    let secret = Secret::Encoded(cleaned)
        .to_bytes()
        .map_err(|_| TotpError::Secret)?;
    if secret.is_empty() {
        return Err(TotpError::Secret);
    }
    let alg = match cfg.algorithm {
        TotpAlg::Sha1 => Algorithm::SHA1,
        TotpAlg::Sha256 => Algorithm::SHA256,
        TotpAlg::Sha512 => Algorithm::SHA512,
    };
    // `new_unchecked` allows the short secrets many real services still issue.
    let totp = TOTP::new_unchecked(alg, cfg.digits as usize, 1, cfg.period as u64, secret);
    if cfg.period == 0 {
        return Err(TotpError::Params);
    }
    Ok(totp.generate(unix_time))
}

/// Seconds until the current code changes.
pub fn remaining_secs(period: u32, unix_time: u64) -> u64 {
    let p = period.max(1) as u64;
    p - (unix_time % p)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::vault::SecretString;

    fn cfg(secret: &str, digits: u8, alg: TotpAlg) -> TotpConfig {
        TotpConfig {
            secret_base32: SecretString::new(secret),
            digits,
            period: 30,
            algorithm: alg,
        }
    }

    #[test]
    fn rfc6238_vectors() {
        // Secret "12345678901234567890" (RFC 6238 appendix B, SHA-1).
        let c = cfg("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 8, TotpAlg::Sha1);
        assert_eq!(generate(&c, 59).unwrap(), "94287082");
        assert_eq!(generate(&c, 1_111_111_109).unwrap(), "07081804");
        assert_eq!(generate(&c, 20_000_000_000).unwrap(), "65353130");
    }

    #[test]
    fn accepts_spaced_lowercase_secret() {
        let a = cfg("JBSWY3DPEHPK3PXP", 6, TotpAlg::Sha1);
        let b = cfg("jbsw y3dp ehpk 3pxp", 6, TotpAlg::Sha1);
        assert_eq!(generate(&a, 1000).unwrap(), generate(&b, 1000).unwrap());
    }

    #[test]
    fn rejects_bad_secret() {
        assert!(generate(&cfg("!!!", 6, TotpAlg::Sha1), 0).is_err());
        assert!(generate(&cfg("", 6, TotpAlg::Sha1), 0).is_err());
    }

    #[test]
    fn remaining() {
        assert_eq!(remaining_secs(30, 59), 1);
        assert_eq!(remaining_secs(30, 60), 30);
    }
}
