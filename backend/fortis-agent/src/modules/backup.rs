use std::process::Command;

pub fn get_logs() -> Result<String, String> {
    let output = Command::new("tail")
        .args(&["-n", "50", "/mnt/FortisVault/Logs/mirror/mirror.log"])
        .output();
        
    match output {
        Ok(out) => Ok(String::from_utf8_lossy(&out.stdout).to_string()),
        Err(e) => Err(format!("Failed to read logs: {}", e)),
    }
}
