# fortis-browser

Password autofill and save for the laptop: a WebExtension for Chromium-based
browsers (Chrome, Brave, Chromium, Edge) and Firefox, plus `fortis-browser-host`,
the native-messaging host that forwards a fixed set of messages to the local
`fortis-agent` over its IPC socket.

Spec: [security §3.6, §7.7](../docs/security/README.md), [API §6](../docs/api/README.md).

```text
extension/
  src/                 background.js, content.js, popup.{html,js,css}, icons
  manifest.base.json   shared manifest; build.sh writes dist/chrome (MV3) and dist/firefox (MV2)
  chrome-public-key.txt / chrome-extension-id.txt   pinned Chromium extension ID
host/                  Rust bin "fortis-browser-host"
install.sh             build + install the host + register it with each browser
```

## Install

```bash
./install.sh
```

Then load `extension/dist/chrome` as an unpacked extension (Developer mode) in
Chrome/Brave, or `extension/dist/firefox/manifest.json` as a temporary add-on in
Firefox. Release Firefox only keeps unsigned add-ons until it restarts.

## Use

- Open a login page → click the Fortis icon → **Fill**, or press **Ctrl+Shift+L**
  (fills directly when exactly one login matches).
- Sign in somewhere new → the icon shows **+** (new) or **!** (changed password) →
  open it → **Save** / **Update** / **Never for this site**.
- Unlock and lock the vault from the popup.

## Tests

```bash
(cd host && cargo test)
./tests/e2e.sh        # real Chrome/Brave + a throw-away agent; never touches your vault
```
