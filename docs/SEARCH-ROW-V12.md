# v12 — icon-only search controls

Requested correction to v11: Library Filters and grid/list view actions sit immediately beside the search field in one centered row, as icons only. Removed the extra action row, visible Filters label and external current-sort label from Movies, TV Shows and My List. Home's filter is also an icon next to search; Home retains its existing shelves rather than adding an unrelated view mode.

Sort remains inside Library Filters, with the existing per-section persistence and Reset behavior. Search still fills the remaining row width, caps queries at 160 characters and truncates its placeholder; each icon retains its 48 dp target, existing touch feedback and accessible description. No playback, download, notification, backend or permission changes.

Two new device-test regressions cover same-row geometry, icon-only presentation, filter activation, grid/list toggling/search input and Home's filter-only case. These are compiled by GitHub Actions without emulators, not executed. Existing 40 JVM policy/content tests, 11 isolated Python tests and lint remain the host validation gates. Physical-phone review is still required for actual UI appearance.
