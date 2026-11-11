# Web / native parity audit — v7 work

Reference: the deployed PTS `index.html` and `app.js` (web app asset revision 144), inspected read-only. Native implementation remains Kotlin/Compose + Media3. Backend source in the checkout is older than the deployed multi-account service; **no Go service was rebuilt**. No existing production ingestion job was retried, edited or submitted to validate this app.

## Feature inventory

| Web feature / contract | Native equivalent / verification boundary |
| --- | --- |
| Password login, remember choice, session restoration/logout | Existing authenticated native sessions; CSRF/owner tests retained. |
| Google sign-in via configured client and `/auth/google` | Added Android Credential Manager / Google ID credential flow and the same CSRF-protected `{credential, remember}` request. Password fallback remains. A physical Google-enabled device and matching Android OAuth package/signing registration are still required; emulator contract tests do not certify that external setup. No WebView, local account creation or token-in-URL bridge. |
| Profile name/email, password change, authenticated photo upload/removal | Existing native profile forms and image client; regression-tested. |
| Stream Home / Movies / Series / My List, search, genre filtering, progress, related titles | Existing native browse/detail pages, with one grid/list destination toggle. Playback speed stays in the player, as requested, rather than reproducing the web Settings speed control. |
| Dashboard system metrics and VPS / Pi choice | Existing metrics. Pipeline shortcut and Live badge intentionally remain removed at the user's request. |
| Queue stage filters, counts, retry-all | Nine equal-width icon/count cells in a **non-scrolling, non-wrapping** row. `99+` visible cap; accessibility retains the real count. Small screens necessarily use narrower cells, like the web row; other app buttons are not shrunk. Retry now calls `POST /queue/retry-all` with CSRF, confirmation and in-flight duplicate protection. |
| Queue per-job Retry / Edit link / Remove | Added failed-job Retry (`POST /queue/{id}/retry`) and Edit (`POST /queue/{id}/edit`, `{download_url}`). Edit warns about restart and Pi overwrite and pre-fills the original link. Server source/ZIP validation and completed-upload reuse remain authoritative. Remove and stage-named snapshot deletion remain per-job DELETE; public bulk DELETE stays unused/blocked. |
| IMDb lookup/search and movie submission | Native Ingest result selection, ID/URL lookup and movie-link submission now work. Uses the deployed key-free metadata service, not a new paid/API-key provider. An explicit submission confirmation precedes POST. |
| Generate series rows; paste links; upload `links.txt` | Native season/count generation (1–200), bounded 2 MiB import, numbered filename mapping, Pixeldrain member/URL manifest support and unnumbered paste-in-order. Replacement of populated rows requires confirmation. Duplicate/conflicting numbers and unsafe paths are rejected. |
| Public ZIP preflight and selected episode members | Calls `POST /queue/zip/preflight` before mapping members. Keeps exact `zip_member`, explicit row selection, ambiguous-member rejection, HTTPS/range warning and the runner-access limitation. ZIP contents are not copied permanently to the VPS by this app. |
| Queue duplicate overwrite prompt | Preserves structured 409 duplicate data. `force` defaults to false and is sent true only after the separate Replace warning. Tests exercise preflight → deselection → initial 409 → explicit replacement. |
| Files browse, device/path, upload, mkdir/touch, download, text edit, rename, cut/copy/paste, ZIP/unzip, multiselect/delete | Existing authenticated native operations and guarded forms. One toolbar and shared file-list rows; anchored menus. Safe bounded uploads and captured device/path contracts remain. |
| Files operations / relay transfer list, cancel/delete, clear finished, copy/open published links | Existing native transfer screens and confirmations; regression-tested. Native transfer services own phone downloads separately. |
| API Management GitHub account selection/status and Google configuration | Existing forms plus active/account-limit/cooldown and configured-origin status. Secrets clear when leaving/stopping; provisioning/configuration was not tested by production mutations. |
| Pipeline limits, active/paused status, refresh/save | Native limits and status, original speed conversion retained, paired controls. Typed confirmation remains stronger than web before writes. |
| Devices reboot and historical bucket cleanup/token replacement | Reboot remains password/typed-confirmation guarded. Added native legacy cleanup forms using the web endpoints and warning. **The provider still rejects historical account access.** Adding the UI does not repair/swap accounts or make cleanup succeed; no purge/token change/reboot was executed for testing. |
| User create, role/active changes, delete; self-protection | Existing native management, role/password/typed confirmations and no self-destructive buttons. |
| HLS playback and temporary MP4 preparation/download | Existing Media3 HLS. Added native **admin-only** preparation → owned-status polling → validated MP4 saving through the web endpoints, using the service-owned download queue. Server slot/expiry checks remain; a permission failure is never bypassed. Normal-user preparation is still gated because the deployed authorization contract has not been verified for it. Pausing/cancelling stops the phone worker; any already-started Pi preparation follows server expiry, just as with the web flow. No production preparation was triggered in tests. |
| Browser Web Push registration/test notifications | **Platform gap:** the web uses browser `PushSubscription`/service-worker keys. Android download-service notifications exist, but native remote job push is not implemented. Sending fake browser subscription keys would not deliver native notifications. A compatible native delivery design/server registration is required before claiming parity here. |

## Episode-name coverage

The database contains many series with no stored year and placeholder episode names. Automatic matching must therefore not require a year in every case, nor trust the first fuzzy search result.

- Uses a separate credential-free client to public TVmaze data, IMDb's existing key-free suggestion metadata and public Wikipedia search/pages.
- Accepts an automatic exact show-name + known debut-year match, or a **unique matching IMDb series identity** verified against TVmaze/Wikipedia. Duplicate/remake identities remain unresolved rather than guessed.
- When TVmaze has no suitable names, parses actual public Wikipedia episode tables, not cast/soundtrack tables. Uses explicit season headings and episode-in-season numbers, never overall-series order. Strips markup/references, not original text content.
- Only replaces generated labels (`Series name`, `E1 - Series name`, `Episode 1`, etc.). Actual server/manual names remain authoritative. Correcting a previous imported match can replace that import without overriding manually corrected backend names.
- Names/summaries, source URL and attribution are cached on device and reused offline; existing saved media is not redownloaded. Source correction is available on details/watch pages, with year/language/provider information and explicit selection.
- Sources may have missing episodes or genuinely unnamed episodes. Keep the fallback label when data is unavailable; no invented names, challenge bypass, account creation or paid/keyed API.
- TVmaze/Wikipedia attribution and source links remain visible (CC BY-SA). Detailed private library coverage reports are kept outside the public repository.

## Simpler player and timeout fix

Removed episode-switching transport buttons. The center has only **back 10s / play-pause / forward 10s**. Episode selection is a separate footer action; end-of-playback autoplay and its cancellation remain compatible with web preferences.

Header, center transport and footer occupy separate measured layout regions, rather than independently overlaid absolute positions. All existing seek/brightness/volume/double-tap/pinch/lock gestures remain. Tap shows/refreshes controls instead of immediately toggling them away. One resettable timeout waits until touch/seek/menu/buffering ends; pause keeps controls visible. A held finger cannot lose the controls underneath it.

Portrait controls reserve the status/cutout insets, not the phone's bottom navigation inset inside a top-of-screen video frame. Fullscreen controls still use all safe insets. This prevents the portrait footer from floating above an unnecessary navigation-bar-sized gap; an actual Activity bounds assertion covers it.

Tests cover compact widths (including 280dp), larger fonts, landscape-shaped regions, visibility/hit-testing of all nine Queue cells, non-overlapping player regions, exact ±10s actions, and actual Activity timer resetting in addition to screenshots. The short generated playback fixture is slowed for the timeout test so legitimate clip-end/rebuffer events cannot invalidate it.

## LAN findings and changes

Read-only Pi network inspection found the connection was actually associated on **2437 MHz / channel 6 / 20 MHz (2.4 GHz)** despite its 5G-labelled SSID. One sample reported approximately 57.7 Mbit/s TX and 26.0 Mbit/s RX. SSID names are not proof of a 5 GHz connection; wireless PHY rates are not application throughput and may vary.

One bounded, automatically removed 4 MiB synthetic fixture measured Pi-self reads of **62.75 MiB/s** directly from the loopback origin and **19.65 MiB/s** through the certificate-verified TLS relay. This measures neither the phone nor the radio path; it does not prove home-phone performance or compare to the user's internet route.

The Android download loop previously serialized every episode's full title metadata twice per 250ms report. It now skips unchanged state and checkpoints byte counters at most every two seconds while persisting state transitions immediately. A 256 KiB file-output buffer reduces small filesystem writes. Resume still validates the actual partial file length, response range, total and validator; UI progress updates are not throttled to the disk-checkpoint interval.

An explicit player Connection comparison reads at most 1 MiB per route, with actual route telemetry, and saves no media. Internet-only sampling bypasses LAN for that request without changing preferences. A range-ignoring large response is refused. Results include startup latency and can be distorted by concurrent playback; they are estimates, not guaranteed download speeds. LAN pinning, signed origin authorization, header isolation and fallback remain unchanged.

No Wi-Fi credentials, channels, network-manager configuration, production streamer or relay service were modified. A real 5 GHz/Ethernet upgrade would need a deliberate network change and reachable hardware, not an app claim.

## Interaction and motion revision

Refresh icons/buttons reflect real request state, rotate only while active, announce Refreshing/Ready and prevent repeated in-flight taps. Queue reload requests are coalesced so a mutation during an existing poll still receives a fresh result afterward. There is no artificial network delay or permanent idle animation.

Shared controls have ripple/press feedback without changing measured bounds. Clickable cards have a small press response; tabs transition selected colours; progress bars interpolate updates; season chevrons rotate; synopsis/provider sections expand smoothly. There are no new page decorations, autoplaying background effects, video-surface transitions or remote CPU work. One root motion observer respects Android's disabled/reduced animator setting and is inherited by nested player/dialog themes. Interaction tests check duplicate-tap suppression, stable bounds and reduced-motion inheritance.

## Release gate / limits

Use GitHub Actions for builds, lint and generated-media emulator tests. Production is inspected read-only except the explicitly bounded synthetic Pi-self fixture, which is automatically removed. Keep app ID/signing key and publish an increasing signed test version only after reviewing final evidence.

The disposable emulator acknowledges Android's one-time immersive tutorial before app navigation tests; that non-focusable System UI overlay is not part of the app layout. No such setting is changed in the APK or on a user's phone, and installer/Google consent is not bypassed. Browser mutation assertions await the actual intercepted POST response, not just dismissal of a confirmation dialog.

This is a tracked parity audit, **not a claim that every browser feature is natively implemented or that software is bug-free**. Google OAuth setup, native remote push, normal-user HLS preparation, provider-locked historical cleanup, real phone/Pi Wi-Fi speed, OEM codecs/PiP/cutouts/background restrictions and installer consent remain explicit external/device boundaries.
