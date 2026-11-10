# Native web rows and Media3 player revision

## UI scope

- Dashboard no longer contains Pipeline or Live. Its selector is VPS / Pi.
- Queue uses one icon/count row in web stage order, with the web SVG paths. It scrolls horizontally rather than wrapping or reducing touch targets. The retry affordance stays disabled: unverified source/ZIP checks must never be bypassed to imitate a working button.
- Files follows Browse → device selector → Up/path → one toolbar row → shared file-list rows. Clipboard and selected-file actions remain single horizontal rows; context actions use anchored menus rather than a new full-screen panel. Existing bounded uploads, file confirmations and authenticated operations are unchanged.
- Settings uses the web Pipeline / Playback / Devices / Users icon tabs. API provider sections, pipeline limits, paired save/refresh buttons, power identity/reboot rows and user action rows keep the web ordering. Update/sign-out actions remain in Profile, not extra Settings sections. Existing role/password/typed-confirmation boundaries remain. Native push subscriptions and blocked historical-bucket cleanup are not invented or advertised as implemented.
- Browse has one toggle button beside Search, showing the destination layout. Details omit codec/container badges. Season menus allow direct selection; the download dialog is height-bounded, independently scrollable and has aligned footer buttons. Saved episodes cannot be queued again.

## ExoPlayer, not an external player

The engine remains AndroidX Media3/ExoPlayer 1.7.1. No VLC, mpv, WebView or external video app was added. The new MX Player-inspired control layout is native Compose, not copied proprietary player code.

- Large 48dp minimum icon targets, 30dp icons, 72dp play/pause and 64dp seek buttons. Transport controls and the timeline have separate layout regions to prevent overlap on a compact portrait screen.
- Gear menu contains track selection, subtitle appearance, Speed, Synchronisation, Fit/Fill, zoom reset, control timeout, connection details, autoplay, sleep and screen lock. There is no separate speed selector on the timeline or the app Settings page.
- Control timeout defaults to 12 seconds; 8/12/20/Never are available. Controls do not expire while paused, buffering, seeking or in any player menu. Parent-level gesture detection cooperates with clickable controls and the seek slider. Lock disables gestures until explicitly unlocked.
- Subtitle size/colour/outline and top/middle/bottom placement are applied to Media3 subtitle output. Renderer-time subtitle delay supports earlier/later cues; it is not a delayed UI timer. Synchronisation keeps the audio renderer as the master clock and adjusts the video/text render positions, avoiding custom PCM processing or codec replacement. Audio correction is bounded to ±2 seconds, subtitle correction to ±10 seconds; changing timing resets at the current position. Physical audio-device latency and subtitle formats still require device testing.
- Existing SurfaceView surface-layout preparation/synchronisation is preserved. Direct→server fallback changes the item on the same ExoPlayer instance, preserving playback speed, track preferences, zoom, mute and desired paused/playing state. Initial failed restoration retains the requested resume position. Network availability can retry an interrupted player while preserving its pause intention; this never retries ingestion jobs.
- Previous/next and the episode picker remain available for series. Optional “Next downloaded episode only” filters navigation/autoplay to saved episodes, including across seasons.

## Actual route evidence

Each player/download has its own media telemetry object. Request tags survive redirects. The shared media router records which successful media response supplied bytes:

- Local Pi: verified TLS LAN child request.
- Pi via Internet: the known public Pi media origin.
- Server fallback: an actual API stream response supplying media, not merely an initial redirect.
- Internet: another media origin.
- Offline: the local file data source's transfer listener.

Health probes, API/profile traffic and HLS manifests are excluded from payload counters and cannot replace the route of actual media segments. No URLs, credentials or exportable diagnostics are retained. Counters show application media-body reads, excluding TLS/network overhead; repeated file reads can exceed file size. The measured rate may fall to zero when the playback buffer is full, so equal rates alone are not a LAN test. Connection details show route-specific bytes, recent read rate and buffered time. Live downloads also show their observed route.

The existing pinned TLS trust anchor, signed media authorization, LAN header isolation and tunnel/server fallback remain unchanged. There is no cleartext LAN or trust-all certificate fallback.

## Cutout/system bars

Playback draws on black behind the system bars; light system-bar appearance is disabled. Video, including pinch zoom, is clipped inside the display-cutout insets; controls use safe insets. Navigation-bar insets sit outside the themed portrait metadata surface. Android 15 may report system-bar colours as transparent even when black pixels are correctly drawn underneath, so visual tests inspect rendered pixels as well as window flags. Physical cutout shapes, OEM system-bar policies and full-screen consent overlays remain device checks.

## Regression boundaries

Automated fixtures remain loopback/mock APIs and generated rights-cleared HEVC MP4/HLS. They cover rendered video, paused restoration/fallback, repeated offline surfaces, gesture/pinch/lock interaction, route identity and exact byte accounting, renderer timing forwarding, downloaded-only navigation, compact rows/dialogs, native admin confirmation and black edge pixels. No production admin mutation or media-pipeline change is needed for validation.

Keep the application ID and persistent signing key. Publish only an increasing signed numeric-version test release after the final audit; never ask users to uninstall to update, because that deletes private downloads.
