# v8 native presentation audit

## User-facing changes

- Match the deployed web player's clean structure: back/title/PiP/settings above, seek bar and Play/Pause/time/tracks/fullscreen below, central Play only when paused. No visible codec, route, metadata editor, synopsis, genre, year or related shelf clutter. Episode selection, double-tap ±10 seconds, swipe seek/brightness/volume, pinch zoom, lock, timing, connection details and download controls remain available.
- Single blank-video tap toggles controls in both directions, including paused playback. Playing controls hide after the web default 3.8 seconds; explicit longer choices remain. Pause, touch, seek, errors and open menus suspend hiding. Buffering does not continually re-show controls or restart the timer.
- Migrate the prior release's sticky controls setting to the working default once. No account, library, offline-media or progress data is deleted.
- Pitch-black watch/details backgrounds with neutral surfaces and no decorative purple background in dark mode. Playback is always black regardless of app theme.
- Equal 2:3 artwork and reserved two-line title areas for all grid/shelf posters. List artwork uses fixed bounds; remove secondary metadata that made list rows inconsistent.
- Power device names retain space; equal fixed-width Reboot buttons align. Existing confirmation/password protections are unchanged.
- Android 12+ uses system auto-enter PiP with a visible-source rectangle; Android 8–11 uses Home/leave fallback. Eligibility includes buffering playback but excludes paused, ended, failed and idle media. Unsupported/disabled PiP fails safely. PiP is for leaving a playing video with Home/system navigation, not closing playback with Back or locking the phone.
- Source attribution is accessible under Profile → About, not a watch-page metadata management panel. Existing cached episode names remain; backend names/files are never renamed.

## Validation scope

The GitHub Actions native audit runs the real Activity, Media3 decoder, mock API flows, downloads, admin confirmations, layout and gesture tests on API 29 and 35. New checks exercise actual Home-button PiP with continued playback, tap-to-hide/show, automatic timeout, paused visibility, poster bounds and Power alignment at compact widths/large fonts, and pitch-black rendering. Screenshots include all major screens and new playing/paused/layout states.

The deployed-web workflow reads public HTML/CSS/JS and intercepts all APIs. A generated rights-cleared H.264 test pattern permits actual browser playback, toggle/timeout assertions and playing/paused/hidden screenshots across four viewports. Production writes, jobs and real media are never used for testing.

Results and release references are recorded after execution. Physical-device OEM PiP/cutouts, hardware codecs, Google services, background restrictions and actual Wi-Fi throughput still require device feedback. This is not a claim that every possible field/state/device has been exhaustively tested. The v7 remote-push, Google OAuth-registration and ordinary-user HLS-authorization gaps remain documented in WEB-PARITY-V7.md.
