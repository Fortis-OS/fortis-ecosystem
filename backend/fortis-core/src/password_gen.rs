//! Password generator (API `POST /tools/generate-password`).

use rand::{Rng, rngs::OsRng, seq::SliceRandom};
use serde::Deserialize;
use zeroize::Zeroizing;

const LOWER: &[u8] = b"abcdefghijklmnopqrstuvwxyz";
const UPPER: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const DIGITS: &[u8] = b"0123456789";
const SYMBOLS: &[u8] = b"!@#$%^&*()_+-=[]{}|;:,.<>?";

pub const MIN_LEN: usize = 8;
pub const MAX_LEN: usize = 128;

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields, default)]
pub struct PasswordOptions {
    pub length: usize,
    pub uppercase: bool,
    pub numbers: bool,
    pub symbols: bool,
}

impl Default for PasswordOptions {
    fn default() -> Self {
        Self {
            length: 20,
            uppercase: true,
            numbers: true,
            symbols: true,
        }
    }
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
#[error("length must be {MIN_LEN}-{MAX_LEN}")]
pub struct LengthError;

/// Random password from `OsRng` with at least one character of each enabled class.
pub fn generate(opts: &PasswordOptions) -> Result<Zeroizing<String>, LengthError> {
    if !(MIN_LEN..=MAX_LEN).contains(&opts.length) {
        return Err(LengthError);
    }
    let mut classes: Vec<&[u8]> = vec![LOWER];
    if opts.uppercase {
        classes.push(UPPER);
    }
    if opts.numbers {
        classes.push(DIGITS);
    }
    if opts.symbols {
        classes.push(SYMBOLS);
    }
    let all: Vec<u8> = classes.concat();
    let mut rng = OsRng;
    let mut out: Zeroizing<Vec<u8>> = Zeroizing::new(Vec::with_capacity(opts.length));
    for class in &classes {
        out.push(class[rng.gen_range(0..class.len())]);
    }
    while out.len() < opts.length {
        out.push(all[rng.gen_range(0..all.len())]);
    }
    out.shuffle(&mut rng);
    Ok(Zeroizing::new(
        String::from_utf8(out.to_vec()).expect("charset is ASCII"),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn has_every_class_and_length() {
        for _ in 0..200 {
            let p = generate(&PasswordOptions {
                length: 8,
                ..Default::default()
            })
            .unwrap();
            assert_eq!(p.len(), 8);
            assert!(p.bytes().any(|b| LOWER.contains(&b)));
            assert!(p.bytes().any(|b| UPPER.contains(&b)));
            assert!(p.bytes().any(|b| DIGITS.contains(&b)));
            assert!(p.bytes().any(|b| SYMBOLS.contains(&b)));
        }
    }

    #[test]
    fn respects_disabled_classes() {
        let o = PasswordOptions {
            length: 64,
            uppercase: false,
            numbers: false,
            symbols: false,
        };
        assert!(generate(&o).unwrap().bytes().all(|b| LOWER.contains(&b)));
    }

    #[test]
    fn length_bounds() {
        assert!(
            generate(&PasswordOptions {
                length: 7,
                ..Default::default()
            })
            .is_err()
        );
        assert!(
            generate(&PasswordOptions {
                length: 129,
                ..Default::default()
            })
            .is_err()
        );
    }
}
