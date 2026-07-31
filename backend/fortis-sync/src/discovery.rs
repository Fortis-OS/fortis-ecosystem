pub struct PeerDiscovery;

impl PeerDiscovery {
    pub fn new() -> Self {
        Self {}
    }

    pub async fn discover_peers(&self) -> Vec<String> {
        // TODO: Query Tailscale local API (/localapi/v0/status) to find peers running FortisEncrypt
        vec!["http://[::1]:50051".to_string()]
    }
}
