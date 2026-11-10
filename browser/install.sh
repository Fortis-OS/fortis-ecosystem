#!/usr/bin/env bash
# Install fortis-browser-host and register it with Firefox, Chrome, Chromium
# and Brave for the current user (docs/installation, docs/security §7.7).
set -euo pipefail
cd "$(dirname "$0")"
BIN="$HOME/.local/bin/fortis-browser-host"
CHROME_ID=$(cat extension/chrome-extension-id.txt)
FIREFOX_ID="fortis@fortis.local"

(cd host && cargo build --release -q)
install -Dm755 host/target/release/fortis-browser-host "$BIN"
extension/build.sh >/dev/null

chromium_manifest() {
  cat <<JSON
{
  "name": "fortis_browser_host",
  "description": "Fortis password manager (talks to the local fortis-agent)",
  "path": "$BIN",
  "type": "stdio",
  "allowed_origins": ["chrome-extension://$CHROME_ID/"]
}
JSON
}
firefox_manifest() {
  cat <<JSON
{
  "name": "fortis_browser_host",
  "description": "Fortis password manager (talks to the local fortis-agent)",
  "path": "$BIN",
  "type": "stdio",
  "allowed_extensions": ["$FIREFOX_ID"]
}
JSON
}

put() { install -d -m 700 "$1"; "$2" > "$1/fortis_browser_host.json"; chmod 644 "$1/fortis_browser_host.json"; echo "  registered: $1"; }

echo "fortis-browser-host → $BIN"
for d in "$HOME/.config/google-chrome" "$HOME/.config/chromium" "$HOME/.config/BraveSoftware/Brave-Browser" "$HOME/.config/microsoft-edge" "$HOME/.config/vivaldi"; do
  [ -d "$d" ] && put "$d/NativeMessagingHosts" chromium_manifest
done
# Firefox (deb and snap; the snap reads this path through the WebExtensions portal).
if [ -d "$HOME/.mozilla" ] || [ -d "$HOME/snap/firefox" ]; then
  put "$HOME/.mozilla/native-messaging-hosts" firefox_manifest
fi
cat <<MSG

Now load the extension:
  Chrome / Brave:  open chrome://extensions (brave://extensions), turn on Developer mode,
                   "Load unpacked" → $(pwd)/extension/dist/chrome
                   (extension ID must be $CHROME_ID)
  Firefox:         about:debugging#/runtime/this-firefox → "Load Temporary Add-on…"
                   → $(pwd)/extension/dist/firefox/manifest.json   (until Firefox restarts)
MSG
