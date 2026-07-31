use crate::pb::sync_service_server::SyncService;
use crate::pb::{Ack, Challenge, Delta, DeviceAuth, EncryptedBatch, SinceTimestamp, VectorClock};
use tonic::{Request, Response, Status};

#[derive(Default)]
pub struct FortisSyncService {}

#[tonic::async_trait]
impl SyncService for FortisSyncService {
    async fn handshake(&self, request: Request<DeviceAuth>) -> Result<Response<Challenge>, Status> {
        let auth = request.into_inner();
        println!("Handshake from device: {}", auth.device_id);
        
        let challenge = Challenge {
            nonce: vec![0; 32], // TODO: Generate actual cryptographic nonce
        };
        Ok(Response::new(challenge))
    }

    async fn exchange_clocks(
        &self,
        _request: Request<VectorClock>,
    ) -> Result<Response<Delta>, Status> {
        println!("Received clock exchange request");
        Ok(Response::new(Delta {
            missing_entries: vec![],
        }))
    }

    async fn push_entries(
        &self,
        _request: Request<EncryptedBatch>,
    ) -> Result<Response<Ack>, Status> {
        println!("Received push entries");
        Ok(Response::new(Ack { success: true }))
    }

    async fn pull_entries(
        &self,
        _request: Request<SinceTimestamp>,
    ) -> Result<Response<EncryptedBatch>, Status> {
        println!("Received pull entries");
        Ok(Response::new(EncryptedBatch {
            device_id: "local_device".to_string(),
            entries: vec![],
        }))
    }
}
