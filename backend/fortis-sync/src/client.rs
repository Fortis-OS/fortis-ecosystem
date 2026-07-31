use crate::pb::sync_service_client::SyncServiceClient;
use crate::pb::DeviceAuth;
use tonic::transport::Channel;

pub struct FortisSyncClient {
    client: SyncServiceClient<Channel>,
}

impl FortisSyncClient {
    pub async fn connect(addr: String) -> Result<Self, tonic::transport::Error> {
        let client = SyncServiceClient::connect(addr).await?;
        Ok(Self { client })
    }

    pub async fn handshake(&mut self, device_id: &str) -> Result<(), Box<dyn std::error::Error>> {
        let request = tonic::Request::new(DeviceAuth {
            device_id: device_id.to_string(),
            public_key: vec![],
        });

        let response = self.client.handshake(request).await?;
        println!("Handshake response: {:?}", response.into_inner().nonce);
        Ok(())
    }
}
