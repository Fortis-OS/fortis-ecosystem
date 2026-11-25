# fortis-browser

Password autofill and save for the laptop: a WebExtension for Chromium-based
browsers (Chrome, Brave, Chromium, Edge) and Firefox, plus `fortis-browser-host`,
the native-messaging host that forwards a fixed set of messages to the local
`fortis-agent` over its IPC socket.

It also carries **Fortis Lock**: hide chosen sites (WhatsApp Web, mail, …) behind a copy
of the browser's own "Your connection is not private" error before you hand the laptop to
someone, and unlock with a password or your phone's fingerprint.

Spec: [security §3.6, §7.7, §7.8](../docs/security/README.md), [API §6](../docs/api/README.md).

```text
extension/
  src/                 background.js, content.js, popup.{html,js,css}, icons
  src/lock/            Fortis Lock: background.js, page.js (the error page), options page,
                       pages/{chrome,brave,firefox}.js (generated copies of each browser's page)
  manifest.base.json   shared manifest; build.sh writes dist/chrome (MV3) and dist/firefox (MV2)
  chrome-public-key.txt / chrome-extension-id.txt   pinned Chromium extension ID
host/                  Rust bin "fortis-browser-host"
tools/error-pages/     update.py: re-copies the error pages from the installed browsers
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

### Fortis Lock

1. Add sites: popup → **Settings** (or `fortis browser-lock add web.whatsapp.com`, or the phone's
   Laptop tab). A site covers its subdomains.
2. Set an unlock password there (or `fortis browser-lock password`). Without one, only the phone
   can unlock.
3. Lock: **Alt+Shift+L** in the browser, the popup's **Lock sites**, the phone, or
   `fortis browser-lock on` — bind that to a KDE shortcut (System Settings → Shortcuts → Add New →
   Command) to lock from anywhere. Locking also locks the vault.
4. Unlock: on a locked site, **triple-click the warning icon** (Chrome/Brave: the red triangle;
   Firefox: the fox) → password, or **Use phone** for a fingerprint. Or unlock from the phone,
   or `fortis browser-lock off [--phone]`.

It stops casual use, nothing more: anyone who knows it's there can turn the extension off.
When a browser update changes its error page, run `tools/error-pages/update.py` and rebuild.

## Tests

```bash
(cd host && cargo test)
./tests/e2e.sh        # real Chrome/Brave + a throw-away agent; never touches your vault
./tests/lock-e2e.sh   # Fortis Lock in real Brave + a throw-away agent
python3 tests/lock_firefox.py   # Fortis Lock's page in a throw-away headless Firefox
```
