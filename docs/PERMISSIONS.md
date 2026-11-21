# Permissions and Android compatibility

## Child launcher permissions

| Permission | Why | If denied |
| --- | --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Learning site, family service | Native fallback screens; launcher still works |
| `POST_NOTIFICATIONS` (13+) | Incoming calls, find-phone | In-app call screen still opens when allowed; notification path unavailable |
| `USE_FULL_SCREEN_INTENT` | Incoming call over lock screen | Heads-up notification with Answer action |
| `FOREGROUND_SERVICE` + `DATA_SYNC` | Keep the family connection alive | Configured commands only arrive while the launcher is open |
| `FOREGROUND_SERVICE_LOCATION` | On-demand locate while backgrounded | "Locate now" only works with the app open |
| `ACCESS_COARSE/FINE_LOCATION` | Parent "Locate now" | Location reported as unavailable — no fabricated fix |
| `ACCESS_BACKGROUND_LOCATION` (10+) | Safe-zone geofences | Zones registered but inactive; parent is told |
| `CAMERA` | Camera tile | Camera tile explains that permission is needed |
| `RECORD_AUDIO` | Calls | Voice calls refuse to start with a clear message |
| `VIBRATE`, `WAKE_LOCK` | Ringing, screen-on during calls | Silent ringing, screen may sleep |
| `RECEIVE_BOOT_COMPLETED` | Restart family connection after reboot | User opens the launcher once |
| `MODIFY_AUDIO_SETTINGS` | Speaker routing, find-phone volume | Calls use the default route |

No storage permission is requested: media is written through MediaStore.

## Parent app permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`,
`VIBRATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE` (+`DATA_SYNC`), `RECORD_AUDIO`,
`CAMERA`, `MODIFY_AUDIO_SETTINGS`, `RECEIVE_BOOT_COMPLETED`.

The parent app never requests location: it only displays what the child phone
reports.

## Package visibility (`<queries>`)

Android 11+ hides installed packages by default. The child app declares exactly
what it needs:

- `com.google.android.apps.youtube.kids` (detect + launch)
- `com.google.android.youtube` (fallback detection)
- `IMAGE_CAPTURE` / `VIDEO_CAPTURE` intents (camera availability)
- `VIEW https` (opening the Play Store / links)

## Device capability matrix

| Capability | Android 8–9 | 10 | 11 | 12 | 13 | 14 | 15 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| HOME launcher | yes | yes | yes | yes | yes | yes | yes |
| Learning WebView (allow-list) | yes | yes | yes | yes | yes | yes | yes |
| Service workers | yes | yes | yes | yes | yes | yes | yes |
| Background activity start for incoming call | yes | **blocked** → notification | blocked | blocked | blocked | blocked | blocked |
| Full-screen intent | n/a | yes | yes | yes (calls) | yes | yes | yes (calls) |
| Foreground service location | n/a | yes | yes | yes | yes | needs type | needs type |
| Auto-answer (voice) | attempt | attempt | attempt | attempt | attempt | attempt | attempt |
| CameraX photo/video | yes | yes | yes | yes | yes | yes | yes |
| MediaStore `RELATIVE_PATH` | no (app dir) | yes | yes | yes | yes | yes | yes |
| Background location permission | n/a | separate prompt | yes | yes | yes | yes | yes |
| Geofencing (Play services) | yes | yes | yes | yes | yes | yes | yes (if GMS present) |

"attempt" means: the app rings, announces the caller aloud, shows End Call, and
the OS may still present the normal incoming-call notification instead. This is
intentional and is never presented as guaranteed.

## Hard requirements

| Feature | Requirement |
| --- | --- |
| Learning site | A WebView provider (Android System WebView or Chrome) |
| Location | Google Play services (Fused Location). Without GMS the app reports location unavailable rather than inventing data |
| Calls | Microphone; a TURN server for reliable mobile connectivity |
| YouTube Kids | The official app, installed by a grown-up |
| Push (optional) | A Firebase project — otherwise polling is used |

## OEM notes

- **Xiaomi/Redmi/Poco, Oppo, Vivo, Realme, Samsung**: enable "Autostart" and set
  battery usage to "No restrictions" for both apps, or the connection service
  will be killed in the background.
- **Huawei** devices without Google Play services: location is unavailable; calls
  and the learning site still work.
- Some OEM camera apps conflict with CameraX on old devices; the app reports
  "no camera available" instead of crashing.
