uniffi::setup_scaffolding!();

use fortis_core::totp::generate_totp_code;

#[uniffi::export]
pub fn get_totp(secret_base32: String) -> String {
    match generate_totp_code(&secret_base32) {
        Ok(code) => code,
        Err(_) => "ERROR".to_string(),
    }
}

#[uniffi::export]
pub fn generate_secure_password(_length: u32) -> String {
    // Stub - would call fortis_core::password_gen
    "SecurePassword123!".to_string()
}
