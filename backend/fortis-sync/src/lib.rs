pub mod pb {
    tonic::include_proto!("sync");
}

pub mod server;
pub mod client;
pub mod discovery;
