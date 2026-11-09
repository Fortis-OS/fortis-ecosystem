# fortis-desktop

The Fortis vault on your desktop: create/unlock the vault, browse and search entries, view and copy secrets, live 2FA codes, add/edit/delete entries, a password generator, and changing the master password.

It is a client of [fortis-agent](https://github.com/Fortis-Ecosystem/fortis-backend) over the agent's **loopback** API. Spec: [docs/development §4](https://github.com/Fortis-Ecosystem/docs/blob/main/development/README.md#4-fortis-desktop), [security §5.2](https://github.com/Fortis-Ecosystem/docs/blob/main/security/README.md).

## Security design

- **All agent calls happen in Rust** (`src-tauri/agent`). The agent token is read from `~/.config/fortis/agent.token` in Rust on every call and is never given to the webview.
- **Copying secrets goes agent → Rust → clipboard**; the webview only asks "copy field X of entry Y". The clipboard is cleared after 30 s if it still holds that secret.
- **Locked-down webview:** strict CSP (`default-src 'self'`, no remote scripts, fonts or images), `withGlobalTauri` off, prototype freezing, and the window may call **only** Fortis' own 13 commands (`build.rs` + `capabilities/default.json`) — no plugin, filesystem, shell or opener permissions.
- **No `innerHTML`:** all text is set with `textContent`, so entry titles or notes can't inject markup. Icons and fonts are bundled; nothing loads from the internet.
- No dependency on `fortis-core` — the desktop app never opens the vault file.

## Build

System libraries (Debian/Ubuntu):

```bash
sudo apt install -y libwebkit2gtk-4.1-dev libsoup-3.0-dev libjavascriptcoregtk-4.1-dev librsvg2-dev libxdo-dev libssl-dev libayatana-appindicator3-dev
```

```bash
npm install
npm run tauri dev     # development
npm run tauri build   # .deb and AppImage in src-tauri/target/release/bundle/
```

The agent must be running (`systemctl --user status fortis-agent`).

## Layout

```text
src/                 UI (TypeScript, no framework)
├── api.ts           typed wrappers for the Rust commands
├── dom.ts           element builder (textContent only), bundled SVG icons, toast
├── editor.ts        add/edit dialog → EntryInput JSON
├── main.ts          screens: agent unreachable, create vault, unlock, vault, generator, settings
└── styles.css
src-tauri/
├── agent/           Tauri-free agent client (token, port, errors, field extraction) — unit-tested
├── src/lib.rs       Tauri commands
├── build.rs         allow-list of app commands
└── capabilities/    grants only those commands
```

## Test

```bash
cd src-tauri && cargo test -p fortis-desktop-agent   # agent client (no WebKit needed)
npm run build                                       # type-check + bundle the UI
```
