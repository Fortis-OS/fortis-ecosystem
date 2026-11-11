//! Laptop remote endpoints: telemetry, capabilities, actions, media,
//! clipboard, backup log, password generator, mirrored notifications (API §5, §5b).

use std::path::Path;
use std::process::Stdio;
use std::time::Duration;

use axum::Extension;
use axum::extract::{Query, State};
use axum::response::IntoResponse;
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::io::AsyncWriteExt;

use fortis_core::password_gen::{self, PasswordOptions};

use crate::config::expand;
use crate::error::{ApiError, ApiJson, ApiResult};
use crate::modules::input;
use crate::state::Shared;
use crate::transport::auth::AuthedDevice;
use crate::util::{self, which};

// ---- Telemetry (read from /proc and /sys, no shelling out) ----------------------

fn cpu_times() -> Option<(u64, u64)> {
    let s = std::fs::read_to_string("/proc/stat").ok()?;
    let line = s.lines().find(|l| l.starts_with("cpu "))?;
    let v: Vec<u64> = line
        .split_whitespace()
        .skip(1)
        .filter_map(|x| x.parse().ok())
        .collect();
    if v.len() < 5 {
        return None;
    }
    let idle = v[3] + v[4];
    Some((v.iter().take(8).sum(), idle))
}

async fn cpu_percent() -> Option<f64> {
    let (t1, i1) = cpu_times()?;
    tokio::time::sleep(Duration::from_millis(200)).await;
    let (t2, i2) = cpu_times()?;
    let dt = t2.saturating_sub(t1) as f64;
    if dt == 0.0 {
        return Some(0.0);
    }
    Some(((1.0 - i2.saturating_sub(i1) as f64 / dt) * 1000.0).round() / 10.0)
}

fn meminfo() -> std::collections::HashMap<String, u64> {
    std::fs::read_to_string("/proc/meminfo")
        .unwrap_or_default()
        .lines()
        .filter_map(|l| {
            let (k, rest) = l.split_once(':')?;
            Some((k.to_string(), rest.split_whitespace().next()?.parse().ok()?))
        })
        .collect()
}

fn disk(path: &str) -> Option<(u64, u64)> {
    let c = std::ffi::CString::new(path).ok()?;
    // SAFETY: statvfs writes into the zeroed struct we own; `c` is a valid C string.
    let mut st: libc::statvfs = unsafe { std::mem::zeroed() };
    if unsafe { libc::statvfs(c.as_ptr(), &mut st) } != 0 {
        return None;
    }
    let frsize = st.f_frsize as u64;
    let total = st.f_blocks as u64 * frsize;
    let used = (st.f_blocks as u64).saturating_sub(st.f_bfree as u64) * frsize;
    Some((total, used))
}

fn read_milli_c(p: &Path) -> Option<f64> {
    let v: f64 = std::fs::read_to_string(p).ok()?.trim().parse().ok()?;
    Some((v / 100.0).round() / 10.0)
}

fn cpu_temp() -> Option<f64> {
    if let Ok(rd) = std::fs::read_dir("/sys/class/hwmon") {
        for e in rd.flatten() {
            let name = std::fs::read_to_string(e.path().join("name")).unwrap_or_default();
            if ["coretemp", "k10temp", "zenpower", "cpu_thermal", "acpitz"].contains(&name.trim())
                && let Some(t) = read_milli_c(&e.path().join("temp1_input"))
            {
                return Some(t);
            }
        }
    }
    read_milli_c(Path::new("/sys/class/thermal/thermal_zone0/temp"))
}

pub async fn telemetry(State(st): State<Shared>) -> axum::Json<Value> {
    let mem = meminfo();
    let kb = |k: &str| mem.get(k).copied().unwrap_or(0);
    let (disk_total, disk_used) = disk("/").unwrap_or((0, 0));
    let uptime = std::fs::read_to_string("/proc/uptime")
        .ok()
        .and_then(|s| s.split_whitespace().next()?.parse::<f64>().ok())
        .unwrap_or(0.0);
    axum::Json(json!({
        "cpu_percent": cpu_percent().await.unwrap_or(0.0),
        "mem_total_mb": kb("MemTotal") / 1024,
        "mem_used_mb": kb("MemTotal").saturating_sub(kb("MemAvailable")) / 1024,
        "swap_total_mb": kb("SwapTotal") / 1024,
        "swap_used_mb": kb("SwapTotal").saturating_sub(kb("SwapFree")) / 1024,
        "disk_total_bytes": disk_total,
        "disk_used_bytes": disk_used,
        "temp_c": cpu_temp(),
        "uptime_secs": uptime as u64,
        "hostname": st.hostname,
    }))
}

// ---- Capabilities ----------------------------------------------------------------

fn on_wayland() -> bool {
    util::display_env()
        .iter()
        .any(|(k, _)| *k == "WAYLAND_DISPLAY")
}

pub fn clipboard_tool() -> Option<&'static str> {
    if on_wayland() && which("wl-copy").is_some() {
        Some("wl-copy")
    } else if which("xclip").is_some() {
        Some("xclip")
    } else {
        None
    }
}

fn volume_tool() -> Option<&'static str> {
    ["wpctl", "pactl"].into_iter().find(|t| which(t).is_some())
}

pub fn webcam_available(st: &Shared) -> bool {
    st.config.webcam.enabled
        && which("ffmpeg").is_some()
        && Path::new(&st.config.webcam.device).exists()
}

pub fn capabilities_json(st: &Shared) -> Value {
    json!({
        "vault_state": st.vault_state(),
        "tailnet": st.tailnet_ip().is_some(),
        "sudo_approval": !util::pam_trust_devices().is_empty(),
        "clipboard": clipboard_tool(),
        "media": which("playerctl").is_some(),
        "volume": volume_tool(),
        "backup": expand(&st.paths.home, &st.config.backup.script).is_file(),
        "notify": which("notify-send").is_some(),
        "input": if st.config.input.enabled { input::backend().map(|b| b.name()) } else { None },
        "webcam": webcam_available(st),
    })
}

pub async fn capabilities(State(st): State<Shared>) -> axum::Json<Value> {
    axum::Json(capabilities_json(&st))
}

// ---- Actions ------------------------------------------------------------------------

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ActionBody {
    action: String,
}

async fn must_run(program: &str, args: &[&str]) -> ApiResult<String> {
    if which(program).is_none() {
        return Err(ApiError::unavailable(format!("{program} is not installed")));
    }
    let out = util::run(program, args).await.map_err(ApiError::internal)?;
    if !out.status_ok {
        return Err(ApiError::internal(format!("{program} failed")));
    }
    Ok(out.stdout)
}

/// (volume_percent, muted)
async fn current_volume(tool: &str) -> Option<(u32, bool)> {
    if tool == "wpctl" {
        let o = util::run("wpctl", &["get-volume", "@DEFAULT_AUDIO_SINK@"])
            .await
            .ok()?;
        // "Volume: 0.45 [MUTED]"
        let v: f64 = o.stdout.split_whitespace().nth(1)?.parse().ok()?;
        Some(((v * 100.0).round() as u32, o.stdout.contains("MUTED")))
    } else {
        let o = util::run("pactl", &["get-sink-volume", "@DEFAULT_SINK@"])
            .await
            .ok()?;
        let pct = o
            .stdout
            .split_whitespace()
            .find(|w| w.ends_with('%'))?
            .trim_end_matches('%')
            .parse()
            .ok()?;
        let m = util::run("pactl", &["get-sink-mute", "@DEFAULT_SINK@"])
            .await
            .ok()?;
        Some((pct, m.stdout.contains("yes")))
    }
}

pub async fn run_action(st: &Shared, action: &str) -> ApiResult<Value> {
    let mut resp = json!({ "action": action, "ok": true });
    match action {
        "lock_screen" => {
            must_run("loginctl", &["lock-session"]).await?;
            // Locking the screen ends all remote-control sessions (security §5.6).
            st.sessions.end_all();
        }
        "suspend" | "shutdown" => {
            if which("systemctl").is_none() {
                return Err(ApiError::unavailable("systemctl is not installed"));
            }
            st.lock_vault(action);
            let verb = if action == "suspend" {
                "suspend"
            } else {
                "poweroff"
            };
            util::spawn_detached("systemctl", &[verb]).map_err(ApiError::internal)?;
        }
        "media_play_pause" => {
            must_run("playerctl", &["play-pause"]).await?;
        }
        "media_next" => {
            must_run("playerctl", &["next"]).await?;
        }
        "media_prev" => {
            must_run("playerctl", &["previous"]).await?;
        }
        "volume_up" | "volume_down" | "volume_mute" => {
            let tool = volume_tool()
                .ok_or_else(|| ApiError::unavailable("no volume tool (wpctl/pactl)"))?;
            let args: &[&str] = match (tool, action) {
                ("wpctl", "volume_up") => {
                    &["set-volume", "-l", "1.0", "@DEFAULT_AUDIO_SINK@", "5%+"]
                }
                ("wpctl", "volume_down") => &["set-volume", "@DEFAULT_AUDIO_SINK@", "5%-"],
                ("wpctl", _) => &["set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"],
                (_, "volume_up") => &["set-sink-volume", "@DEFAULT_SINK@", "+5%"],
                (_, "volume_down") => &["set-sink-volume", "@DEFAULT_SINK@", "-5%"],
                _ => &["set-sink-mute", "@DEFAULT_SINK@", "toggle"],
            };
            must_run(tool, args).await?;
            if let Some((pct, muted)) = current_volume(tool).await {
                resp["volume_percent"] = json!(pct);
                resp["muted"] = json!(muted);
            }
        }
        "run_backup" => {
            let script = expand(&st.paths.home, &st.config.backup.script);
            if !script.is_file() {
                return Err(ApiError::unavailable(
                    "backup script not found (backup.script in agent.toml)",
                ));
            }
            util::spawn_detached(&script.to_string_lossy(), &[]).map_err(ApiError::internal)?;
            resp["started_at"] = json!(fortis_core::unix_now());
        }
        _ => return Err(ApiError::bad_request("unknown action")),
    }
    log::info!("action {action}");
    Ok(resp)
}

pub async fn actions(
    State(st): State<Shared>,
    ApiJson(b): ApiJson<ActionBody>,
) -> ApiResult<axum::Json<Value>> {
    Ok(axum::Json(run_action(&st, &b.action).await?))
}

// ---- Media / clipboard -----------------------------------------------------------------

pub async fn media() -> ApiResult<axum::Json<Value>> {
    if which("playerctl").is_none() {
        return Err(ApiError::unavailable("playerctl is not installed"));
    }
    let fmt = "{{title}}\u{1f}{{artist}}\u{1f}{{album}}\u{1f}{{position}}\u{1f}{{mpris:length}}\u{1f}{{status}}\u{1f}{{playerName}}";
    let out = util::run("playerctl", &["metadata", "--format", fmt])
        .await
        .map_err(ApiError::internal)?;
    let line = out.stdout.trim_end_matches('\n');
    if !out.status_ok || line.is_empty() {
        return Err(ApiError::not_found());
    }
    let p: Vec<&str> = line.split('\u{1f}').collect();
    if p.len() < 7 {
        return Err(ApiError::not_found());
    }
    let num = |s: &str| s.parse::<u64>().ok();
    Ok(axum::Json(json!({
        "title": p[0], "artist": p[1], "album": p[2],
        "position_us": num(p[3]), "length_us": num(p[4]),
        "status": p[5], "player": p[6],
    })))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ClipboardBody {
    text: String,
}

pub async fn clipboard(ApiJson(b): ApiJson<ClipboardBody>) -> ApiResult<axum::Json<Value>> {
    let tool = clipboard_tool()
        .ok_or_else(|| ApiError::unavailable("no clipboard tool (wl-copy/xclip)"))?;
    let args: &[&str] = if tool == "xclip" {
        &["-selection", "clipboard"]
    } else {
        &[]
    };
    let mut child = tokio::process::Command::new(tool)
        .args(args)
        .envs(util::display_env())
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(ApiError::internal)?;
    if let Some(mut stdin) = child.stdin.take() {
        stdin
            .write_all(b.text.as_bytes())
            .await
            .map_err(ApiError::internal)?;
    }
    // wl-copy / xclip fork to keep serving the selection; don't wait on them.
    tokio::spawn(async move {
        let _ = child.wait().await;
    });
    Ok(axum::Json(json!({ "ok": true })))
}

// ---- Backup log / tools / notify ----------------------------------------------------------

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LinesQuery {
    lines: Option<usize>,
}

pub async fn backup_logs(
    State(st): State<Shared>,
    Query(q): Query<LinesQuery>,
) -> ApiResult<impl IntoResponse> {
    let n = q.lines.unwrap_or(50);
    if !(1..=500).contains(&n) {
        return Err(ApiError::bad_request("lines must be 1-500"));
    }
    let path = expand(&st.paths.home, &st.config.backup.log);
    let text = tokio::fs::read(&path)
        .await
        .map_err(|_| ApiError::not_found())?;
    let text = String::from_utf8_lossy(&text);
    let lines: Vec<&str> = text.lines().collect();
    let tail = lines[lines.len().saturating_sub(n)..].join("\n");
    Ok((
        [(
            axum::http::header::CONTENT_TYPE,
            "text/plain; charset=utf-8",
        )],
        tail,
    ))
}

pub async fn generate_password(
    ApiJson(o): ApiJson<PasswordOptions>,
) -> ApiResult<axum::Json<Value>> {
    let p = password_gen::generate(&o).map_err(|e| ApiError::bad_request(e.to_string()))?;
    Ok(axum::Json(json!({ "password": p.as_str() })))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct NotifyBody {
    app: String,
    title: String,
    body: String,
}

pub async fn notify(
    State(st): State<Shared>,
    Extension(AuthedDevice(d)): Extension<AuthedDevice>,
    ApiJson(b): ApiJson<NotifyBody>,
) -> ApiResult<axum::Json<Value>> {
    if which("notify-send").is_none() {
        return Err(ApiError::unavailable("notify-send is not installed"));
    }
    if !st.notify_allowed(d.device_id) {
        return Err(ApiError::rate_limited(1));
    }
    let title = if b.title.is_empty() {
        b.app.clone()
    } else {
        format!("{} — {}", b.app, b.title)
    };
    util::notify(&title, &b.body, false).await;
    Ok(axum::Json(json!({ "ok": true })))
}
