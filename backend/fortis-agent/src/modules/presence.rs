//! Warns on the desktop when a paired phone has been away for a long time (architecture §3.4).
//!
//! Sudo approvals and phone backups silently stop working when the phone's Fortis app is
//! killed (battery savers, updates). One notification per away period makes that visible.

use std::collections::HashSet;
use std::time::Duration;

use uuid::Uuid;

use crate::state::Shared;
use crate::util;

const CHECK_EVERY: Duration = Duration::from_secs(10 * 60);
pub const AWAY_AFTER_SECS: i64 = 12 * 60 * 60;

/// Device ids that should be warned about now.
/// `seen` = (device, last_seen, connected now); `since` = when the agent started watching.
pub fn away(seen: &[(Uuid, i64, bool)], since: i64, now: i64) -> Vec<Uuid> {
    seen.iter()
        .filter(|(_, last, online)| !online && now - (*last).max(since) >= AWAY_AFTER_SECS)
        .map(|(id, _, _)| *id)
        .collect()
}

fn ago(secs: i64) -> String {
    let h = secs / 3600;
    if h >= 48 {
        format!("{} days", h / 24)
    } else {
        format!("{h} hours")
    }
}

pub async fn watch(st: Shared) {
    let started = fortis_core::unix_now();
    let mut warned: HashSet<Uuid> = HashSet::new();
    let mut t = tokio::time::interval(CHECK_EVERY);
    loop {
        t.tick().await;
        let now = fortis_core::unix_now();
        let devices = st.devices.list();
        let seen: Vec<(Uuid, i64, bool)> = devices
            .iter()
            .map(|d| (d.device_id, d.last_seen, st.events.is_online(&d.device_id)))
            .collect();
        let away_now = away(&seen, started, now);
        // Back again: allow a new warning next time it goes away.
        warned.retain(|id| away_now.contains(id));
        for d in devices.iter().filter(|d| away_now.contains(&d.device_id)) {
            if warned.insert(d.device_id) {
                let gone = now - d.last_seen.max(started);
                log::warn!("paired device {} not seen for {}s", d.device_id, gone);
                util::notify(
                    "Fortis: phone not connected",
                    &format!(
                        "{} hasn't connected for {}. Sudo approvals and phone backups need it: open the Fortis app on the phone.",
                        d.name,
                        ago(gone)
                    ),
                    false,
                )
                .await;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn away_rules() {
        let a = Uuid::new_v4();
        let h = 3600;
        let now = 100 * h;
        // connected now: never away, however old last_seen is
        assert!(away(&[(a, 0, true)], 0, now).is_empty());
        // seen 11 h ago: fine; 12 h ago: away
        assert!(away(&[(a, now - 11 * h, false)], 0, now).is_empty());
        assert_eq!(away(&[(a, now - 12 * h, false)], 0, now), vec![a]);
        // stale last_seen on disk, but the agent only started 1 h ago: not yet
        assert!(away(&[(a, 0, false)], now - h, now).is_empty());
    }

    #[test]
    fn ago_text() {
        assert_eq!(ago(13 * 3600), "13 hours");
        assert_eq!(ago(72 * 3600), "3 days");
    }
}
