# Native lifecycle and device-download rework

This pass addresses the owner’s post-v3 device report. It is not a claim that every
web administrator workflow is now implemented.

## Ownership and persistence

- `DeviceState` holds device theme, per-library-tab grid/list preference, cached
  account/library, and owner-scoped navigation. Logout removes the cached session
  and library; a temporary network outage does not erase offline metadata.
- The launcher opens Stream for both roles. Hub ingestion is explicitly labeled
  **Ingest**, not confused with **Downloads**. Hub/Files remain admin-only. There is
  no embedded browser or admin web page in the application.
- `DownloadRepository` owns the transfer queue independently of the Activity.
  `DownloadService` is a `dataSync` foreground service with a notification. The
  native Downloads page displays active and paused transfers as well as saved
  videos, byte counts, speed, ETA, pause/resume/cancel and playback/deletion.
- A Save offline action navigates immediately to Downloads. Closing/recreating the
  Activity does not cancel the service. After process loss, a persisted in-flight
  task becomes **Paused**, with its title/episode metadata retained. The user can
  explicitly resume; force-stop and Android foreground-service time limits are
  not bypassed.
- Partial-file resume requires a strong ETag or Last-Modified validator, `If-Range`,
  an exact 206 Content-Range and unchanged total/validator. If the server does not
  support safe ranges or changes the object, a fresh complete transfer is used
  instead of joining unrelated bytes. Offline media stays device-private. HLS
  offline preparation remains unavailable until its authorization is verified.

## Playback and watch layout

The player has one video overlay toolbar and one compact timeline row, instead of
the previous permanently visible duplicate rows. Portrait details are separately
scrollable; fullscreen hides system bars and Back exits fullscreen first.

`PlayerGestures` uses Android/Compose touch slop and continuous drag feedback:
horizontal seek preview/commit, left-side vertical brightness, right-side vertical
volume, double-tap ±10 seconds and a visible gesture guide. Playback state and exact
position are persisted for Activity/process restoration, including paused state.
Ordinary background/reopen retains the same player rather than re-fetching the
whole app with a blocking loading screen.

## Shared web data versus device preferences

Profile photos are loaded using the authenticated native HTTP client, including
relative avatar URLs. Native photo upload/removal and profile name/email editing
use the web account APIs. While foregrounded, account/library data refreshes on
entry and periodically (30 seconds); watchlist/progress mutations use the shared
API. Offline data can naturally be stale until connectivity returns. This is not
instant server-push synchronization.

Light/Dark/System themes and Grid/List presentation are persistent device settings.
The web theme itself is browser-local; no nonexistent account theme-sync endpoint
is invented. Player chrome remains dark for video readability.

## OTA

The native Updates page distinguishes **Stable** and **Test releases**. Signed RC
builds default to Test; stable releases default to Stable. Stable still excludes
prereleases. APK transfer is owned by Android DownloadManager and can be observed
again after Activity/process recreation. Package, numeric version, signing
certificate and any published SHA-256 are verified before installation; installer
consent is always required. The install-permission screen returns to an explicit
Install button, rather than requiring a second APK download.

Old v3 and earlier builds still have the old stable-only updater, so the first
upgrade to this implementation must be installed manually using the same signing
key. Do not uninstall: doing so deletes app-private offline files.

## Verification status

The expanded isolated suite covers exact-range resume, changed-source protection,
interrupted journal recovery, cached metadata round-trips, test-channel discovery,
APK verification, native profile avatar requests, Activity recreation, Downloads
reopen, paused playback position, gesture feedback, themes and list presentation.
See the release notes for the actual final passing run; merely adding a test does
not mean it has passed. Real devices, production authentication/media failover,
Android installer consent and OEM battery restrictions still need verification.
