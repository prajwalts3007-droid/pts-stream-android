# PTS Stream Android

Native Kotlin/Compose client for `https://ptsmovies.online`. Android 8+, HEVC-capable device required for playback. Uses Media3 for video and native local storage for approved offline MP4s. No media or PTS credentials are stored in this repository.

## Build

GitHub Actions builds a **debug test APK** on each commit; find it under the run's Artifacts. Debug APKs are not published as OTA releases. A signed production APK requires a persistent signing key, kept outside Git and provided as repository Actions secrets: `PTS_KEYSTORE_B64`, `PTS_KEYSTORE_PASSWORD`, `PTS_KEY_ALIAS`, `PTS_KEY_PASSWORD`. Never change or lose this key: Android will reject updates signed by a different key. Once configured, tag the verified commit with a monotonically increasing numeric tag (`v2`, `v3`, …) to publish a signed GitHub release. The app checks the public release API and opens the system package installer; Android always asks the user to approve the update. No silent installation.

## Constraints before production release

- This is an independent Android client; the production backend has **not** been rebuilt or changed. The checked-out VPS Go source predates the running binary. Verify normal-user login, playback URLs, progress, watchlist and download authorization against the deployed API before distributing.
- The Android app downloads **only approved MP4** directly to private device storage. HLS-only titles need a safe user-authorized MP4 preparation endpoint before offline saving; the older server source currently grants its preparation endpoint only to administrators. Do not bypass that restriction.
- Android media support varies by device. Check HEVC hardware decoding and audio/subtitle tracks on the intended phones. Offline files remain on the device and are deleted on uninstall. A device backup is disabled.
- Existing web push subscriptions are browser-only; native remote pushes require an authenticated FCM registration/delivery backend. Android notification permission currently covers local download notifications, not unimplemented remote pushes.
- The admin console opens the existing server-rendered UI in an authenticated WebView. The video player, browsing and downloads are native; a fully native rewrite of every admin form is not yet implemented.
- Test login/role boundaries, transfers, Pi offline/fallback, rotation, downloads, update install, and low-storage scenarios on real devices before calling this production-ready.
