# Backend API for APT Access

This guide describes the HTTP contract expected by the Android client. The repository does not include a backend or a door controller. Implement the service with your preferred framework and connect the door commands to your own hardware.

Configure your service's base URL in **Definições**. No server is configured by default. All examples below use fictional users and coordinates.

## What the server needs

- An HTTPS API with the routes below and JSON responses.
- Bearer tokens mapped to users, with an `admin` role for user management and history.
- Persistent users, access schedules, and access events.
- Server-side checks for enabled accounts, allowed times, local/remote access, and administrator permissions.
- A hardware integration that opens the building door and opens, locks, or unlocks the apartment door.

Provision the first administrator and token outside the app, for example through your backend's CLI. There is no login, registration, token refresh, or initial administrator creation endpoint in this client.

## Common request and response rules

Every request includes:

```http
Authorization: Bearer <user-token>
Accept: application/json
```

Requests with JSON bodies also send `Content-Type: application/json; charset=utf-8`. Door command POSTs have an **empty body**. Admin requests do not send location.

Return a `2xx` status for success. Capability, user, user-list, and history responses require JSON bodies with the exact keys shown below. For door commands and deletion, a simple message works:

```json
{"output": "Command completed"}
```

Use appropriate non-2xx codes for errors, for example `401` for an invalid token, `403` for denied access, `404` for an unknown user, or `503` for unavailable hardware:

```json
{"error": "Access is not allowed at this time"}
```

The app supports English and European Portuguese. Backend `error` and `output` messages are displayed as supplied by the server; localise those on your backend when appropriate.

The app displays `error` first, then a non-empty `output`, then the raw body or HTTP status. Do not put internal diagnostics or credentials in user-visible responses.

The connection timeout is 5 seconds. Door commands have a 50-second read timeout; other requests have a 10-second read timeout. Avoid automatically retrying physical commands whose outcome is uncertain.

## 1. Access capabilities

```http
GET /access/capabilities?lat=0.0&lon=0.0
```

Coordinates arrive as query parameters on this route, **not** as location headers. Authenticate the token and return the user's identity and permissions for both doors. This GET must not operate hardware.

```json
{
  "user": {"id": 1, "name": "Example Admin", "role": "admin"},
  "doors": {
    "APT": {"can_open": true, "mode": "local", "distance_m": 12.5},
    "BLD": {"can_open": true, "mode": "local", "distance_m": 12.5}
  }
}
```

`APT` and `BLD` are case-sensitive. `can_open` is a boolean. `mode` must be `local` or `remote`, even when access is denied. `distance_m` may be absent or null; otherwise it must be a finite, non-negative number. Only the exact role `admin` enables administrator controls.

The client assumes a local zone of 100 metres and remote access without a distance limit. Enforce those rules on the server, along with schedules and account status. Capabilities are a display hint; re-check authorization when executing each command.

## 2. Door commands

| Method | Route | Hardware action |
| :--- | :--- | :--- |
| `POST` | `/bld_door` | Open the building entrance. |
| `POST` | `/apt_door/open` | Open the apartment door. |
| `POST` | `/apt_door/lock` | Lock the apartment door. |
| `POST` | `/apt_door/unlock` | Unlock the apartment door. |

These requests include the token and current location headers, with no JSON body:

```http
POST /apt_door/open
Authorization: Bearer <user-token>
X-Latitude: 0.0
X-Longitude: 0.0
Content-Length: 0
```

Authenticate and authorize every action before controlling the hardware. Record the user, door, action, and timestamp for history. Return success only according to the hardware acknowledgement your integration actually provides; the app does not independently measure the physical door state.

Device fingerprint/PIN confirmation happens locally on Android. The API does not receive a biometric attestation. Client-reported GPS can be spoofed and is not cryptographic proof of presence.

## 3. User administration

All `/admin/*` routes must require administrator authorization on the server.

### User representation

```json
{
  "id": 2,
  "name": "Example User",
  "role": "user",
  "enabled": true,
  "allow_remote": false,
  "schedules": [
    {
      "allowed_days": ["monday", "tuesday", "wednesday", "thursday", "friday"],
      "start_time": "08:00",
      "end_time": "12:00"
    },
    {
      "allowed_days": ["saturday"],
      "start_time": "10:00",
      "end_time": "14:00"
    }
  ]
}
```

All fields above are required in user responses. Days are lowercase English names from `monday` through `sunday`; times use 24-hour `HH:mm` strings. Multiple schedule periods are supported. The client sends no timezone: choose and document the server's access-rule timezone and its policy for overnight periods, boundaries, and empty schedules.

| Method | Route | Successful response |
| :--- | :--- | :--- |
| `GET` | `/admin/users` | `{"users": [<user>, ...]}`; use an empty array when there are no users. |
| `GET` | `/admin/users/{id}` | A user object directly, without a wrapper. |
| `POST` | `/admin/users` | The created user object, optionally with a top-level `token`. |
| `PUT` | `/admin/users/{id}` | The updated user object, without a wrapper. |
| `DELETE` | `/admin/users/{id}` | A `2xx` result, optionally with `output`. |

POST and PUT send only these fields:

```json
{
  "name": "Example User",
  "enabled": true,
  "allow_remote": false,
  "schedules": [
    {"allowed_days": ["monday"], "start_time": "08:00", "end_time": "12:00"}
  ]
}
```

The app does not send an `id` or a `role` in the body. Assign these on the server; role assignment is not an app feature. Treat PUT's schedules as the supplied complete list, so adding and removing periods works.

To let an administrator copy a newly created user's credential, include `token` alongside the returned user fields:

```json
{
  "id": 3,
  "name": "Example Visitor",
  "role": "user",
  "enabled": true,
  "allow_remote": false,
  "schedules": [],
  "token": "<new-user-token>"
}
```

Issue unique, unguessable tokens and provide a way to revoke them. Do not return tokens in user lists or ordinary user reads. Deactivated or deleted users must lose access even if their token is still stored on a phone.

## 4. Access history

```http
GET /admin/access-history?limit=30
```

```json
{
  "entries": [
    {
      "id": 100,
      "user_id": 2,
      "user_name": "Example User",
      "door": "APT",
      "action": "open",
      "opened_at": "2026-01-15T10:30:00+00:00"
    }
  ]
}
```

Return an empty `entries` array when there is no history. The app requests up to 30 events and displays those within the last 48 hours. Return newest events first. Use ISO 8601 timestamps with a UTC offset or `Z` so the client can filter dates correctly. `action` supports `open`, `lock`, and `unlock`; when absent, the client defaults to `open`.

## Bringing up a compatible service

1. Provision an administrator token and implement the capabilities response first. The app should show both door cards and administrator controls.
2. Connect each POST route to the corresponding hardware operation; test permission denial as well as success.
3. Implement user CRUD, weekly schedules, account disabling, and token issuance/revocation.
4. Add recent-event storage and the history endpoint.
5. Check that unauthorized requests cannot operate hardware or use administrator routes, regardless of what the phone UI displays.

The definitive client implementation is [ApiClient.java](../app/src/main/java/pt/xsoulp/domotica/ApiClient.java).
