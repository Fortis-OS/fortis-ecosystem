use serde::Deserialize;
use std::fs;
use std::path::PathBuf;

#[derive(Debug, Deserialize)]
pub struct AgentConfig {
    pub rest_port: u16,
    pub ipc_socket_path: String,
    pub grpc_port: u16,
}

impl Default for AgentConfig {
    fn default() -> Self {
        AgentConfig {
            rest_port: 8023,
            ipc_socket_path: "/tmp/fortis_agent.sock".to_string(),
            grpc_port: 50051,
        }
    }
}

pub async fn load_config() -> Result<AgentConfig, Box<dyn std::error::Error>> {
    // In the future, this will load from ~/.config/fortis/agent.toml
    Ok(AgentConfig::default())
}
