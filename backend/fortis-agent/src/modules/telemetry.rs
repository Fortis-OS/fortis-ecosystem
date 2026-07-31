use std::process::Command;
use serde_json::{json, Value};

fn get_command_output(cmd: &str, args: &[&str]) -> String {
    let output = Command::new(cmd).args(args).output();
    match output {
        Ok(out) => String::from_utf8_lossy(&out.stdout).trim().to_string(),
        Err(_) => "".to_string(),
    }
}

pub fn get_telemetry() -> Value {
    let uptime = get_command_output("uptime", &["-p"]);
    
    // CPU usage
    let cpu_out = get_command_output("bash", &["-c", "top -bn1 | grep 'Cpu(s)' | awk '{print $2 + $4}'"]);
    
    // Memory
    let mem_total = get_command_output("bash", &["-c", "free -m | awk '/Mem:/ {print $2}'"]);
    let mem_used = get_command_output("bash", &["-c", "free -m | awk '/Mem:/ {print $3}'"]);
    
    // Swap
    let swap_total = get_command_output("bash", &["-c", "free -m | awk '/Swap:/ {print $2}'"]);
    let swap_used = get_command_output("bash", &["-c", "free -m | awk '/Swap:/ {print $3}'"]);
    
    // Storage
    let storage_total = get_command_output("bash", &["-c", "df -h / | awk 'NR==2 {print $2}'"]);
    let storage_used = get_command_output("bash", &["-c", "df -h / | awk 'NR==2 {print $3}'"]);
    
    // Temperature
    let mut temp = get_command_output("bash", &["-c", "sensors | awk '/Tctl/ {print $2}' | sed 's/+//'"]);
    if temp.is_empty() {
        temp = get_command_output("bash", &["-c", "sensors | awk '/CPU:/ {print $2}' | sed 's/+//'"]);
    }

    json!({
        "uptime": uptime,
        "cpu": format!("{}%", cpu_out),
        "mem_used": format!("{} MB", mem_used),
        "mem_total": format!("{} MB", mem_total),
        "swap_used": format!("{} MB", swap_used),
        "swap_total": format!("{} MB", swap_total),
        "storage_used": storage_used,
        "storage_total": storage_total,
        "temp": temp,
    })
}
