# Setup guide

Everything below is for a private family deployment of two phones. Nothing here
requires a paid service; the only required account is a free Cloudflare account
for production hosting.

---

## 1. Requirements

| Tool | Version used |
| --- | --- |
| JDK | 17 |
| Android SDK Platform | 35 (build-tools 35.0.0) |
| Gradle | 8.10.2 (wrapper-compatible) |
| Node.js | 20+ (tested with 26) |
| Cloudflare account | optional; only for a public deployment |

Android support: **minSdk 26 (Android 8.0)**, targetSdk 35. Features degrade
gracefully below Android 10/12/13 — see [`PERMISSIONS.md`](PERMISSIONS.md).

---

## 2. Backend: local development

```bash
cd backend
npm install
npx wrangler d1 execute family_connect --local --file=./migrations/0001_init.sql
cp .dev.vars.example .dev.vars
```

Edit `.dev.vars`:

```ini
SESSION_SECRET=<openssl rand -base64 48>
ALLOW_REGISTRATION=true
ICE_SERVERS_JSON=[{"urls":["stun:stun.l.google.com:19302"]}]
# Optional, only when testing the parent app through a tunnel/emulator:
# PUBLIC_ORIGIN=http://10.0.2.2:8787
```

Start it:

```bash
npm run dev          # http://localhost:8787
```

Verify with the end-to-end script (registers a throwaway parent, pairs a device,
checks isolation, commands, location, safe zones and call signalling):

```bash
node scripts/smoke.mjs
```

### Environment variables

| Name | Required | Purpose |
| --- | --- | --- |
| `SESSION_SECRET` | yes | HMAC key for parent session and call tokens. ≥24 chars; rotate to invalidate all sessions. |
| `ALLOW_REGISTRATION` | no | `"true"` allows `POST /auth/register`. Set `"false"` in production after creating your account. |
| `ICE_SERVERS_JSON` | recommended | JSON array handed to clients (`urls`, optional `username`/`credential`). Add TURN for mobile networks. |
| `PUBLIC_ORIGIN` | no | Public base URL used to build call WebSocket URLs. Derived from the request when empty. |
| `CALLS_DISABLED` | no | `"true"` returns `mode: "simulated"` tickets so apps show an honest "not configured" state. |
| `FCM_SERVICE_ACCOUNT_JSON` | no | Enables the optional FCM sender in `src/push.ts` (see Testing → unverified). |

Never commit `.dev.vars` or any secret. The Worker itself contains no secrets.

---

## 3. Backend: production (Cloudflare)

```bash
cd backend
npx wrangler login
npx wrangler d1 create family_connect          # copy the database_id
# put the id into wrangler.toml -> [[d1_databases]].database_id
npx wrangler d1 execute family_connect --remote --file=./migrations/0001_init.sql
npx wrangler secret put SESSION_SECRET         # paste a long random value
npx wrangler secret put ICE_SERVERS_JSON       # optional override
npx wrangler deploy
```

Then in `wrangler.toml` set `ALLOW_REGISTRATION = "false"` once the parent
account exists (registration is the only endpoint that creates accounts).

A small TURN server is strongly recommended for calls on mobile data. Any
standards-compliant TURN service works; put it in `ICE_SERVERS_JSON`. Do not put
provider API keys in the Android apps — only the TURN username/credential, which
the server hands to clients with the call ticket.

---

## 4. Build the APKs

```bash
./gradlew :child:assembleDebug :parent:assembleDebug
# or, for signed release builds (requires keystore.properties):
cp keystore.properties.example keystore.properties   # fill in, keep it secret
./gradlew :child:assembleRelease :parent:assembleRelease
```

Outputs:

- `child/build/outputs/apk/debug/child-debug.apk`
- `parent/build/outputs/apk/debug/parent-debug.apk`

Debug builds carry an `.debug` application-id suffix, so both can coexist with a
release install.

### Install over ADB

```bash
adb install -r dist/my-little-world-child-debug.apk
adb install -r dist/my-little-world-parent-debug.apk
```

Or copy the APKs to each phone and open them (allow "install unknown apps" for
the file manager once).

---

## 5. Configure the server address in the apps

- **Parent app, sign-in screen** → *Family service address*.
- **Child app, adult settings** → *Family service address* (PIN protected).

Production values must be `https://`. The apps only permit cleartext HTTP to
`10.0.2.2`, `localhost` and `127.0.0.1` (see `network_security_config.xml`), so a
production deployment cannot silently fall back to plain HTTP.

For an emulator talking to `wrangler dev` on the host:

- Parent: `http://10.0.2.2:8787`
- Child: `http://10.0.2.2:8787`
- Backend: set `PUBLIC_ORIGIN=http://10.0.2.2:8787` so call WebSocket URLs point
  at the host instead of the emulator loopback.

---

## 6. Pair the phones

1. Parent app → sign in (or create the account while registration is enabled).
2. **Add a child phone** → a 6-digit code appears, valid for 10 minutes, single
   use.
3. Child phone → launcher card **Almost ready** → *Pair this phone* → enter the
   code. (Also reachable from adult settings.)
4. On success the child phone stores a device token in Keystore-encrypted
   storage and starts the family connection service.
5. Make it the Home app: *Settings → Apps → Default apps → Home app → My Little
   World*.
6. Grant the permissions you actually want:
   - Notifications (Android 13+) — needed for incoming calls.
   - Location while using the app; optionally "Allow all the time" for safe zones.
   - Camera / microphone are requested only when used.
   - Exempt the app from aggressive battery optimisation on Xiaomi/Oppo/Vivo
     etc., otherwise the connection service may be killed.

---

## 7. Configure the learning website

Adult settings → *Learning website address* (or the parent app → Settings tab).
Enter an `https://` URL. Rules:

- Each navigation and subresource must stay on the approved host or a subdomain.
- `javascript:`, `file:`, `content:`, `intent:`, `data:` and other schemes are
  blocked, as are URL shorteners.
- The page opens full-screen with no address bar and no JavaScript bridge.

See [`LEARNING_SITE.md`](LEARNING_SITE.md) for the website-side contract (PWA,
service workers, audio autoplay, storage, and what *not* to build).

---

## 8. YouTube Kids and camera

- **YouTube Kids** must be installed by a grown-up from the Play Store. The app
  detects it via `<queries>` package visibility, launches the official app, and
  otherwise shows a parent-guided install screen. It never recreates or bypasses
  Google's parental controls; configure approved content in YouTube Kids itself
  (or via Family Link).
- **Camera** is a CameraX fallback with two huge buttons that saves photos to
  `Pictures/MyLittleWorld` and videos to `Movies/MyLittleWorld` (app-private
  storage on Android 8–9). Nothing is uploaded anywhere.

---

## 9. Auto-answer

Off by default. To enable:

1. Parent app → Settings → *Auto-answer voice calls*.
2. Child phone → adult settings → same switch → accept the on-device notice.

Behaviour: voice calls only, brief ring, spoken announcement of the caller, End
Call always visible, video requires a tap. Android may still refuse to launch the
call screen in the background; the incoming-call notification remains.

---

## 10. Updating

The learning website can change without touching the APK: edit the URL (parent
app) or publish a new version of the site at the same URL.

The APK must be rebuilt only for app changes. Install updates over the old
version (`adb install -r`) so the device token and pairing survive. If you
reinstall with a different signing key, the OS will refuse the update; uninstall
first and re-pair.
