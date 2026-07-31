# Fortis Agent — REST API Reference

**Base URL:** `http://127.0.0.1:8023/api/v1`

---

## Authentication

All authenticated endpoints require the `Authorization` header:

```
Authorization: Bearer <FORTIS_AGENT_KEY>
```

The API key is set via the `FORTIS_AGENT_KEY` environment variable when starting the agent.

Unauthenticated requests return `401 Unauthorized`.

---

## Authenticated Endpoints

### GET `/telemetry`

Returns real-time system telemetry.

**Response:**
```json
{
  "cpu": "12.5%",
  "mem_total": "16384 MB",
  "mem_used": "8192 MB",
  "storage_total": "500G",
  "storage_used": "200G",
  "swap_total": "8192 MB",
  "swap_used": "1024 MB",
  "temp": "52.0°C",
  "uptime": "up 3 hours, 42 minutes"
}
```

---

### GET `/capabilities`

Returns the agent's enabled feature flags.

**Response:**
```json
{
  "telemetry": true,
  "actions": true,
  "files": true,
  "vault": true,
  "biometric_auth": true
}
```

---

### POST `/clipboard`

Copies text to the system clipboard.

**Request Body:** Raw text string

**Response:**
```json
{
  "status": "success",
  "message": "Clipboard updated"
}
```

---

### POST `/action`

Executes a system action.

**Request Body:**
```json
{
  "action": "lock_screen"
}
```

**Available Actions:**

| Action | Description |
|--------|-------------|
| `lock_screen` | Lock the desktop session |
| `run_backup` | Execute the backup script |
| `media_play_pause` | Toggle media play/pause |
| `media_next` | Skip to next track |
| `media_prev` | Go to previous track |
| `volume_up` | Increase volume by 5% |
| `volume_down` | Decrease volume by 5% |
| `suspend` | Suspend the machine |
| `shutdown` | Power off the machine |

**Response:**
```json
{
  "status": "success",
  "action": "lock_screen"
}
```

For volume actions, includes:
```json
{
  "status": "success",
  "action": "volume_up",
  "volume": "75%"
}
```

---

### GET `/media`

Returns currently playing media metadata (requires `playerctl`).

**Response:**
```json
{
  "title": "Bohemian Rhapsody",
  "artist": "Queen",
  "album": "A Night at the Opera",
  "position": "120000000",
  "length": "354000000",
  "status": "Playing",
  "player": "spotify"
}
```

**Error:** `404 Not Found` if no player is active.

---

### POST `/files/drop`

Upload a file via multipart form data.

**Request:** `Content-Type: multipart/form-data` with field `file`

**Response:**
```json
{
  "status": "success",
  "file": "document.pdf"
}
```

---

### POST `/files/list`

List contents of a directory.

**Request Body:**
```json
{
  "path": "/home/user/Documents"
}
```

**Response:**
```json
{
  "path": "/home/user/Documents",
  "files": [
    { "name": "report.pdf", "size": 1234567, "is_dir": false },
    { "name": "photos", "size": 0, "is_dir": true }
  ]
}
```

---

### GET `/files/download?path=<filepath>`

Download a file. Returns the file as a binary stream with `Content-Disposition` header.

**Query Parameter:** `path` — Absolute path to the file

---

### GET `/backup/logs`

Returns the latest backup log output as plain text.

---

### GET `/vault/entries`

Returns all vault entries.

**Response:**
```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "folder_id": null,
    "title": "GitHub",
    "entry_type": {
      "Login": {
        "username": "user@example.com",
        "password": "s3cur3pass",
        "url": "https://github.com",
        "totp_secret": null
      }
    },
    "custom_fields": [],
    "notes": "My GitHub account"
  }
]
```

---

### POST `/vault/entries`

Create a new vault entry.

**Request Body:**
```json
{
  "title": "GitHub",
  "notes": "Work account",
  "entry_type": "Login",
  "username": "user@example.com",
  "password": "s3cur3pass"
}
```

**Entry Types:**

| Type | Required Fields |
|------|----------------|
| `Login` | `username`, `password` |
| `CreditCard` | (created with empty fields, edit via Desktop UI) |
| `SecureNote` | `notes` (used as content) |

**Response:**
```json
{
  "status": "success"
}
```

---

## Public Endpoints (No Authentication Required)

### POST `/pair`

Accept a device pairing request.

**Request Body:**
```json
{
  "code": "482916",
  "public_key": "base64_encoded_ed25519_public_key"
}
```

**Success Response:**
```json
{
  "status": "success",
  "message": "Paired successfully."
}
```

**Error:** `401 Unauthorized` if the code is invalid or expired.

---

### POST `/auth/unlock`

Request biometric unlock (used by the browser extension).

**Request Body:**
```json
{
  "domain": "reddit.com"
}
```

**Success Response (biometric approved):**
```json
{
  "status": "success"
}
```

**Error:** `401 Unauthorized` if biometric authentication fails or is denied.

---

## IPC Protocol

The agent also listens on a Unix domain socket at `/tmp/fortis_agent.sock`.

| Command | Description |
|---------|-------------|
| `ADD <title>` | Add a SecureNote entry |
| `GET` | List all vault entries |
| `SYNC` | Start P2P sync (stub) |
| `AUTH_CHALLENGE` | Send biometric auth challenge to mobile |
| `PAIR_START` | Generate a 6-digit pairing code |

**Example (using socat):**
```bash
echo "GET" | socat - UNIX-CONNECT:/tmp/fortis_agent.sock
```

---

## gRPC Service

Port: `50051`

See [fortis-sync/proto/sync.proto](../../fortis-sync/proto/sync.proto) for the full protobuf definition.

### FortisAuth Service

```protobuf
service FortisAuth {
    rpc AuthenticateChallenge (ChallengeRequest) returns (ChallengeResponse);
}
```
