# v10: approved scope only

This update adds better library search/sorting, an in-app mini-player, and grouped individual download notifications. Other newly suggested features (pins, missing episodes, widgets, extra watched controls, collections, device sessions, etc.) are excluded. Existing v9 features remain.

## Search and sorting

- Home search, Movies, TV Shows and My List search actual title/episode names, genres and season/episode identifiers, with case/Latin accent normalization and bounded small-typo matching. Exact title matches rank first; mixed series/episode queries work. The query is capped at160characters and fuzzy tokens/edit distance are bounded. No external search provider or invented metadata.
- Sort by relevance, recently added, titleA–Z/Z–A or yearnewest/oldest. Per-section sort is saved on-device. Recently added uses server timestamps; absent dates preserve backend ordering rather than guessing based on IDs. Existing genre/year/language/unwatched/downloaded filters still combine with search/sort.

## Native in-app mini-player

- Use the down-arrow in the player's top row to minimize inside the app. Continue browsing tabs, title details, downloads and settings. Play/pause, expand and close controls remain available above the navigation dock; space is reserved so it does not cover the last list item.
- Playback title/episode is separate from the title being browsed. The same keyed PlayerScreen, Media3 player and native video view remain composed while resizing; browsing another title cannot replace the current stream. Selecting another video's Play intentionally replaces it. Mini-state, playback identity and position survive activity recreation; Android system PiP remains separate and works when leaving playing video.
- Gestures/full controls are disabled only inside the compact strip, restored when expanded. Imported subtitles, offline media, audio, timing, zoom and player state are preserved. Closing releases playback; minimizing does not.

## Individual grouped transfer notifications

- Foreground notification is a fixed queue summary, no longer whichever episode reported last. Each movie/episode has a stable owner+media tag and its own title, progressbar, percentage, downloaded bytes/speed or explicit queued/preparing/Wi-Fi/paused/failed/completed state. Progress updates do not reorder siblings; notification timestamps are suppressed and sortkeys follow queue order.
- Per-item Pause/Resume/Cancel PendingIntents have distinct URI identities and are validated against the signed-in owner. Completion/paused notifications remain in the group; cancellation removes only its own item. Old historical completed jobs are not reposted at service start. Sign-out clears account-private transfer notifications.
- Updates are throttled per child rather than globally; status changes publish immediately. Group summary updates on state changes, not every byte report. Notification permission/OSgrouping still applies; expand the Android group to see individual bars.
- Android caps notifications per application. Very large queues show up to40 individual items, prioritizing running/queued work and reserving room for the group/other app notifications; the summary reports this cap. The full queue always remains in Profile→My Downloads. This avoids service crashes for100+episode queues.

## Validation policy

As last requested, GitHub Actions builds the app, compiles instrumentation regressions, runs hostJVM policy/content tests, Python security/relay tests and lint **without emulators**. The new same-decoder mini-player regression is compiled but not device-executed. No fresh device screenshots or every-device runtime proof is claimed; actual phone review is still needed for mini-player layout/lifecycle/PiP and Android notification presentation. No production jobs, provider credentials, backend binary, companion service or media paths are changed in this update.
