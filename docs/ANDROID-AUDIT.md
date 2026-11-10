# Native Android audit — November 2026

## Scope and design

The native interface uses the live `education.css` design tokens: indigo background,
lavender accent, bordered rounded cards, DM Sans body text, explicitly weighted
Nunito headings, and floating section-specific bottom docks. Administrators switch
Hub → Files → Stream by swiping the dock or choosing Profile → Sections. Normal
users see Stream only. No WebView is embedded.

Rendered screens are inspected, not inferred from a successful compilation. The
screen suite includes login, dashboard, queue, Files, transfers, Stream home, browse,
details, offline Downloads, profile, settings and metadata search, plus a 320 dp
phone at 130% text size. Fixtures are synthetic; empty posters deliberately exercise
fallback states rather than depending on a third-party image host.

The visual audit corrected font variation axes, malformed outline icons, crowded
hero actions, unequal metric tiles, keyboard/confirmation scrolling, navigation
insets and the oversized default Material player seek bar.

## Automated release gate

Workflow: **Native screen and API audit**. Build the application and instrumentation
APK, run Android lint, then run the Android 35 emulator suite. Reports and PNGs are
retained in the `native-audit-evidence` artifact.

The first complete pass was [run 42792633183](https://github.com/sundar211954-debug/pts-stream-android/actions/runs/42792633183),
with 23 tests, zero failures, zero skips and zero lint errors. Subsequent UI fixes
must rerun the same gate before a release candidate is published.

Covered with a loopback mock API:

- Real Activity login, persistent-session restoration, sign-out, role-specific
  navigation, dock swipes, folder back navigation and Activity recreation.
- Real Media3 HEVC playback using an 8-second generated test pattern and tone.
- A failed direct stream followed by successful `?via=vps` playback.
- Saving a complete private offline copy, playing it through a local file source,
  and deleting it with confirmation.
- Password visibility, remember-session choice and reactive watchlist changes.
- Typed, stage-named queue confirmation using the displayed job snapshot. Newly
  arriving or other-stage jobs are excluded. Deletions use individual job URLs,
  never public bulk DELETE.
- API CSRF and request-body contracts, identifiable HTTP authorization errors,
  HLS URL construction and refusal of unverified HLS offline preparation.
- Oversized-transfer refusal without a pointless VPS retry; source failures leave
  no saved or partial file.
- OTA selection excludes prereleases and rejects APK URLs outside the exact
  expected public repository/release path. Downloaded packages are checked for
  package ID, version, signing certificate and published SHA-256 when provided.

No test logs in to production or modifies the production queue, settings or files.
The debug-only API injection is absent from release builds.

## Read-only production checks

On 2026-11-10 the public site and Pi media health endpoint returned HTTP 200.
Unauthenticated `/auth/me`, `/queue`, `/admin/files` and `/stream/library` requests
returned HTTP 401. These negative checks do **not** prove authenticated role
authorization or media behavior. No backend binary, database, nginx configuration
or pipeline was changed for this Android audit.

## Not a stable/full-parity release

The later [native lifecycle rework](NATIVE-LIFECYCLE.md) supersedes the old
foreground-only download and stable-only update restrictions below. Historical
results here describe the first audit; use current release notes for the new run.

- Real-device codec/track support, rotation/PiP, deployed Pi/Tunnel/VPS failover,
  authenticated production behavior, actual low-device-storage conditions and
  installer consent still require physical-device testing.
- Metadata search does not submit jobs. Native source selection, ZIP preflight,
  retry/source editing and several administrator settings/file forms remain
  unavailable until the deployed backend and source are reconciled.
- Offline transfers remain foreground-only, non-resumable and direct-MP4-only.
  A live session is still needed to display the library and offline list.
- Notifications are local transfer notifications, not native FCM remote push.
- OTA installer consent is not automated. Prerelease APKs are manual test updates
  and intentionally excluded from `/releases/latest`.

Debug APKs cannot upgrade a release installation (or vice versa). Signed release
candidates retain the existing app ID and signing key and use an increasing
versionCode, so earlier signed candidates can upgrade without uninstalling.
