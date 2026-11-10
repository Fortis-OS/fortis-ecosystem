//! Login matching for autofill (security §3.6). The agent (browser
//! extension) and the phone (via fortis-bindings) both use this, so a login
//! fills in exactly the same places on both devices.

use std::net::IpAddr;
use url::{Host, Url};

/// Where a form lives.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Target {
    /// A web page (browser). The URL of the page/frame being filled.
    Web(String),
    /// An Android app with no web domain reported.
    App(String),
}

/// Prefix for app targets stored in `Login.urls`.
pub const APP_SCHEME: &str = "androidapp://";

#[derive(Debug, PartialEq, Eq)]
struct Site {
    https: bool,
    host: SiteHost,
}

#[derive(Debug, PartialEq, Eq)]
enum SiteHost {
    Name(String), // lowercase, punycode, no trailing dot
    Ip(IpAddr),
}

fn parse_web(s: &str) -> Option<Site> {
    let s = s.trim();
    // Entries are often saved as bare hosts ("github.com"); treat as https.
    let with_scheme = if s.contains("://") {
        s.to_string()
    } else {
        format!("https://{s}")
    };
    let u = Url::parse(&with_scheme).ok()?;
    let https = match u.scheme() {
        "https" => true,
        "http" => false,
        _ => return None,
    };
    let host = match u.host()? {
        Host::Domain(d) => {
            let d = d.trim_end_matches('.').to_ascii_lowercase();
            if d.is_empty() {
                return None;
            }
            SiteHost::Name(d)
        }
        Host::Ipv4(a) => SiteHost::Ip(IpAddr::V4(a)),
        Host::Ipv6(a) => SiteHost::Ip(IpAddr::V6(a)),
    };
    Some(Site { https, host })
}

fn is_local(h: &SiteHost) -> bool {
    match h {
        SiteHost::Name(n) => n == "localhost",
        SiteHost::Ip(ip) => ip.is_loopback(),
    }
}

/// A page we're willing to fill at all: https, or http on localhost.
pub fn fillable_page(page_url: &str) -> bool {
    parse_web(page_url).is_some_and(|p| p.https || is_local(&p.host))
}

/// The page's host (lowercase, punycode), for display and "never save" lists.
pub fn page_host(page_url: &str) -> Option<String> {
    parse_web(page_url).map(|p| match p.host {
        SiteHost::Name(n) => n,
        SiteHost::Ip(ip) => ip.to_string(),
    })
}

fn web_matches(saved: &str, page: &Site) -> bool {
    let Some(s) = parse_web(saved) else {
        return false;
    };
    // A saved https login is never filled into plain http.
    if s.https && !page.https {
        return false;
    }
    match (&s.host, &page.host) {
        (SiteHost::Ip(a), SiteHost::Ip(b)) => a == b,
        (SiteHost::Name(h), SiteHost::Name(p)) => {
            if !h.contains('.') && h != "localhost" {
                return false; // never match a bare TLD like "com"
            }
            p == h || p.ends_with(&format!(".{h}"))
        }
        _ => false,
    }
}

/// Does a Login with these `urls` match `target`?
pub fn matches(urls: &[String], target: &Target) -> bool {
    match target {
        Target::Web(page_url) => {
            let Some(page) = parse_web(page_url) else {
                return false;
            };
            if !(page.https || is_local(&page.host)) {
                return false;
            }
            urls.iter()
                .filter(|u| !u.trim().starts_with(APP_SCHEME))
                .any(|u| web_matches(u, &page))
        }
        Target::App(pkg) => {
            if pkg.is_empty() {
                return false;
            }
            urls.iter()
                .filter_map(|u| u.trim().strip_prefix(APP_SCHEME))
                .any(|p| p == pkg)
        }
    }
}

/// The URL to store in a new Login saved for `target`.
pub fn url_for_new_login(target: &Target) -> Option<String> {
    match target {
        Target::Web(page_url) => {
            let p = parse_web(page_url)?;
            if !(p.https || is_local(&p.host)) {
                return None;
            }
            let scheme = if p.https { "https" } else { "http" };
            Some(format!("{scheme}://{}", page_host(page_url)?))
        }
        Target::App(pkg) if !pkg.is_empty() => Some(format!("{APP_SCHEME}{pkg}")),
        Target::App(_) => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn web(urls: &[&str], page: &str) -> bool {
        let v: Vec<String> = urls.iter().map(|s| s.to_string()).collect();
        matches(&v, &Target::Web(page.into()))
    }

    #[test]
    fn exact_host_and_subdomains() {
        assert!(web(&["https://github.com"], "https://github.com/login"));
        assert!(web(&["https://github.com"], "https://gist.github.com/x"));
        assert!(web(&["github.com"], "https://github.com/"));
        assert!(web(&["https://GitHub.com."], "https://github.com:443/"));
        assert!(web(
            &["https://accounts.google.com/signin"],
            "https://accounts.google.com/x"
        ));
    }

    #[test]
    fn lookalikes_never_match() {
        assert!(!web(
            &["https://github.com"],
            "https://github.com.evil.io/login"
        ));
        assert!(!web(&["https://github.com"], "https://notgithub.com/"));
        assert!(!web(&["https://github.com"], "https://evil.io/github.com"));
        assert!(!web(&["https://gist.github.com"], "https://github.com/"));
        assert!(!web(&["https://com"], "https://github.com/"));
        assert!(!web(&["com"], "https://github.com/"));
        assert!(!web(&["https://github.com@evil.io"], "https://github.com/"));
    }

    #[test]
    fn scheme_rules() {
        assert!(!web(&["https://github.com"], "http://github.com/"));
        assert!(
            !web(&["http://github.com"], "http://github.com/"),
            "plain http page never filled"
        );
        assert!(web(
            &["http://localhost:3000"],
            "http://localhost:8080/login"
        ));
        assert!(web(&["http://127.0.0.1"], "http://127.0.0.1:5000/"));
        assert!(!web(&["https://github.com"], "ftp://github.com/"));
        assert!(!web(&["https://github.com"], "javascript:alert(1)"));
        assert!(!web(&["https://github.com"], "file:///etc/passwd"));
    }

    #[test]
    fn idn_and_ip() {
        assert!(web(&["https://bücher.de"], "https://xn--bcher-kva.de/"));
        assert!(web(&["https://xn--bcher-kva.de"], "https://bücher.de/"));
        assert!(web(&["https://192.168.1.1"], "https://192.168.1.1/admin"));
        assert!(!web(&["https://192.168.1.1"], "https://192.168.1.10/"));
        assert!(!web(&["https://1.1"], "https://0.1.1/"));
    }

    #[test]
    fn apps_match_only_exact_package() {
        let urls = vec![
            "androidapp://com.github.android".to_string(),
            "https://github.com".into(),
        ];
        assert!(matches(&urls, &Target::App("com.github.android".into())));
        assert!(!matches(
            &urls,
            &Target::App("com.github.android.evil".into())
        ));
        assert!(!matches(&urls, &Target::App("com.github".into())));
        assert!(!matches(&urls, &Target::App("".into())));
        // An app URL never matches a web page and vice versa.
        assert!(!web(&["androidapp://github.com"], "https://github.com/"));
        assert!(!matches(
            &["https://github.com".to_string()],
            &Target::App("github.com".into())
        ));
    }

    #[test]
    fn helpers() {
        assert!(fillable_page("https://x.com/"));
        assert!(fillable_page("http://localhost/"));
        assert!(!fillable_page("http://x.com/"));
        assert_eq!(
            page_host("https://Gist.GitHub.com/x").as_deref(),
            Some("gist.github.com")
        );
        assert_eq!(
            url_for_new_login(&Target::Web("https://github.com/login?x=1".into())).as_deref(),
            Some("https://github.com")
        );
        assert_eq!(
            url_for_new_login(&Target::Web("http://evil.com/".into())),
            None
        );
        assert_eq!(
            url_for_new_login(&Target::App("com.x".into())).as_deref(),
            Some("androidapp://com.x")
        );
    }
}
