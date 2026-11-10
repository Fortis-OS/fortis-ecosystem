//! Terminal prompts. Secrets are read without echo on a TTY, or as one line
//! from stdin when piped (`--password-stdin`, scripts, tests).

use std::io::{BufRead, IsTerminal, Write};

use zeroize::Zeroizing;

pub fn stdin_is_tty() -> bool {
    std::io::stdin().is_terminal()
}

fn read_line() -> std::io::Result<String> {
    let mut s = String::new();
    std::io::stdin().lock().read_line(&mut s)?;
    while s.ends_with('\n') || s.ends_with('\r') {
        s.pop();
    }
    Ok(s)
}

pub fn secret(prompt: &str) -> std::io::Result<Zeroizing<String>> {
    if stdin_is_tty() {
        Ok(Zeroizing::new(rpassword::prompt_password(prompt)?))
    } else {
        Ok(Zeroizing::new(read_line()?))
    }
}

/// New password, typed twice on a TTY.
pub fn new_secret(prompt: &str) -> std::io::Result<Zeroizing<String>> {
    let a = secret(prompt)?;
    if stdin_is_tty() {
        let b = secret("Repeat: ")?;
        if *a != *b {
            return Err(std::io::Error::other("the two entries don't match"));
        }
    }
    Ok(a)
}

/// Ask with an optional default shown in brackets; empty input keeps it.
pub fn ask(prompt: &str, default: Option<&str>) -> std::io::Result<String> {
    match default {
        Some(d) if !d.is_empty() => eprint!("{prompt} [{d}]: "),
        _ => eprint!("{prompt}: "),
    }
    std::io::stderr().flush()?;
    let s = read_line()?;
    Ok(if s.trim().is_empty() {
        default.unwrap_or("").to_string()
    } else {
        s.trim().to_string()
    })
}

pub fn confirm(prompt: &str) -> std::io::Result<bool> {
    eprint!("{prompt} [y/N]: ");
    std::io::stderr().flush()?;
    Ok(matches!(read_line()?.trim(), "y" | "Y" | "yes" | "YES"))
}
