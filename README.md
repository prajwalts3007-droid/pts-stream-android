# PTS Library Android

Native Kotlin/Compose client for `https://ptsmovies.online`. Android 8+, HEVC-capable device required for playback. Uses Media3 for video and native local storage for approved offline MP4s. No production media or PTS credentials are stored in this repository; the only video fixture is a generated test pattern packaged in the test APK. The installed app is named **PTS Library**; the package identifier and signing key are retained for Android updates.

## Build

GitHub Actions builds a **debug test APK** on each commit; find it under the run's Artifacts. Debug APKs are not published as OTA releases. A signed production APK requires a persistent signing key, kept outside Git and provided as repository Actions secrets: `PTS_KEYSTORE_B64`, `PTS_KEYSTORE_PASSWORD`, `PTS_KEY_ALIAS`, `PTS_KEY_PASSWORD`. Never change or lose this key: Android will reject updates signed by a different key. Once configured, tag the verified commit with a monotonically increasing numeric tag (`v2`, `v3`, …) to publish a signed GitHub release. The app checks the public release API and opens the system package installer; Android always asks the user to approve the update. No silent installation. APKs installed from debug artifacts cannot be upgraded in place by a signed release; uninstall the debug build first (which deletes its app-private downloads).

Release-candidate tags (`v1-rc1`) produce a **prerelease** APK for manual testing; they are excluded from the in-app `releases/latest` update channel. Do not distribute it as a finished app until backend compatibility and device tests pass.

## Constraints before production release

- This is an independent Android client; the production backend has **not** been rebuilt or changed. The checked-out VPS Go source predates the running binary. Verify normal-user login, playback URLs, progress, watchlist and download authorization against the deployed API before distributing.
- The Android app downloads **only approved MP4** directly to private device storage. HLS-only titles need a safe user-authorized MP4 preparation endpoint before offline saving; the older server source currently grants its preparation endpoint only to administrators. Do not bypass that restriction.
- Offline downloads currently need the app to remain open, restart rather than resume after interruption, and require a live session to display. Background/resumable downloads need a foreground transfer service and integration testing; do not advertise them as complete.
- Android media support varies by device. Check HEVC hardware decoding and audio/subtitle tracks on the intended phones. Offline files remain on the device and are deleted on uninstall. A device backup is disabled.
- Existing web push subscriptions are browser-only; native remote pushes require an authenticated FCM registration/delivery backend. Android notification permission currently covers local download notifications, not unimplemented remote pushes.
- Hub, Files and Streaming are native Compose sections with their own bottom navigation. Administrators switch by swiping the dock or using Profile → Sections; tapping the brand returns to Hub. Users see Streaming only. No WebView is embedded. The available source predates the deployed backend, so retries/source editing and sensitive settings remain unavailable until source validation and access rules are verified; metadata search does not submit jobs. Do not advertise full feature parity yet.
- Test login/role boundaries, transfers, Pi offline/fallback, rotation, downloads, update install, and low-storage scenarios on real devices before calling this production-ready.

## Automated audit

`Native screen and API audit` builds the app and instrumentation APK, runs Android lint, then runs tests on an Android 35 emulator. Tests use a loopback MockWebServer and generated HEVC test media; they never sign in to or modify production. The audit artifact contains rendered native screen PNGs, test reports and lint output. The real Activity tests cover login, viewer/admin navigation, dock swipes, recreation, network HEVC playback and saving/playing/deleting an offline copy. Separate tests cover typed stage-snapshot cancellation, API/CSRF contracts, password visibility and watchlist reactivity.

These tests do not prove deployed backend authorization, real Pi/Tunnel/VPS failover, physical-device codec support, OTA installer consent or low-storage behavior. A green APK build alone is not an end-to-end pass.
