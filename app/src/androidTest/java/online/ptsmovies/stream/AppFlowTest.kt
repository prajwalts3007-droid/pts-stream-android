package online.ptsmovies.stream

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import okhttp3.CookieJar
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue

/** Runs the real Activity against a loopback API and a generated HEVC test pattern. */
class AppFlowTest {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val notifications = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val testName = org.junit.rules.TestName()
    private lateinit var server: MockWebServer
    private var scenario: ActivityScenario<MainActivity>? = null
    private val signedIn = AtomicBoolean(false)
    private val adminCalls = AtomicInteger(0)
    private val failDirectStream = AtomicBoolean(false)
    private val useSeries = AtomicBoolean(false)
    private val useHls = AtomicBoolean(false)
    private val clearedTransfers = AtomicBoolean(false)
    private val clearedProgress = AtomicBoolean(false)
    private val progressAfterClear = AtomicInteger(0)
    private val playedEpisodes = ConcurrentLinkedQueue<String>()
    private val deletedJobs = ConcurrentLinkedQueue<String>()
    private val fileCommands = ConcurrentLinkedQueue<Pair<String, JSONObject>>()
    @Volatile private var role = "user"
    private lateinit var media: ByteArray

    @Before fun setup() {
        media = InstrumentationRegistry.getInstrumentation().context.assets.open("synthetic-hevc.mp4").use { it.readBytes() }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath.removePrefix("/api/v1")
                fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                val user = """{"user":{"id":90011,"name":"Test Viewer","username":"viewer","role":"$role"}}"""
                if (path == "/auth/csrf") return json("""{"csrf_token":"test-csrf"}""")
                if (path == "/auth/login") { signedIn.set(true); return json(user) }
                if (!signedIn.get()) return json("""{"error":"Unauthorized"}""").setResponseCode(401)
                if (path.startsWith("/admin/") || path == "/queue" || path == "/system/metrics") {
                    adminCalls.incrementAndGet()
                    if (role != "admin") return json("""{"error":"Forbidden"}""").setResponseCode(403)
                }
                if (path.startsWith("/queue/") && request.method == "DELETE") { deletedJobs.add(path.substringAfterLast('/')); return json("{}") }
                if (path == "/stream/progress/clear") { clearedProgress.set(true); return json("{}") }
                if (path == "/stream/progress" && clearedProgress.get()) progressAfterClear.incrementAndGet()
                if (path.startsWith("/admin/files/") && request.method in listOf("POST", "PUT")) {
                    fileCommands.add(path to JSONObject(request.body.readUtf8().ifBlank { "{}" }))
                    if (path == "/admin/files/ops/clear") clearedTransfers.set(true)
                    return json("{}")
                }
                if (path.startsWith("/stream/file/episode/")) {
                    playedEpisodes.add(path.substringAfterLast('/'))
                    return MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(media))
                }
                if (path.startsWith("/stream/hls/movie/90001/")) {
                    val name = path.substringAfterLast('/')
                    if (name !in setOf("master.m3u8", "init.mp4", "segment0.m4s")) return MockResponse().setResponseCode(404)
                    val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("hls/$name").use { it.readBytes() }
                    return MockResponse().setHeader("Content-Type", if (name.endsWith("m3u8")) "application/vnd.apple.mpegurl" else "video/mp4").setBody(Buffer().write(bytes))
                }
                return when (path) {
                    "/auth/me" -> json(user)
                    "/auth/logout" -> { signedIn.set(false); json("{}") }
                    "/stream/library" -> if (useSeries.get()) json("""{"titles":[{"id":90001,"kind":"series","title":"Synthetic series","synopsis":"${"A generated series description for the short watch-page summary. ".repeat(8)}","video_codec":"hevc","container":"mp4","episodes":[{"id":101,"season":1,"episode":1,"title":"First season episode","container":"mp4","duration":8},{"id":201,"season":2,"episode":1,"title":"Second season episode","container":"mp4","duration":8}]}],"watch":[],"progress":[]}""") else json("""{"titles":[{"id":90001,"kind":"movie","title":"Synthetic HEVC","year":2026,"synopsis":"Generated test pattern and tone. No third-party media.","video_codec":"hevc","container":"${if (useHls.get()) "hls" else "mp4"}","genres":["Test"],"episodes":[]}],"watch":[],"progress":[]}""")
                    "/stream/watch", "/stream/progress" -> json("{}")
                    "/queue" -> json("""{"jobs":[{"job_id":"test-job","title":"Synthetic queue item","type":"movie","stage":"queued","progress":0}],"completed":${if ("done-one" in deletedJobs) "[]" else """[{"job_id":"done-one","title":"Finished job","stage":"done","progress":100}]"""}}""")
                    "/system/metrics" -> json("{}")
                    "/admin/users" -> json("""{"users":[]}""")
                    "/admin/github" -> json("""{"configured":true}""")
                    "/admin/files" -> json("""{"entries":[{"name":"Samples","is_dir":true,"size":0},{"name":"sample.zip","is_dir":false,"size":100},{"name":"notes.txt","is_dir":false,"size":20}]}""")
                    "/admin/files/content" -> json("""{"content":"synthetic notes","size":15}""")
                    "/admin/files/ops" -> json("""{"ops":[{"id":"running","kind":"copy","detail":"Active copy","state":"running","pct":42}${if (clearedTransfers.get()) "" else "," + """{"id":"done","kind":"zip","detail":"Finished zip","state":"done","pct":100}"""}]}""")
                    "/stream/file/movie/90001" -> {
                        if (failDirectStream.get() && request.requestUrl?.queryParameter("via") != "vps")
                            return MockResponse().setResponseCode(503)
                        val start = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        val response = MockResponse().setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
                        if (start > 0) response.setResponseCode(206).setHeader("Content-Range", "bytes $start-${media.lastIndex}/${media.size}")
                        response.setBody(Buffer().write(media, start, media.size - start))
                    }
                    else -> json("{}")
                }
            }
        }
        server.start()
        val baseUrl = server.url("/").toString()
        ApiProvider.override = { PtsApi(baseUrl, CookieJar.NO_COOKIES) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val store = OfflineStore(context, ApiProvider.create(), 90011)
            store.list().forEach { store.remove(it) }
        }
    }

    @After fun close() {
        if (scenario != null) try { capture("last-state-${testName.methodName}") } catch (_: Exception) { }
        scenario?.close()
        ApiProvider.override = null
        server.shutdown()
    }

    private fun waitForText(text: String) {
        ui.waitUntil(20000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun launchLoggedIn() {
        signedIn.set(true)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(if (role == "admin") "Dashboard" else "Stream")
    }

    private fun openMovie() {
        ui.onNodeWithContentDescription("Movies").performClick()
        ui.onNodeWithText("Synthetic HEVC").performClick()
        waitForText("Details")
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.waitForIdle()
    }

    private fun capture(name: String) {
        ui.waitForIdle()
        saveAuditCapture(name, checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
    }

    private fun waitForMovingPlayback() {
        ui.waitUntil(20000) {
            // One semantics snapshot: seven separate fetches can chase a moving clock
            // past the entire short clip on a busy emulator.
            ui.onAllNodesWithText(" / 0:08", substring = true).fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any {
                    val clock = it.text.substringBefore(" / ")
                    clock.startsWith("0:") && (clock.substringAfter(':').toIntOrNull() ?: 0) > 0
                }
            }
        }
    }

    private fun pauseIfPlaying() {
        if (ui.onAllNodesWithContentDescription("Pause playback").fetchSemanticsNodes().isNotEmpty()) {
            ui.onNodeWithContentDescription("Pause playback").performClick()
        }
    }

    private fun assertDecodedTestPattern() {
        // TextureView composition and screenshot capture are asynchronous. Keep the
        // rendered-pixel requirement, but allow the frame to reach the compositor.
        ui.waitUntil(5000) { decodedHueBins().size >= 3 }
    }

    private fun decodedHueBins(): Set<Int> {
        ui.waitForIdle()
        val bounds = ui.onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot
        val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val hues = mutableSetOf<Int>()
        val hsv = FloatArray(3)
        for (x in 1..9) for (y in 1..5) {
            val px = (bounds.left + bounds.width * x / 10f).toInt().coerceIn(0, image.width - 1)
            val py = (bounds.top + bounds.height * y / 6f).toInt().coerceIn(0, image.height - 1)
            android.graphics.Color.colorToHSV(image.getPixel(px, py), hsv)
            if (hsv[1] > .5f && hsv[2] > .25f) hues.add((hsv[0] / 30).toInt())
        }
        image.recycle()
        return hues
    }

    @Test fun viewerLoginNavigationAndRecreation() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("Welcome back")
        ui.onAllNodes(hasSetTextAction())[0].performTextInput("viewer")
        ui.onAllNodes(hasSetTextAction())[1].performTextInput("synthetic-password")
        ui.onNodeWithText("Sign in").performScrollTo().performClick()
        waitForText("Stream")
        ui.onNodeWithText("TV Shows").performClick()
        waitForText("No matching titles")
        scenario!!.recreate()
        waitForText("No matching titles")
        ui.onNodeWithContentDescription("Open profile").performClick()
        waitForText("Profile")
        ui.onNodeWithText("Sections").assertDoesNotExist()
        ui.onNodeWithText("My Downloads").performClick()
        waitForText("No offline videos")
        assertEquals("Viewer must never call admin APIs", 0, adminCalls.get())
        capture("app-viewer-downloads")
        pressBack()
        ui.onNodeWithContentDescription("Open profile").performClick()
        ui.onNodeWithText("Sign out").performScrollTo().performClick()
        waitForText("Welcome back")
    }

    @Test fun adminDockSwipeAndFolderBack() {
        role = "admin"
        launchLoggedIn()
        ui.onNodeWithTag("section-dock").performTouchInput { swipeRight() }
        waitForText("PTSFiles")
        waitForText("Samples")
        ui.onNodeWithText("Samples").performClick()
        waitForText("/Samples")
        pressBack()
        waitForText("/")
        ui.onNodeWithTag("section-dock").performTouchInput { swipeRight() }
        waitForText("PTSStream")
        capture("app-admin-stream")
    }

    @Test fun syntheticHevcStreamsThenPlaysOfflineAndDeletes() {
        launchLoggedIn()
        openMovie()
        ui.onNodeWithText("Play").performClick()
        waitForMovingPlayback()
        pauseIfPlaying()
        assertDecodedTestPattern()
        capture("app-hevc-network-player")
        pressBack()
        waitForText("Details")
        ui.onNodeWithText("Save offline").performScrollTo().performClick()
        waitForText("Downloaded")
        ui.onNodeWithContentDescription("Open profile").performClick()
        waitForText("Profile")
        ui.onNodeWithText("My Downloads").performClick()
        waitForText("My Downloads")
        ui.onNodeWithContentDescription("Play").performClick()
        waitForMovingPlayback()
        pauseIfPlaying()
        ui.onNodeWithText("Offline").assertExists()
        assertDecodedTestPattern()
        capture("app-hevc-offline-player")
        pressBack()
        // Playback returns to title details. Open the offline list again to remove the copy.
        ui.onNodeWithContentDescription("Open profile").performClick()
        ui.onNodeWithText("My Downloads").performClick()
        ui.onNodeWithContentDescription("Delete").performClick()
        ui.onNodeWithText("Delete").performClick()
        waitForText("No offline videos")
    }

    @Test fun syntheticHevcUsesVpsFallbackWhenDirectFails() {
        failDirectStream.set(true)
        launchLoggedIn()
        openMovie()
        ui.onNodeWithText("Play").performClick()
        waitForMovingPlayback()
        pauseIfPlaying()
        ui.onNodeWithText("VPS fallback").assertExists()
        assertDecodedTestPattern()
        capture("app-hevc-vps-fallback")
    }

    @Test fun watchSeasonSwitchingLoadsTheSelectedEpisode() {
        useSeries.set(true)
        launchLoggedIn()
        ui.onNodeWithContentDescription("TV Shows").performClick()
        ui.onNodeWithText("Synthetic series").performClick()
        ui.onNodeWithTag("season-selector").performScrollTo().performClick()
        ui.onNodeWithText("Season 2").performClick()
        ui.onNodeWithText("E1 · Second season episode").performScrollTo().performClick()
        waitForMovingPlayback()
        pauseIfPlaying()
        ui.onNodeWithContentDescription("Choose episode").performClick()
        ui.onAllNodesWithTag("season-selector").onLast().performClick()
        ui.onNodeWithText("Season 1").performClick()
        ui.onNode(hasText("E1 · First season episode") and hasAnyAncestor(isDialog())).performClick()
        waitForMovingPlayback()
        pauseIfPlaying()
        ui.runOnIdle { assertTrue(playedEpisodes.contains("201")); assertTrue(playedEpisodes.contains("101")) }
        capture("watch-season-switching")
    }

    @Test fun markPlayedDoesNotRecreateHistoryOnExit() {
        launchLoggedIn(); openMovie()
        ui.onNodeWithText("Play").performClick(); waitForMovingPlayback()
        pauseIfPlaying()
        ui.onNodeWithText("Mark as played").performScrollTo().performClick()
        waitForText("Marked as played · removed from Continue watching")
        pressBack()
        ui.runOnIdle { assertTrue(clearedProgress.get()); assertEquals(0, progressAfterClear.get()) }
    }

    @Test fun filesArchiveEditorClipboardAndTransferClearReachApis() {
        role = "admin"; launchLoggedIn()
        ui.onNodeWithTag("section-dock").performTouchInput { swipeRight() }; waitForText("PTSFiles"); waitForText("sample.zip")
        fun openMenu(name: String) { ui.onNode(hasContentDescription("More") and hasAnyAncestor(hasTestTag("file-row-$name"))).performClick() }
        openMenu("sample.zip"); ui.onNodeWithText("Unzip").performClick()
        ui.onNode(hasText("Unzip") and hasClickAction()).performClick()
        ui.waitUntil(10000) { fileCommands.any { it.first.endsWith("/unzip") } }
        waitForText("Unzip started · see Transfers")
        openMenu("notes.txt"); ui.onNodeWithText("Edit").performClick()
        waitForText("synthetic notes")
        ui.onNode(hasSetTextAction()).performTextReplacement("updated synthetic notes")
        ui.onNodeWithText("Save").performClick()
        ui.waitUntil(10000) { fileCommands.any { it.first.endsWith("/content") && it.second.optString("content") == "updated synthetic notes" } }
        waitForText("File saved")
        openMenu("sample.zip"); ui.onNodeWithText("Copy").performClick()
        ui.onNodeWithText("Samples").performClick(); waitForText("/Samples")
        ui.onNodeWithText("Paste").performClick(); ui.onNode(hasText("Paste") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        ui.waitUntil(10000) { fileCommands.any { it.first.endsWith("/paste") } }
        waitForText("Paste started · see Transfers")
        ui.onNodeWithContentDescription("Transfers").performClick(); waitForText("Finished zip")
        ui.onNodeWithText("Clear").performClick(); ui.onNode(hasText("Clear") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        ui.waitUntil(10000) { clearedTransfers.get() }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Finished zip").fetchSemanticsNodes().isEmpty() }
        waitForText("Active copy")
        ui.onNodeWithText("Finished zip").assertDoesNotExist()
        val paste = fileCommands.first { it.first.endsWith("/paste") }.second
        assertEquals("copy", paste.getString("mode")); assertEquals("Samples", paste.getString("target"))
    }

    @Test fun clearCompletedUsesOnlyPerJobDeletes() {
        role = "admin"; launchLoggedIn()
        ui.onNodeWithContentDescription("Queue").performClick()
        ui.onNode(hasText("Completed") and hasClickAction()).performClick()
        waitForText("Clear completed")
        ui.onNodeWithText("Clear completed").performClick(); ui.onNodeWithText("Clear").performClick()
        ui.waitUntil(10000) { "done-one" in deletedJobs }
        assertEquals(listOf("done-one"), deletedJobs.toList())
    }

    @Test fun syntheticHevcHlsPlaysThroughNativeTransport() {
        useHls.set(true); launchLoggedIn(); openMovie()
        ui.onNodeWithText("Play").performClick(); waitForMovingPlayback()
        pauseIfPlaying()
        assertDecodedTestPattern()
        capture("watch-hevc-hls")
    }

    @Test fun fullscreenBackReturnsToWatchAndSettingsRemainUsable() {
        launchLoggedIn(); openMovie(); ui.onNodeWithText("Play").performClick(); waitForMovingPlayback()
        pauseIfPlaying()
        ui.onNodeWithContentDescription("Fullscreen").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("Exit fullscreen").fetchSemanticsNodes().isNotEmpty() }
        capture("watch-fullscreen")
        pressBack()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("Fullscreen").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("Playback settings").performClick()
        ui.onNodeWithText("1.25×").performClick()
        ui.onNodeWithContentDescription("Playback settings").assertExists()
    }
}
