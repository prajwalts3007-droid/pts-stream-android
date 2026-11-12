# Native Android follow-up fixes (unreleased)

Scope: Kotlin/Compose Android application only. No web UI, Pi configuration, backend deployment, production ingestion job, or compression setting changes. No release tag or public APK is created by this work.

## Requested changes

- Featured card/poster tap opens title details; its separate Play button still plays and My list still toggles independently.
- Stream Home no longer has filter/sort controls or hidden filter state. Browse pages retain their controls.
- Profile no longer has the duplicate card literally labelled **Sections**. Administrator mode switching via the existing dock swipe and header remains; role checks are unchanged. About attribution stays available for metadata still in use.
- Peaky Blinders' device-cached TVmaze source is excluded and removed when read. Other titles' metadata and server catalogue data are untouched. No new metadata lookup UI is added.
- Custom motion durations are 1.4× the previous durations; page entry is 280 ms rather than 200 ms. Reduced-motion behaviour remains immediate. The active refresh glyph rotates more slowly. This is a timing change, not evidence of measured frame-rate improvement.
- Pi discovery probes the saved address, then at most the current Wi-Fi /24 (or the actual smaller subnet) after failure. It uses the existing trusted TLS certificate, correct hostname, a bounded health response and no cookies or credentials. Concurrency and total scan time are bounded; cancellation cancels socket calls. Scanning is not run on cellular/VPN, does not occupy the durable database work scope, and does not overwrite a concurrent manual edit. A verified new address is saved only on the same network with LAN preference still enabled. Internet media remains the fallback. Cross-subnet discovery, guest-network isolation and provider/OEM restrictions are not solved by a /24 sweep.
- Ingest searches distinguish cancellation from errors, debounce before sending, ignore stale query/account/session responses, clear obsolete results, and retry only transient read failures with a bounded backoff. Authentication, rate-limit and validation failures are not automatically retried. Queue submission is never automatically retried. Real search failures get a local retry action rather than a misleading cancellation toast.
- Ingest search results and the selected title now render a bounded poster using the existing image loader, with an initial as fallback. Stale explicit lookup / ZIP preflight responses cannot overwrite a changed query/title.
- The ingestion confirmation's ownership-warning sentence was removed as requested. Add confirmation, duplicate/overwrite consent, source validation, ZIP checks and admin-only access remain.
- Queue cards show reported `speed_bps` and `eta_seconds` for applicable active stages. Missing ETA is shown as `ETA —`, not fabricated from mixed-stage sizes. Inactive stages hide stale telemetry. Padding and gaps are reduced while action touch targets remain 48 dp. Messages use two lines initially and expand on tap; diagnostics are not discarded.
- Google auth configuration is cached and can be fetched again on demand after a failed startup request. Credential Manager failures retain an error type; backend account-matching/verification errors are no longer hidden behind a generic device error. This does **not** establish successful Google sign-in or fix Google Cloud registration from inside the APK.

## Google sign-in external prerequisite

The public server config returns Google sign-in enabled and a web/server OAuth client ID. Confirm an **Android OAuth client in the same Google Cloud project**, with:

- Package: `online.ptsmovies.stream`
- Release signing certificate SHA-1: `60:8B:2E:72:02:F6:D6:EA:B6:86:0A:B2:EB:CC:C6:8F:A7:44:4B:F3`

The SHA-1 was extracted from the public signed v15 APK; it is not a private key. Compatible Google Play services and an existing permitted PTS account whose email matches the verified Google email are also required. No console configuration was changed, and no production sign-in/account mutation was performed for testing. The actual device error is still needed to establish which external prerequisite is failing.

## Validation boundary

GitHub Actions runs host unit tests, lint, builds the debug APK, and **compiles** Compose/device regression tests without an emulator. Compiling those tests does not execute taps, measure phone performance, establish Pi discovery on the user's network, or establish Google authentication on the phone. No published v16 APK should be inferred from the branch name.

Required real-device checks after an approved build: in-place upgrade, featured card versus Play navigation, Home/Browse filter separation, admin dock access, ingest cancellation and posters, queue ETA/large text, Wi-Fi DHCP change and fallback, Google error/success, playback/PiP and existing downloads.

See [the separate read-only media-size audit](MEDIA-SIZE-AUDIT-20261112.md) for Actions media output sizes. Encoder settings were not changed.
