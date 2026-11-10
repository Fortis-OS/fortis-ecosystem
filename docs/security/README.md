# Fortis Ecosystem — Security Model

This document is the **security specification** for Fortis. Code must implement what is written here. If code and this document disagree, the code is wrong.

Every rule below is written as **MUST** / **MUST NOT** / **SHOULD**. A MUST rule is a release blocker.

---

## 1. Threat Model

### Assets

| Asset | Where it lives |
|---|---|
| Vault entries (passwords, cards, notes, TOTP secrets) | `vault.db` on the laptop, encrypted copy on the phone |
| Master password | Only in the user's head; briefly in agent / phone memory during unlock |
| Vault key | In agent memory while unlocked; wrapped on disk and on the phone |
| Phone device keys | Android Keystore (hardware-backed, non-exportable) |
| Root access on the laptop | Guarded by `sudo` + `pam_fortis` |
| Laptop files and controls | Reachable through the agent's file and action endpoints |
| Laptop keyboard and mouse | Reachable through remote input (§7.5) — equivalent to running commands as the user |
| Phone camera | Streamed to the laptop as a webcam (§7.6) |
| Phone notifications and photos | Mirrored / uploaded to the laptop (§6.4) |
| Backups | `/mnt/FortisVault` and `~/FortisVault` on the phone |

### Attackers we defend against

| Attacker | What they can do | Defence |
|---|---|---|
| **Thief with the laptop powered off** | Reads the disk | Vault is SQLCipher-encrypted with a key derived from the master password (§3) |
| **Thief with the phone** | Holds a locked phone | Phone vault key is wrapped by a Keystore key that needs biometric/device credential (§6) |
| **Another device on the LAN / Wi-Fi** | Sends packets to the laptop | Agent never listens on LAN interfaces (§5.1) |
| **Another device on the tailnet** | Reaches the Tailscale listener | Every request must be signed by a paired device key (§5.3) |
| **A web page in the user's browser** | Sends requests to `127.0.0.1:8023`, DNS rebinding | No CORS, `Host` header allow-list, bearer token never exposed to web content (§5.2) |
| **Another local user account** | Reads files, connects to sockets | `0700` directories, `0600` files, socket peer-UID check (§7) |
| **Malicious process running as the user** | Everything the user can do | **Cannot forge a sudo approval** — the PAM module verifies the phone's signature itself against a root-owned key (§4). Can read the vault while it is unlocked (accepted, see below). |
| **Network observer** | Sniffs traffic | Phone ↔ laptop traffic only travels inside Tailscale (WireGuard) |
| **Replay of a captured request or approval** | Re-sends old messages | Timestamps, nonces, single-use challenge IDs (§4, §5.3) |
| **Online guessing** | Brute-forces pairing code or master password | Attempt limits and back-off (§5.4, §3.4) |
| **Someone on the phone's Wi-Fi** | Connects to services the phone runs | The phone's webcam stream listens only on its Tailscale address, needs a one-time token, and only accepts the paired laptop (§7.6) |
| **Someone holding the unlocked phone, or malware that can use the request key** | Sends signed requests | Can use ordinary remote features, but **cannot start remote keyboard/mouse control or approve sudo** without a fresh fingerprint (§5.6, §4) |

### Accepted risks (out of scope)

- **Root compromise** of the laptop, or a **rooted / compromised phone**.
- **Same-user malware while the vault is unlocked.** Like `ssh-agent` or any desktop password manager, a process running as the user can talk to the agent while it is unlocked. Auto-lock (§3.5) limits the window. It still cannot gain root through Fortis (§4).
- **Weak master password.** Argon2id slows guessing; it cannot save `password123`.
- **Tailscale account compromise.** Gives network reach to the tailnet listener, but not past request signing.

---

## 2. Cryptographic Primitives

Only these primitives are allowed. Adding one requires updating this table.

| Purpose | Primitive | Rust crate | Parameters |
|---|---|---|---|
| Password → key | Argon2id v1.3 | `argon2` | m = 65 536 KiB, t = 3, p = 4, 32-byte output, 16-byte random salt |
| Key separation | HKDF-SHA-256 | `hkdf` + `sha2` | distinct `info` label per derived key |
| Database at rest | SQLCipher 4 | `rusqlite` (`bundled-sqlcipher`) | raw 256-bit key (`PRAGMA key = "x'…'"`), SQLCipher KDF bypassed |
| Entry / key encryption | XChaCha20-Poly1305 | `chacha20poly1305` | 24-byte random nonce, 16-byte tag, AAD as specified |
| Device & approval signatures | ECDSA P-256 / SHA-256 | `p256` (laptop), Android Keystore (phone) | DER signatures, uncompressed SEC1 public keys |
| Constant-time compare | — | `subtle` | for every secret comparison |
| Randomness | OS CSPRNG | `rand::rngs::OsRng` / `getrandom` | never `thread_rng` for keys, nonces or codes |
| TOTP | RFC 6238 | `totp-rs` | SHA-1, 6 digits, 30 s (per-entry overrides allowed) |

**Why P-256 instead of Ed25519:** the phone's keys must live in the Android Keystore and be gated by a biometric prompt. P-256 is supported there (hardware-backed, StrongBox where available) on every supported Android version; Ed25519 is not reliably hardware-backed.

---

## 3. Vault Encryption

### 3.1 Files

| Path | Mode | Contents |
|---|---|---|
| `~/.local/share/fortis/` | `0700` | Data directory |
| `~/.local/share/fortis/vault.meta.json` | `0600` | Plaintext header: format version, KDF name + parameters, salt (base64) |
| `~/.local/share/fortis/vault.db` | `0600` | SQLCipher database |

`vault.meta.json` example:

```json
{
  "format_version": 2,
  "kdf": { "alg": "argon2id", "m_kib": 65536, "t": 3, "p": 4, "salt": "base64-16-bytes" },
  "vault_id": "uuid-v4"
}
```

The salt **MUST** be random per vault (generated once at vault creation). A hardcoded or shared salt is forbidden.

### 3.2 Key hierarchy

```text
master password
   │  Argon2id(salt from vault.meta.json)
   ▼
master key (32 B, never stored)
   ├── HKDF(info="fortis/v2/db-key")   → db key   → SQLCipher raw key
   └── HKDF(info="fortis/v2/wrap-key") → wrap key → decrypts the wrapped vault key
                                                        │
vault key (32 B, random, generated once) ◄──────────────┘
   └── encrypts each entry (XChaCha20-Poly1305)
```

- The **vault key** is stored in the `metadata` table as `wrapped_vault_key = nonce ‖ XChaCha20-Poly1305(wrap key, vault key, AAD = "fortis/v2/vault-key" ‖ vault_id)`.
- The master key **MUST NOT** be used directly for more than one purpose. It only feeds HKDF.
- Changing the master password re-wraps the vault key and runs `PRAGMA rekey` with the new db key. Entries are not re-encrypted.

### 3.3 Entry encryption

Each row in `entries` stores one encrypted entry:

```text
encrypted_data = nonce(24) ‖ ciphertext ‖ tag(16)
AAD            = "fortis/v2/entry" ‖ entry_id (16 B) ‖ version (u64, big-endian)
```

Binding the entry ID and version into the AAD means a blob cannot be moved to another entry or relabelled with another version without decryption failing. The writer (agent or phone) increments `version` on every change (see sync rules in the [API reference](../api/README.md#sync)).

The plaintext is the JSON-serialised `VaultEntry`, including the folder. Titles, usernames, URLs and folder names are inside the ciphertext — nothing about an entry is visible without the vault key except its ID, version, deleted flag and timestamps.

### 3.4 Unlock

1. Read `vault.meta.json`, derive the master key with Argon2id, derive db key and wrap key with HKDF.
2. Open SQLCipher with the db key; read `wrapped_vault_key`; decrypt it with the wrap key.
3. Any failure returns the same error (`invalid_password`) — no hint whether the password or the file was wrong.
4. Failed attempts are rate-limited **per agent process**: after 5 consecutive failures, each further attempt waits `2^(n-5)` seconds (cap 300 s). The counter resets on success.
5. The master key, db key and wrap key **MUST** be zeroised as soon as the database is open and the vault key is unwrapped.

### 3.5 Locked / unlocked state

- While **unlocked**, the agent holds the vault key and one open SQLCipher connection, both behind a single mutex. Key material is in `zeroize`-on-drop types.
- The agent **MUST** lock (drop and zeroise key + connection) when:
  - no vault request has arrived for `vault.auto_lock_minutes` (default **15**),
  - `POST /api/v1/vault/lock` or IPC `lock` is received,
  - the agent receives `SIGTERM`/`SIGINT`.
- The agent **SHOULD** also lock on the logind `Lock` signal for the user's session.
- Vault endpoints return `423 Locked` while locked.

### 3.6 Login matching (autofill)

Autofill on both devices uses **one** rule, implemented once in `fortis-core` (`urlmatch`) and exposed to the phone through `fortis-bindings`:

- A Login's `urls` are its targets. Each is either a web address (`https://github.com`) or an Android app (`androidapp://com.github.android`).
- **Web:** the page's host `P` matches a saved web URL with host `H` when `P == H` or `P` ends with `"." + H` (so `github.com` fills on `gist.github.com`, never on `github.com.evil.io` or `notgithub.com`). The host is lowercased, IDNs compared in punycode, a trailing dot and the port are ignored, IP addresses must match exactly. `H` must have at least one dot (no `"com"`).
- **Scheme:** the page must be `https`, or `http` on `localhost` / `127.0.0.1`. A saved `https` login is never filled into an `http` page.
- **App:** an Android app matches only an `androidapp://<package>` URL with exactly its package name. If Android reports a **web domain** for the form (a browser), the web rule is used and the package name is ignored — so an app can't claim a site's password by being a browser.
- No match → nothing is offered. There is no fuzzy matching on titles.

### 3.7 Secrets in memory

- All secret strings use `SecretString` (`zeroize::Zeroizing<String>`).
- Secrets **MUST NOT** be logged. `Debug` output of secret types prints `"[redacted]"`.
- The agent **SHOULD** call `mlockall` / disable core dumps (`prctl(PR_SET_DUMPABLE, 0)`) at start-up.

---

## 4. Biometric `sudo` (`pam_fortis`)

### 4.1 Design goal

Approving `sudo` with a fingerprint on the paired phone. **A compromised user account must not be able to forge an approval**, and any failure must fall back to the normal password prompt.

### 4.2 Trust anchor

The PAM module trusts **only** the approval public keys in:

```text
/etc/fortis/pam/<username>.json      owner root:root, mode 0644, directory /etc/fortis/pam 0755 root:root
```

```json
{
  "version": 1,
  "devices": [
    { "device_id": "uuid", "name": "Pixel 8", "approval_pubkey": "base64 SEC1 P-256", "enrolled_at": 1731110400 }
  ]
}
```

This file is written only by `sudo fortis pam-enroll` (§4.5). The PAM module **MUST** refuse to use the file (and return `PAM_IGNORE`) if it, or any parent directory, is not owned by root or is group/world-writable.

The agent's own `paired_devices.json` is **never** trusted by the PAM module.

### 4.3 Flow

```text
sudo ──► pam_fortis (runs inside sudo, as root)
           1. Read /etc/fortis/pam/<user>.json (checks of §4.2). No file → PAM_IGNORE.
           2. Generate challenge: request_id (16 B), nonce (32 B), issued_at (unix s).
           3. Connect to /run/user/<uid>/fortis/agent.sock (timeout 1 s). Fail → PAM_IGNORE.
           4. Send auth_request { challenge, user, host, service, tty }.
                 │
                 ▼
         fortis-agent (runs as the user)
           5. Push the request to every online paired phone over its event stream.
                 │
                 ▼
         fortis-mobile
           6. Show notification → full-screen prompt:
              "Approve sudo for <user> on <host> (tty <tty>)?"
           7. BiometricPrompt unlocks the approval key (per-use auth).
           8. Sign canonical approval bytes (§4.4). Send to agent.
                 │
                 ▼
         fortis-agent
           9. Relay { request_id, device_id, decision, signature } back over the socket.
                 │
                 ▼
         pam_fortis
          10. Check request_id matches and now − issued_at ≤ 30 s.
          11. Look up device_id in /etc/fortis/pam/<user>.json; verify the P-256 signature.
          12. Valid "approve" → PAM_SUCCESS. Anything else → PAM_IGNORE.
```

- The PAM module generates the nonce itself, so the agent cannot pre-compute or replay approvals.
- The PAM module verifies the signature itself, so a fake or compromised agent can only **deny** service (falls back to password), never grant access.
- On the phone, **Approve** and **Deny** both need a fingerprint (the approval key signs either answer); **Ignore** signs nothing and the laptop times out to the password. The prompt shows user, laptop, service and tty, blocks screenshots, and expires on the phone's own clock (receipt time + `expires_at − issued_at`, at most 60 s), so a skewed phone clock can't keep a stale prompt open. Pending requests live in memory only.
- If fingerprints were changed since pairing the approval key is invalidated; the phone says so and the laptop must be re-paired.
- The wait is bounded at **30 s** (`auth.approval_timeout_secs`, max 60). The user can press Ctrl-C to fall back immediately.

### 4.4 Canonical approval bytes

```text
"fortis/v2/sudo-approval\n"
request_id   (hex)  "\n"
nonce        (hex)  "\n"
issued_at    (decimal) "\n"
user         "\n"
host         "\n"
service      "\n"
tty          "\n"
decision     ("approve" | "deny")
```

The phone signs exactly these bytes (UTF-8) with ECDSA P-256/SHA-256. Fields **MUST NOT** contain `\n`; the PAM module rejects any that do.

### 4.5 Enrollment and revocation

- `fortis pair` pairs the phone with the agent (§5.4). This alone does **not** enable sudo approval.
- `sudo fortis pam-enroll` reads the user's paired devices from the agent, prints each device's name and approval-key fingerprint (first 16 hex chars of SHA-256 of the key), and writes `/etc/fortis/pam/<user>.json` after the user confirms. It requires the normal sudo password once.
- `fortis devices revoke <device_id>` removes the device from the agent, then tells the user to run `sudo fortis pam-enroll` to drop it from the PAM file. Until then the revoked phone can still approve sudo but cannot reach the agent, so it never receives requests.

### 4.6 PAM configuration and fail-safety

```text
# /etc/pam.d/sudo — directly above the first auth line / @include common-auth
-auth   sufficient    pam_fortis.so
```

- The leading **`-`** is required. It tells libpam to skip the line silently if `pam_fortis.so` is missing or can't be loaded, so a deleted, moved or broken module file can never break `sudo`.
- `pam_fortis` returns only `PAM_SUCCESS` or `PAM_IGNORE`. It **MUST NOT** return `PAM_AUTH_ERR` or any value that blocks the password fallback.
- It **MUST NOT** contain any development bypass. There is no "mock success" path in any build profile.
- It only acts for the services named in its `services=` argument (default `sudo,sudo-i`); for any other service it returns `PAM_IGNORE` at once. It is not supported in `login`, `sshd`, display-manager or `common-auth` stacks.

### 4.7 Lockout safeguards

A sudo lockout is the worst failure Fortis can cause, so the module and its installation follow these extra rules.

**Module construction**

| Rule | Why |
|---|---|
| Every exported function body runs inside `catch_unwind`; any panic → `PAM_IGNORE` | A Rust panic must never unwind into `sudo` |
| The crate is built with `panic = "unwind"` (never `abort`) | `catch_unwind` only works with unwinding |
| No threads, no async runtime, no global state, std + `fortis-core` (no `db` feature) + `libc` only | Less code that can crash inside `sudo` |
| `unsafe` only in the thin FFI layer (reading PAM items, the conversation message, `getpwnam_r`, `gethostname`); all logic is safe Rust with unit tests | Crash-level bugs live in unsafe code |
| A **hard deadline** of `timeout + 2 s` from entry. Every socket operation uses a timeout computed from what is left; when the deadline passes the module returns `PAM_IGNORE` | A bug or a stuck agent can't hang `sudo` |
| Reads are size-limited (trust file 64 KiB, agent reply 64 KiB) | No unbounded allocation |
| Nothing is written to stdout/stderr; user messages go through the PAM conversation (`PAM_TEXT_INFO`), diagnostics only to syslog with `debug` | Doesn't corrupt `sudo`'s terminal I/O |

**Installation** (done by `sudo fortis pam-install`, never by hand-editing):

1. The module must already be installed in the system PAM module directory (`/usr/lib/x86_64-linux-gnu/security/` on Debian/Ubuntu, `/usr/lib64/security/` on Fedora), owned by root and not group/world-writable.
2. **Stage 1 — test service.** `pam-install` writes `/etc/pam.d/fortis-test` (Fortis followed by `pam_deny.so`). `sudo fortis pam-test` runs a real PAM conversation against it. `sudo` itself is untouched.
3. **Stage 2 — sudo.** Only `sudo fortis pam-install --sudo` edits `/etc/pam.d/sudo`, and only after `pam-test` has passed **for the module file currently installed** (`pam-test` records the module's SHA-256 in `/etc/fortis/pam-test.ok`; a rebuilt or upgraded module must be tested again). It:
   - copies the original to `/etc/pam.d/sudo.fortis-backup` (never overwriting an existing backup),
   - inserts exactly one `-auth sufficient pam_fortis.so` line directly above the first `auth` line or `@include common-auth`,
   - writes the file atomically (temp file, fsync, rename; root `0644`),
   - re-reads it and checks that the only difference from the original is that one line; otherwise restores the original and fails.
4. `sudo fortis pam-uninstall` removes every `pam_fortis` line from `/etc/pam.d/sudo` (same atomic write and check) and deletes `/etc/pam.d/fortis-test`.

**Recovery that doesn't need `sudo`**: `pkexec` uses its own PAM service (`polkit-1`), which Fortis never touches, so `pkexec fortis pam-uninstall` or `pkexec cp /etc/pam.d/sudo.fortis-backup /etc/pam.d/sudo` works even if `sudo` were broken. Keep a `sudo -s` shell open while enabling Stage 2 as a second way back.

---

## 5. Agent Network Security

### 5.1 Listeners

| Listener | Address | Who uses it | Auth |
|---|---|---|---|
| Loopback REST | `127.0.0.1:8023` | fortis-desktop (from its Rust backend) | Local bearer token (§5.2) |
| Tailnet REST | `<tailscale IPv4>:8023` | fortis-mobile | Signed device requests (§5.3) |
| IPC socket | `$XDG_RUNTIME_DIR/fortis/agent.sock` | fortis-cli, pam_fortis | Peer UID (§7.2) |

- The agent **MUST NOT** bind `0.0.0.0`, `::` or any LAN address. It refuses to start if configured to.
- The tailnet listener is only started if `rest.tailnet = true` and a Tailscale IPv4 address (`100.64.0.0/10`) is found. Connections whose peer address is outside `100.64.0.0/10` are dropped.
- Each listener accepts **only** its own auth scheme: bearer tokens are rejected on the tailnet listener; device signatures are rejected on loopback.
- Bind failures are logged and the agent exits non-zero (systemd restarts it). No `unwrap()` panics.

### 5.2 Local bearer token (desktop)

- On first start the agent generates a 256-bit random token and writes it to `~/.config/fortis/agent.token` (`0600`, directory `0700`). It is never read from an environment variable in production.
- Compared with `subtle::ConstantTimeEq`.
- fortis-desktop reads the token **in its Rust backend** and calls the agent from Rust. The token **MUST NOT** be passed to the webview / JavaScript.
- The agent sends **no CORS headers** and rejects any request carrying an `Origin` header that is not `tauri://localhost` or `http://tauri.localhost`.
- The agent rejects requests whose `Host` header is not `127.0.0.1:8023` or `localhost:8023` (DNS-rebinding defence).

### 5.3 Signed device requests (phone)

Every request on the tailnet listener carries:

| Header | Value |
|---|---|
| `X-Fortis-Device` | device ID (UUID) from pairing |
| `X-Fortis-Timestamp` | unix seconds |
| `X-Fortis-Nonce` | 16 random bytes, hex |
| `X-Fortis-Signature` | base64 DER ECDSA P-256 signature of the canonical string |

Canonical string:

```text
"fortis/v2/request\n" METHOD "\n" PATH_AND_QUERY "\n" TIMESTAMP "\n" NONCE "\n" hex(SHA-256(body))
```

The agent:

1. Looks up the device's **request public key** in `paired_devices.json`. Unknown or revoked → `401`.
2. Rejects `|now − timestamp| > 60 s` → `401`.
3. Rejects a nonce already seen in the last 120 s for this device → `401`.
4. Verifies the signature → `401` on failure.

The request key is a separate Keystore key from the approval key and does **not** require a biometric per use (it is used for every request).

The WebSocket upgrade for `/api/v1/events` is authenticated the same way (signature over the `GET` request).

### 5.4 Pairing

1. User runs `fortis pair` on the laptop (IPC `pair_start`).
2. The agent creates a pairing session: a **10-character** code from the Crockford base32 alphabet (50 bits), valid for **120 s**, **single use**, max **5** failed attempts. It also prints a QR code containing `fortis://pair?host=<tailscale ip>&port=8023&code=<code>&agent_fp=<agent key fingerprint>`.
3. The phone generates two Keystore P-256 keys (request key; approval key with per-use biometric auth, invalidated on new biometric enrolment) and calls `POST /api/v1/pair` with the code, device name and both public keys.
4. The agent checks the code in constant time, stores the device, invalidates the session, and returns the device ID plus the agent's public key.
5. The CLI shows the new device's name and key fingerprint; the user confirms they match the phone screen.

Only one pairing session exists at a time. `POST /api/v1/pair` is the **only** unauthenticated endpoint, and only on the tailnet listener, and only while a session is open.

### 5.5 Agent identity

The agent has its own P-256 key (`~/.config/fortis/agent.key`, `0600`). It signs every message it pushes over `/api/v1/events`, so the phone can reject a fake agent on the tailnet. The phone pins the agent's public key at pairing.

### 5.6 Fingerprint-confirmed sessions

Some features give the phone control equivalent to running commands as the user. A signed request alone is **not** enough for them; the phone must also prove a fresh fingerprint:

| Feature | Session lifetime |
|---|---|
| Remote keyboard / mouse over the network (§7.5) | Until the phone disconnects, max **30 min**, idle timeout **2 min** |

Flow:

1. Phone: `POST /api/v1/sessions` `{ "kind": "input" }` (signed). Agent returns `{ session_id, nonce, issued_at }` (valid 60 s).
2. Phone: BiometricPrompt unlocks the **approval key**, which signs:

   ```text
   "fortis/v2/session\n" session_id "\n" nonce "\n" issued_at "\n" kind
   ```

3. Phone: `POST /api/v1/sessions/{id}/confirm` `{ signature }`. The agent verifies against that device's approval key and activates the session.
4. The session ID is then sent on the WebSocket upgrade for that feature. One active session per device per kind.

While an input session is active the laptop shows a persistent desktop notification ("📱 Pixel 8 is controlling keyboard and mouse") and `fortis status` lists it. `fortis sessions end` or locking the laptop screen ends all sessions.

---

## 6. Phone Security (`fortis-mobile`)

### 6.1 Platform

- Minimum Android version: **9 (API 28)**. Target: latest stable.
- Application ID: `com.fortis.mobile`.
- Per-use biometric keys use `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` on API 30+, and `setUserAuthenticationRequired(true)` with no validity duration on API 28–29 (both mean: every signature needs a `BiometricPrompt` with a `CryptoObject`).

### 6.2 Keys

The phone may pair with **more than one laptop**. Each pairing has its own keys (aliases contain the agent ID), so revoking one laptop never affects another. All keys are non-exportable and StrongBox-backed when available.

| Key (per pairing) | Type | User auth |
|---|---|---|
| Request key | EC P-256 sign | none (device unlocked) |
| Approval key | EC P-256 sign | BIOMETRIC_STRONG, every use, invalidated on new biometric enrolment |

| Key (per phone) | Type | User auth |
|---|---|---|
| Local wrap key | AES-256-GCM | BIOMETRIC_STRONG or device credential, valid 5 min |

The phone **MUST NOT** store any shared secret for talking to the agent (no bearer token / API key). Pairing records (agent ID, name, Tailscale address, pinned agent public key) are stored in app-private storage.

### 6.3 Phone vault

- Exactly **one** paired laptop is the **vault laptop** (chosen at pairing; changeable). The phone syncs the vault only with it. Other paired laptops get remote control and sudo approval only.
- On first sync the phone receives `vault.meta.json` and `wrapped_vault_key` — never the plaintext vault key. The user enters the master password once; the phone derives the wrap key with the same Argon2id + HKDF steps (via `fortis-bindings`), unwraps the vault key, re-wraps it with the local wrap key and forgets the master password.
- Entries are stored on the phone exactly as the agent stores them (encrypted blobs, same AAD); decryption happens in Rust via `fortis-bindings`.
- Vault screens set `FLAG_SECURE`. Copied secrets are marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`) and cleared after 30 s.
- The app's data is excluded from Android cloud backup and device transfer (`android:allowBackup="false"`, empty `data_extraction_rules`).

### 6.4 Data the phone sends to the laptop

| Feature | Rule |
|---|---|
| **Notification mirroring** | Off by default. The user picks which apps are mirrored (allow-list). Content from the Fortis app itself and from apps marked sensitive by Android (`VISIBILITY_SECRET`) is never sent. Max 1 per second. |
| **Camera-roll sync** | Off by default. Uploads only new photos (tracked by MediaStore ID) into the laptop's camera folder (§7.3). Runs with WorkManager, only on unmetered network and Tailscale up. Reads through `MediaStore` content URIs, not file paths. |
| **Clipboard send** | Only on an explicit user tap. |

### 6.5 Autofill (phone)

`FortisAutofillService` plugs into Android's Autofill framework (user picks Fortis in Settings → Passwords → Autofill service). It works in apps and in browsers that support the framework (Chrome: Settings → Autofill services → "Autofill using another service"; Firefox: built in).

- **Fill:** the service finds username/password fields (autofill hints, HTML `autocomplete`/`type`, input type, and the text field directly before the first password field; never by guessing from labels) and the target (web domain if Android reports one, else the package). It offers a single **"Unlock Fortis"** item; choosing it opens Fortis, which asks for the fingerprint / screen lock (local key, §6.2) and then shows only the matching logins (§3.6), by title and username. Nothing about the vault is sent to the requesting app until the user picks an entry.
- **Save:** when a form with a password is submitted, Android asks Fortis; the user sees "Save to Fortis?" and, after unlocking, Fortis creates a Login (title = domain/app label, `urls` = the target) or, if a login for the same target and username exists, offers to update its password. The change is synced to the vault laptop like any phone edit (§6.3).
- Fortis never fills its own screens, and ignores requests while the phone vault isn't set up.
- Datasets carry only the chosen entry's username and password; no TOTP is filled automatically (it is copied to the clipboard on request, §6.3 rules apply).

### 6.6 Services the phone runs

| Service | Rule |
|---|---|
| Events connection (foreground service, type `specialUse`) | Outgoing WebSocket only; no listening socket. Every frame's `agent_sig` is checked against the agent key pinned at pairing; failures are dropped. Restarts after reboot; can be switched off in the app (laptops then just ask for the password) |
| Webcam stream | See §7.6 — bound to the Tailscale address, token-protected, laptop-only, stops when the screen is left or after 1 h |
| Bluetooth HID keyboard/mouse | Uses normal Bluetooth pairing with the laptop; no network involved; only active while the remote-input screen is open |
| Termux `sshd` (backup target) | Separate from the app; key-only auth, used by `fortis-backup`; runs only during a backup session (`fortis-mobile start/stop`) |

---

## 7. Local System Security

### 7.1 Files and directories

| Path | Owner / Mode | Contents |
|---|---|---|
| `~/.config/fortis/` | user `0700` | Config directory |
| `~/.config/fortis/agent.toml` | user `0600` | Agent configuration |
| `~/.config/fortis/agent.token` | user `0600` | Local bearer token |
| `~/.config/fortis/agent.key` | user `0600` | Agent identity key |
| `~/.config/fortis/paired_devices.json` | user `0600` | Paired phones (public keys only) |
| `~/.local/share/fortis/` | user `0700` | Vault files (§3.1) |
| `$XDG_RUNTIME_DIR/fortis/` | user `0700` | Runtime directory |
| `$XDG_RUNTIME_DIR/fortis/agent.sock` | user `0600` | IPC socket |
| `/etc/fortis/pam/<user>.json` | root `0644` | sudo approval trust anchor |
| `/mnt/FortisVault/` | user `0700` | Backup root (enforced by fortis-backup) |
| `fortis-backup/backup.conf` | user `0600` | Backup config (script refuses group/world-writable or foreign-owned) |

The agent checks modes at start-up and tightens anything too permissive, logging a warning.

### 7.2 IPC socket

- Lives under `$XDG_RUNTIME_DIR` (`/run/user/<uid>`), never `/tmp`.
- On every connection the agent reads `SO_PEERCRED`; it accepts only the agent's own UID and UID 0 (pam_fortis runs as root). All others are closed immediately.
- Messages are newline-delimited JSON, max 64 KiB each.

### 7.3 File endpoints

- `files/list` and `files/download` only work inside `files.roots` (default `["~/Downloads", "~/Documents"]`). The requested path is canonicalised (symlinks resolved) and **MUST** still be inside a root, otherwise `403`.
- `files/drop` writes only into one of two fixed folders, chosen by `dest`: `drop` → `~/Downloads/FortisMobileDrop/`, `camera` → `files.camera_dir` (default `~/Pictures/FortisCameraRoll/`). The uploaded name is reduced to its final component, stripped of control characters and leading dots, and de-duplicated (`name (1).ext`). Max size `files.max_upload_mb` (default 100). Uploads are written to a temporary file and renamed when complete.
- Hidden files and files inside `~/.config/fortis` and `~/.local/share/fortis` are never listed or served, even if a root contains them.

### 7.4 System actions and notifications

- Only the fixed action list in the API reference is accepted. Arguments are never passed to a shell; commands are run with `std::process::Command` and fixed argument vectors.
- `run_backup` runs the script path from `backup.script` in `agent.toml`; it is not hardcoded.
- Mirrored phone notifications are shown with `notify-send` using fixed arguments; title and body are passed as separate arguments, truncated (title 120, body 500 chars) and stripped of control characters and markup (`<`, `>`, `&` escaped).

### 7.5 Remote keyboard and mouse

Typing on the laptop is equivalent to running any command as the user, so this feature is gated:

- Requires an active **input session** (§5.6), confirmed with a fingerprint.
- Disabled unless `input.enabled = true` in `agent.toml` (default **false**).
- Backend: `xdotool` on X11, `ydotool` on Wayland (needs the `ydotoold` service and `uinput` access). The agent picks one at start-up and reports it in `/capabilities`; if neither works the feature is unavailable.
- Events are validated before use: mouse deltas clamped to ±2000, scroll to ±50, buttons 1–3, keys from a fixed allow-list of key names, `type` text max 1000 characters with control characters removed. Text is passed to `xdotool type --` / `ydotool type --` as a single argument, never through a shell.
- Max 200 events per second per session; excess is dropped. The phone coalesces mouse moves to about 50 per second and clamps every value to these limits before sending.
- Leaving the remote-input screen on the phone ends the session (`DELETE /sessions/{id}`).
- Bluetooth HID mode (phone acts as a Bluetooth keyboard/mouse) does not involve the agent and is governed by normal Bluetooth pairing.

### 7.6 Phone camera as a webcam

- The phone serves an MJPEG stream **only** on its Tailscale address (never `0.0.0.0` or Wi-Fi), on a random port, with a random 128-bit token in the path. It accepts connections only from the paired laptop's Tailscale address, and only one at a time.
- The phone calls `POST /api/v1/webcam/start` `{ port, token }`; the agent pulls `http://<phone tailscale ip>:<port>/<token>/stream` into a `v4l2loopback` device using `ffmpeg` with fixed arguments. The phone's address comes from the Tailscale peer address of the signed request, not from the request body.
- Requests whose path isn't exactly `/<token>/stream` get `404`; the token is compared in constant time. A connection from any address other than the paired laptop's, or while another viewer is connected, is closed without a reply.
- The stream stops when the phone leaves the webcam screen (or the app goes to the background), on `POST /api/v1/webcam/stop`, or after 1 hour. The phone shows the Android camera-in-use indicator and a persistent notification while streaming.
- Disabled unless `webcam.enabled = true` in `agent.toml` (default **false**) and a `v4l2loopback` device exists.

### 7.7 Browser extension (laptop)

`fortis-browser` = a WebExtension for Firefox and Chromium-based browsers + `fortis-browser-host`, a native-messaging host that forwards a fixed set of messages to the agent's IPC socket (§7.2).

- **Who can talk to it:** the browser starts the host only for the extension IDs in the host manifest (`allowed_extensions` for Firefox, `allowed_origins` with the pinned `chrome-extension://<id>/` for Chromium). Web pages can't reach native messaging. The host runs as the user, so it adds no access the user's own processes don't already have through IPC.
- **Allowed messages only:** `status`, `unlock`, `lock`, `match`, `fill`, `totp`, `save_check`, `save`. Anything else is rejected by the host. Frames are limited to 64 KiB.
- **Matching is enforced by the agent**, not the extension: `browser_fill` and `browser_totp` take the page URL and return secrets only if the entry matches it (§3.6); otherwise `403`.
- **Fill only on user action:** from the toolbar popup or the keyboard shortcut (default `Ctrl+Shift+L`). Never on page load, never into hidden fields, only in the **top-level frame**, and only into fields whose frame origin equals the URL used for matching.
- **Save:** when a page submits a form with a password, the extension asks the agent (`browser_save_check`) whether it's new or changed and, if so, shows "Save / Update / Never for this site" in its popup (badge on the icon). The pending login is held in extension memory only, for at most 5 minutes. "Never" sites are stored in extension storage as host names only.
- The vault can be unlocked from the popup (the master password goes popup → host → agent IPC, never to a page) and follows the agent's auto-lock.
- The extension stores no secrets on disk.

### 7.8 Not provided

There is **no remote shell / terminal** feature. Use SSH over Tailscale (`tailscale ssh` or `ssh`) instead; it has its own authentication and audit trail.

---

## 8. Logging

- Log levels: `info` by default. Logs go to the journal.
- **Never logged:** passwords, entry contents, tokens, pairing codes, signatures, nonces, file contents.
- Logged: start-up, listener addresses, lock/unlock (no secrets), pairing success/failure, sudo request ID + outcome, device ID on every rejected request.

---

## 9. Release Audit Checklist

- [ ] No hardcoded keys, salts, tokens or passwords anywhere in the code (`grep -rn "dev_master_key\|dev_key\|salt_static"` is empty)
- [ ] Agent refuses to bind `0.0.0.0`; `ss -tlnp | grep 8023` shows only `127.0.0.1` and the Tailscale IP
- [ ] No CORS headers in any response; `Host` check in place
- [ ] Bearer token compared with `subtle`; read from `agent.token`, not env
- [ ] Every tailnet request signature-checked, timestamp and nonce checked
- [ ] Pairing code: 10 chars, 120 s, single use, 5 attempts
- [ ] Vault salt random per vault; master key only used via HKDF
- [ ] Entry AAD includes entry ID and version
- [ ] Auto-lock works and zeroises keys
- [ ] Unlock back-off works
- [ ] `pam_fortis` verifies signatures against `/etc/fortis/pam/<user>.json` itself; returns only `PAM_SUCCESS`/`PAM_IGNORE`; no mock path
- [ ] PAM line is `-auth sufficient pam_fortis.so`; module has the hard deadline, `catch_unwind` everywhere, `panic = "unwind"`; installed only via `pam-install` after a passing `pam-test`
- [ ] IPC socket in `$XDG_RUNTIME_DIR`, peer-UID checked
- [ ] File endpoints confined to roots; drop filenames sanitised
- [ ] File permissions match §7.1
- [ ] No secrets in logs
- [ ] Phone: keys non-exportable and per pairing, approval key per-use biometric, no stored API key, `allowBackup=false`, `FLAG_SECURE`
- [ ] Remote input off by default; needs fingerprint-confirmed session; inputs validated; desktop notification while active
- [ ] Phone webcam server bound to Tailscale IP only, random port + token, laptop-only, auto-stop
- [ ] Notification mirroring and camera sync off by default; notification text escaped on the laptop
- [ ] No remote shell endpoint exists
- [ ] Autofill: one matching rule (§3.6) in `fortis-core`, used by the agent and the phone; no title-based matching; https only (except localhost)
- [ ] Browser: native host allows only the listed messages and extension IDs; agent re-checks the URL for `browser_fill`; fill only on user action, top frame, visible fields
- [ ] Phone autofill: vault unlock required for every fill; save goes through the user's confirmation
