//! fortis-agent — the laptop daemon (architecture §3).
//!
//! Listens on 127.0.0.1 (desktop, bearer token), the Tailscale IPv4 address
//! (phones, signed requests) and a Unix socket (CLI, pam_fortis). Never on
//! 0.0.0.0 or a LAN address.

use std::process::ExitCode;
use std::sync::Arc;
use std::time::Duration;

use rand::RngCore;

use fortis_core::sign::AgentKey;

mod config;
mod devices;
mod error;
mod hub;
mod modules;
mod paths;
mod state;
mod transport;
mod util;

use config::Config;
use paths::Paths;
use state::AppState;

fn harden_process() {
    // Keep key material out of core dumps and away from same-user ptrace (security §3.7).
    // SAFETY: prctl with these constant arguments has no memory-safety implications.
    unsafe {
        libc::prctl(libc::PR_SET_DUMPABLE, 0, 0, 0, 0);
    }
}

fn load_secrets(paths: &Paths) -> std::io::Result<(String, AgentKey)> {
    let token = paths::read_or_create(&paths.token_file(), || {
        let mut b = [0u8; 32];
        rand::rngs::OsRng.fill_bytes(&mut b);
        hex::encode(b).into_bytes()
    })?;
    let token = String::from_utf8_lossy(&token).trim().to_string();
    if token.len() < 32 {
        return Err(std::io::Error::other(
            "agent.token is too short; delete it to regenerate",
        ));
    }
    let key_bytes = paths::read_or_create(&paths.key_file(), || {
        AgentKey::generate().to_bytes().to_vec()
    })?;
    let key = AgentKey::from_bytes(&key_bytes)
        .map_err(|_| std::io::Error::other("agent.key is invalid"))?;
    Ok((token, key))
}

async fn shutdown_signal() {
    let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        .expect("SIGTERM handler");
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {}
        _ = term.recv() => {}
    }
}

async fn run() -> Result<(), String> {
    let paths = Paths::from_env()?;
    paths
        .prepare()
        .map_err(|e| format!("cannot prepare directories: {e}"))?;
    let config = Config::load_or_create(&paths.config_file()).map_err(|e| e.to_string())?;
    if std::env::var_os("FORTIS_AGENT_KEY").is_some() {
        log::warn!(
            "FORTIS_AGENT_KEY is ignored; the desktop token is {}",
            paths.token_file().display()
        );
    }
    let (token, agent_key) =
        load_secrets(&paths).map_err(|e| format!("cannot load agent secrets: {e}"))?;
    log::info!("agent identity {}", agent_key.public().fingerprint());

    let st = Arc::new(
        AppState::new(config, paths, token, agent_key)
            .map_err(|e| format!("cannot load paired devices: {e}"))?,
    );

    let ipc = transport::ipc::bind(&st).map_err(|e| format!("cannot bind IPC socket: {e}"))?;
    let loopback = transport::rest::bind_loopback(&st)
        .await
        .map_err(|e| format!("cannot bind 127.0.0.1:{}: {e}", st.config.rest.port))?;
    log::info!(
        "loopback REST listening on 127.0.0.1:{}",
        st.config.rest.port
    );

    tokio::spawn(transport::ipc::serve(st.clone(), ipc));
    let app = transport::rest::loopback_router(st.clone());
    let lb = tokio::spawn(transport::rest::serve(loopback, app));
    if st.config.rest.tailnet {
        tokio::spawn(transport::rest::tailnet_supervisor(st.clone()));
    }
    let st_lock = st.clone();
    tokio::spawn(async move {
        let mut t = tokio::time::interval(Duration::from_secs(15));
        loop {
            t.tick().await;
            st_lock.auto_lock_tick();
        }
    });
    if st.config.vault.lock_on_screen_lock {
        tokio::spawn(modules::session_lock::watch(st.clone()));
    }
    tokio::spawn(modules::presence::watch(st.clone()));

    tokio::select! {
        _ = shutdown_signal() => log::info!("shutting down"),
        r = lb => log::error!("loopback listener stopped: {r:?}"),
    }
    st.lock_vault("shutdown");
    if let Some(mut c) = st.webcam.lock().await.take() {
        let _ = c.kill().await;
    }
    let _ = std::fs::remove_file(st.paths.socket());
    Ok(())
}

#[tokio::main]
async fn main() -> ExitCode {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    harden_process();
    match run().await {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            log::error!("{e}");
            ExitCode::FAILURE
        }
    }
}
