# backend — Rust workspace

The laptop side of Fortis and the crypto shared with the phone. Spec: [docs](../docs/README.md) — mainly [Security](../docs/security/README.md), [Architecture §2–§3](../docs/architecture/README.md) and the [API](../docs/api/README.md).

| Crate | Type | What it is |
|---|---|---|
| [fortis-core](./fortis-core/) | lib | Vault crypto (Argon2id, HKDF, XChaCha20-Poly1305), SQLCipher vault (`db` feature), sync rules, P-256 signatures and canonical bytes, TOTP, password generator, autofill URL matching. No networking. |
| [fortis-agent](./fortis-agent/) | bin | The laptop daemon: loopback REST for the desktop app, signed Tailscale REST + events WebSocket for the phone, IPC socket for the CLI / PAM / browser host. Owns the vault. |
| [fortis-pam](./fortis-pam/) | cdylib | `pam_fortis.so` — fingerprint-approved `sudo`, verified against a root-owned key file, with lockout safeguards. |
| [fortis-bindings](./fortis-bindings/) | cdylib | UniFFI bindings used by the Android app (key derivation, entry crypto, TOTP, canonical bytes, signature checks, autofill matching). |

```bash
cargo build --release -p fortis-agent -p fortis-pam
cargo test --workspace
cargo clippy --workspace --all-targets
```

Run a throw-away agent for development: [docs/development §2](../docs/development/README.md). The PAM module has its own sandboxed tests: [fortis-pam/README.md](./fortis-pam/README.md).
