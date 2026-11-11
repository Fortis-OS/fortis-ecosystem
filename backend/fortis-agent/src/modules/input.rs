//! Remote keyboard / mouse backend and input validation (security §7.5).

use serde::Deserialize;

use crate::util::which;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Backend {
    Xdotool,
    Ydotool,
}

impl Backend {
    pub fn name(self) -> &'static str {
        match self {
            Backend::Xdotool => "xdotool",
            Backend::Ydotool => "ydotool",
        }
    }
}

/// `ydotool` on Wayland, `xdotool` on X11, or nothing. Checked per call: the
/// desktop usually starts after the agent.
pub fn backend() -> Option<Backend> {
    let env = crate::util::display_env();
    let set = |v: &str| env.iter().any(|(k, _)| *k == v);
    if set("WAYLAND_DISPLAY") {
        which("ydotool").map(|_| Backend::Ydotool)
    } else if set("DISPLAY") {
        which("xdotool").map(|_| Backend::Xdotool)
    } else {
        None
    }
}

#[derive(Clone, Debug, Deserialize, PartialEq)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum InputEvent {
    Mousemove { dx: f64, dy: f64 },
    Mousedown { button: u8 },
    Mouseup { button: u8 },
    Mouseclick { button: u8 },
    Scroll { dx: f64, dy: f64 },
    Key { key: String },
    Type { text: String },
}

pub const MAX_EVENTS_PER_SEC: u32 = 200;
const MODIFIERS: [&str; 4] = ["ctrl", "alt", "shift", "super"];

/// Linux input keycodes for the allowed key names (ydotool needs codes).
fn keycode(name: &str) -> Option<u16> {
    const LETTERS: [u16; 26] = [
        30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17,
        45, 21, 44,
    ];
    if name.len() == 1 {
        let c = name.as_bytes()[0];
        return match c {
            b'a'..=b'z' => Some(LETTERS[(c - b'a') as usize]),
            b'1'..=b'9' => Some((c - b'1') as u16 + 2),
            b'0' => Some(11),
            _ => None,
        };
    }
    Some(match name {
        "Return" => 28,
        "BackSpace" => 14,
        "Tab" => 15,
        "Escape" => 1,
        "space" => 57,
        "Up" => 103,
        "Down" => 108,
        "Left" => 105,
        "Right" => 106,
        "Home" => 102,
        "End" => 107,
        "Page_Up" => 104,
        "Page_Down" => 109,
        "Delete" => 111,
        "Insert" => 110,
        "ctrl" => 29,
        "alt" => 56,
        "shift" => 42,
        "super" => 125,
        f if f.starts_with('F') => match f[1..].parse::<u16>().ok()? {
            n @ 1..=10 => 58 + n,
            11 => 87,
            12 => 88,
            _ => return None,
        },
        _ => return None,
    })
}

/// `ctrl+shift+t` → (["ctrl","shift"], "t"); rejects anything not allow-listed.
fn parse_key(spec: &str) -> Option<(Vec<&str>, &str)> {
    if spec.len() > 40 {
        return None;
    }
    let parts: Vec<&str> = spec.split('+').collect();
    let (key, mods) = parts.split_last()?;
    if mods.iter().any(|m| !MODIFIERS.contains(m)) || MODIFIERS.contains(key) {
        return None;
    }
    keycode(key)?;
    Some((mods.to_vec(), key))
}

impl InputEvent {
    /// Clamp and check every field (security §7.5). Returns the safe event.
    pub fn validated(self) -> Option<Self> {
        let clamp = |v: f64, lim: f64| {
            if v.is_finite() {
                v.clamp(-lim, lim)
            } else {
                0.0
            }
        };
        let button_ok = |b: u8| (1..=3).contains(&b);
        Some(match self {
            InputEvent::Mousemove { dx, dy } => InputEvent::Mousemove {
                dx: clamp(dx, 2000.0),
                dy: clamp(dy, 2000.0),
            },
            InputEvent::Scroll { dx, dy } => InputEvent::Scroll {
                dx: clamp(dx, 50.0),
                dy: clamp(dy, 50.0),
            },
            InputEvent::Mousedown { button } if button_ok(button) => self,
            InputEvent::Mouseup { button } if button_ok(button) => self,
            InputEvent::Mouseclick { button } if button_ok(button) => self,
            InputEvent::Key { ref key } => {
                parse_key(key)?;
                self
            }
            InputEvent::Type { text } => {
                let clean: String = text
                    .chars()
                    .filter(|c| !c.is_control())
                    .take(1000)
                    .collect();
                if clean.is_empty() {
                    return None;
                }
                InputEvent::Type { text: clean }
            }
            _ => return None,
        })
    }

    /// The exact argument vector for the backend (never a shell).
    pub fn command(&self, b: Backend) -> Vec<String> {
        let s = |v: &[&str]| v.iter().map(|x| x.to_string()).collect::<Vec<_>>();
        let ydo_button = |btn: u8| match btn {
            1 => 0u8,
            2 => 2,
            _ => 1,
        };
        match (b, self) {
            (Backend::Xdotool, InputEvent::Mousemove { dx, dy }) => s(&[
                "mousemove_relative",
                "--",
                &(*dx as i64).to_string(),
                &(*dy as i64).to_string(),
            ]),
            (Backend::Xdotool, InputEvent::Mousedown { button }) => {
                s(&["mousedown", &button.to_string()])
            }
            (Backend::Xdotool, InputEvent::Mouseup { button }) => {
                s(&["mouseup", &button.to_string()])
            }
            (Backend::Xdotool, InputEvent::Mouseclick { button }) => {
                s(&["click", &button.to_string()])
            }
            (Backend::Xdotool, InputEvent::Scroll { dx, dy }) => {
                let (btn, n) = if dy.abs() >= dx.abs() {
                    (if *dy < 0.0 { "4" } else { "5" }, dy.abs())
                } else {
                    (if *dx < 0.0 { "6" } else { "7" }, dx.abs())
                };
                s(&[
                    "click",
                    "--repeat",
                    &(n.round().max(1.0) as i64).to_string(),
                    btn,
                ])
            }
            (Backend::Xdotool, InputEvent::Key { key }) => s(&["key", "--clearmodifiers", key]),
            (Backend::Xdotool, InputEvent::Type { text }) => {
                s(&["type", "--delay", "0", "--", text])
            }

            (Backend::Ydotool, InputEvent::Mousemove { dx, dy }) => s(&[
                "mousemove",
                "-x",
                &(*dx as i64).to_string(),
                "-y",
                &(*dy as i64).to_string(),
            ]),
            (Backend::Ydotool, InputEvent::Mousedown { button }) => {
                s(&["click", &format!("{:#04x}", 0x40 | ydo_button(*button))])
            }
            (Backend::Ydotool, InputEvent::Mouseup { button }) => {
                s(&["click", &format!("{:#04x}", 0x80 | ydo_button(*button))])
            }
            (Backend::Ydotool, InputEvent::Mouseclick { button }) => {
                s(&["click", &format!("{:#04x}", 0xC0 | ydo_button(*button))])
            }
            (Backend::Ydotool, InputEvent::Scroll { dx, dy }) => s(&[
                "mousemove",
                "--wheel",
                "-x",
                &(*dx as i64).to_string(),
                "-y",
                &(-*dy as i64).to_string(),
            ]),
            (Backend::Ydotool, InputEvent::Key { key }) => {
                let (mods, k) = parse_key(key).expect("validated");
                let codes: Vec<u16> = mods
                    .iter()
                    .chain(std::iter::once(&k))
                    .filter_map(|n| keycode(n))
                    .collect();
                let mut args = vec!["key".to_string()];
                args.extend(codes.iter().map(|c| format!("{c}:1")));
                args.extend(codes.iter().rev().map(|c| format!("{c}:0")));
                args
            }
            (Backend::Ydotool, InputEvent::Type { text }) => s(&["type", "--", text]),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ev(j: &str) -> Option<InputEvent> {
        serde_json::from_str::<InputEvent>(j).ok()?.validated()
    }

    #[test]
    fn validation() {
        assert_eq!(
            ev(r#"{"type":"mousemove","dx":99999,"dy":-5}"#),
            Some(InputEvent::Mousemove {
                dx: 2000.0,
                dy: -5.0
            })
        );
        assert_eq!(
            ev(r#"{"type":"scroll","dx":0,"dy":-900}"#),
            Some(InputEvent::Scroll { dx: 0.0, dy: -50.0 })
        );
        assert!(ev(r#"{"type":"mouseclick","button":9}"#).is_none());
        assert!(ev(r#"{"type":"key","key":"ctrl+c"}"#).is_some());
        assert!(ev(r#"{"type":"key","key":"F12"}"#).is_some());
        assert!(ev(r#"{"type":"key","key":"ctrl+alt+Delete"}"#).is_some());
        assert!(ev(r#"{"type":"key","key":"--help"}"#).is_none());
        assert!(ev(r#"{"type":"key","key":"XF86PowerOff"}"#).is_none());
        assert!(ev(r#"{"type":"key","key":"ctrl"}"#).is_none());
        assert_eq!(
            ev("{\"type\":\"type\",\"text\":\"rm -rf\\n\"}"),
            Some(InputEvent::Type {
                text: "rm -rf".into()
            })
        );
        assert!(ev(r#"{"type":"exec","cmd":"x"}"#).is_none());
        assert_eq!(
            ev(&format!(
                r#"{{"type":"type","text":"{}"}}"#,
                "a".repeat(5000)
            ))
            .map(|e| match e {
                InputEvent::Type { text } => text.len(),
                _ => 0,
            }),
            Some(1000)
        );
    }

    #[test]
    fn commands_never_take_text_as_options() {
        let t = InputEvent::Type {
            text: "--window 1".into(),
        };
        assert_eq!(
            t.command(Backend::Xdotool),
            vec!["type", "--delay", "0", "--", "--window 1"]
        );
        assert_eq!(
            t.command(Backend::Ydotool),
            vec!["type", "--", "--window 1"]
        );
        let m = InputEvent::Mousemove { dx: -3.0, dy: 4.0 };
        assert_eq!(
            m.command(Backend::Xdotool),
            vec!["mousemove_relative", "--", "-3", "4"]
        );
        let k = InputEvent::Key {
            key: "ctrl+c".into(),
        };
        assert_eq!(
            k.command(Backend::Ydotool),
            vec!["key", "29:1", "46:1", "46:0", "29:0"]
        );
        assert_eq!(
            InputEvent::Mouseclick { button: 3 }.command(Backend::Ydotool),
            vec!["click", "0xc1"]
        );
    }
}
