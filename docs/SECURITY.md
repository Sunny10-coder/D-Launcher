# Security and privacy

This system is used by a preschool child. The design assumes the needs of a
two-device family rather than an enterprise fleet: small, honest, and defensive.

---

## Threat model

| Threat | Control |
| --- | --- |
| Stranger pairs with the child phone | 6-digit single-use code, 10-minute TTL, 10 attempts/15 min per IP, 10 codes/hour per account; claim requires the code the parent generated |
| Stolen device token used by another service | Tokens are opaque 256-bit secrets stored only as SHA-256 hashes; rotate by unpairing and re-pairing |
| Another parent account reads your child | Every device query is scoped by `parent_id`; foreign reads return 404 (tested) |
| Malicious learning website | HTTPS-only, host allow-list on main frame, subresources and service workers; no JavaScript bridge; file/content access disabled; permission requests always denied |
| Open redirect / shortener pivot | `bit.ly`, `tinyurl.com`, `goo.gl`, `t.co` rejected; credentials and fragments rejected in approved URLs |
| Evil page phoning home via a hidden frame | Sub-frame navigations are validated too; blocked subresources return 403 |
| Compromised backend database | Only password hashes (PBKDF2, 210k), device-token hashes and non-secret metadata are stored; no message content is stored |
| Token replay after expiry | Parent sessions 12 h; call tickets 1 h and bound to one `callId` and role |
| Brute-force login | PBKDF2 + constant-ish timing check, 12 attempts/15 min per IP and 20/account |
| Child escaping the launcher | Back is intercepted; learning site has always-visible Home/Back controls; adult settings require the PIN; a crash records a hint but never locks the device |
| Parent accidentally spying | Location requires explicit Android permission and is reported with accuracy + timestamp; stale fixes are labelled and mock providers flagged; no remote camera/mic activation exists |
| Tokens leaking into logs | `AppLog.redact` strips bearer tokens, emails and coordinates before logcat |

No root, no Accessibility-service abuse, no hidden APIs, no "device admin"
requirement for normal operation. Advanced device-owner provisioning is *not*
implemented; the app is an ordinary launcher by design.

---

## Credential handling

- **Parent sessions** — HMAC-SHA256 signed compact tokens
  (`v1.<payload>.<signature>`), 12-hour expiry, role `parent`.
- **Device tokens** — `dt_` + 32 random bytes, base64url. The server stores
  `sha256(token)` and looks it up on each request. A database dump does not
  yield usable credentials.
- **Call tickets** — signed claims `{sub, role, did: callId, exp}` valid for one
  hour, required on the WebSocket URL. The Durable Object additionally rejects a
  second socket for the same role.
- **Storage on device** — `EncryptedSharedPreferences` with an AES-256-GCM
  Keystore master key. If the Keystore entry is corrupted, credentials are
  cleared and the phone must be re-paired instead of crashing.
- **Backups** — disabled (`allowBackup=false`, extraction rules exclude prefs,
  databases and files) so tokens never leave the device via ADB backup or cloud
  backup.

---

## Server-side authorisation

Every sensitive route resolves the caller first:

1. `authenticate(request, env, "parent" | "device")`
2. ownership check (`parent_id`) for any `devices/:id` route
3. command allow-list (`LOCATE`, `FIND_PHONE`, `REFRESH_CONFIG`, `STOP_RINGING`);
   `CALL` must go through the call endpoint so it carries a call row
4. state checks (a code cannot be claimed twice; a finished call cannot be joined)

Audit rows are written for pairing, config changes, commands, calls, zone changes
and login. The audit table contains identifiers and action names only — never
child content.

## Data minimisation and retention

Stored: parent email/name/password hash; device name/model/Android/WebView
versions/battery; configuration; location fixes (lat/lng/accuracy/time/source);
safe zones; call metadata (participants, media, status, timestamps); audit.

Not stored: call content, camera media, the learning site's content, or any
behavioural profile. Locations are pruned to the last **30 days** per device on
each upload; the parent app shows only the last 100 by request. Unpairing removes
the device row and children (cascade), which removes its config, commands,
locations, safe zones and calls.

## Android-specific protections

- Learning WebView: `allowFileAccess=false`, `allowContentAccess=false`,
  `mixedContentMode=NEVER_ALLOW`, `setSafeBrowsingEnabled(true)`,
  `multipleWindows=false`, download listener drops everything, permission
  requests denied, no JS interface.
- Call WebView: loads only from the app-assets HTTPS origin; navigation elsewhere
  is refused; microphone starts muted; camera only on explicit tap; screen-lock
  visibility is limited to the call activity.
- Notifications are public-visibility only for calls; service notifications are
  minimal.
- The child app has no INTERNET-adjacent privileged permission beyond what each
  feature needs; `SYSTEM_ALERT_WINDOW` is declared only because some OEMs ask
  for it during setup and it is not requested at runtime by this app.

## Known gaps / non-goals

- No end-to-end encrypted messaging; the system stores no messages at all.
- WebRTC media is encrypted in transit (DTLS-SRTP) but relies on standard
  signalling trust: the family backend issues tickets, so it could observe call
  metadata (not media).
- The backend operator (you) has database access. For a two-person family system
  that is the intended trust boundary.
- Auto-answer cannot bypass OS restrictions; attempts to do so were deliberately
  not implemented.
- TURN credentials are shared with clients via the ticket; use a TURN service
  with short-lived credentials if you care about this.

## Reporting a problem

This is a private project. If you fork it, add a `SECURITY.md` contact before
publishing, and rotate `SESSION_SECRET` if you suspect token misuse (rotating it
signs every parent session and in-flight call ticket out).
