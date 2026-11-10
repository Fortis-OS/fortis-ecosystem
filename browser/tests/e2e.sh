#!/usr/bin/env bash
# End-to-end test: a real Chromium-based browser + the extension + the native
# host + a THROW-AWAY fortis-agent (own HOME, own socket, port 8124, no tailnet).
# Never touches your real vault or agent.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ECO="$(cd "$ROOT/.." && pwd)"
AGENT="${AGENT:-$ECO/backend/target/debug/fortis-agent}"
CLI="${CLI:-$ECO/cli/target/debug/fortis}"
BROWSER="${BROWSER:-$(command -v brave-browser || command -v google-chrome || command -v chromium)}"

T=$(mktemp -d /tmp/fortis-e2e.XXXXXX)
# The browser launcher forks; stop it by its profile dir ("[p]" so pkill can't match itself).
stop() { kill ${PIDS:-} 2>/dev/null || true; pkill -f -- "user-data-dir=$T/[p]rofile" 2>/dev/null || true; sleep 0.5; }
cleanup() { stop; rm -rf "$T"; }
trap cleanup EXIT
[ -n "${KEEP:-}" ] && trap 'stop; echo "kept $T"' EXIT
PIDS=""

# Build with the real HOME (rustup lives there), then switch to the sandbox.
(cd "$ROOT/host" && cargo build -q); HOSTBIN="$ROOT/host/target/debug/fortis-browser-host"
"$ROOT/extension/build.sh" >/dev/null
export HOME="$T/home" XDG_RUNTIME_DIR="$T/run" XDG_CONFIG_HOME="$T/home/.config" XDG_DATA_HOME="$T/home/.local/share"
mkdir -p "$XDG_CONFIG_HOME/fortis" "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR" "$HOME"
printf '[rest]\nport = 8124\ntailnet = false\n' > "$XDG_CONFIG_HOME/fortis/agent.toml"
chmod 600 "$XDG_CONFIG_HOME/fortis/agent.toml"


"$AGENT" >"$T/agent.log" 2>&1 & PIDS="$PIDS $!"
for _ in $(seq 50); do [ -S "$XDG_RUNTIME_DIR/fortis/agent.sock" ] && break; sleep 0.1; done
echo "test-master-password-123" | "$CLI" init --password-stdin >/dev/null
echo '{"title":"Local","entry_type":{"Login":{"username":"alice","password":"pw1","urls":["http://localhost:8765"],"totp":{"secret_base32":"JBSWY3DPEHPK3PXP","digits":6,"period":30,"algorithm":"Sha1"}}}}' \
  | "$CLI" add --from-json - >/dev/null

# One file per test page, so each tab has a unique URL.
mkdir -p "$T/www"; for n in a b new chg same; do cp "$ROOT/tests/login.html" "$T/www/login-$n.html"; done; cp "$ROOT/tests/done.html" "$T/www/"
(cd "$T/www" && exec python3 -m http.server 8765 --bind 127.0.0.1 >/dev/null 2>&1) & PIDS="$PIDS $!"

CDP_PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')
PROFILE="$T/profile"; mkdir -p "$PROFILE/NativeMessagingHosts"
cat > "$PROFILE/NativeMessagingHosts/fortis_browser_host.json" <<JSON
{"name":"fortis_browser_host","description":"test","path":"$HOSTBIN","type":"stdio",
 "allowed_origins":["chrome-extension://$(cat "$ROOT/extension/chrome-extension-id.txt")/"]}
JSON
# Browsers look in their default config dir (inside the sandbox HOME) too.
for d in "$XDG_CONFIG_HOME/BraveSoftware/Brave-Browser" "$XDG_CONFIG_HOME/google-chrome" "$XDG_CONFIG_HOME/chromium"; do
  mkdir -p "$d/NativeMessagingHosts" && cp "$PROFILE/NativeMessagingHosts/fortis_browser_host.json" "$d/NativeMessagingHosts/"
done
"$BROWSER" --headless=new --no-first-run --no-default-browser-check --user-data-dir="$PROFILE" \
  --disable-features=DisableLoadExtensionCommandLineSwitch \
  --load-extension="$ROOT/extension/dist/chrome" --disable-extensions-except="$ROOT/extension/dist/chrome" \
  --remote-debugging-port=$CDP_PORT about:blank >"$T/browser.log" 2>&1 & PIDS="$PIDS $!"

CDP_PORT=$CDP_PORT EXT_ID=$(cat "$ROOT/extension/chrome-extension-id.txt") CLI="$CLI" python3 "$ROOT/tests/e2e.py"
