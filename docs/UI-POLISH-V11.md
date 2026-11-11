# v11 — filters, motion and layout polish

## Requested scope

Sorting now lives **inside Library filters**, not a separate dropdown taking up library space. Available on Home, Movies, TV Shows and My List; the current sort is shown beside the filter button and saved per section. Reset in the dialog resets both sorting and the existing year/language/unwatched/downloaded controls. Home now applies these controls to its search results and shelves as well.

## Motion

- Shared 200 ms fade/8 dp entry transitions cover Stream tabs/details, Profile, Downloads, preferences, Updates, admin Hub tabs and Files navigation. Only one screen is composed: no crossfade duplicates network effects, sensitive forms or native players.
- Native playback is a separate stable sibling and is not inside page transitions. Browsing cannot recreate the decoder. Player-menu subpages and season changes animate their own content, not the video surface or seek position.
- Library sorting/filtering and keyed file/download rows animate placement and appearance. Poster touches, shared buttons/cards, focused fields, dock selection/icons, dropdown arrows, progress bars, featured-page indicators and confirmation/filter dialogs use short feedback transitions.
- All new custom transitions honor Android's animation-disabled setting through the existing live system observer. Pager-dot navigation jumps immediately with motion disabled. No idle looping decorations, artificial loading delay, continuous whole-page animation, extra animation dependency or emulator.

## Source alignment audit and fixes

| Page family | Review / fixes |
| --- | --- |
| Shared shell/components | Full-width aligned headings; consistent 52 dp button minimums, centered/wrapping two-line labels, common horizontal padding; 48 dp default icon/profile/refresh targets; adaptive equal-height paired actions that stack for narrow windows or large text; aligned dropdown label/field groups and constrained scrollable menus. |
| Home/library | Full-width search separated from action controls, sorting consolidated into filters, consistent section spacing, grid columns adapt to available width and font scale, equal poster title slots, accessible animated featured dots. |
| Details/episodes | Poster width capped and proportional on narrow pages; centered poster/title row; responsive Play/My List actions; two-line episode titles and larger explicit actions; keyed episode state and season transitions; descriptions and Related retained. |
| Mini-player | Compact action arrangement reserves more title space on narrow/large-text windows. Preview and title still expand; the redundant expand icon appears when space allows. Background follows the app card theme so light-mode text is readable. Same decoder and native view retained. |
| Profile/preferences/sync/updates | Shared page entry, responsive photo controls, missing menu separator restored, consistent switch label gaps/minimum height with one full-row accessible toggle, paired controls/buttons use shared layout. Existing sync/update/installer behavior unchanged. |
| Downloads/dialogs | Folder/season entry and stable title row placement; deletion uses the shared scrollable confirmation dialog; episode-picker and editor actions stack when needed. No download-engine or notification-policy changes. |
| Hub/settings/queue/transfers | Shared page entry and feedback, settings category entry, responsive equal-height metrics and Save/Refresh actions, existing non-scrolling nine-stage queue strip/equal Settings tabs and Power controls preserved. Progress uses existing animated shared bars. Admin actions/confirmation/authentication unchanged. |
| Files/ingest | Page/folder entry, stable keyed file row motion, shared button/field alignment, responsive editor confirmation actions. Existing scrollable command toolbars preserve every action; existing field/source/ZIP validation and job consent remain. |
| Player menus | Entry on menu subpage changes, bounded trailing values, separate delay label and centered timing controls. Main video controls/gestures/PiP behavior unchanged. |

## Validation limits

GitHub Actions compiles app and instrumentation APKs, runs JVM policy tests and isolated Python tests, and performs lint without emulators. New instrumentation sources exercise sort selection/reset, equal action bounds, large-text stacking and single-composition transitions, but are **compiled only**, not runtime proof. Existing same-decoder mini-player regression is retained.

This is a source/layout audit, not a claim that every element has been rendered or that every OEM/font/window is visually verified. Physical-phone review is still required for overall appearance, animation frame pacing, accessibility fonts, keyboard/cutouts, mini-player/PiP and notification rendering. No new screenshots or emulators are used. No production jobs, backend/companion services, source media, signing keys or LAN trust are changed.
