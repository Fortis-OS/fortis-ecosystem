# Fortis Agent — API Reference

This is the **wire specification** for `fortis-agent`. Security requirements referenced as §n are in the [Security Model](../security/README.md).

The agent exposes three interfaces:

| Interface | Address | Clients | Auth |
|---|---|---|---|
| **Loopback REST** (`L`) | `http://127.0.0.1:8023/api/v1` | fortis-desktop (Rust side) | `Authorization: Bearer <agent.token>` (§5.2) |
| **Tailnet REST** (`T`) | `http://<tailscale-ip>:8023/api/v1` | fortis-mobile | Signed device request headers (§5.3) |
| **IPC socket** | `$XDG_RUNTIME_DIR/fortis/agent.sock` | fortis-cli, pam_fortis | Peer UID (§7.2) |

There is **no gRPC interface**.

---

## Conventions

- JSON bodies, `Content-Type: application/json`, UTF-8. Max request body 1 MiB except `files/drop`.
- Timestamps are unix seconds (integer). IDs are UUID v4 strings. Binary values are base64 (standard, padded).
- **Listener column** in each table: `L` = loopback only, `T` = tailnet only, `L T` = both. A request on the wrong listener gets `404`.
- **State column:** `any`, or `unlocked` (returns `423` when the vault is locked).

### Errors

Every non-2xx response has this body:

```json
{ "error": { "code": "vault_locked", "message": "Vault is locked" } }
```

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `bad_request` | Malformed JSON, missing or invalid field |
| 401 | `unauthorized` | Missing/invalid token, bad signature, stale timestamp, replayed nonce, unknown device |
| 401 | `invalid_password` | Wrong master password (same response for any unlock failure) |
| 403 | `forbidden` | Path outside file roots, bad `Host`/`Origin` |
| 404 | `not_found` | Unknown route, entry, file, or device |
| 409 | `conflict` | Vault already initialised; sync version conflict |
| 413 | `too_large` | Body or upload over the limit |
| 423 | `vault_locked` | Vault is locked |
| 425 | `vault_uninitialised` | No vault yet — call `POST /vault/init` |
| 429 | `rate_limited` | Unlock back-off or pairing attempts exhausted; `Retry-After` header set |
| 500 | `internal` | Unexpected error (details only in the agent log) |
| 503 | `unavailable` | Required tool missing (e.g. no `playerctl`) |

### Authentication headers

**Loopback (`L`):**

```http
Authorization: Bearer 3f9c…(64 hex chars)
Host: 127.0.0.1:8023
```

**Tailnet (`T`):**

```http
X-Fortis-Device: 0b7c2f4e-…
X-Fortis-Timestamp: 1731110400
X-Fortis-Nonce: 9f2c1a…(32 hex chars)
X-Fortis-Signature: MEUCIQ…(base64 DER)
```

Signature input (security §5.3):

```text
fortis/v2/request
<METHOD>
<path and query, e.g. /api/v1/sync/pull?since=42>
<timestamp>
<nonce>
<hex SHA-256 of the raw body; SHA-256 of empty string for GET>
```

---

## 1. Vault

| Method & path | Listener | State | Purpose |
|---|---|---|---|
| `GET /vault/status` | L T | any | Locked / unlocked / uninitialised |
| `POST /vault/init` | L | uninitialised | Create a new vault |
| `POST /vault/unlock` | L | locked | Unlock with master password |
| `POST /vault/lock` | L T | any | Lock now |
| `POST /vault/change-password` | L | unlocked | Change master password |
| `GET /vault/entries` | L | unlocked | List entry summaries |
| `GET /vault/entries/{id}` | L | unlocked | Full entry including secrets |
| `POST /vault/entries` | L | unlocked | Create entry |
| `PUT /vault/entries/{id}` | L | unlocked | Replace entry |
| `DELETE /vault/entries/{id}` | L | unlocked | Delete entry (tombstone) |
| `GET /vault/entries/{id}/totp` | L | unlocked | Current TOTP code |

### `GET /vault/status`

```json
{ "state": "unlocked", "auto_lock_minutes": 15, "locks_in_secs": 742 }
```

`state` is `"uninitialised"`, `"locked"` or `"unlocked"`. `locks_in_secs` is `null` unless unlocked.

### `POST /vault/init`

```json
{ "password": "correct horse battery staple" }
```

Password must be at least **12** characters. Creates `vault.meta.json` (random salt) and `vault.db`, leaves the vault **unlocked**. → `201 {"state":"unlocked"}`. `409 conflict` if a vault exists.

### `POST /vault/unlock`

```json
{ "password": "…" }
```

→ `200 {"state":"unlocked"}`. Wrong password → `401 invalid_password`. During back-off → `429 rate_limited` with `Retry-After`.

### `POST /vault/lock`

No body. → `200 {"state":"locked"}`.

### `POST /vault/change-password`

```json
{ "current_password": "…", "new_password": "…" }
```

Re-wraps the vault key and rekeys SQLCipher (security §3.2). The vault key itself does not change, so paired phones keep syncing without re-entering the password. → `200`.

### `GET /vault/entries`

Optional query: `?q=<text>` (case-insensitive match on title, username, URL, folder), `?type=Login|CreditCard|Identity|SecureNote|Totp`.

Returns summaries only — **no secrets**:

```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "title": "GitHub",
    "type": "Login",
    "folder": "Work",
    "username": "user@example.com",
    "urls": ["https://github.com"],
    "has_totp": true,
    "updated_at": 1731110400
  }
]
```

### `GET /vault/entries/{id}`

Returns the full `VaultEntry` (see [Architecture §4.1](../architecture/README.md#41-vault-entry-plaintext-inside-the-ciphertext)):

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "folder": "Work",
  "title": "GitHub",
  "entry_type": {
    "Login": {
      "username": "user@example.com",
      "password": "s3cur3pass",
      "urls": ["https://github.com"],
      "totp": { "secret_base32": "JBSWY3DPEHPK3PXP", "digits": 6, "period": 30, "algorithm": "Sha1" }
    }
  },
  "custom_fields": [],
  "notes": "",
  "created_at": 1731100000,
  "updated_at": 1731110400
}
```

### `POST /vault/entries` / `PUT /vault/entries/{id}`

Body: a `VaultEntry` **without** `id`, `created_at`, `updated_at` (the agent sets them). Every variant field must be present; optional fields may be `null`. Unknown fields → `400`.

→ `201 {"id": "…"}` / `200 {"id": "…"}`.

### `DELETE /vault/entries/{id}`

→ `200`. The row becomes a tombstone so the deletion syncs.

### `GET /vault/entries/{id}/totp`

→ `200 {"code": "492039", "remaining_secs": 17}`. `404` if the entry has no TOTP.

---

## 2. Sync (phone)

All tailnet-only, all need `unlocked`.

| Method & path | Purpose |
|---|---|
| `GET /sync/bootstrap` | Material for the phone to derive its own copy of the vault key |
| `GET /sync/pull?since=<seq>` | Rows changed after `seq` |
| `POST /sync/push` | Upload local changes |

### `GET /sync/bootstrap`

```json
{
  "meta": { "format_version": 2, "kdf": { "alg": "argon2id", "m_kib": 65536, "t": 3, "p": 4, "salt": "…" }, "vault_id": "…" },
  "wrapped_vault_key": "base64(nonce ‖ ciphertext ‖ tag)",
  "seq": 0
}
```

The plaintext vault key is never sent. The phone needs the master password to use this (security §6).

### `GET /sync/pull?since=<seq>`

```json
{
  "seq": 57,
  "rows": [
    { "id": "…", "version": 4, "updated_at": 1731110400, "deleted": false, "encrypted_data": "base64" },
    { "id": "…", "version": 2, "updated_at": 1731110000, "deleted": true,  "encrypted_data": "" }
  ]
}
```

At most 500 rows per call; if `rows.length == 500`, call again with the returned `seq`.

### `POST /sync/push`

```json
{
  "changes": [
    { "id": "…", "base_version": 3, "version": 4, "updated_at": 1731110400, "deleted": false, "encrypted_data": "base64" }
  ]
}
```

Rules, applied per row:

1. `version` **must** equal `base_version + 1`. A new entry has `base_version: 0`.
2. If the stored version equals `base_version` (or the row doesn't exist and `base_version` is 0), the agent checks the blob decrypts with AAD (`entry_id`, `version`), stores it, and assigns the next `seq`. → accepted.
3. Otherwise the row is a **conflict**: the agent returns its current row unchanged.

```json
{
  "seq": 58,
  "accepted": ["…id…"],
  "conflicts": [
    { "id": "…", "version": 5, "updated_at": 1731110500, "deleted": false, "encrypted_data": "base64" }
  ]
}
```

**Conflict resolution (client side):** the copy with the larger `updated_at` wins. If the phone's copy wins, it re-encrypts with `version = server.version + 1` and pushes again with `base_version = server.version`. Otherwise it adopts the server row.

---

## 3. Pairing and Devices

| Method & path | Listener | Auth | Purpose |
|---|---|---|---|
| `POST /pair` | T | **none** (pairing code) | Pair a phone |
| `GET /devices` | L | bearer | List paired phones |
| `DELETE /devices/{id}` | L | bearer | Revoke a phone |

### `POST /pair`

Only accepted while a pairing session is open (started by `fortis pair`). This is the only unauthenticated endpoint.

```json
{
  "code": "7KQ2-M9XD-4H",
  "device_name": "Pixel 8",
  "request_pubkey": "base64 SEC1 uncompressed P-256",
  "approval_pubkey": "base64 SEC1 uncompressed P-256"
}
```

The code is compared with dashes removed, case-insensitively, in constant time.

→ `200`:

```json
{ "device_id": "0b7c2f4e-…", "agent_pubkey": "base64 SEC1 P-256", "agent_name": "laptop-hostname" }
```

No open session, expired, or wrong code → `401`. Fifth wrong attempt closes the session → `429`.

### `GET /devices`

```json
[
  {
    "device_id": "0b7c2f4e-…",
    "name": "Pixel 8",
    "paired_at": 1731100000,
    "last_seen": 1731110400,
    "online": true,
    "request_key_fp": "a1b2c3d4e5f60718",
    "approval_key_fp": "99aa88bb77cc66dd"
  }
]
```

`*_fp` = first 16 hex chars of SHA-256 of the public key.

### `DELETE /devices/{id}`

Removes the device and closes its event stream. → `200`. Reminder in response: `{"pam_reenroll_required": true}` if the device is listed in `/etc/fortis/pam/<user>.json`.

---

## 4. Sudo Approvals

### Events stream — `GET /events` (T, WebSocket)

The phone opens a WebSocket (signed upgrade request). The agent sends JSON messages, each signed with the agent key:

```json
{ "msg": "{\"type\":\"sudo_request\",…}", "agent_sig": "base64 DER over the UTF-8 bytes of msg" }
```

| `type` | Fields | Meaning |
|---|---|---|
| `hello` | `agent_name`, `vault_state` | Sent on connect |
| `sudo_request` | `request_id`, `nonce`, `issued_at`, `user`, `host`, `service`, `tty`, `expires_at` | Ask the user to approve |
| `sudo_cancelled` | `request_id`, `reason` (`timeout` \| `answered_elsewhere` \| `pam_closed`) | Dismiss the prompt |
| `backup_request` | `request_id`, `issued_at`, `expires_at` | `fortis-backup` is waiting for the phone. Show a notification; **tapping it** starts Termux backup mode (`fortis-mobile start`), the same as the app's button. Nothing runs without the tap. No reply is sent. A newer `backup_request` replaces the notification; it is dismissed at `expires_at` |
| `vault_state` | `state` | Vault locked/unlocked on the laptop |
| `ping` | `t` | Every 25 s; phone replies with `{"type":"pong","t":…}` |

`msg` is the event **as a JSON string**, so the phone verifies the signature over exactly the bytes it received and then parses `msg`. The phone verifies `agent_sig` against the agent key it pinned at pairing and drops anything that fails.

### `POST /auth/respond` (T)

```json
{
  "request_id": "hex 16 bytes",
  "decision": "approve",
  "signature": "base64 DER ECDSA P-256 by the approval key over the canonical approval bytes"
}
```

Canonical approval bytes are defined in security §4.4. The agent does not need to trust this signature — it relays it to `pam_fortis`, which verifies it — but it **does** verify it as well and returns `401` on failure so the phone can show an error.

→ `200 {"relayed": true}`; `404` if the request expired or was already answered.

---

## 5. Laptop Remote

| Method & path | Listener | State | Purpose |
|---|---|---|---|
| `GET /telemetry` | L T | any | System metrics |
| `GET /capabilities` | L T | any | What this laptop can actually do |
| `POST /actions` | L T | any | Run a system action |
| `GET /media` | L T | any | Now playing |
| `POST /clipboard` | L T | any | Set the laptop clipboard |
| `GET /files/roots` | L T | any | Allowed browse roots |
| `GET /files/list?path=` | L T | any | List a directory |
| `GET /files/download?path=` | L T | any | Download a file |
| `POST /files/drop` | L T | any | Upload a file to the laptop |
| `GET /backup/logs?lines=` | L T | any | Tail of the backup log |
| `POST /tools/generate-password` | L T | any | Generate a password |

### `GET /telemetry`

Read from `/proc` and `/sys`. Numbers, not formatted strings:

```json
{
  "cpu_percent": 12.5,
  "mem_total_mb": 15872,
  "mem_used_mb": 8120,
  "swap_total_mb": 8191,
  "swap_used_mb": 1024,
  "disk_total_bytes": 512110190592,
  "disk_used_bytes": 214748364800,
  "temp_c": 52.0,
  "uptime_secs": 13320,
  "hostname": "laptop"
}
```

`temp_c` is `null` if no sensor is found. `cpu_percent` is measured over a 200 ms window.

### `GET /capabilities`

Reflects the real system, detected at start-up and refreshed every 60 s:

```json
{
  "vault_state": "locked",
  "tailnet": true,
  "sudo_approval": true,
  "clipboard": "wl-copy",
  "media": true,
  "volume": "wpctl",
  "backup": true,
  "notify": true,
  "input": "ydotool",
  "webcam": false
}
```

- `sudo_approval`: `/etc/fortis/pam/<user>.json` exists and lists at least one device.
- `clipboard`: `"wl-copy"`, `"xclip"` or `null`.
- `notify`: `notify-send` is available.
- `input`: `"xdotool"`, `"ydotool"` or `null` (also `null` when `input.enabled = false`).
- `webcam`: `webcam.enabled = true`, `ffmpeg` present and a `v4l2loopback` device exists.

The phone hides screens for features that are `false`/`null`.

### `POST /actions`

```json
{ "action": "lock_screen" }
```

| `action` | Runs | Response extras |
|---|---|---|
| `lock_screen` | `loginctl lock-session` | — |
| `suspend` | `systemctl suspend` | — |
| `shutdown` | `systemctl poweroff` | — |
| `media_play_pause` | `playerctl play-pause` | — |
| `media_next` | `playerctl next` | — |
| `media_prev` | `playerctl previous` | — |
| `volume_up` / `volume_down` | `wpctl set-volume @DEFAULT_AUDIO_SINK@ 5%+/5%-` (fallback `pactl`) | `volume_percent` |
| `volume_mute` | `wpctl set-mute @DEFAULT_AUDIO_SINK@ toggle` | `muted` |
| `run_backup` | `backup.script` from config, detached | `started_at` |

Commands run with fixed argument vectors and are awaited (except `run_backup`, `suspend`, `shutdown`). A non-zero exit → `500`. Missing tool → `503`. Unknown action → `400`.

```json
{ "action": "volume_up", "ok": true, "volume_percent": 75 }
```

### `GET /media`

```json
{ "title": "…", "artist": "…", "album": "…", "position_us": 120000000, "length_us": 354000000, "status": "Playing", "player": "spotify" }
```

`404` if no player is active.

### `POST /clipboard`

```json
{ "text": "…" }
```

Max 1 MiB. → `200`.

### `GET /files/roots`

```json
{ "roots": ["/home/user/Downloads", "/home/user/Documents"] }
```

### `GET /files/list?path=<absolute path>`

`path` defaults to the first root. Path is canonicalised and must be inside a root (security §7.3), otherwise `403`. Hidden entries are omitted.

```json
{
  "path": "/home/user/Documents",
  "entries": [
    { "name": "report.pdf", "is_dir": false, "size": 1234567, "modified": 1731100000 },
    { "name": "photos", "is_dir": true, "size": 0, "modified": 1731000000 }
  ]
}
```

### `GET /files/download?path=<absolute path>`

Same confinement rules. Streams the file with `Content-Type: application/octet-stream` and `Content-Disposition: attachment; filename*=UTF-8''<encoded name>`. Directories → `400`.

### `POST /files/drop?dest=drop|camera`

`multipart/form-data` with one field `file`. `dest` defaults to `drop`:

| `dest` | Folder | Used by |
|---|---|---|
| `drop` | `~/Downloads/FortisMobileDrop/` | "Send file to laptop" |
| `camera` | `files.camera_dir` (default `~/Pictures/FortisCameraRoll/`) | Camera-roll sync |

Saved with a sanitised, de-duplicated name (security §7.3). Max `files.max_upload_mb`.

```json
{ "saved_as": "document (1).pdf", "size": 48213 }
```

### `GET /backup/logs?lines=50`

`lines` 1–500, default 50. → `200 text/plain`. `404` if the log doesn't exist.

### `POST /tools/generate-password`

```json
{ "length": 24, "uppercase": true, "numbers": true, "symbols": true }
```

`length` 8–128. Uses `OsRng`; guarantees at least one character from each enabled class. → `{"password": "…"}`.

---

## 5b. Phone Features

| Method & path | Listener | Purpose |
|---|---|---|
| `POST /notify` | T | Show a mirrored phone notification on the laptop |
| `POST /sessions` | T | Start a fingerprint-confirmed session |
| `POST /sessions/{id}/confirm` | T | Confirm it with the approval key |
| `DELETE /sessions/{id}` | T | End it |
| `GET /input?session=<id>` | T | Remote keyboard/mouse (WebSocket) |
| `POST /webcam/start` | T | Start pulling the phone camera into `v4l2loopback` |
| `POST /webcam/stop` | T | Stop it |

### `POST /notify`

```json
{ "app": "WhatsApp", "title": "Mum", "body": "Call me when you're free" }
```

Shown with `notify-send` (security §7.4). Rate-limited to 1/s per device (`429` beyond). `503` if `notify-send` is missing.

### `POST /sessions`

```json
{ "kind": "input" }
```

→ `200`:

```json
{ "session_id": "hex16", "nonce": "hex32", "issued_at": 1731110400, "expires_at": 1731110460 }
```

`403` if the feature is disabled in `agent.toml`.

### `POST /sessions/{id}/confirm`

```json
{ "signature": "base64 DER by the approval key over the session bytes (security §5.6)" }
```

→ `200 {"active": true, "max_duration_secs": 1800, "idle_timeout_secs": 120}`. Bad signature or expired → `401`.

### `GET /input?session=<id>` (WebSocket)

Signed upgrade request, plus an active, confirmed `input` session owned by the same device. Client → agent messages:

```json
{ "type": "mousemove",  "dx": 12.5, "dy": -3 }
{ "type": "mousedown",  "button": 1 }
{ "type": "mouseup",    "button": 1 }
{ "type": "mouseclick", "button": 3 }
{ "type": "scroll",     "dx": 0, "dy": -2 }
{ "type": "key",        "key": "Return" }
{ "type": "type",       "text": "hello" }
```

Limits and allowed `key` names: security §7.5. Invalid messages are dropped; the agent sends `{"type":"error","code":"bad_request"}` and keeps the connection. The connection closes when the session ends.

### `POST /webcam/start`

```json
{ "port": 48213, "token": "hex32" }
```

The phone's stream is plain HTTP/1.0 with `Content-Type: video/x-motion-jpeg` and a body of JPEG frames back to back (what ffmpeg's `-f mjpeg` reads); no multipart boundaries. The agent takes the phone's address from the Tailscale peer address of this request, pulls `http://<phone>:<port>/<token>/stream` with `ffmpeg` into the configured `v4l2loopback` device, and returns `200 {"device": "/dev/video10"}`. Only one stream at a time (`409` otherwise). `403` if disabled.

### `POST /webcam/stop`

→ `200`. Also stops automatically after 1 hour or if the stream drops for 10 s.

---

## 6. IPC Protocol

**Socket:** `$XDG_RUNTIME_DIR/fortis/agent.sock` (e.g. `/run/user/1000/fortis/agent.sock`), mode `0600`.

**Framing:** newline-delimited JSON. One request per line; one or more responses per line, matched by `id`. Max 64 KiB per line. A connection may send several requests.

Request:

```json
{ "id": 1, "cmd": "status", "args": {} }
```

Response:

```json
{ "id": 1, "ok": true, "data": { "state": "locked" } }
{ "id": 2, "ok": false, "error": { "code": "vault_locked", "message": "Vault is locked" } }
```

Error codes are the same as REST.

**Who may call what:** the agent's own UID may call every command except `auth_request`. UID 0 may call **only** `auth_request` and `pam_devices`. Any other UID is disconnected.

| `cmd` | `args` | Needs | `data` |
|---|---|---|---|
| `status` | — | any | `{state, locks_in_secs, tailnet_ip, paired_devices}` |
| `init` | `{password}` | uninitialised | `{state}` |
| `unlock` | `{password}` | locked | `{state}` |
| `lock` | — | any | `{state}` |
| `list` | `{query?, type?}` | unlocked | entry summaries (as REST) |
| `get` | `{id}` or `{title}` | unlocked | full entry; `{title}` must match exactly one entry, else `409` with candidates |
| `add` | `{entry}` | unlocked | `{id}` |
| `edit` | `{id, entry}` | unlocked | `{id}` |
| `delete` | `{id}` | unlocked | `{}` |
| `totp` | `{id}` | unlocked | `{code, remaining_secs}` |
| `generate` | `{length, uppercase, numbers, symbols}` | any | `{password}` |
| `pair_start` | — | any | `{code, qr_uri, expires_at}`; a later line `{id, ok, data:{event:"paired", device}}` is sent on the same connection when pairing completes |
| `devices_list` | — | any | as `GET /devices` |
| `devices_revoke` | `{device_id}` | any | `{pam_reenroll_required}` |
| `pam_devices` | `{user}` | any (UID 0 or own) | `[{device_id, name, approval_pubkey, approval_key_fp}]` |
| `auth_request` | see below | any (UID 0 only) | see below |
| `backup_run` | — | any | `{started_at}` |
| `backup_ask_phone` | `{wait_secs?}` (default 900, 60–3600) | any (owner only) | `{phones, expires_at}`: pushes `backup_request` to every online paired phone; `503 unavailable` ("no phone online") if none is connected |
| `sessions_list` | — | any | active fingerprint-confirmed sessions `[{session_id, device_id, kind, started_at}]` |
| `sessions_end` | `{session_id?}` | any | ends one session, or all if omitted |
| `browser_match` | `{url}` | unlocked | `{host, entries: [{id, title, username}]}` — Logins matching `url` (security §3.6); no secrets |
| `browser_fill` | `{id, url}` | unlocked | `{username, password}`; `403` if the entry doesn't match `url` |
| `browser_totp` | `{id, url}` | unlocked | `{code, remaining_secs}`; `403` if no match, `404` if no TOTP |
| `browser_save_check` | `{url, username, password}` | unlocked | `{status: "new" \| "changed" \| "same", id?}` — `id` of the matching login with that username |
| `browser_save` | `{url, username, password, id?}` | unlocked | `{id, action: "created" \| "updated"}`; with `id`, that login (must match `url`) gets the new password; without, a new Login titled with the host |

#### Browser native messaging (`fortis-browser-host`)

The browser frames each message as a 4-byte little-endian length + UTF-8 JSON (max 64 KiB here). Extension → host:

```json
{ "id": 7, "cmd": "match", "args": { "url": "https://github.com/login" } }
```

The host maps `status`, `unlock`, `lock` to the IPC commands of the same name and `match`/`fill`/`totp`/`save_check`/`save` to `browser_*`, and returns the IPC response unchanged (`{id, ok, data|error}`). Unknown `cmd` → `{ok:false, error:{code:"bad_request"}}`. Agent not running → `{ok:false, error:{code:"unavailable"}}`.

### `auth_request` (pam_fortis only)

```json
{ "id": 1, "cmd": "auth_request", "args": {
    "request_id": "hex16", "nonce": "hex32", "issued_at": 1731110400,
    "user": "ganesh", "host": "laptop", "service": "sudo", "tty": "pts/3"
} }
```

The agent pushes `sudo_request` to every online paired phone and replies **once**, either:

```json
{ "id": 1, "ok": true, "data": { "request_id": "hex16", "device_id": "…", "decision": "approve", "signature": "base64 DER" } }
```

or, after `auth.approval_timeout_secs`, or if no phone is online:

```json
{ "id": 1, "ok": false, "error": { "code": "unavailable", "message": "no phone online" } }
```

If the PAM side closes the connection first (user pressed Ctrl-C), the agent sends `sudo_cancelled` to the phones.

---

## 7. Endpoint Summary

| Path | L | T | Locked OK |
|---|:-:|:-:|:-:|
| `/vault/status` | ✓ | ✓ | ✓ |
| `/vault/init`, `/vault/unlock` | ✓ | | ✓ |
| `/vault/lock` | ✓ | ✓ | ✓ |
| `/vault/change-password`, `/vault/entries*` | ✓ | | |
| `/sync/*` | | ✓ | |
| `/pair` | | ✓ (no auth) | ✓ |
| `/devices*` | ✓ | | ✓ |
| `/events`, `/auth/respond` | | ✓ | ✓ |
| `/telemetry`, `/capabilities`, `/actions`, `/media`, `/clipboard`, `/files/*`, `/backup/logs`, `/tools/generate-password` | ✓ | ✓ | ✓ |
| `/notify`, `/sessions*`, `/input`, `/webcam/*` | | ✓ | ✓ |

There is no `/terminal`, `/exec` or shell endpoint (security §7.8).
