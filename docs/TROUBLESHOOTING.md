# Troubleshooting

## "The family service address is not configured"

The default server value is a placeholder (`family-connect.example.invalid`).

- Parent app: sign-in screen → *Family service address*.
- Child app: adult settings → *Family service address*.

Production must be `https://`. Local development uses `http://10.0.2.2:8787`
(emulator) and requires `PUBLIC_ORIGIN=http://10.0.2.2:8787` on the backend so
call WebSocket URLs work.

## Sign-in fails

| Status | Meaning | Fix |
| --- | --- | --- |
| 401 | Wrong email/password | Re-check; reset by deleting the row in `parents` and registering again (development) |
| 403 | Registration disabled | Set `ALLOW_REGISTRATION=true` temporarily, or create the user in development |
| 429 | Rate limited | Wait 15 minutes; limits are 12 logins/15 min/IP |
| Network | Wrong address / firewall | `curl <server>/api/v1/devices` should return 401 (not a connection error) |

## Pairing code does not work

- Codes are **6 digits**, valid **10 minutes**, **single use**.
- 10 claim attempts per 15 minutes per IP.
- "expired" → create a new code.
- "already used" → the phone paired once already; check the parent app's device
  list. If the device row exists but the phone lost its token, unpair the row
  (delete from `devices`) and pair again.

## Child phone is not receiving calls or commands

1. Is the persistent "My Little World is connected" notification present? If not,
   open the launcher (it starts the service) and check battery restrictions.
2. Parent app → Home → *Connection* shows **Offline**? The phone has not polled
   in 2 minutes.
3. OEM battery managers: enable autostart / "no restrictions" (Xiaomi, Oppo,
   Vivo, Realme, Samsung).
4. Check the backend logs (`wrangler tail`) for `POST /api/v1/device/status`.

## Calls connect but there is no audio

- Grant microphone permission on both phones.
- Check that a TURN server is configured in `ICE_SERVERS_JSON` — STUN-only fails
  on symmetric carrier NATs.
- Toggle speaker once; some OEMs route audio only after a route change.
- Confirm the call shows "Connected" on both sides; "Reconnecting…" means ICE
  failed.

## Auto-answer does not work

Expected in many cases. Requirements: enabled in the parent app **and** accepted
on the child phone; voice call only; screen on/unlocked for a background launch.
Android 10+ blocks background activity starts, and the child sees the normal
incoming-call notification instead. This is not a bug and cannot be bypassed
without root or Accessibility abuse.

## Learning site shows the blocked screen

The link leaves the approved host. Use a subdomain of the approved host for CDNs,
or proxy through your origin. Check `adb logcat -s LearningActivity`.

## Learning site is blank or crashes repeatedly

- Try *Try again* on the fallback screen.
- Update Android System WebView / Chrome from the Play Store.
- Check `adb logcat | grep -i chromium`.
- A permanently crashing site can be replaced remotely from the parent app
  without touching the phone.

## Camera says "no camera available"

- Permission denied → grant it, or the tile retries the prompt.
- Another app is holding the camera; close it.
- Emulators: configure a virtual camera in AVD settings.

## YouTube Kids tile opens the install screen

The official app is missing. Install it from the Play Store. On Android 11+
package visibility is declared in the manifest, so detection works without broad
`QUERY_ALL_PACKAGES`.

## Location is not reported

- Child phone: location permission must be granted (and "Allow all the time" for
  background "Locate now").
- Location services must be enabled at the OS level; the phone reports
  `location_disabled` and never sends fake coordinates.
- Devices without Google Play services cannot use Fused Location; the app says so.

## Recovering a child phone

| Problem | Recovery |
| --- | --- |
| Child escaped to the system launcher | Swipe up / open *My Little World* from the app list, or Settings → Default apps → Home app |
| Settings PIN forgotten | Clear app data (`adb shell pm clear com.mylittleworld.child`) — this unpairs the device; pair again |
| Device token lost / server wiped | Create a new pairing code and pair again |
| App crashes on start | `adb logcat -d | grep AndroidRuntime`; reinstall the APK; data can be cleared without affecting the rest of the phone |
| Wrong server address after a typo | Adult settings → correct it; if the UI is unreachable, clear app data |

The app never enables Android's kiosk/lock-task mode, so the normal system
recovery paths (Home, recents, Settings, safe mode) always remain available.

## Backend errors

| Symptom | Cause |
| --- | --- |
| 500 `SESSION_SECRET is missing or too short` | Set a ≥24 char secret: `wrangler secret put SESSION_SECRET` |
| 500 with `D1_ERROR` | Migrations not applied: `npm run db:migrate:remote` |
| 101 on `/api/v1/signal/...` but no audio | ICE/TURN problem, not signalling |
| `mode: "simulated"` in tickets | `CALLS_DISABLED=true` is set |

## Useful commands

```bash
adb logcat -s ChildApp FamilyService LearningActivity CallActivity
adb shell dumpsys package com.mylittleworld.child | grep -A3 HOME
adb shell cmd package resolve-activity -c android.intent.category.HOME
cd backend && npx wrangler tail            # live production logs
cd backend && node scripts/smoke.mjs       # re-verify a deployment
```
