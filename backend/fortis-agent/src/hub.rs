//! Agent → phone event stream, sudo approvals and fingerprint-confirmed
//! sessions (API §4, §5b; security §4, §5.6).

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde_json::{Value, json};
use tokio::sync::{broadcast, oneshot};
use uuid::Uuid;

use fortis_core::sign::{
    AgentKey, ApprovalRequest, Decision, canonical_approval, canonical_session,
};

use crate::devices::Device;
use crate::error::ApiError;

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

// ---------------------------------------------------------------------------
// Events

/// Every frame is `{"msg": "<json string>", "agent_sig": "<base64 DER>"}`.
/// The phone verifies the signature over the exact bytes of `msg`.
pub struct EventHub {
    tx: broadcast::Sender<String>,
    online: Mutex<HashMap<Uuid, usize>>,
}

impl Default for EventHub {
    fn default() -> Self {
        let (tx, _) = broadcast::channel(64);
        Self {
            tx,
            online: Mutex::new(HashMap::new()),
        }
    }
}

pub fn sign_frame(key: &AgentKey, msg: &Value) -> String {
    let msg_s = msg.to_string();
    json!({ "msg": msg_s, "agent_sig": key.sign_b64(msg_s.as_bytes()) }).to_string()
}

impl EventHub {
    pub fn publish(&self, key: &AgentKey, msg: Value) {
        let _ = self.tx.send(sign_frame(key, &msg));
    }

    pub fn subscribe(&self) -> broadcast::Receiver<String> {
        self.tx.subscribe()
    }

    pub fn connected(&self, id: Uuid) {
        *lock(&self.online).entry(id).or_default() += 1;
    }

    pub fn disconnected(&self, id: Uuid) {
        let mut g = lock(&self.online);
        if let Some(n) = g.get_mut(&id) {
            *n -= 1;
            if *n == 0 {
                g.remove(&id);
            }
        }
    }

    pub fn is_online(&self, id: &Uuid) -> bool {
        lock(&self.online).contains_key(id)
    }

    pub fn online_count(&self) -> usize {
        lock(&self.online).len()
    }
}

// ---------------------------------------------------------------------------
// Sudo approvals

#[derive(Clone, Debug, serde::Serialize)]
pub struct ApprovalReply {
    pub request_id: String,
    pub device_id: Uuid,
    pub decision: Decision,
    pub signature: String,
}

struct Pending {
    req: ApprovalRequest,
    reply: oneshot::Sender<ApprovalReply>,
}

#[derive(Default)]
pub struct Approvals {
    pending: Mutex<HashMap<String, Pending>>,
}

impl Approvals {
    pub fn register(&self, req: ApprovalRequest) -> oneshot::Receiver<ApprovalReply> {
        let (tx, rx) = oneshot::channel();
        lock(&self.pending).insert(req.request_id.clone(), Pending { req, reply: tx });
        rx
    }

    pub fn cancel(&self, request_id: &str) -> bool {
        lock(&self.pending).remove(request_id).is_some()
    }

    /// Verify the phone's signature (so it gets a clear error) and hand the
    /// reply to the waiting PAM connection. pam_fortis verifies again itself.
    pub fn respond(
        &self,
        request_id: &str,
        device: &Device,
        decision: Decision,
        signature_b64: &str,
    ) -> Result<(), ApiError> {
        let mut g = lock(&self.pending);
        let pending = g.get(request_id).ok_or_else(ApiError::not_found)?;
        let key = device.approval_key().ok_or_else(ApiError::unauthorized)?;
        let msg = canonical_approval(&pending.req, decision)
            .map_err(|e| ApiError::bad_request(e.to_string()))?;
        if !key.verify_b64(&msg, signature_b64) {
            return Err(ApiError::unauthorized());
        }
        let p = g.remove(request_id).expect("present");
        let _ = p.reply.send(ApprovalReply {
            request_id: request_id.into(),
            device_id: device.device_id,
            decision,
            signature: signature_b64.into(),
        });
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// Fingerprint-confirmed sessions

pub const SESSION_CONFIRM_TTL: Duration = Duration::from_secs(60);
pub const SESSION_IDLE: Duration = Duration::from_secs(120);
pub const SESSION_MAX: Duration = Duration::from_secs(30 * 60);

#[derive(Clone, Debug)]
pub struct Session {
    pub id: String,
    pub nonce: String,
    pub issued_at: u64,
    pub device_id: Uuid,
    pub kind: String,
    pub confirmed: bool,
    pub created: Instant,
    pub started: Option<Instant>,
    pub last_activity: Instant,
}

impl Session {
    fn expired(&self, now: Instant) -> bool {
        match self.started {
            None => now.duration_since(self.created) > SESSION_CONFIRM_TTL,
            Some(s) => {
                now.duration_since(s) > SESSION_MAX
                    || now.duration_since(self.last_activity) > SESSION_IDLE
            }
        }
    }
}

#[derive(Default)]
pub struct Sessions {
    map: Mutex<HashMap<String, Session>>,
}

impl Sessions {
    fn prune(map: &mut HashMap<String, Session>) {
        let now = Instant::now();
        map.retain(|_, s| !s.expired(now));
    }

    pub fn create(&self, device_id: Uuid, kind: &str, now_unix: u64) -> Session {
        let s = Session {
            id: fortis_core::crypto::random_hex(16),
            nonce: fortis_core::crypto::random_hex(32),
            issued_at: now_unix,
            device_id,
            kind: kind.into(),
            confirmed: false,
            created: Instant::now(),
            started: None,
            last_activity: Instant::now(),
        };
        let mut g = lock(&self.map);
        Self::prune(&mut g);
        g.insert(s.id.clone(), s.clone());
        s
    }

    pub fn confirm(
        &self,
        id: &str,
        device: &Device,
        signature_b64: &str,
    ) -> Result<Session, ApiError> {
        let mut g = lock(&self.map);
        Self::prune(&mut g);
        let s = g.get(id).ok_or_else(ApiError::unauthorized)?.clone();
        if s.device_id != device.device_id || s.confirmed {
            return Err(ApiError::unauthorized());
        }
        let key = device.approval_key().ok_or_else(ApiError::unauthorized)?;
        let msg = canonical_session(&s.id, &s.nonce, s.issued_at, &s.kind)
            .map_err(|e| ApiError::bad_request(e.to_string()))?;
        if !key.verify_b64(&msg, signature_b64) {
            return Err(ApiError::unauthorized());
        }
        // One active session per device per kind.
        g.retain(|_, o| !(o.confirmed && o.device_id == s.device_id && o.kind == s.kind));
        let now = Instant::now();
        let active = Session {
            confirmed: true,
            started: Some(now),
            last_activity: now,
            ..s
        };
        g.insert(active.id.clone(), active.clone());
        Ok(active)
    }

    /// True if `id` is an active, confirmed session of `kind` owned by `device`.
    /// Also records activity.
    pub fn touch(&self, id: &str, device_id: &Uuid, kind: &str) -> bool {
        let mut g = lock(&self.map);
        Self::prune(&mut g);
        match g.get_mut(id) {
            Some(s) if s.confirmed && s.device_id == *device_id && s.kind == kind => {
                s.last_activity = Instant::now();
                true
            }
            _ => false,
        }
    }

    /// Like [`touch`](Self::touch) but doesn't count as activity.
    pub fn is_active(&self, id: &str, device_id: &Uuid, kind: &str) -> bool {
        let mut g = lock(&self.map);
        Self::prune(&mut g);
        g.get(id)
            .is_some_and(|s| s.confirmed && s.device_id == *device_id && s.kind == kind)
    }

    pub fn end(&self, id: &str) -> Option<Session> {
        lock(&self.map).remove(id)
    }

    pub fn end_all(&self) -> usize {
        let mut g = lock(&self.map);
        let n = g.values().filter(|s| s.confirmed).count();
        g.clear();
        n
    }

    pub fn active(&self) -> Vec<Session> {
        let mut g = lock(&self.map);
        Self::prune(&mut g);
        g.values().filter(|s| s.confirmed).cloned().collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn device(key: &AgentKey) -> Device {
        Device {
            device_id: Uuid::new_v4(),
            name: "Phone".into(),
            request_pubkey: key.public().to_base64(),
            approval_pubkey: key.public().to_base64(),
            paired_at: 0,
            last_seen: 0,
        }
    }

    #[test]
    fn approval_respond_verifies_signature() {
        let phone = AgentKey::generate();
        let d = device(&phone);
        let a = Approvals::default();
        let req = ApprovalRequest::new("u", "h", "sudo", "pts/1", 100);
        let mut rx = a.register(req.clone());
        let msg = canonical_approval(&req, Decision::Approve).unwrap();
        // Wrong key → 401, request stays pending.
        let other = AgentKey::generate();
        assert!(
            a.respond(
                &req.request_id,
                &d,
                Decision::Approve,
                &other.sign_b64(&msg)
            )
            .is_err()
        );
        // Signature for "deny" used as "approve" → 401.
        let deny = canonical_approval(&req, Decision::Deny).unwrap();
        assert!(
            a.respond(
                &req.request_id,
                &d,
                Decision::Approve,
                &phone.sign_b64(&deny)
            )
            .is_err()
        );
        a.respond(
            &req.request_id,
            &d,
            Decision::Approve,
            &phone.sign_b64(&msg),
        )
        .unwrap();
        let r = rx.try_recv().unwrap();
        assert_eq!(r.decision, Decision::Approve);
        assert!(
            a.respond(
                &req.request_id,
                &d,
                Decision::Approve,
                &phone.sign_b64(&msg)
            )
            .is_err()
        );
    }

    #[test]
    fn session_confirm_and_scope() {
        let phone = AgentKey::generate();
        let d = device(&phone);
        let s = Sessions::default();
        let sess = s.create(d.device_id, "input", 5);
        assert!(!s.touch(&sess.id, &d.device_id, "input"));
        let msg = canonical_session(&sess.id, &sess.nonce, sess.issued_at, "input").unwrap();
        // Another device can't confirm it.
        let d2 = device(&phone);
        assert!(s.confirm(&sess.id, &d2, &phone.sign_b64(&msg)).is_err());
        s.confirm(&sess.id, &d, &phone.sign_b64(&msg)).unwrap();
        assert!(s.touch(&sess.id, &d.device_id, "input"));
        assert!(!s.touch(&sess.id, &d.device_id, "webcam"));
        assert!(!s.touch(&sess.id, &d2.device_id, "input"));
        assert_eq!(s.end_all(), 1);
        assert!(!s.touch(&sess.id, &d.device_id, "input"));
    }

    #[test]
    fn frames_are_signed_over_msg_string() {
        let k = AgentKey::generate();
        let f: Value =
            serde_json::from_str(&sign_frame(&k, &json!({"type":"ping","t":1}))).unwrap();
        let msg = f["msg"].as_str().unwrap();
        assert!(
            k.public()
                .verify_b64(msg.as_bytes(), f["agent_sig"].as_str().unwrap())
        );
    }
}
