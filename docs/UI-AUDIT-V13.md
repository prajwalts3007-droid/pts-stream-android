# v13 — approved redesign and end-to-end source audit

## What is actually validated

This release is built and audited in GitHub Actions **without emulators**, following the standing testing preference. App and device-test APK compilation, JVM tests, isolated Python security/relay tests and lint are build gates. The page/menu inventory below is a source/control-flow/layout audit, not executed device coverage or a pixel-perfect certificate. Physical-phone rendering, frame pacing, large fonts, keyboard/cutouts, codecs, PiP and OS notifications remain device checks. No new screenshots or emulator results are claimed.

## Approved changes

- Files starts with the VPS/Raspberry Pi toggle, then folder path and actions; removed the duplicate Browse heading. Stream header says **PTS Stream**; Hub/Files retain their own brands. Home/Movies/Shows/My List start with the icon-only search row rather than duplicate page headings.
- Home order: **five real featured titles**, Continue Watching, **ten recently added movies**, **ten recently added shows**, omitting empty shelves. Smaller libraries are not padded with duplicate/fake items. Recent ordering uses real server dates and retains server order when dates are absent. Featured card/poster/Play all open the same native watch destination; My List remains independent. Other poster cards still open details.
- Audio and subtitle choices show names from language metadata only, never label/codec/channel/bitrate strings. ISO two-/three-letter aliases are supported. Unknown metadata says Unknown language; tracks remain individually selectable, supported-state checked and selected-state marked. Off/import/removal subtitle actions remain.
- Filter icon has an overlay dot when any year/language/unwatched/downloaded/genre filter or non-default sort is active. It does not change measured toolbar bounds or add a visible text label. Reset/clear also clears genre/sort so the dot reliably disappears. Search alone does not count as a filter. Sort stays in Library Filters.
- Public posters share a dedicated credential-free loader, 12% memory cache and 64 MiB device disk cache, with constraint-sized/bucketed decoding and 120 ms motion-aware crossfade. Known unsigned TMDB paths use documented thumbnail sizes; signed/unknown URLs are unchanged. HTTPS downgrade redirects are disabled. Profile/account images remain on the separate authenticated image client. No media/ZIP storage or new routing on the VPS.

## Navigation performance fixes

The source audit found `persistNavigation()` serializing and saving the **entire library on every section tap**, plus a blocking navigation preference `commit()`. Navigation now uses a small asynchronous preference save and never rewrites the library. Library serialization happens off the UI thread only when library/progress data changes, then publishes the serialized cache only if account/session still match. Account logout and critical download/progress durability checks are not weakened.

Search episode indexing and fuzzy/filter/sort scans run on a background dispatcher. Normalization regexes and indexed sort keys are reused, rather than repeatedly built inside sorting comparisons. Kind lists and saved-key sets are memoized. Empty states wait for the initial filtered computation instead of flashing a false empty library. Existing page transitions stay lightweight and native video remains outside them.

Hub metrics/queue/settings requests are no longer eagerly repeated on login while the user is viewing Stream; visible Hub lifecycle polling owns refresh. Recent same-folder results are retained for 15 seconds on Files reentry, with explicit refresh/mutations still forcing a reload. Stale/cross-account/role-lost admin responses cannot repopulate protected lists. No measured FPS/startup/switching-time claim is made without a phone.

## Regular-user isolation and deployed web comparison

Read-only reference: deployed `app.js?v=144`, saved privately as `/DATA/go-tmp/pts-v13-web-app.js`. The web restricts ordinary users to its five streaming pages and gates admin panels/Files/Hub. Native cached accounts do not grant administrative navigation before server role verification. Main navigation, dock/profile controls, notification routing, file/editor callbacks and administrative loaders all enforce the current role. Demotion clears protected UI state and stops the admin alert listener. Restored route values are whitelisted. The server's existing session/CSRF/owner/role checks remain authoritative; no backend was rebuilt or production database written.

Two real parity gaps found during the audit were closed:

1. Manual HLS offline requests now use the same `/stream/download/prepare/{kind}/{id}` contract as the web for an authenticated matching user/admin owner, rather than a native-only administrator restriction. The worker revalidates `/auth/me` before preparing. The server may reject authorization or capacity; that becomes an explicit failed task, never a bypass/retry loop. Smart downloads remain MP4-only and cannot start HLS preparation automatically. No production preparation job was triggered for testing, so successful normal-user server preparation is **not** certified here.
2. Profile includes the web's `/auth/password` form with current/new/confirmation, CSRF, duplicate-submit protection and no saved secrets. Fields clear when backgrounded/closed and before submission; no password-change request is made against production during validation. Existing profile/avatar/session behavior is retained.

## Page, subpage and menu inventory

| Surface reviewed | Source/layout/control-flow checks and retained boundaries |
| --- | --- |
| Login, password/Google flow, cached session | Native fields/remember/show-hide, server login, cached owner, role verification, Google fallback. External OAuth/device setup remains unverified. |
| App header, avatar/profile, mode/dock navigation | Stream brand, no duplicate Stream headings, role-safe dock, shared target sizing, single-composition motion; native decoder separate. |
| Home search, filters, featured pager and both recent shelves | Five/ten/ten caps and order, actual dates/fallback, Continue Watching guards, consistent featured action, separate My List; real artwork only. |
| Movies, Shows, My List grid/list | Search first, adjacent icons only, adaptive equal-size posters, current view callbacks, genre/filter/sort combination and active-dot reset, proper loading/empty states. |
| Library Filters and all dropdown menus | Sort/year/language, switches, reset/done, bounded scrolling, selected markers, large-text action stacking. |
| Details, description/More/Less, Related | Proportional art/title layout, paired actions, resume/saved state, descriptions and Related retained; no codec badges/editor clutter. |
| Season menus, episode rows/completion/download | Named season anchor, individually keyed episodes/actions, progress, completion, unknown episode fallbacks; no fabricated metadata. |
| Full native watch page, player header/seek/transport/footer | Existing gestures, seek preview, error/retry, controls timer, safe insets, fullscreen/PiP. These are compiled regressions, not new device playback proof. |
| Player options root | Episodes, audio/subtitles, mute, subtitle appearance, speed, timing, aspect/zoom, controls, connection, autoplay, sleep, lock remain distinct actions. |
| Player option subpages | Speed selection, subtitle size/colour/outline/position, timing increment/reset, control timeout/downloaded-only, bounded connection comparison; trailing values bounded. |
| Audio/subtitle picker, local import/remove | Language-only labels, unsupported-state handling and selected mark; Off/import actions and account/video-private subtitle storage unchanged. |
| Sleep/episode dialogs, lock and Up Next | Sleep dialog made scrollable for short windows; episode choices/up-next cancel remain; no surface animations that reset the decoder. |
| Mini-player and system PiP | Stable keyed player/view, browse-title separation, compact actions/title and themed contrast, reserved bottom space; actual OEM transitions still require a phone. |
| Profile appearance, edit/avatar and password dialogs | Equal/stacking photo actions, separators, common controls, private password form, no admin panels for ordinary users. |
| Playback preferences, Downloads & network | Accessible row switches, bounded numeric/network values, large-text controls, saved settings; smart/removal choices remain opt-in. |
| Sync & alerts | Owner-scoped progress/status; native job alerts only for verified admins, permission/foreground-service limits retained. |
| Updates and installer consent | Stable/Test selection, signed candidate checks/progress/install; no auto-consent or signing-key/package change. |
| About/source credits | Scrollable attribution, version/source links; metadata management remains excluded from watch/detail presentation. |
| Downloads folders, seasons, items and deletion | Existing native services own media, queue status/actions, per-item notifications, safe deletion confirmation and offline metadata/subtitles. |
| Files device/path/list/row menus | Toggle first, directory path and toolbar, cached same-folder entry, stable rows, selection/More anchors; existing path/source validation retained. |
| File selection/clipboard/editor and confirmations | Every cut/copy/paste/create/upload/rename/edit/ZIP/unzip/delete action remains reachable; editor and confirmation scrolling/paired actions checked. |
| File operation/relay transfer lists and menus | Clear/cancel/delete/link-copy/open confirmations and bounded progress retained; no test writes to live transfer jobs. |
| Hub Dashboard | Equal/stacking metrics, real refresh state, current device choice, no forced refetch blocking section entry. |
| Hub Queue, nine-stage strip and per-job menus | Non-wrapping/non-scrolling nine cells retained, retry/edit/remove/bulk confirmation and progress; no production retry/edit/cancel testing. |
| Ingest lookup/result/form/submission dialogs | Native IMDb/rows/paste/text import/ZIP preflight/selection/replace/submit flows retain validation and consent. No jobs submitted to test the UI. |
| Admin Settings: Pipeline/Playback/Devices/Users | Equal tabs, unlock/provider panels/account selection, safe speed conversion, refresh/save, aligned Power actions, user confirmations. Secrets/guarded lifecycle unchanged; no provisioning/reboot/user changes tested live. |
| Toasts/errors/loading and theme | Bounded shared typography/spacing, accessible feedback, pitch-black dark backgrounds/neutral surfaces and light mode retained; errors are not hidden by animation. |

The nine Queue cells intentionally fit one compact row, an existing user requirement; they are not falsely described as nine 48 dp targets on a narrow phone. No claim that every row in this inventory has been runtime-exercised in this release.

## Regression evidence and remaining gates

Existing 40 host tests remain, with new presentation/security/cache/password policies covering home caps/order, language labels, role isolation, filter indication, poster bounds/URL safety and manual-preparation ownership. New device regression sources cover removed headings, filter-dot bounds/reset, featured/card action equivalence and existing role-safe navigation. Historical UI captures do not count as v13 screenshots. Final workflow/count/lint/signature/public APK data are recorded separately after the release gates finish.
