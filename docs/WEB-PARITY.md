# Web → Android parity audit

Reference: deployed `/DATA/PTSlibrary/web/index.html` and `app.js`, not a guessed feature list.
No production operations were run to test the native client. Destructive actions require
an explicit user action and confirmation; authenticated API authorization remains server-side.

## This pass

See [NATIVE-LIFECYCLE.md](NATIVE-LIFECYCLE.md) for the subsequent player, persistent
device Downloads, avatar/profile, theme, grid/list and native OTA rework.

| Web control / behavior | Native integration |
| --- | --- |
| `st-season`, `st-episodes` | Season picker and season-filtered episodes in details **and** watch page; episode sheet in fullscreen; current episode identified. |
| `st-synopsis`, `st-more` | Three-line collapsed description with More/Less, also used for episode descriptions. |
| `st-play`, `st-seek`, `st-speed`, `st-mute` | Central playback/10-second seek controls, draggable timeline, speed menu, mute toggle. |
| `st-audio-btn`, subtitles | Human-readable language/codec/channel labels, selected/unsupported track indication, subtitle Off and preference saving. |
| `st-fs`, `st-pip` | Explicit fullscreen/exit, Back exits fullscreen first, PiP, fit/fill. |
| `st-spin`, `st-player-msg`, retry | Buffering state and visible retry/error UI in portrait and fullscreen. TextureView avoids Compose/SurfaceView overlay/rotation synchronization problems. |
| `plResumePosition` | Ignore the first five seconds and restart when saved within 20 seconds of the end. |
| `st-upnext` | Five-second next-episode countdown, Play next and Cancel; respects saved autoplay preference. |
| `st-played`, Continue watching removal | Mark as played and the shelf's remove button → `POST /stream/progress/clear`; later player disposal/polling must not recreate the cleared record. |
| `st-download` | Private MP4 offline transfer with progress/cancel and saved-state indication. HLS preparation is still gated, below. |
| Related / Home search / clear filters | Related shelves, Home search, clear search/filter actions. |
| `fx-menu` | Open/Download, text Edit, Cut, Copy, Rename, Zip, Unzip for ZIP files, Delete. No longer a Delete-only popup. |
| `fx-sel`, `fx-selbar` | Multi-select, Select all, Cut/Copy/Zip/Delete, Done. |
| `fx-clip`, `fx-paste`, `fx-clip-clear` | Device-aware clipboard, cross-device copy/move contract, destination confirmation and Clear. |
| `fx-new-folder`, `fx-new-file`, `fx-upload-btn` | Native forms and Android document picker; bounded streamed multipart uploads (64 MiB/file, 100 selections max). |
| File content editor | Small UTF-8 file editor, Save/Cancel and unsaved-edit confirmation. Captured device/path prevents writing to a newly navigated folder. |
| File Download | Native Save As destination, streamed without a permanent intermediate local/VPS copy. |
| `tx-clear` | Clear finished operations with confirmation; running transfers remain visible. |
| `relay-panel` | Previous ZIP transfer status, copy/open HTTPS link, confirmed cancel/remove. No new relay jobs or retries are started. |
| `q-clear-done` | Clear the displayed completed-job snapshot through individual DELETEs; Pi media is kept. |
| Stage Cancel all | Still typed, stage-named, snapshot-scoped per-job deletion. No public bulk queue DELETE. |

## Verification, not blanket parity

The instrumentation suite uses the real Activity, a loopback API, a generated HEVC MP4,
and an fMP4 HLS copy. It exercises season switching into the selected media source,
native MP4/HLS playback, fallback, fullscreen Back, offline copies, archive menus and
request bodies, rename validation, multipart upload bounds, clipboard destinations,
clear-action snapshots and the three-line description limit. Rendered PNGs and JUnit/lint
reports are retained in `native-audit-evidence`. This does not prove a particular physical
phone's codecs or authenticated behavior of the deployed server.

## Remaining differences — do not advertise full feature parity

- Native metadata search still does not submit pipeline jobs; source selection/ZIP
  preflight and safe retry/source-edit flows are not verified against the newer deployed
  binary. No blind retry button is exposed.
- Administrator pipeline/account/provider configuration, power and purge controls are
  not fully native. The checked-out backend source still predates the running binary.
- Browser-specific compatibility/remux routes are not silently substituted for HEVC.
  Native playback errors show a Media3/HTTP code; device-specific failures need that
  code and the phone/Android version to diagnose.
- HLS offline MP4 preparation, native remote push and some advanced library filters
  remain incomplete. Device MP4 downloads now use a foreground service and validated
  range resume; unsupported or changed sources safely restart rather than append.
- Browser keyboard shortcuts and browser download presentation are platform-specific;
  Android uses native controls and document pickers instead of embedding a WebView.
