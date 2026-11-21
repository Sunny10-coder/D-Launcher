# Testing

Nothing below is claimed as tested unless the command was actually executed in
this environment. Hardware-dependent checks are explicitly marked
**NOT VERIFIED**.

---

## What was run here

| # | Command | Result |
| --- | --- | --- |
| 1 | `./gradlew :shared:testDebugUnitTest` | **PASS** — 15 tests |
| 2 | `./gradlew :child:assembleDebug` | **PASS** — `dist/my-little-world-child-debug.apk` |
| 3 | `./gradlew :parent:assembleDebug` | **PASS** — `dist/my-little-world-parent-debug.apk` |
| 4 | `cd backend && npx tsc --noEmit` (strict) | **PASS** — no errors |
| 5 | `npx wrangler d1 execute family_connect --local --file=migrations/0001_init.sql` | **PASS** — schema applied |
| 6 | `npx wrangler dev --local --port 8787` | **PASS** — Miniflare + D1 + Durable Object |
| 7 | `node backend/scripts/smoke.mjs` | **PASS** — 47/47 checks |
| 8 | `./gradlew :child:lintDebug :parent:lintDebug` | **PASS** — 0 errors (92 child / 20 parent warnings, mostly translations and unused-resource advisories) |
| 9 | `./gradlew :child:assembleRelease :parent:assembleRelease` | **PASS** — R8 + resource shrinking produce unsigned APKs (2.5 MB child, 1.6 MB parent) |

### Unit test coverage (JVM, `shared/`)

`UrlPolicyTest`
- https normalisation, case folding
- http rejected (except loopback in dev)
- credentials/fragments/shorteners rejected
- host + subdomain matching, look-alike host rejected
- `javascript:`, `file:`, `intent:`, `content:` blocked
- empty allow-list blocks everything

`PinAndScheduleTest`
- PBKDF2 hash/verify, salt uniqueness, 4–8 digit rules
- schedule windows incl. crossing midnight, malformed windows fail open
- location freshness states (live/recent/stale/expired/future), accuracy labels,
  mock-provider detection
- pairing code normalisation

### Backend smoke test coverage (real HTTP + WebSocket against the Worker)

Registration, login, wrong password, pairing code shape, wrong code, claim,
single-use replay, cross-family read denial, device-token/parent-token role
separation, forged token rejection, config update + https validation, device
config edit merge, command queue/ack/no-repeat, unknown command rejection,
status upload, location upload + invalid latitude rejection, snapshot battery/
online/location/accuracy/timestamp, location history, safe-zone create/list,
safe-zone cross-family denial, parent-initiated call + CALL command payload,
child/parent tickets, foreign ticket denial, WebSocket offer/answer relay
(two roles), pending calls, decline, child-initiated call visibility.

---

## Not verified (needs hardware or services)

| Scenario | Status | How to verify |
| --- | --- | --- |
| Fresh install opens the child launcher | **NOT VERIFIED** | Install APK, press Home, choose *My Little World* |
| Home-screen selection survives reboot | **NOT VERIFIED** | Reboot, confirm launcher resumes and service restarts |
| Parent↔child pairing on real phones | **NOT VERIFIED** (API verified) | Pair with a real code |
| Learning site full-screen + offline fallback | **NOT VERIFIED** | Airplane mode, reopen tile |
| WebView crash recovery | **NOT VERIFIED** | Enable "Don't keep activities" or exhaust memory |
| YouTube Kids installed / missing states | **NOT VERIFIED** | Install/uninstall the app and tap the tile |
| Camera photo and video capture | **NOT VERIFIED** | Grant camera, capture both |
| Voice/video call media (audio, video, ICE, reconnect) | **NOT VERIFIED** — signalling verified only | Two phones + TURN server |
| Auto-answer + documented fallback | **NOT VERIFIED** | Enable on both devices, lock the child screen, call |
| Location accuracy/timestamp display | **NOT VERIFIED** (API verified) | Grant location, press *Locate now* |
| Safe-zone enter/exit notifications | **NOT VERIFIED** | Radius 100 m, walk out |
| Find phone | **NOT VERIFIED** | Press *Ring the phone* |
| Background restrictions (Doze, OEM killers) | **NOT VERIFIED** | Leave idle 30+ min, send a call |
| FCM push (`backend/src/push.ts`) | **NOT VERIFIED** — optional, needs a Firebase project | Configure `FCM_SERVICE_ACCOUNT_JSON` and a device token |

---

## Lint

Lint passes with **zero errors** in both apps. The remaining warnings are
advisory (missing translations in the parent app, unused-resource hints, and
"newer library available" notices) and were reviewed rather than blanket
suppressed. To run it yourself:

```bash
./gradlew :child:lintDebug :parent:lintDebug
# reports: child/build/reports/lint-results-debug.html
```

## Release builds

Release configuration is present and verified (R8 + resource shrinking +
optional signing via `keystore.properties`). Both apps produce
`*-release-unsigned.apk`; a signed, distributable APK requires your own keystore
(see `keystore.properties.example`). No release APK is published in `dist/`
because signing keys must never be created or shared by the agent.

---

## Manual acceptance script (two phones)

Prereqs: backend deployed or running locally, both apps installed, child phone
paired, TURN configured for calls.

1. **Fresh install** — launcher opens; greeting shows the configured nickname.
2. **Home app** — select *My Little World* as default Home; pressing Home returns
   to it; Back does not leave it.
3. **Reboot** — launcher returns; the connection service restarts (check the
   persistent notification).
4. **Pairing** — an invalid code fails; a used code fails; a valid code succeeds.
5. **Isolation** — a second parent account cannot see or call the child.
6. **Config sync** — change nickname + colours in the parent app; the child UI
   updates within ~20 s without reinstalling.
7. **Learning** — tile opens full-screen; no address bar; back/Home controls work.
8. **Blocked navigation** — a link to another host shows the "not part of My
   Little World" screen.
9. **Offline** — airplane mode → friendly retry screen; disable airplane mode →
   *Try again* loads.
10. **WebView crash** — kill the renderer; the page reloads automatically.
11. **YouTube Kids** — installed: opens the real app; missing: install guidance.
12. **Camera** — permission prompt, photo and video saved to the gallery.
13. **Child → parent call** — one tap; parent receives the ringing notification;
    answer; audio both ways; mute and speaker work; End Call on both sides.
14. **Parent → child call** — child sees Answer/End; answer connects.
15. **Auto-answer** — enabled: brief ring + spoken announcement + End Call; lock
    the screen and confirm the fallback notification appears instead.
16. **Location** — *Locate now* shows a fix with accuracy and age; stale fixes are
    labelled, never as "live".
17. **Find phone** — rings at alarm volume and shows a notification.
18. **Quiet hours** — set a window covering now; the learning tile explains the
    pause instead of opening.
19. **Settings protection** — the gear requires the PIN; a wrong PIN is refused.
20. **Recovery** — from the child phone: adult settings → *Unpair* returns full
    device access; re-pair succeeds with a new code.
