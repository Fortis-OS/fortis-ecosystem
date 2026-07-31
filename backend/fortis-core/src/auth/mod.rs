use ed25519_dalek::{Signature, VerifyingKey, Verifier};
use rand::RngCore;
use std::time::{SystemTime, UNIX_EPOCH};
use sha2::{Sha512, Digest};

#[derive(Debug, Clone)]
pub struct AuthChallenge {
    pub nonce: String,
    pub timestamp: u64,
}

impl AuthChallenge {
    pub fn new() -> Self {
        let mut rng = rand::thread_rng();
        let mut nonce_bytes = [0u8; 32];
        rng.fill_bytes(&mut nonce_bytes);
        let nonce = hex::encode(nonce_bytes);

        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs();

        AuthChallenge { nonce, timestamp }
    }

    pub fn payload(&self) -> Vec<u8> {
        let mut hasher = Sha512::new();
        hasher.update(self.nonce.as_bytes());
        hasher.update(&self.timestamp.to_le_bytes());
        hasher.finalize().to_vec()
    }
}

pub struct AuthResponse {
    pub signature: String,
}

pub struct PairedDevice {
    pub public_key: String, // hex encoded
}

pub fn verify_response(
    challenge: &AuthChallenge,
    response: &AuthResponse,
    device: &PairedDevice,
) -> bool {
    let pub_key_bytes: Vec<u8> = match hex::decode(&device.public_key) {
        Ok(b) => b,
        Err(_) => return false,
    };
    
    let verifying_key = match VerifyingKey::from_bytes(
        &pub_key_bytes.as_slice().try_into().unwrap_or([0u8; 32])
    ) {
        Ok(k) => k,
        Err(_) => return false,
    };

    let sig_bytes: Vec<u8> = match hex::decode(&response.signature) {
        Ok(b) => b,
        Err(_) => return false,
    };
    let signature = match Signature::from_slice(&sig_bytes) {
        Ok(s) => s,
        Err(_) => return false,
    };

    let payload = challenge.payload();

    verifying_key.verify(&payload, &signature).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{SigningKey, Signer};
    use rand::rngs::OsRng;

    #[test]
    fn test_auth_roundtrip() {
        let mut csprng = OsRng;
        let mut key_bytes = [0u8; 32];
        rand::RngCore::fill_bytes(&mut csprng, &mut key_bytes);
        let signing_key = SigningKey::from_bytes(&key_bytes);
        let verifying_key = signing_key.verifying_key();

        let device = PairedDevice {
            public_key: hex::encode(verifying_key.as_bytes()),
        };

        let challenge = AuthChallenge::new();
        
        let signature = signing_key.sign(&challenge.payload());
        let response = AuthResponse {
            signature: hex::encode(signature.to_bytes()),
        };

        assert!(verify_response(&challenge, &response, &device));
    }
}
