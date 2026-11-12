# PTS Library Android

App source/releases: `prajwalts3007-droid/pts-stream-android`, a separate Android
repository on an existing account authorized by the owner. The previous
`sundar211954-debug` release account was suspended; no replacement account was
created. The app ID and persistent signing key are unchanged. Existing installs
need one manual signed APK update to switch their stable-update repository.

Native Kotlin/Compose client for `https://ptsmovies.online`. Android 8+, HEVC-capable device required for playback. Uses Media3 for video and native local storage for approved offline MP4s. No production media or PTS credentials are stored in this repository; the only video fixture is a generated test pattern packaged in the test APK. The installed app is named **PTS Library**; the package identifier and signing key are retained for Android updates.

## Build

GitHub Actions builds a **debug test APK** on each commit; find it under the run's Artifacts. Debug APKs are not published as OTA releases. A signed production APK requires a persistent signing key, kept outside Git and provided as repository Actions secrets: `PTS_KEYSTORE_B64`, `PTS_KEYSTORE_PASSWORD`, `PTS_KEY_ALIAS`, `PTS_KEY_PASSWORD`. Never change or lose this key: Android will reject updates signed by a different key. Once configured, tag the verified commit with a monotonically increasing numeric tag (`v2`, `v3`, …) to publish a signed GitHub release. The app checks the public release API and opens the system package installer; Android always asks the user to approve the update. No silent installation. APKs installed from debug artifacts cannot be upgraded in place by a signed release; uninstall the debug build first (which deletes its app-private downloads).

Release-candidate tags (`v1-rc1`) produce a **prerelease** APK for manual testing. The native Updates page has separate Stable and Test channels; Stable excludes prereleases. RC builds default to Test; production builds default to Stable. A release-channel label is not a substitute for phone validation. Earlier builds with the old stable-only updater need one manual signed update.

The v15 stable-channel build keeps the v13 interface and adds the requested download-location controls. It moves catalogue, queue, download-index and progress state to owner-scoped SQLite, reduces UI-thread and minimized-time work, and releases decoder/artwork/repository memory; see [performance changes and explicit device-validation limits](docs/PERFORMANCE-V15.md). v14 notes remain in [PERFORMANCE-V14.md](docs/PERFORMANCE-V14.md). The same standalone signed APK supports a fresh installation on Android 8+ or an in-place update from the existing signed app. Existing users should **not uninstall**: app-private downloads are deleted on uninstall.

## Constraints before production release

- This is an independent Android client; the production backend has **not** been rebuilt or changed. The checked-out VPS Go source predates the running binary. Verify normal-user login, playback URLs, progress, watchlist and download authorization against the deployed API before distributing.
- The Android app downloads **only approved MP4** directly to private device storage. Manual HLS preparation follows the authenticated web endpoint after matching owner/role revalidation; server authorization/capacity/expiry remain authoritative. The old server source differs from the deployed service; successful ordinary-user production preparation has not been tested and restrictions are never bypassed. Smart downloads remain MP4-only.
- Device MP4 downloads use a native foreground service and persistent owner-scoped queue. Downloads show progress, speed/ETA, pause/resume and saved videos after reopening. Range resume requires a matching validator; unsupported/changed sources restart safely. Android process/force-stop limits are not bypassed. Cached library metadata supports local offline display without waiting for the server. See [native lifecycle behavior and limitations](docs/NATIVE-LIFECYCLE.md).
- Android media support varies by device. Check HEVC hardware decoding and audio/subtitle tracks on the intended phones. Offline files remain on the device and are deleted on uninstall. A device backup is disabled.
- Existing web push subscriptions are browser-only. Native download notifications are local; optional admin-only job alerts use a user-started foreground service, not FCM. Notification permission, connection, OEM background rules, force-stop and reboot restrictions still apply.
- Hub, Files and Streaming are native Compose sections with their own bottom navigation. Administrators switch by swiping the dock or using Profile → Sections; tapping the brand returns to Hub. Users see Streaming only. No WebView is embedded. The available source predates the deployed backend, so retries/source editing and sensitive settings remain unavailable until source validation and access rules are verified; metadata search does not submit jobs. Do not advertise full feature parity yet.
- Test login/role boundaries, transfers, Pi offline/fallback, rotation, downloads, update install, and low-storage scenarios on real devices before calling this production-ready.

## Automated audit

See [the web-to-native control inventory](docs/WEB-PARITY.md) for the second-pass season,
watch-page/player, Files menu and clear-action integrations and the remaining differences.

The active `Native build and non-emulator audit` compiles the app and instrumentation APK, runs debug/release JVM tests and lint, and executes isolated Python regressions. The Android APK workflow separately validates a signed, R8-optimized release and 16 KiB packaging. Device test sources cover loopback login, role/navigation, playback, persistence and layout flows, but are **compiled only**, not executed by this audit. No emulator workflow or streamed preview is dispatched for this release; host test success does not establish pixel-perfect rendering or phone frame pacing.

These tests do not prove deployed backend authorization, real Pi/Tunnel/VPS failover, physical-device codec support, OTA installer consent or low-storage behavior. A green APK build alone is not an end-to-end pass.
