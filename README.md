# D-Launcher — My Little World

Private family Android launcher project.

## Purpose
A child-friendly Android HOME launcher that displays a parent-approved interactive learning website, with large buttons for Call Daddy, YouTube Kids, Camera, and Play Together. A separate parent companion app will eventually provide authenticated device pairing, calls, location requests, and family settings.

## Technical direction
- Android: Kotlin, Jetpack Compose, Android HOME intent filter, secure WebView.
- Base/reference: https://github.com/nktnet1/webview-kiosk (AGPL-3.0-or-later; comply with upstream license when reusing code).
- Education website: separately hosted HTTPS web app; URL is parent-configurable.
- Safety: secure pairing, visible call indicators, Android permission compliance, no covert media or location capture.
- No real backend, credentials, or production services are connected yet.

## Development status
Repository initialized on `main`. **Application code and APKs have not been added yet.**

## Next build step
Implement the child launcher and run Android Gradle checks before calling any feature complete. Build the parent app and backend as separate modules/services.
