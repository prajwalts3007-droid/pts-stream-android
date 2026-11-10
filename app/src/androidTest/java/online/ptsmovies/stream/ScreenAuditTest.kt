package online.ptsmovies.stream

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import java.io.File

/** Deterministic, synthetic data only. No production API or media is used. */
class ScreenAuditTest {
    @get:Rule val ui = createComposeRule()
    private var mock: okhttp3.mockwebserver.MockWebServer? = null
    @After fun closeMock() { mock?.shutdown() }

    private val episode = Episode(101, 1, 1, "A new beginning", 2400.0, "mp4")
    private val series = Title(1, "series", "The Open Road", 2025, "", "A small team explores the world, one story at a time. Synthetic audit fixture.", listOf("Adventure", "Documentary"), listOf(episode), "hevc", "mp4")
    private val movie = series.copy(id = 2, kind = "movie", title = "A Quiet Morning", episodes = emptyList())
    private val library = Library(listOf(series, movie), setOf(1L), mapOf(1L to Resume(101, 900, 2400)))
    private val admin = JSONObject("""{"id":1,"name":"Alex","username":"alex","role":"admin"}""")
    private val job = JSONObject("""{"job_id":"a","title":"The Open Road","type":"series","season":1,"episode":1,"stage":"queued","progress":0}""")
    private val file = JSONObject("""{"name":"Movies","is_dir":true,"size":0}""")
    private val metrics = JSONObject("""{"vps":{"online":true,"hostname":"pts-test","cpu_percent":34,"cpu_cores":4,"load1":0.62,"mem_percent":58,"mem_used_bytes":4000000000,"mem_total_bytes":8000000000,"disk_percent":71,"disk_used_bytes":142000000000,"disk_total_bytes":200000000000,"net_rx_bps":1250000,"net_tx_bps":340000}}""")

    private fun capture(name: String) {
        ui.waitForIdle()
        saveAuditCapture(name, ui.onRoot().captureToImage().asAndroidBitmap())
    }

    @Test fun renderEveryScreen() {
        val server = okhttp3.mockwebserver.MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"pi_bwlimit_kbps":12345,"max_runners":4,"max_pi_transfers":1,"min_free_gib":10,"max_staging_gib":0,"reserve_per_job_gib":6}""")
            }
            start()
        }
        mock = server
        var screen by mutableIntStateOf(0)
        ui.setContent {
            PtsTheme {
                PtsBackground(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    if (screen == 0) LoginScreen("", false) { _, _, _ -> }
                    else Column(Modifier.fillMaxSize()) {
                        PtsTopBar("PTS Library", "A", {})
                        Box(Modifier.weight(1f)) {
                            when (screen) {
                                1 -> DashboardScreen("Alex", metrics, "vps", {}, {}, listOf(job), emptyList(), {})
                                2 -> QueueScreen(listOf(job), emptyList(), "queued", {}, {}, {}, { _, _ -> })
                                3 -> FilesScreen("vps", "", listOf(file), false, {}, {}, {}, {}, {}, {})
                                4 -> TransfersScreen(listOf(JSONObject("""{"id":"1","kind":"upload","detail":"Synthetic clip.mp4","state":"running","pct":42}""")), {})
                                5 -> StreamHomeScreen(library, {}, {}, {}, {})
                                6 -> BrowseScreen("Movies", library.titles.filter { it.kind == "movie" }, library.progress, library.watch, "", {}, {}, {})
                                7 -> TitleScreen(series, library.progress[1], true, emptySet(), "", {}, {}, {}, {})
                                8 -> DownloadsScreen(emptyList(), {}, {})
                                9 -> ProfileScreen(admin, 0, {}, {}, {}, {})
                                10 -> NativeAdminSettings(admin, remember { PtsApi(server.url("/").toString(), okhttp3.CookieJar.NO_COOKIES) }, {}, {})
                                11 -> SearchScreen("Open Road", {}, listOf(JSONObject("""{"title":"The Open Road","year":2025,"type":"series"}""")), false)
                            }
                        }
                        PtsDock(listOf(DockItem("Dashboard", PtsIcons.Home), DockItem("Download", PtsIcons.Download), DockItem("Queue", PtsIcons.Queue), DockItem("Settings", PtsIcons.Settings)), "Dashboard", {})
                    }
                }
            }
        }
        val names = listOf("login", "dashboard", "queue", "files", "transfers", "stream-home", "browse", "details", "downloads", "profile", "settings", "search")
        names.forEachIndexed { index, name ->
            ui.runOnIdle { screen = index }
            capture(name)
        }
    }

    @Test fun loginHonorsRememberAndPasswordToggle() {
        var submitted: Triple<String, String, Boolean>? = null
        ui.setContent { PtsTheme { PtsBackground { LoginScreen("", false) { u, p, r -> submitted = Triple(u, p, r) } } } }
        ui.onAllNodes(hasSetTextAction())[0].performTextInput("  viewer  ")
        ui.onAllNodes(hasSetTextAction())[1].performTextInput("synthetic-password")
        ui.onNodeWithText("SHOW").performClick()
        ui.onNodeWithText("HIDE").assertExists()
        ui.onNodeWithText("Keep me signed in").performScrollTo().performClick().assertIsOff()
        ui.onNodeWithText("Sign in").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(Triple("viewer", "synthetic-password", false), submitted) }
    }

    @Test fun dockUsesNavigationValuesNotLabels() {
        var selected by mutableStateOf("Home")
        ui.setContent { PtsTheme { PtsDock(listOf(DockItem("Home", PtsIcons.Home), DockItem("TV Shows", PtsIcons.Tv, "Series"), DockItem("My List", PtsIcons.Watchlist, "Watchlist")), selected, { selected = it }) } }
        ui.onNodeWithText("TV Shows").performClick()
        ui.runOnIdle { assertEquals("Series", selected) }
        ui.onNodeWithText("My List").performClick()
        ui.runOnIdle { assertEquals("Watchlist", selected) }
    }

    @Test fun queueCancelUsesTypedStageSnapshot() {
        var jobs by mutableStateOf(listOf(job, JSONObject(job.toString()).put("job_id", "b"), JSONObject(job.toString()).put("job_id", "other").put("stage", "failed")))
        var deleted = emptyList<String>()
        ui.setContent { PtsTheme { QueueScreen(jobs, emptyList(), "queued", {}, {}, {}, { stage, ids -> assertEquals("queued", stage); deleted = ids }) } }
        ui.onNodeWithText("Cancel all").performClick()
        ui.runOnIdle { jobs = jobs + JSONObject(job.toString()).put("job_id", "new-arrival") }
        ui.onNodeWithText("Delete 2").assertIsNotEnabled()
        ui.runOnIdle { assertTrue(deleted.isEmpty()) }
        ui.onNode(hasSetTextAction()).performTextInput("QUEUED")
        ui.onNodeWithText("Delete 2").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(listOf("a", "b"), deleted) }
    }

    @Test fun watchlistChangesAreReactive() {
        var watch by mutableStateOf(emptySet<Long>())
        ui.setContent { PtsTheme { BrowseScreen("Watchlist", listOf(series), emptyMap(), watch, "", {}, {}, {}) } }
        ui.onNodeWithText("Nothing saved yet").assertExists()
        ui.runOnIdle { watch = setOf(series.id) }
        ui.onNodeWithText(series.title).assertExists()
        ui.onNodeWithText("Nothing saved yet").assertDoesNotExist()
    }

    @Test fun nonAdminProfileHasNoSectionControls() {
        val viewer = JSONObject(admin.toString()).put("role", "user")
        ui.setContent { PtsTheme { ProfileScreen(viewer, 0, {}, {}, {}, {}) } }
        ui.onNodeWithText("Sections").assertDoesNotExist()
        ui.onNodeWithText("My Downloads").assertExists()
    }

    @Test fun fileDeleteRequiresConfirmation() {
        var deletes = 0
        ui.setContent { PtsTheme { FilesScreen("vps", "", listOf(file), false, {}, {}, {}, {}, { deletes++ }, {}) } }
        ui.onNodeWithContentDescription("More").performClick()
        ui.runOnIdle { assertEquals(0, deletes) }
        ui.onNodeWithText("Delete").performClick()
        ui.runOnIdle { assertEquals(0, deletes) }
        ui.onNode(hasText("Delete") and hasClickAction()).performClick()
        ui.runOnIdle { assertEquals(1, deletes) }
    }

    @Test fun compactPhoneWithLargeTextKeepsPrimaryActionsVisible() {
        var screen by mutableIntStateOf(0)
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                PtsTheme {
                    Box(Modifier.width(320.dp).height(640.dp)) {
                        PtsBackground {
                            if (screen == 0) LoginScreen("", false) { _, _, _ -> }
                            else PtsHero(series.copy(title = "A very long title for a compact phone"), false, {}, {}, {})
                        }
                    }
                }
            }
        }
        ui.onNodeWithText("Sign in").performScrollTo().assertIsDisplayed()
        capture("compact-large-text-login")
        ui.runOnIdle { screen = 1 }
        ui.onNodeWithText("Play").assertIsDisplayed()
        ui.onNodeWithText("My list").assertIsDisplayed()
        capture("compact-large-text-hero")
    }

    @Test fun seasonPickerShowsOnlySelectedSeason() {
        val two = series.copy(episodes = listOf(episode, episode.copy(id = 201, season = 2, title = "Second season opener")))
        var played = 0L
        ui.setContent { PtsTheme { PtsBackground { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp)) {
            SeasonEpisodes(two, onPlay = { played = it.id }, onDownload = {})
        } } } }
        ui.onNodeWithText("E1 · A new beginning").assertExists()
        ui.onNodeWithTag("season-selector").performClick()
        ui.onNodeWithText("Season 2").performClick()
        ui.onNodeWithText("E1 · Second season opener").assertExists()
        ui.onNodeWithText("E1 · A new beginning").assertDoesNotExist()
        ui.onNodeWithContentDescription("Play episode").performClick()
        ui.runOnIdle { assertEquals(201L, played) }
        capture("season-selector")
    }

    @Test fun synopsisIsThreeLinesUntilExpanded() {
        ui.setContent { PtsTheme { Box(Modifier.width(280.dp)) { ShortSynopsis("A long synthetic description. ".repeat(30)) } } }
        val collapsed = mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag("watch-synopsis").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(collapsed) }
        assertEquals(3, collapsed.single().lineCount)
        ui.onNodeWithText("More").performClick()
        val expanded = mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag("watch-synopsis").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(expanded) }
        assertTrue(expanded.single().lineCount > 3)
        ui.onNodeWithText("Less").assertExists()
    }

    @Test fun archiveMenuHasZipUnzipRenameAndClipboardActions() {
        val archive = JSONObject("""{"name":"sample.zip","is_dir":false,"size":10}""")
        var command: FileCommand? = null
        ui.setContent { PtsTheme { FilesScreen("pi", "Series", listOf(archive), false, {}, {}, {}, {}, {}, {}, onCommand = { command = it }) } }
        ui.onNodeWithContentDescription("More").performClick()
        listOf("Download", "Cut", "Copy", "Rename", "Zip", "Unzip", "Delete").forEach { ui.onNodeWithText(it).assertExists() }
        saveAuditCapture("files-archive-menu", checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
        ui.onNodeWithText("Unzip").performClick()
        ui.runOnIdle { assertNull(command) }
        ui.onNode(hasText("Unzip") and hasClickAction()).performClick()
        ui.runOnIdle { assertEquals(FileCommand("unzip", "pi", "Series", listOf("sample.zip")), command) }
    }

    @Test fun clearCompletedKeepsSnapshotAndOtherStages() {
        var done by mutableStateOf(listOf(JSONObject(job.toString()).put("job_id", "done-a").put("stage", "done")))
        var deleted = emptyList<String>()
        ui.setContent { PtsTheme { QueueScreen(listOf(job), done, "done", {}, {}, {}, { stage, ids -> assertEquals("done", stage); deleted = ids }) } }
        ui.onNodeWithText("Clear completed").performClick()
        ui.runOnIdle { done = done + JSONObject(job.toString()).put("job_id", "arrived-later").put("stage", "done") }
        ui.onNodeWithText("Clear").performClick()
        ui.runOnIdle { assertEquals(listOf("done-a"), deleted) }
    }

    @Test fun transfersClearRequiresExplicitConfirmation() {
        var cleared = false
        val list = listOf(JSONObject("""{"id":"done","state":"done","kind":"zip"}"""), JSONObject("""{"id":"active","state":"running","kind":"zip","pct":10}"""))
        ui.setContent { PtsTheme { TransfersScreen(list, {}, { cleared = true }) } }
        ui.onNodeWithText("Clear").performClick()
        ui.runOnIdle { assertFalse(cleared) }
        ui.onNode(hasText("Clear") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        ui.runOnIdle { assertTrue(cleared) }
    }

    @Test fun resumeMatchesWebStartAndEndGuards() {
        assertEquals(0L, resumePosition(Resume(1, 5, 100), 1))
        assertEquals(0L, resumePosition(Resume(1, 85, 100), 1))
        assertEquals(0L, resumePosition(Resume(1, 40, 100), 2))
        assertEquals(40L, resumePosition(Resume(1, 40, 100), 1))
        assertEquals(0L, resumePosition(Resume(0, 7, 8), 0))
    }

    @Test fun continueWatchingClearDoesNotOpenTheTitle() {
        var cleared = 0L
        var opened = false
        ui.setContent { PtsTheme { PtsBackground { Column {
            PtsPosterShelf("Continue watching", listOf(series), library.progress, library.watch, { opened = true }, {}, { cleared = it.id })
        } } } }
        ui.onNodeWithContentDescription("Remove from Continue watching").performClick()
        ui.runOnIdle { assertEquals(series.id, cleared); assertFalse(opened) }
    }

    @Test fun downloadsUseSeriesSeasonEpisodeFoldersAndAlignedConfirmation() {
        val two = series.copy(episodes = listOf(episode, episode.copy(id = 201, season = 2, title = "Season two opener")))
        val videos = two.episodes.map { ep -> SavedVideo("1_${ep.id}", 1, two.title, ep.season, ep.episode, ep.title, 1024, File("fixture-${ep.id}.mp4"), metadata = two) }
        var deleted = emptyList<SavedVideo>()
        var played: SavedVideo? = null
        ui.setContent { PtsTheme { Box(Modifier.width(320.dp).height(640.dp)) {
            DownloadsScreen(videos, { played = it }, { deleted = deleted + it })
        } } }
        ui.onNodeWithText("The Open Road").performClick()
        ui.onNodeWithText("Season 2").performClick()
        ui.onNodeWithText("E1 · Season two opener").assertExists()
        ui.onNodeWithText("E1 · A new beginning").assertDoesNotExist()
        ui.onNodeWithContentDescription("Play").performClick()
        ui.runOnIdle { assertEquals("1_201", played?.key) }
        ui.onNodeWithContentDescription("Delete").performClick()
        ui.runOnIdle { assertTrue(deleted.isEmpty()) }
        val cancel = ui.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        val delete = ui.onNodeWithText("Delete").fetchSemanticsNode().boundsInRoot
        assertEquals(cancel.center.y, delete.center.y, 2f)
        saveAuditCapture("compact-offline-delete-dialog", checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
        ui.onNodeWithText("Delete").performClick()
        ui.runOnIdle { assertEquals(listOf(videos[1]), deleted) }
    }

    @Test fun seasonDownloadSelectionSkipsSavedAndWatchedEpisodes() {
        val two = series.copy(episodes = listOf(episode, episode.copy(id = 102, episode = 2), episode.copy(id = 103, episode = 3)))
        var selection = emptyList<Episode>()
        ui.setContent { PtsTheme { EpisodeDownloadDialog(two, setOf("1_101"), setOf("1_102"), { selection = it }, {}) } }
        ui.onNodeWithText("Unwatched").performClick()
        ui.onNodeWithText("1 selected", substring = true).assertExists()
        ui.onNodeWithText("Download").performClick()
        ui.runOnIdle { assertEquals(listOf(103L), selection.map { it.id }) }
    }

    @Test fun browseHasSearchAdjacentIconsWithoutTitleTotals() {
        var selected = "grid"
        ui.setContent { PtsTheme { BrowseScreen("Movies", listOf(movie), emptyMap(), emptySet(), "", {}, {}, {}, onViewChange = { selected = it }) } }
        ui.onNodeWithText("1 title").assertDoesNotExist()
        ui.onNodeWithText("Grid").assertDoesNotExist()
        ui.onNodeWithContentDescription("Switch to list view").performClick()
        ui.runOnIdle { assertEquals("list", selected) }
        capture("compact-browse-search-icons")
    }

    @Test fun playerActionLabelsRemainAlignedOnCompactPhone() {
        ui.setContent { PtsTheme { Box(Modifier.width(320.dp)) { WatchActionBar(false, false, true, {}, {}, {}) } } }
        val labels = listOf("Mark completed", "Save offline", "Download episodes").map { ui.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        assertEquals(labels[0].top, labels[1].top, 2f)
        assertEquals(labels[1].top, labels[2].top, 2f)
        labels.forEach { assertTrue(it.right <= 320 * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density + 1) }
        capture("compact-watch-actions")
    }

    @Test fun dashboardOnlyShowsTheRequestedDeviceToggleWithoutShortcutOrLiveBadge() {
        ui.setContent { PtsTheme { DashboardScreen("Alex", metrics, "vps", {}, {}, listOf(job), emptyList(), {}) } }
        ui.onNodeWithText("Pipeline").assertDoesNotExist(); ui.onNodeWithText("Live").assertDoesNotExist()
        ui.onNodeWithText("VPS").assertExists(); ui.onNodeWithText("Pi").assertExists()
        capture("web-dashboard-toggle")
    }

    @Test fun queueAllStagesRemainInOneIconAndCountRow() {
        ui.setContent { PtsTheme { Box(Modifier.width(320.dp).height(640.dp)) { QueueScreen(listOf(job), emptyList(), "queued", {}, {}, {}, { _, _ -> }) } } }
        val tops = listOf("Queued", "Starting", "Downloading", "Compressing", "Uploading", "To Pi", "Failed", "Completed").map {
            ui.onNodeWithContentDescription(it).performScrollTo().fetchSemanticsNode().boundsInRoot.top
        }
        tops.forEach { assertEquals(tops.first(), it, 2f) }
        capture("web-queue-single-row")
    }

    @Test fun movieDetailsHaveNoCodecOrContainerBadges() {
        ui.setContent { PtsTheme { TitleScreen(movie, null, false, emptySet(), "", {}, {}, {}, {}) } }
        ui.onNodeWithText("HEVC").assertDoesNotExist(); ui.onNodeWithText("MP4").assertDoesNotExist()
        capture("web-movie-details")
    }

    @Test fun episodeDownloadSeasonSelectionIsDirectAndFooterButtonsAlign() {
        val five = series.copy(episodes = (1..5).map { season -> episode.copy(id = season.toLong(), season = season, title = "Season $season opener") })
        var selection = emptyList<Episode>()
        ui.setContent { PtsTheme { EpisodeDownloadDialog(five, emptySet(), emptySet(), { selection = it }, {}) } }
        ui.onNodeWithTag("download-season-selector").performClick()
        ui.onNodeWithText("Season 4").performScrollTo().performClick()
        ui.onNodeWithText("E1 · Season 4 opener").assertExists()
        ui.onNodeWithText("E1 · Season 1 opener").assertDoesNotExist()
        ui.onNodeWithText("Whole season").performClick()
        val cancel = ui.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        val download = ui.onNodeWithText("Download").fetchSemanticsNode().boundsInRoot
        assertEquals(cancel.center.y, download.center.y, 2f)
        saveAuditCapture("direct-download-season-picker", checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
        ui.onNodeWithText("Download").performClick()
        ui.runOnIdle { assertEquals(listOf(4), selection.map { it.season }) }
    }
}
