use std::sync::Arc;
use tokio::sync::Mutex;
use log::{info, error};

pub mod config;
pub mod modules;
pub mod transport;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    env_logger::init();
    info!("Starting Unified Fortis Agent...");

    // TODO: Load config
    // let config = config::load_config().await?;

    // TODO: Initialize shared state (DB connections, etc.)
    
    // Start REST API
    let rest_server = tokio::spawn(async move {
        if let Err(e) = transport::rest::start_server().await {
            error!("REST server failed: {}", e);
        }
    });

    // Start IPC Socket
    let ipc_server = tokio::spawn(async move {
        if let Err(e) = transport::ipc::start_server().await {
            error!("IPC server failed: {}", e);
        }
    });

    // Start gRPC Server
    let grpc_server = tokio::spawn(async move {
        if let Err(e) = transport::grpc::start_server().await {
            error!("gRPC server failed: {}", e);
        }
    });

    let _ = tokio::join!(rest_server, ipc_server, grpc_server);

    Ok(())
}
