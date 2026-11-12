# v14 stable-channel performance release

This release preserves v13's screen layout, features, package, signing identity,
account boundaries, installer consent and Stable/Test update channels. It does
not change the server, Pi relay, ingestion jobs or any production accounts.

## Changes with source-level evidence

- Bounded three-entry, account-scoped LRU search indexes survive section changes.
  Equal newly parsed title/episode lists reuse the index. Work stays on Default;
  concurrent requests build only once. Account changes replace the cache.
- Library, file-operation and relay refreshes each have one in-flight request and
  at most one coalesced follow-up. Obsolete session tickets cannot unlock a newer
  request. Existing visible-screen/lifecycle polling rules remain in place.
- Equal library refreshes preserve the previous title-list identity and skip
  unnecessary library-cache serialization and offline metadata hydration.
- The library is displayed with the durable local progress overlay before
  progress uploads/episode-sync finish. Slow progress endpoints no longer delay
  displaying a successfully fetched library.
- Progress stores are reused per account; parsed progress records and completion
  sets are cached by their actual persisted source and invalidate after edits by
  any store instance. Episode sync is serialized per owner and performs parsing,
  merge and durable commits on IO, not the UI dispatcher. Journal flush commits
  also run on IO. Local user edits and lifecycle progress still commit durably;
  this release does not trade persistence for asynchronous best-effort writes.
- Bulk title completion uses one durable JSON update instead of rewriting and
  committing the entire record list once for every episode.
- Season episode filtering/sorting is memoized; the language alias table loads
  only when needed rather than during unrelated presentation initialization.
- Release code/resource shrinking and R8 optimization are enabled. Library
  consumer rules, explicit JSON schemas, manifest entry points and merged
  dependency baseline profiles are retained; mapping files are archived in CI.
  No fabricated or unmeasured app-specific baseline/startup profile is supplied.

## Fresh-install and upgrade checks

The signed asset remains a standalone universal APK, not a split requiring an
existing installation. Minimum Android is API26 (Android8), target API35.
Existing users install over the app; do not uninstall or lose local downloads.
New phones install the same APK then sign in normally. Android's install consent
and any notification permission prompt are still required.

`infra/verify_release_apk.py` fails CI on a changed signer/package, wrong version,
debuggable APK, splits, unexpected required touchscreen, enabled backups or
cleartext, absent application/launcher entry points, missing dependency profiles,
changed/missing LAN anchor, packaged fixture media or signing key, invalid native
ELF load alignment, or failed 16KiB APK zip alignment. These are packaging checks,
not actual device install execution or end-to-end runtime verification.

## Validation boundaries

CI executes JVM tests in debug and release variants, isolated Python regressions,
debug/release lint, application compilation and device-regression compilation.
The separate signed validation builds and inspects the R8-optimized release
before a stable tag is published. No emulators or streamed previews are used.

The unchanged v13 source UI inventory remains in `UI-AUDIT-V13.md`. This is NOT
a pixel-perfect certificate. No new screenshots, phone frame timings, runtime
instrumentation, clean-install/upgrade execution, R8 runtime smoke test, OEM
background-service test or codec playback claim is made by these host checks.
Actual phones must still verify large-font/keyboard/cutout layout, light/dark
themes, scroll pacing, native mini-player/PiP, playback/audio/subtitles, role
navigation, installer/notifications, authentication and download persistence.
The user requested a stable-channel build; "stable" is the release/update channel,
not a guarantee of identical pixels or measured smoothness on every phone.
