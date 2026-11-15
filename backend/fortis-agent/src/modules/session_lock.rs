//! Lock the vault when the user's desktop session locks (security §3.5).
//!
//! Reads logind's `LockedHint` for the user's display session every few seconds and locks on
//! the change from unlocked to locked (screen lock, lid close, `loginctl lock-session`). Only
//! that change counts, so a vault unlocked while the screen stays locked is left alone.

use std::time::Duration;

use crate::state::Shared;
use crate::util;

const POLL: Duration = Duration::from_secs(3);

fn parse_hint(s: &str) -> Option<bool> {
    match s.trim() {
        "yes" => Some(true),
        "no" => Some(false),
        _ => None,
    }
}

/// A logind session id as printed by `loginctl` (e.g. "4", "c2").
fn valid_session_id(s: &str) -> bool {
    !s.is_empty() && s.len() <= 32 && s.chars().all(|c| c.is_ascii_alphanumeric())
}

/// Whether the user's display session is locked; `None` when there is none or logind can't say.
async fn display_session_locked(user: &str) -> Option<bool> {
    let out = util::run("loginctl", &["show-user", user, "-p", "Display", "--value"])
        .await
        .ok()?;
    let sid = out.stdout.trim();
    if !out.status_ok || !valid_session_id(sid) {
        return None;
    }
    let out = util::run(
        "loginctl",
        &["show-session", sid, "-p", "LockedHint", "--value"],
    )
    .await
    .ok()?;
    if !out.status_ok {
        return None;
    }
    parse_hint(&out.stdout)
}

pub async fn watch(st: Shared) {
    if util::which("loginctl").is_none() {
        log::warn!("loginctl not found: the vault won't lock with the screen");
        return;
    }
    let user = util::username();
    let mut was_locked = false;
    let mut t = tokio::time::interval(POLL);
    loop {
        t.tick().await;
        // Unknown (no graphical session, logind hiccup): keep the last known state.
        let Some(locked) = display_session_locked(&user).await else {
            continue;
        };
        if locked && !was_locked {
            st.lock_vault("screen locked");
        }
        was_locked = locked;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hint_parsing() {
        assert_eq!(parse_hint("yes\n"), Some(true));
        assert_eq!(parse_hint("no"), Some(false));
        assert_eq!(parse_hint(""), None);
        assert_eq!(parse_hint("maybe"), None);
    }

    #[test]
    fn session_ids() {
        assert!(valid_session_id("4"));
        assert!(valid_session_id("c2"));
        assert!(!valid_session_id(""));
        assert!(!valid_session_id("4 -p Name"));
        assert!(!valid_session_id("../x"));
    }
}
