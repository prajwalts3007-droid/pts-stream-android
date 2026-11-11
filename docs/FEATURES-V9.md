# v9 scope and validation

## Presentation

Dark/system-dark uses a true #000000 page background throughout the native app, neutral near-black surfaces, and no decorative purple gradient. Light mode remains available. Description and related shelves return on details and portrait watch pages, without technical metadata/editor clutter. Settings category icons fit equally across one row; the bottom dock has equal cells, centered labels and hides during keyboard entry. Legacy Hugging Face cleanup UI is removed, not provider files/accounts or production APIs.

## Additions

- Combined year/audio-language/unwatched/device-downloaded filters on Movies, TV Shows and My List. Languages come from real audio-track metadata, never title guesses. Whole-series completion requires all episodes.
- Private per-account/per-video SRT/WebVTT import through Android's document picker, validated content/UTF-8 or BOM UTF-16 and 2MiB limit. No shared-storage permission or external subtitle upload. Imports attach to native Media3 playback including offline video; removal remains available under Audio and subtitles.
- Opt-in next1–3 episode smart downloads, off by default, using the existing persistent queue's Wi-Fi/storage/concurrency/account guards. Skip completed, saved, paused, failed and explicitly cancelled work. No automatic HLS preparation and no automatic deletion unless separately enabled by the user.
- Account-scoped per-episode resume/completion, offline edit queue, replay-safe newest-edit merge, explicit mark watched/unwatched controls and Profile→Sync & alerts status/manual refresh. Existing title-level progress continues for web compatibility. New detailed records sync across native Android devices; the deployed web UI does not yet render individual native episode completion.
- Real server job-completion/failure events over authenticated long polling into native notifications. Admin-only, explicit opt-in user-started foreground service with ongoing notification and Stop action; no fake browser push, paid provider or FCM credential dependency. Requires notification permission/network; force-stop, reboot or OEM restrictions may stop it. No claim of invisible always-on FCM-equivalent delivery. Payloads omit source URLs, filesystem paths and raw job errors. No historical flood on first connection; events retained7days and deduplicated by cursor.

## Companion service

`infra/native_companion.py` binds only127.0.0.1:8791 behind the existing HTTPS origin at `/api/v1/native/`. It opens the production SQLite library/session/jobs database read-only; validates the existing hashed session, active account, expiration and session-bound CSRF for writes. Progress validates title/episode ownership and is stored in a separate owner-scoped SQLite state database. Unsafe bodies bounded64KiB/100records. Server-authenticated long polls revalidate access before returning events. A single5second read-only job scan feeds all phones; historical snapshots are baseline-only. No production pipeline binary rebuild, migration, job mutation or permanent media storage.

## Requested non-emulator validation

The user explicitly requested no emulation for this release. GitHub Actions compiles debug/release and instrumentation regressions, runs JVM policy/content tests, Python companion/relay security tests and Android lint. It does **not** boot an emulator, stream a preview, or run device instrumentation. Existing v8 device evidence is historical, not v9 proof. New layouts, subtitle decoding and background-service behavior require physical-phone feedback after install. Do not claim fresh screenshots or device end-to-end validation for v9.

Deployment/build results and signed release reference are recorded separately after execution. Existing Google OAuth registration and ordinary-user HLS preparation restrictions remain unchanged.
