# v17 — native reliability and connection UI

Android-only follow-up to v16. No diagnostics page, telemetry, web/backend changes,
production ingestion tests, account mutation or media compression changes.

## Features

- Shared profile photo component: static red glow for a trusted Pi verified on the current Wi-Fi network, green for a verified internet server, neutral while checking/offline. Same sizing on every header; larger matching photo in Profile. Header tap still opens Profile; long press opens Connection. Profile photo and Connection row open the same panel. Full-screen playback/PiP do not acquire an obstructing profile overlay.
- Connection panel shows preferred verified media connectivity, last-check age, local address when verified, manual recheck and limitations. Account APIs still use internet even with a red ring; this is not a per-request route promise. Foreground checks only, every 30 seconds and on network changes; certificate-trusted bounded LAN probes, cookie-free internet checks and safe Wi-Fi fallback. VPN is not treated as a verified direct route.
- Profile-only download recovery: Pause all / Resume all, free-space summary, accessible failures, restore original-folder permission, refresh access, retry a completed staged export to another folder or private storage without redownloading/HLS preparation. Changing folders never moves existing downloads. Missing external documents cannot be recovered without the original file/provider.
- Optional charging-only scheduling; automatic wake on power changes while the foreground download service remains alive. Explicit pause remains paused. Android force-stop/reboot/FGS time limits may require reopening Downloads and Resume.
- Optional low-memory mode: one transfer worker, smaller poster decodes/cache and fewer animations/decorations; no codec/quality changes or loss of gestures/PiP. Automatic downloads and deletion remain separately opt-in.
- Account-scoped settings/progress sync status and safe manual retry. Never retries ingestion submissions.

## Logic/performance fixes

- Balance each service repository acquisition: one retained lease per service, temporary repeated-start leases released on all paths.
- Preserve partial bytes and validators across transient network errors. Unsafe range/validator/source changes discard partials; HTTP 416 restarts safely. Cancellation is still propagated.
- Settings use persisted revisions and shared owner locks: old PUT acknowledgement/GET cannot clear or overwrite newer edits; save all playback form fields in one batch. Untouched forms track late server preferences; dirty forms retain edits.
- Reserve private staging plus a second copy for identifiable primary-storage exports, including concurrent reservations. Opaque providers retain explicit warnings and export failure recovery rather than guessing their free space.
- Separate frequent byte counter checkpoints from full title/episode queue metadata; structural transitions remain durable and legacy v15/v16 queues remain readable without schema downgrade/migration.
- Bounded single-worker poster queue; completion never waits for artwork, cancellation reaches image sockets, requests have short deadlines.
- Endpoint-specific JSON byte bounds (16 MiB library, 8 MiB queue/files, 4 MiB ordinary API, smaller public probes/errors), including chunked responses. Oversized results fail visibly, never truncate into a partial catalogue.
- Serialize legacy resume flushes per owner, and keep pending/synced labels account-aware.

## Verification boundary

GitHub Actions runs host tests/lint, compiles device UI tests and builds the signed APK.
No emulator or phone tests are represented as executed. UI regressions cover stable
avatar bounds, independent click/long press, revision-safe forms, and recovery controls
at 288 dp/1.5x text. These must still be executed on a real phone. Pixel-perfect Oppo
layout, frame timing, allocation/decoder profiling, OAuth success, SAF provider behaviour,
in-place upgrade, DHCP/network switching, PiP and OEM scheduling require device checks.
No blanket phone-level smoothness or visual perfection claim is made.

For performance acceptance on the phone: compare cold/warm Home and large-catalogue
refresh, frame stalls/peak heap, long scrolling, and concurrent transfers before/after.
Do not infer device allocation savings from host timings or slower animations.

Build is an Actions artifact only; no Stable release/tag is authorized by the build request.
