//! Test fixture for fortis-mobile's vault interop test (not shipped).
//!
//!   phone_fixture create <dir> <password>          → prints {"bootstrap":…, "pull":…}
//!   phone_fixture push   <dir> <password> <file>   → applies the phone's changes with
//!                                                    the agent's own push rules
//!   phone_fixture sudo-frame                       → a sudo_request event framed and
//!                                                    signed exactly like the agent does,
//!                                                    plus the expected canonical bytes
use fortis_core::db::{SyncChange, VaultDb, VaultPaths};
use fortis_core::vault::{EntryInput, EntryType, SecretString, TotpAlg, TotpConfig};
use serde_json::json;

fn main() {
    let a: Vec<String> = std::env::args().collect();
    if a[1] == "sudo-frame" {
        return sudo_frame();
    }
    let paths = VaultPaths::in_dir(&a[2]);
    match a[1].as_str() {
        "create" => {
            std::fs::create_dir_all(&a[2]).unwrap();
            let mut db = VaultDb::create(&paths, &a[3]).unwrap();
            db.insert(
                EntryInput {
                    folder: Some("Work".into()),
                    title: "GitHub".into(),
                    entry_type: EntryType::Login {
                        username: "me@example.com".into(),
                        password: SecretString::new("hunter2"),
                        urls: vec!["https://github.com".into()],
                        totp: Some(TotpConfig {
                            secret_base32: "JBSWY3DPEHPK3PXP".into(),
                            digits: 6,
                            period: 30,
                            algorithm: TotpAlg::Sha1,
                        }),
                    },
                    custom_fields: vec![],
                    notes: SecretString::default(),
                },
                1_700_000_000,
            )
            .unwrap();
            db.insert(
                EntryInput {
                    folder: None,
                    title: "Wifi".into(),
                    entry_type: EntryType::SecureNote {
                        content: "pw123".into(),
                    },
                    custom_fields: vec![],
                    notes: SecretString::default(),
                },
                1_700_000_000,
            )
            .unwrap();
            println!(
                "{}",
                json!({ "bootstrap": db.bootstrap().unwrap(), "pull": db.pull(0).unwrap() })
            );
        }
        "push" => {
            let mut db = VaultDb::open(&paths, &a[3]).unwrap();
            let changes: Vec<SyncChange> =
                serde_json::from_str(&std::fs::read_to_string(&a[4]).unwrap()).unwrap();
            match db.push(&changes) {
                Ok(r) => println!(
                    "push: accepted={} conflicts={}",
                    r.accepted.len(),
                    r.conflicts.len()
                ),
                Err(e) => println!("push REJECTED: {e}"),
            }
            for e in db.list().unwrap().entries {
                println!("  entry: {} ({})", e.title, e.entry_type.type_name());
            }
        }
        _ => panic!("usage"),
    }
}

fn sudo_frame() {
    use fortis_core::sign::{canonical_approval, canonical_session, AgentKey, ApprovalRequest, Decision};
    let key = AgentKey::generate();
    let req = ApprovalRequest::new("ganesh", "laptop", "sudo", "pts/3", 1_731_110_400);
    let msg = json!({
        "type": "sudo_request",
        "request_id": req.request_id, "nonce": req.nonce, "issued_at": req.issued_at,
        "user": req.user, "host": req.host, "service": req.service, "tty": req.tty,
        "expires_at": req.issued_at + 30,
    })
    .to_string();
    // Same shape as fortis-agent's hub::sign_frame.
    let frame = json!({ "msg": msg, "agent_sig": key.sign_b64(msg.as_bytes()) }).to_string();
    let other = AgentKey::generate();
    let forged = json!({ "msg": msg, "agent_sig": other.sign_b64(msg.as_bytes()) }).to_string();
    let tampered = json!({ "msg": msg.replace("pts/3", "pts/4"), "agent_sig": key.sign_b64(msg.as_bytes()) }).to_string();
    println!(
        "{}",
        json!({
            "agent_pubkey": key.public().to_base64(),
            "frame": frame, "forged": forged, "tampered": tampered,
            "approve_hex": hex::encode(canonical_approval(&req, Decision::Approve).unwrap()),
            "deny_hex": hex::encode(canonical_approval(&req, Decision::Deny).unwrap()),
            "session": { "session_id": req.request_id, "nonce": req.nonce, "issued_at": req.issued_at, "kind": "input",
                "hex": hex::encode(canonical_session(&req.request_id, &req.nonce, req.issued_at, "input").unwrap()) },
        })
    );
}
