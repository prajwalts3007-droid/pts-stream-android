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
import java.io.File

/** Deterministic, synthetic data only. No production API or media is used. */
class ScreenAuditTest {
    @get:Rule val ui = createComposeRule()

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
                                10 -> SettingsScreen(admin, listOf(admin), true, {}, {})
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
}
