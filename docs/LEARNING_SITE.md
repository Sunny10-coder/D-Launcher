# Learning website integration

The APK does **not** contain a learning platform. It is a hardened full-screen
browser for one website that you host and update independently. This document is
the contract between the Android shell and your site.

---

## Configure the URL

Parent app → *Settings* → **Learning website**, or child adult settings. Enter the
root `https://` URL. It is stored on the server, pushed to the phone, and applied
without reinstalling the APK.

If no URL is configured the child sees a native default learning screen
(colourful letter cards, a friendly explanation) — never a broken WebView.

---

## What the shell provides

| Behaviour | Detail |
| --- | --- |
| Full screen | Immersive mode, no address bar, no browser chrome |
| Audio | `mediaPlaybackRequiresUserGesture = false`, so narration can autoplay |
| Storage | DOM storage, IndexedDB and cookies enabled (first-party only) |
| Service workers | Allowed and intercepted by the same host allow-list |
| Offline | Native fallback with **Try again**; the site's own cache still works |
| Crash recovery | A dead renderer recreates the WebView and reloads |
| Interruption | Pausing for a call or the camera pauses timers; returning resumes |
| Navigation | Only the approved host and its subdomains |
| Device access | **None** — no JS bridge, no camera, no mic, no location, no files |

## Navigation rules

Allowed: `https://<approved-host>/**` and `https://<sub>.<approved-host>/**`.

Blocked (navigation shows a friendly native screen; subresources return 403):

- any other host, including `example.com.evil.net` and `evil.net/?u=approved`
- `http://` (except loopback in development builds)
- `javascript:`, `file:`, `content:`, `data:`, `blob:` (main frame), `intent:`,
  `market:`, `mailto:`, `tel:`, `sms:`, `geo:`, `about:`
- URL shorteners: `bit.ly`, `tinyurl.com`, `goo.gl`, `t.co`
- downloads (dropped silently)

If you need a second host (for example a video CDN), use a subdomain of the
approved host (e.g. `cdn.learn.example.com`) or proxy the asset through your
origin. Linking out to other sites is intentionally impossible.

## Recommended site structure

- **Static or PWA.** A service-worker cache gives offline lessons; keep the
  fallback page in the cache so the child sees your content, not a browser error.
- **Add to the learner experience:** big tap targets (≥ 64 px), high contrast,
  no hover-only interactions, no popups/`window.open`, no native dialogs asking
  for permissions.
- **Audio:** use the Web Audio API or `<audio autoplay>`; both work because the
  shell disables the user-gesture requirement. Provide a visible mute control and
  keep narration short.
- **Session state:** store progress in `localStorage`/IndexedDB keyed to the
  device; the WebView keeps its data between launches. There is no account to
  log into, and the shell never passes identity into the page.
- **Malayalam:** use `lang="ml"` and system fonts (`font-family: system-ui`) so
  text renders with the device's Malayalam font.
- **Avoid** `target="_blank"`, external checkout flows, analytics SDKs that
  profile the child, and third-party embeds on unapproved hosts (they will be
  blocked).

## Do not rely on

- Any Android bridge (`window.Android...`) — it does not exist.
- `getUserMedia`, geolocation, notifications, NFC, Bluetooth — denied.
- Reading arbitrary device data — the WebView only sees the web platform.
- Server-side "parental tokens" inside the page: the child's device holds no
  parent credentials, and the page must not need any.

## Updating without an APK

1. Deploy the new site at the same URL (or change the URL in the parent app).
2. If the URL changes, the child phone picks it up on its next sync (≤ 15 s when
   online) or immediately when the parent saves it (a `REFRESH_CONFIG` command
   is queued).
3. Version your service worker cache; the shell may keep an older page in cache
   until the SW updates.

## Testing the integration

- Open the tile in a debug build and check `adb logcat -s LearningActivity`.
- Blocked navigations log `Blocked navigation to an unapproved destination`.
- Turn on airplane mode and re-open: the native fallback must appear and
  recover when connectivity returns.
- Kill the WebView renderer (`adb shell` → developer options → "Don't keep
  activities" or memory pressure) and confirm the page reloads.
