use std::time::{SystemTime, UNIX_EPOCH};
use totp_rs::{Algorithm, Secret, TOTP};

pub fn generate_totp_code(secret_base32: &str) -> Result<String, String> {
    let secret = Secret::Encoded(String::from(secret_base32))
        .to_bytes()
        .map_err(|e| format!("Invalid TOTP secret: {:?}", e))?;
        
    let totp = TOTP::new(
        Algorithm::SHA1,
        6,
        1,
        30,
        secret,
    ).map_err(|e| format!("Failed to create TOTP: {:?}", e))?;

    let current_time = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs();

    Ok(totp.generate(current_time))
}

pub fn get_remaining_seconds() -> u64 {
    let current_time = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs();
    
    30 - (current_time % 30)
}
