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

The initial request authorized an Actions artifact only. The user subsequently requested an in-app update, explicitly authorizing the Stable publication recorded below.

## Completed CI and signed artifact

- Application source: `2d93387ba388c0ff6bd5a0d451d60820b9d2ed63` on `v17-fixes`.
- [Final audit](https://github.com/prajwalts3007-droid/pts-stream-android/actions/runs/43745342685): success. 137 host tests per variant (274 executions), zero failures/errors. Lint: zero errors, 95 warnings and one hint per variant. Device regression APK compiled, not executed; isolated infra tests passed.
- [Signed build](https://github.com/prajwalts3007-droid/pts-stream-android/actions/runs/43745586412): success, manually dispatched with versionCode 17 / versionName 1.0.17.
- [Signed APK artifact](https://github.com/prajwalts3007-droid/pts-stream-android/actions/runs/43745586412/artifacts/14174541907), expires 2027-02-13. GitHub sign-in may be required. Extract `outputs/apk/release/app-release.apk` from the ZIP, not the separate debug artifact.
- APK: 3,810,357 bytes; SHA-256 `034585033171ab37ae11724508c8ab9ba7f70aa31ca4e540499e868a3e0d4ac6`. Independent verification matches CI's packaging hash; package `online.ptsmovies.stream`, unchanged signer `96b14e2e5f2a67dbbb9b2d091c62c800ff61d0a91e5c2e234dd35a28efac4ed7`, standalone/non-debuggable release, API 26/35, baseline profiles and 16 KiB ZIP/native alignment. The new feature strings were also confirmed in release DEX.
- Install over the existing signed app without uninstalling; physical upgrade/data preservation still needs phone verification. The original Actions artifact remains separate from the public tag-built release below.

## User-authorized Stable publication

- [Stable v17](https://github.com/prajwalts3007-droid/pts-stream-android/releases/tag/v17), tagged source `a4ec323a7a16acab56fac7dc5d29d65177a10dbb`; application/test source is unchanged from audited `2d93387` (the intervening commit records artifact documentation).
- Main CI audit `43747458644` and debug build `43747458690`: success. Signed tag release/publication [run 43747815907](https://github.com/prajwalts3007-droid/pts-stream-android/actions/runs/43747815907): success.
- [Public standalone signed APK](https://github.com/prajwalts3007-droid/pts-stream-android/releases/download/v17/PTS-Stream-v17.apk): **3,810,357 bytes**, SHA-256 **`d017cb86da36fbbe206c35c40ac79b357d0bccdc7389d2c4a7e37dcf9f868247`**. Public download, tag CI report and GitHub asset digest match. Package/version, unchanged signer, baseline profiles and 16 KiB ZIP/native alignment independently reverified.
- Whole-file hash differs from the Actions-only APK. All uncompressed ZIP entries match except `META-INF/version-control-info.textproto`, whose source revision correctly changes from `2d93387` to documentation commit `a4ec323`. DEX, resources, manifest, baseline profiles and native libraries are identical. Use the public hash above for Stable OTA/download verification.
- Anonymous `releases/latest` returns non-draft, non-prerelease v17 with the expected asset URL/digest. On v16 or older: **Profile → Check for updates → Stable → Check for updates**. Download and approve Android's installer. Do not uninstall. If 1.0.17 is already installed from Actions, no newer version is expected.
- Publication does not establish phone installation, pixel-perfect appearance, Google OAuth success or measured device performance; the device-validation boundary above remains unchanged.
