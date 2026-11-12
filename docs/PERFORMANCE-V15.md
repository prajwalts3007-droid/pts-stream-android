# v15 low-memory / lifecycle stability work

Target report: Oppo A6x 5G, Android16, 4GB RAM; phone slowdown even without
playback/downloads and when minimizing. Source fixes are not a claim of a
measured device root cause, pixel-perfect rendering or universal zero lag.

## Storage and migration

- `StateDatabase` uses Android SQLite/WAL with owner-scoped documents, unique
  title records and separate resume rows. Catalogue title references and small
  progress updates no longer rewrite a full catalogue in UI preferences.
- Catalogue metadata/references commit transactionally. Legacy library, queue,
  offline index and progress sources are removed only after a destination write
  commits; source cleanup is idempotent. No destructive database upgrade fallback.
- Download queue schema stores each show's metadata once, with compact episode
  references and byte counters. Legacy v14 queues remain readable. Unsupported
  queue versions fail rather than silently clearing data.
- Bulk episode enqueue persists one queue update. Startup/offline validation runs
  on IO; per-title parsed offline metadata is shared between saved episodes.
- Small navigation stores IDs/position, not full selected/player show trees.
  Progress edits use ordered process-owned IO work, survive Activity destruction
  and publish completion after durable writes. Nothing promises survival of a
  process killed before an asynchronous transaction has had time to commit.

## Idle, lifecycle, memory and UI

- UI download collection follows STARTED; startup catalogue work is cancelable
  when stopped. OkHttp cancellation reaches JSON/CSRF network calls.
- Session/role checks remain every30s while foreground; automatic full catalogue
  refresh is stale-based (5minutes) and excluded during playback/Profile/settings.
  Explicit refresh/watchlist changes still refresh immediately.
- Outside PiP, stopping the Activity stops/prepares the same keyed player on
  return, retaining position/play intent while releasing decoder buffers. PiP
  and authorized foreground transfers/alerts are not disabled.
- Playback state timers respect lifecycle; subtitle style updates only when
  changed. Normal Home/browse does not build an unused episode search index.
  Typed search is debounced and cooperatively cancelable.
- Public poster cache has a hard12MiB ceiling and5%-of-app-memory bound; the
  separate authenticated/local loader caps at4MiB and shuts down on destruction.
  Public requests remain credential-free. Cache trimming does not force GC.
- Inactive repositories have explicit UI/service ownership and safe release;
  completed/paused repositories are not immortal process-wide allocations.
- Detail/player episode rows are individually lazy. Download progress updates
  are at most once per second per transfer, plus final/state transitions.

## Download destination (the only new settings controls)

Manual downloads ask once: private app storage or Android system folder picker.
Profile → Downloads & network → Change download folder changes future tasks.
Each task captures its destination; already queued tasks keep their destination.
Persisted document grants are used, never all-files access or recursive scanning.
Existing private media is not moved or deleted. Selected-folder files are visible
outside PTS; subtitles remain private and ownership remains in the app index.

For safe validator/range resume and source-size checks, transfers stage privately
and export a verified complete MP4 to the chosen provider. This requires internal
staging space and an extra copy; it is NOT direct-to-SD, an SD-space bypass or a
claimed speed improvement. On failed/revoked provider access, the verified staged
file is kept for explicit retry; index entries for inaccessible documents remain
so restoring access can recover them. No retry loop bypasses an error.

## Validation boundary

GitHub Actions only for Android builds. JVM/Robolectric storage/migration tests
execute on a hosted JVM, not an emulator or a phone. Existing device regression
sources are compiled, not executed. Same signing key/package, Stable/Test update
channels, installer consent and 16KiB packaging checks are retained.

No production backend/Pi rebuild or production account/media/job mutation.
No rendered screenshot, real Android16 device trace, measured memory/CPU/frame
improvement, runtime clean-install/upgrade, SAF provider/device test, PiP/OEM/codec
execution or pixel-perfect certification is implied by host tests. The interface
is intentionally retained, except the requested destination controls; real-phone
acceptance remains necessary after installation.
