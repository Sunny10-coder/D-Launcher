# My Little World

A private family Android system: a child-friendly **home launcher**, a **parent
companion app**, and a small authenticated **family backend** that connects them.

Built for one household — a young child's spare Android phone and her father's
phone — with real security boundaries, honest platform limits, and no
advertising, analytics or third-party tracking.

> Licence: **AGPL-3.0-or-later** (see `LICENSE` and `NOTICE`). The design was
> informed by the architecture of [webview-kiosk](https://github.com/nktnet1/webview-kiosk)
> (also AGPL-3.0-or-later); no upstream source files were copied.

---

## What is in the box

| Part | Path | What it does |
| --- | --- | --- |
| Child launcher **My Little World** | `child/` | HOME-screen launcher: learning website, one-tap call to the parent, YouTube Kids, camera, play-together, PIN-gated adult settings |
| Parent app **My Little World Parent** | `parent/` | Sign-in, pairing, device status, location map + safe zones, call the child, find-phone, remote configuration |
| Backend **Family Connect** | `backend/` | Cloudflare Worker + D1: pairing, authenticated commands, locations, call signalling (Durable Object relay), audit log, rate limits |
| Shared code | `shared/` | Models, typed API client, URL allow-listing, PIN hashing, schedule + location-freshness logic, keystore-backed storage |
| Built APKs | `dist/` | `my-little-world-child-debug.apk`, `my-little-world-parent-debug.apk` |
| Docs | `docs/` | Setup, security, permissions matrix, learning-site integration, testing, troubleshooting |

---

## Architecture at a glance

```
┌────────────────────┐    HTTPS (session token)     ┌─────────────────────────┐
│ Parent app         │ ───────────────────────────▶ │ Family Connect Worker   │
│ (Kotlin / Compose) │ ◀─── status, locations ───── │  • pairing codes        │
│                    │                              │  • command queue        │
│                    │ ═══ WSS signalling ═══════▶  │  • call tickets         │
└────────┬───────────┘                              │  • audit + rate limits  │
         │                                          └──────────┬──────────────┘
         │  WebRTC media (P2P, DTLS-SRTP)                      │ D1 (SQLite)
         │                                                     │
┌────────┴───────────┐   HTTPS (device token, polling)   ┌─────┴──────────────┐
│ Child launcher     │ ────────────────────────────────▶ │ Durable Object      │
│ (Kotlin / Compose) │ ◀── commands: CALL/LOCATE/... ─── │ CallRoom relay      │
└────────────────────┘                                   └────────────────────┘
```

- **Two credentials.** Parents use signed 12-hour session tokens. Each child
  device uses an opaque 256-bit device token; only its SHA-256 hash is stored.
- **Polling instead of push by default.** The child app polls unacknowledged
  commands every 15 s with exponential backoff; this works with no Firebase
  project and no Google Play services dependency. `backend/src/push.ts` contains
  an optional FCM sender for those who configure it (unverified — see
  `docs/TESTING.md`).
- **Media never touches the backend.** The Worker issues short-lived call
  tickets and relays SDP/ICE through a Durable Object; audio/video flows
  peer-to-peer over WebRTC inside the platform WebView.
- **No JavaScript bridge on the learning site.** The WebView has *zero*
  `addJavascriptInterface` exposure, so a compromised learning page cannot issue
  device commands.

---

## Quick start (local development)

### 1. Backend

```bash
cd backend
npm install
npx wrangler d1 execute family_connect --local --file=./migrations/0001_init.sql
cp .dev.vars.example .dev.vars      # then edit SESSION_SECRET
npm run dev                          # http://localhost:8787
node scripts/smoke.mjs               # 47 end-to-end API checks
```

### 2. Apps

```bash
# Point the apps at your backend. For the Android emulator, the host machine is
# 10.0.2.2; for a real phone use your computer's LAN address and keep the phone
# on the same network (development only, cleartext is restricted to loopback /
# emulator hosts in the network security config).
./gradlew :child:assembleDebug :parent:assembleDebug
adb install -r child/build/outputs/apk/debug/child-debug.apk
adb install -r parent/build/outputs/apk/debug/parent-debug.apk
```

### 3. Pair

1. Parent app → **Add a child phone** → 6-digit code.
2. Child phone → **Pair this phone** (from the launcher card or adult settings)
   → type the code.
3. Then set the home screen as Android's default Home app:
   *Settings → Apps → Default apps → Home app → My Little World*.

Full instructions: [`docs/SETUP.md`](docs/SETUP.md).

---

## Verified in this environment

| Check | Result |
| --- | --- |
| `:shared:testDebugUnitTest` (URL policy, PIN, schedule, freshness) | **passed** |
| `:child:assembleDebug` / `:parent:assembleDebug` | **passed** — APKs in `dist/` |
| Backend `tsc --noEmit` (strict) | **passed** |
| Backend smoke test against local `wrangler dev` + D1 | **47/47 passed** |
| Call signalling relay (two WebSocket roles) | **passed (signalling only)** |
| Physical device install, camera, calls, location, auto-answer | **NOT VERIFIED** — no device/emulator in this environment |

Exact commands and the honesty boundaries are in [`docs/TESTING.md`](docs/TESTING.md).

---

## Honest limitations

- **Auto-answer is best-effort by design.** Android 10+ blocks background
  activity launches. The child app rings, announces the caller out loud, shows
  End Call, and falls back to a normal incoming-call notification when the OS
  refuses. No root, no Accessibility abuse, no hidden APIs. The feature is off
  until a parent enables it *and* accepts the on-device notice.
- **A launcher cannot install apps, grant permissions, or control Family Link.**
  YouTube Kids must be installed by a grown-up; the app detects absence and
  offers the Play Store.
- **Location requires permission in the system UI.** The child app cannot grant
  itself background location; without it, safe zones are reported as inactive.
- **Calls need a TURN server on restrictive mobile networks.** STUN-only works on
  many home networks; configure `ICE_SERVERS_JSON` for reliability.
- **The parent app listens for incoming calls via a foreground service.** Doze
  and OEM battery managers can delay it; on-screen actions always work.
- **English + Malayalam** UI strings are provided for the child launcher; the
  parent app is English.

See [`docs/SECURITY.md`](docs/SECURITY.md) for the threat model and
[`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) for recovery steps.

---

## Repository layout

```
child/      Android launcher (minSdk 26, targetSdk 35, Compose + CameraX)
parent/     Android parent app (Compose, OSM map, WebRTC call screen)
shared/     Kotlin library: API client, models, security logic (+ JVM tests)
backend/    Cloudflare Worker (TypeScript), D1 migrations, smoke test
docs/       Setup, security, permissions, learning site, testing, troubleshooting
dist/       Built debug APKs
```
