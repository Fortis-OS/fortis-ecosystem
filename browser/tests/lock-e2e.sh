#!/usr/bin/env bash
# Fortis Lock end-to-end (docs/security §7.8): a real Chromium-based browser + the extension
# + the native host + a THROW-AWAY fortis-agent (own HOME, own socket, port 8124, no tailnet).
# A fake site, chat.fortis-test.example, is served over HTTPS on 127.0.0.1.
# Never touches your real agent, vault, lock or browser profile.
#
#   BROWSER=$(command -v google-chrome) tests/lock-e2e.sh
#   SHOTS=/some/dir tests/lock-e2e.sh      # also save screenshots of the error page
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ECO="$(cd "$ROOT/.." && pwd)"
AGENT="${AGENT:-$ECO/backend/target/debug/fortis-agent}"
CLI="${CLI:-$ECO/cli/target/debug/fortis}"
BROWSER="${BROWSER:-$(command -v brave-browser || command -v google-chrome || command -v chromium)}"

T=$(mktemp -d /tmp/fortis-lock-e2e.XXXXXX)
stop() { kill ${PIDS:-} 2>/dev/null || true; pkill -f -- "user-data-dir=$T/[p]rofile" 2>/dev/null || true; sleep 0.5; }
cleanup() { stop; rm -rf "$T"; }
trap cleanup EXIT
[ -n "${KEEP:-}" ] && trap 'stop; echo "kept $T"' EXIT
PIDS=""

(cd "$ROOT/host" && cargo build -q); HOSTBIN="$ROOT/host/target/debug/fortis-browser-host"
"$ROOT/extension/build.sh" >/dev/null
export HOME="$T/home" XDG_RUNTIME_DIR="$T/run" XDG_CONFIG_HOME="$T/home/.config" XDG_DATA_HOME="$T/home/.local/share"
mkdir -p "$XDG_CONFIG_HOME/fortis" "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR" "$HOME"
printf '[rest]\nport = 8124\ntailnet = false\n' > "$XDG_CONFIG_HOME/fortis/agent.toml"
chmod 600 "$XDG_CONFIG_HOME/fortis/agent.toml"

start_agent() {
  "$AGENT" >>"$T/agent.log" 2>&1 & AGENT_PID=$!; PIDS="$PIDS $AGENT_PID"
  for _ in $(seq 50); do [ -S "$XDG_RUNTIME_DIR/fortis/agent.sock" ] && break; sleep 0.1; done
}
start_agent
"$CLI" browser-lock add chat.fortis-test.example >/dev/null
printf 'lock-pass\nlock-pass\n' | "$CLI" browser-lock password >/dev/null

# The "real" site: its inline script must never run while locked.
mkdir -p "$T/www"
cat > "$T/www/index.html" <<'HTML'
<!doctype html><title>Real Chat</title>
<script>window.realRan = true;</script>
<h1 id="real">Real messages</h1>
HTML
echo pong > "$T/www/ping"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$T/key.pem" -out "$T/cert.pem" -days 2 \
  -subj /CN=chat.fortis-test.example >/dev/null 2>&1
python3 - "$T" <<'PY' & PIDS="$PIDS $!"
import http.server, ssl, sys, os
t = sys.argv[1]
class H(http.server.SimpleHTTPRequestHandler):
    def __init__(s, *a, **k): super().__init__(*a, directory=t + "/www", **k)
    def log_message(s, *a): pass
    def end_headers(s):
        s.send_header("Access-Control-Allow-Origin", "*")
        # A strict CSP like real sites send: the error page must still render.
        s.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self'; img-src 'self'")
        super().end_headers()
d = http.server.HTTPServer(("127.0.0.1", 8443), H)
c = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); c.load_cert_chain(t + "/cert.pem", t + "/key.pem")
d.socket = c.wrap_socket(d.socket, server_side=True); d.serve_forever()
PY
mkdir -p "$T/other" && echo '<!doctype html><title>other</title>' > "$T/other/index.html"
(cd "$T/other" && exec python3 -m http.server 8766 --bind 127.0.0.1 >/dev/null 2>&1) & PIDS="$PIDS $!"

CDP_PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1])')
PROFILE="$T/profile"; mkdir -p "$PROFILE/NativeMessagingHosts"
cat > "$PROFILE/NativeMessagingHosts/fortis_browser_host.json" <<JSON
{"name":"fortis_browser_host","description":"test","path":"$HOSTBIN","type":"stdio",
 "allowed_origins":["chrome-extension://$(cat "$ROOT/extension/chrome-extension-id.txt")/"]}
JSON
for d in "$XDG_CONFIG_HOME/BraveSoftware/Brave-Browser" "$XDG_CONFIG_HOME/google-chrome" "$XDG_CONFIG_HOME/chromium"; do
  mkdir -p "$d/NativeMessagingHosts" && cp "$PROFILE/NativeMessagingHosts/fortis_browser_host.json" "$d/NativeMessagingHosts/"
done
"$BROWSER" --headless=new --no-first-run --no-default-browser-check --user-data-dir="$PROFILE" \
  --disable-features=DisableLoadExtensionCommandLineSwitch --ignore-certificate-errors \
  --host-resolver-rules="MAP chat.fortis-test.example 127.0.0.1:8443" \
  --load-extension="$ROOT/extension/dist/chrome" --disable-extensions-except="$ROOT/extension/dist/chrome" \
  --remote-debugging-port=$CDP_PORT about:blank >"$T/browser.log" 2>&1 & PIDS="$PIDS $!"

export CDP_PORT CLI T
export EXT_ID=$(cat "$ROOT/extension/chrome-extension-id.txt")
export AGENT_PID AGENT
python3 "$ROOT/tests/lock_e2e.py"
