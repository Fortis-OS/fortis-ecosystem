use std::process::Command;
use serde_json::{json, Value};
use log::error;

pub fn handle_clipboard(data: &str) -> Result<Value, String> {
    let cmd_name = if std::env::var("WAYLAND_DISPLAY").is_ok() {
        "wl-copy"
    } else {
        "xclip"
    };

    let mut args = vec![];
    if cmd_name == "xclip" {
        args.push("-selection");
        args.push("clipboard");
    }

    use std::io::Write;
    
    let mut child = Command::new(cmd_name)
        .args(&args)
        .stdin(std::process::Stdio::piped())
        .spawn()
        .map_err(|e| format!("Failed to start clipboard command: {}", e))?;

    if let Some(mut stdin) = child.stdin.take() {
        stdin.write_all(data.as_bytes()).map_err(|e| format!("Failed to write to stdin: {}", e))?;
    }

    let _ = child.wait();

    Ok(json!({ "status": "success", "message": "Clipboard updated" }))
}

pub fn handle_action(action: &str) -> Result<Value, String> {
    let mut volume = String::new();
    
    match action {
        "lock_screen" => { let _ = Command::new("loginctl").arg("lock-session").spawn(); }
        "run_backup" => { let _ = Command::new("/home/ganeshak11/dev/Fortis-Ecosystem/FortisBackup/fortis-backup.sh").spawn(); }
        "media_play_pause" => { let _ = Command::new("playerctl").arg("play-pause").spawn(); }
        "media_next" => { let _ = Command::new("playerctl").arg("next").spawn(); }
        "media_prev" => { let _ = Command::new("playerctl").arg("previous").spawn(); }
        "volume_up" => {
            let _ = Command::new("amixer").args(&["-D", "pulse", "sset", "Master", "5%+"]).spawn();
            volume = get_volume();
        }
        "volume_down" => {
            let _ = Command::new("amixer").args(&["-D", "pulse", "sset", "Master", "5%-"]).spawn();
            volume = get_volume();
        }
        "suspend" => { let _ = Command::new("systemctl").arg("suspend").spawn(); }
        "shutdown" => { let _ = Command::new("systemctl").arg("poweroff").spawn(); }
        _ => return Err("Unknown action".to_string()),
    }

    let mut resp = json!({
        "status": "success",
        "action": action
    });
    
    if !volume.is_empty() {
        resp.as_object_mut().unwrap().insert("volume".to_string(), Value::String(volume));
    }
    
    Ok(resp)
}

fn get_volume() -> String {
    if let Ok(out) = Command::new("amixer").args(&["-D", "pulse", "sget", "Master"]).output() {
        let output = String::from_utf8_lossy(&out.stdout);
        for line in output.lines() {
            if line.contains("Front Left:") {
                let parts: Vec<&str> = line.split('[').collect();
                if parts.len() > 1 {
                    let vol = parts[1].split(']').next().unwrap_or("");
                    return vol.to_string();
                }
            }
        }
    }
    "".to_string()
}

pub fn handle_media() -> Result<Value, String> {
    let cmd_str = "playerctl metadata --format '{{title}}||{{artist}}||{{album}}||{{position}}||{{mpris:length}}||{{status}}||{{playerName}}'";
    if let Ok(out) = Command::new("bash").arg("-c").arg(cmd_str).output() {
        let output = String::from_utf8_lossy(&out.stdout).trim().to_string();
        if output.is_empty() {
            return Err("No players found".to_string());
        }
        
        let parts: Vec<&str> = output.split("||").collect();
        if parts.len() >= 7 {
            return Ok(json!({
                "title": parts[0],
                "artist": parts[1],
                "album": parts[2],
                "position": parts[3],
                "length": parts[4],
                "status": parts[5],
                "player": parts[6]
            }));
        }
    }
    
    Err("Failed to parse media metadata".to_string())
}
