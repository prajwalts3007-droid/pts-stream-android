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
    @get:Rule val notifications = GrantPermissionRule.grant(*if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
    @get:Rule val testName = org.junit.rules.TestName()
    private lateinit var server: MockWebServer
    private var scenario: ActivityScenario<MainActivity>? = null
    private val signedIn = AtomicBoolean(false)
    private val adminCalls = AtomicInteger(0)
    private val failDirectStream = AtomicBoolean(false)
    private val usedVpsFallback = AtomicBoolean(false)
    private val useSeries = AtomicBoolean(false)
    private val useHls = AtomicBoolean(false)
    private val slowMedia = AtomicBoolean(false)
    private val avatarCalls = AtomicInteger(0)
    private val clearedTransfers = AtomicBoolean(false)
    private val clearedProgress = AtomicBoolean(false)
    private val progressAfterClear = AtomicInteger(0)
    private val playedEpisodes = ConcurrentLinkedQueue<String>()
    private val deletedJobs = ConcurrentLinkedQueue<String>()
    private val fileCommands = ConcurrentLinkedQueue<Pair<String, JSONObject>>()
    private val adminWrites = ConcurrentLinkedQueue<Pair<String, JSONObject>>()
    private val queueWrites = ConcurrentLinkedQueue<Pair<String, JSONObject>>()
    private val failedJob = AtomicBoolean(false)
    @Volatile private var role = "user"
    @Volatile private var profileName = "Test Viewer"
    private lateinit var media: ByteArray
    private lateinit var avatar: ByteArray

    @Before fun setup() {
        media = InstrumentationRegistry.getInstrumentation().context.assets.open("synthetic-hevc.mp4").use { it.readBytes() }
        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.CYAN)
        val output = java.io.ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        avatar = output.toByteArray()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath.removePrefix("/api/v1")
                fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                val user = JSONObject().put("user", JSONObject().put("id", 90011).put("name", profileName).put("username", "viewer")
                    .put("role", role).put("avatar", "/api/v1/auth/avatar/test.png")).toString()
                if (path == "/auth/csrf") return json("""{"csrf_token":"test-csrf"}""")
                if (path == "/auth/login") { signedIn.set(true); return json(user) }
                if (!signedIn.get()) return json("""{"error":"Unauthorized"}""").setResponseCode(401)
                if (path == "/auth/avatar/test.png") {
                    avatarCalls.incrementAndGet()
                    return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(avatar))
                }
                if (path.startsWith("/admin/") || path.startsWith("/queue") || path == "/system/metrics") {
                    adminCalls.incrementAndGet()
                    if (role != "admin") return json("""{"error":"Forbidden"}""").setResponseCode(403)
                }
                if (path.startsWith("/queue/") && request.method == "DELETE") { deletedJobs.add(path.substringAfterLast('/')); return json("{}") }
                if (path.startsWith("/queue/") && request.method == "POST") {
                    if (request.getHeader("X-CSRF-Token") != "test-csrf") return json("{}").setResponseCode(403)
                    queueWrites.add(path to JSONObject(request.body.readUtf8().ifBlank { "{}" })); failedJob.set(false)
                    return json("""{"retried":1,"job":{"stage":"queued"}}""").setResponseCode(202)
                }
                if (path.startsWith("/admin/") && !path.startsWith("/admin/files") && request.method in listOf("POST", "PUT", "PATCH", "DELETE")) {
                    adminWrites.add(path to JSONObject(request.body.readUtf8().ifBlank { "{}" }))
                    return json("{}")
                }
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
                    "/queue" -> json("""{"jobs":[{"job_id":"test-job","title":"Synthetic queue item","type":"movie","stage":"${if (failedJob.get()) "failed" else "queued"}","download_url":"https://example.invalid/original.mp4","progress":0}],"completed":${if ("done-one" in deletedJobs) "[]" else """[{"job_id":"done-one","title":"Finished job","stage":"done","progress":100}]"""}}""")
                    "/system/metrics" -> json("{}")
                    "/admin/users" -> json("""{"users":[]}""")
                    "/admin/github" -> json("""{"configured":true}""")
                    "/admin/pipeline-settings" -> json("""{"pi_bwlimit_kbps":12345,"max_runners":4,"max_pi_transfers":1,"min_free_gib":10,"max_staging_gib":0,"reserve_per_job_gib":6}""")
                    "/admin/files" -> json("""{"entries":[{"name":"Samples","is_dir":true,"size":0},{"name":"sample.zip","is_dir":false,"size":100},{"name":"notes.txt","is_dir":false,"size":20}]}""")
                    "/admin/files/content" -> json("""{"content":"synthetic notes","size":15}""")
                    "/admin/files/ops" -> json("""{"ops":[{"id":"running","kind":"copy","detail":"Active copy","state":"running","pct":42}${if (clearedTransfers.get()) "" else "," + """{"id":"done","kind":"zip","detail":"Finished zip","state":"done","pct":100}"""}]}""")
                    "/stream/file/movie/90001" -> {
                        if (request.requestUrl?.queryParameter("via") == "vps") usedVpsFallback.set(true)
                        if (failDirectStream.get() && request.requestUrl?.queryParameter("via") != "vps")
                            return MockResponse().setResponseCode(503)
                        val start = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        val response = MockResponse().setHeader("Content-Type", "video/mp4").setHeader("Accept-Ranges", "bytes")
                        if (slowMedia.get()) response.throttleBody(4096, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
                        response.setHeader("ETag", "\"synthetic-fixture\"")
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
        DeviceState(context).clearSession()
        context.getSharedPreferences("native_ui", 0).edit().putString("theme", "dark").remove("view_Movies").remove("view_Series").apply()
        context.getSharedPreferences("download_jobs_90011", 0).edit().clear().commit()
        context.getSharedPreferences("progress_journal_90011", 0).edit().clear().commit()
        context.getSharedPreferences("playback_90011", 0).edit().clear().commit()
        context.getSharedPreferences("native_preferences", 0).edit().clear().commit()
        context.getSharedPreferences("playback_local", 0).edit().clear().commit()
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
        waitForText("Stream")
        if (role == "admin") {
            ui.onNodeWithContentDescription("Open profile").performClick()
            ui.onNodeWithText("Hub").performScrollTo().performClick()
            waitForText("Dashboard")
        }
    }

    private fun openMovie() {
        ui.onNodeWithContentDescription("Movies").performClick()
        ui.onNodeWithText("Synthetic HEVC").performClick()
        waitForText("Details")
    }

    private fun pressBack() {
        // Android 10 can show its one-time immersive-mode tutorial in System UI.
        // Acknowledge that test-emulator hint before sending Back to the actual app.
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.pkg("com.android.systemui").text(java.util.regex.Pattern.compile("got it", java.util.regex.Pattern.CASE_INSENSITIVE))), 500)?.click()
        androidx.test.espresso.Espresso.pressBack()
        ui.waitForIdle()
    }

    private fun openDownloads() {
        ui.onNodeWithContentDescription("Open profile").performClick()
        ui.onNodeWithText("My Downloads").performScrollTo().performClick()
        waitForText("My Downloads")
    }

    private fun capture(name: String) {
        ui.waitForIdle()
        if (name in setOf("native-download-progress", "native-profile-light", "native-movies-list"))
            saveAuditCapture(name, ui.onRoot().captureToImage().asAndroidBitmap())
        else saveAuditCapture(name, checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun nativePlayer(view: android.view.View): androidx.media3.common.Player? {
        if (view is androidx.media3.ui.PlayerView) return view.player
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) nativePlayer(view.getChildAt(i))?.let { return it }
        return null
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
        ui.onNodeWithTag("profile-list").performScrollToNode(hasText("Sign out"))
        ui.onNodeWithText("Sign out").performClick()
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
        waitForText("PTS Library")
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
        openDownloads()
        waitForText("My Downloads")
        ui.waitUntil(15000) { ui.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("Play").performClick()
        waitForMovingPlayback()
        pauseIfPlaying()
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
        assertTrue("Player must really request the VPS fallback", usedVpsFallback.get())
        assertDecodedTestPattern()
        capture("app-hevc-vps-fallback")
    }

    @Test fun watchSeasonSwitchingLoadsTheSelectedEpisode() {
        useSeries.set(true)
        launchLoggedIn()
        ui.onNodeWithContentDescription("TV Shows").performClick()
        ui.onNodeWithText("Synthetic series").performClick()
        ui.onNodeWithTag("title-details").performScrollToNode(hasTestTag("season-selector"))
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
        ui.onNodeWithText("Mark completed").performScrollTo().performClick()
        waitForText("Marked as completed")
        ui.waitUntil(10000) { clearedProgress.get() }
        scenario!!.recreate()
        ui.waitUntil(15000) {
            var ready = false
            scenario!!.onActivity { activity -> ready = nativePlayer(activity.window.decorView)?.playbackState == androidx.media3.common.Player.STATE_READY }
            ready
        }
        pressBack()
        ui.runOnIdle { assertTrue(clearedProgress.get()); assertEquals(0, progressAfterClear.get()) }
    }

    @Test fun filesArchiveEditorClipboardAndTransferClearReachApis() {
        role = "admin"; launchLoggedIn()
        ui.onNodeWithTag("section-dock").performTouchInput { swipeRight() }; waitForText("PTSFiles"); waitForText("sample.zip")
        fun openMenu(name: String) {
            ui.onNodeWithTag("files-list").performScrollToNode(hasTestTag("file-row-$name"))
            ui.onNode(hasContentDescription("More") and hasAnyAncestor(hasTestTag("file-row-$name"))).performClick()
        }
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
        ui.onNodeWithContentDescription("Completed").performScrollTo().performClick()
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
        val system = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        system.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("Got it")), 3000)?.click()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("Exit fullscreen").fetchSemanticsNodes().isNotEmpty() }
        capture("watch-fullscreen")
        pressBack()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("Fullscreen").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("Player options").performClick()
        ui.onNodeWithText("Speed").performScrollTo().performClick()
        ui.onNodeWithText("1.25×").performClick()
        ui.onNodeWithContentDescription("Close player menu").performClick()
        ui.onNodeWithContentDescription("Player options").assertExists()
    }

    @Test fun downloadPageSurvivesRecreationAndShowsTransferMetrics() {
        slowMedia.set(true); launchLoggedIn(); openMovie()
        ui.onNodeWithText("Save offline").performScrollTo().performClick()
        openDownloads()
        waitForText("My Downloads")
        ui.waitUntil(15000) { ui.onAllNodesWithText("/s · ETA", substring = true).fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(15000) { ui.onAllNodesWithText("/s · ETA", substring = true).fetchSemanticsNodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { !it.text.startsWith("0 B/s") && !it.text.contains("calculating") }
        } }
        capture("native-download-progress")
        scenario!!.recreate()
        waitForText("My Downloads")
        ui.waitUntil(20000) { ui.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        scenario!!.close(); scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("My Downloads")
        ui.onNodeWithContentDescription("Play").assertExists()
        assertEquals(0, adminCalls.get())
        capture("native-download-reopened")
    }

    @Test fun pausedPlaybackSurvivesActivityRecreationAtItsPosition() {
        launchLoggedIn(); openMovie(); ui.onNodeWithText("Play").performClick(); waitForMovingPlayback(); pauseIfPlaying()
        var before = 0L
        scenario!!.onActivity { activity ->
            val player = checkNotNull(nativePlayer(activity.window.decorView))
            assertFalse(player.playWhenReady)
            before = player.currentPosition
        }
        scenario!!.recreate()
        ui.waitUntil(15000) {
            var restored = false
            scenario!!.onActivity { activity -> nativePlayer(activity.window.decorView)?.let { player ->
                restored = !player.playWhenReady && player.playbackState == androidx.media3.common.Player.STATE_READY && kotlin.math.abs(player.currentPosition - before) <= 250
            } }
            restored
        }
        ui.onNodeWithContentDescription("Play playback").assertExists()
        assertDecodedTestPattern()
        capture("native-player-restored")
    }

    @Test fun nativeThemesViewsAvatarAndUpdatePageAreAvailable() {
        launchLoggedIn()
        ui.waitUntil(10000) { avatarCalls.get() > 0 }
        ui.waitUntil(10000) {
            val bounds = ui.onNodeWithTag("profile-avatar").fetchSemanticsNode().boundsInRoot
            val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            val pixel = image.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt()); image.recycle()
            android.graphics.Color.green(pixel) > 200 && android.graphics.Color.blue(pixel) > 200 && android.graphics.Color.red(pixel) < 50
        }
        ui.onNodeWithContentDescription("Movies").performClick()
        ui.onNodeWithContentDescription("Switch to list view").performClick()
        ui.onNodeWithText("Synthetic HEVC").assertExists()
        scenario!!.recreate()
        ui.onNodeWithContentDescription("Switch to grid view").assertExists()
        assertEquals("list", DeviceState(InstrumentationRegistry.getInstrumentation().targetContext).view("Movies"))
        capture("native-movies-list")
        ui.onNodeWithContentDescription("Open profile").performClick()
        ui.onNodeWithText("Light").performScrollTo().performClick()
        ui.onNode(hasText("Light") and isSelected()).assertExists()
        ui.waitUntil(5000) {
            val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            val pixel = image.getPixel(10, image.height / 2); image.recycle()
            android.graphics.Color.red(pixel) > 180 && android.graphics.Color.green(pixel) > 180
        }
        capture("native-profile-light")
        ui.onNodeWithText("Check for updates").performScrollTo().performClick()
        waitForText("App updates")
        ui.onNodeWithText("Test releases").assertExists()
        ui.onNodeWithText("Installed", substring = true).assertExists()
    }

    @Test fun playerGesturesHaveLiveFeedbackAndSeekWorks() {
        launchLoggedIn(); openMovie(); ui.onNodeWithText("Play").performClick(); waitForMovingPlayback(); pauseIfPlaying()
        ui.onNodeWithTag("player-gesture-zone").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * .14f, height * .58f), androidx.compose.ui.geometry.Offset(width * .14f, height * .28f), 400)
        }
        ui.onNodeWithTag("gesture-feedback").assertTextContains("Brightness", substring = true)
        ui.onNodeWithTag("player-gesture-zone").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * .88f, height * .58f), androidx.compose.ui.geometry.Offset(width * .88f, height * .28f), 400)
        }
        ui.onNodeWithTag("gesture-feedback").assertTextContains("Volume", substring = true)
        ui.onNodeWithTag("player-gesture-zone").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * .2f, height * .3f), androidx.compose.ui.geometry.Offset(width * .65f, height * .3f), 400)
        }
        ui.onNodeWithTag("gesture-feedback").assertTextContains("Seek", substring = true)
        ui.onNodeWithTag("player-gesture-zone").performTouchInput {
            doubleClick(androidx.compose.ui.geometry.Offset(width * .1f, height * .42f))
        }
        ui.onNodeWithTag("gesture-feedback").assertTextContains("−10 seconds")
        capture("native-player-gestures")
    }

    @Test fun stoppedAndReopenedPlaybackRetainsPositionAndScreen() {
        launchLoggedIn(); openMovie(); ui.onNodeWithText("Play").performClick(); waitForMovingPlayback(); pauseIfPlaying()
        val before = ui.onAllNodesWithText(" / 0:08", substring = true).fetchSemanticsNodes().single().config[SemanticsProperties.Text].single().text
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        ui.onNodeWithTag("player-surface").assertExists()
        ui.onNodeWithText(before).assertExists()
        ui.onNodeWithContentDescription("Play playback").assertExists()
    }

    @Test fun deviceDownloadPauseResumeSurvivesLeavingItsPage() {
        slowMedia.set(true); launchLoggedIn(); openMovie()
        ui.onNodeWithText("Save offline").performScrollTo().performClick(); openDownloads()
        ui.waitUntil(10000) { ui.onAllNodesWithText("/s · ETA", substring = true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Pause").performClick(); waitForText("PAUSED")
        pressBack(); pressBack()
        ui.onNodeWithContentDescription("Movies").performClick()
        ui.onNodeWithContentDescription("Downloads").assertDoesNotExist()
        openDownloads(); waitForText("PAUSED")
        ui.onNodeWithText("Resume").performClick()
        ui.waitUntil(20000) { ui.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, adminCalls.get())
        capture("native-download-resumed")
    }

    @Test fun profileChangesOnWebRefreshWhenTheAppReturnsToForeground() {
        launchLoggedIn()
        ui.onNodeWithContentDescription("Open profile").performClick(); waitForText("Test Viewer")
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        profileName = "Updated on web"
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitForText("Updated on web")
        assertEquals(0, adminCalls.get())
    }

    @Test fun offlineVideoSurfaceSurvivesRepeatedOpenAndClose() {
        launchLoggedIn(); openMovie(); ui.onNodeWithText("Save offline").performScrollTo().performClick()
        openDownloads()
        waitForText("My Downloads")
        repeat(3) {
            ui.waitUntil(15000) { ui.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("Play").performClick()
            waitForMovingPlayback(); pauseIfPlaying(); assertDecodedTestPattern()
            pressBack(); openDownloads()
        }
        assertEquals(0, adminCalls.get())
    }

    @Test fun playerPinchesBothWaysAndScreenLockDisablesGestures() {
        launchLoggedIn(); openMovie(); ui.onNodeWithText("Play").performClick(); waitForMovingPlayback(); pauseIfPlaying()
        ui.onNodeWithText("Gesture guide").assertDoesNotExist()
        ui.onNodeWithContentDescription("Picture in picture").assertExists()
        ui.onNodeWithTag("player-gesture-zone").performTouchInput {
            pinch(start0 = androidx.compose.ui.geometry.Offset(width * .4f, height * .3f), end0 = androidx.compose.ui.geometry.Offset(width * .15f, height * .3f),
                start1 = androidx.compose.ui.geometry.Offset(width * .6f, height * .3f), end1 = androidx.compose.ui.geometry.Offset(width * .85f, height * .3f), durationMillis = 500)
        }
        ui.onNodeWithTag("gesture-feedback").assertTextContains("Zoom", substring = true)
        capture("native-player-pinch-out")
        ui.onNodeWithTag("player-gesture-zone").performTouchInput {
            pinch(start0 = androidx.compose.ui.geometry.Offset(width * .1f, height * .3f), end0 = androidx.compose.ui.geometry.Offset(width * .45f, height * .3f),
                start1 = androidx.compose.ui.geometry.Offset(width * .9f, height * .3f), end1 = androidx.compose.ui.geometry.Offset(width * .55f, height * .3f), durationMillis = 500)
        }
        ui.onNodeWithTag("gesture-feedback").assertTextContains("Zoom 0.50", substring = true)
        capture("native-player-pinch-in")
        ui.onNodeWithContentDescription("Player options").performClick()
        ui.onNodeWithText("Lock screen").performScrollTo().performClick()
        ui.onNodeWithTag("player-gesture-zone").assertDoesNotExist()
        ui.onNodeWithContentDescription("Unlock player").performClick()
        ui.onNodeWithTag("player-gesture-zone").assertExists()
    }

    @Test fun nativePipelineSettingsLoadAndRequireTypedSaveConfirmation() {
        role = "admin"; launchLoggedIn()
        ui.onNodeWithContentDescription("Settings").performClick()
        waitForText("GitHub jobs at once")
        ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("pipeline-max_runners"))).performScrollTo().performTextReplacement("3")
        ui.onNodeWithText("Refresh status").performScrollTo().performClick()
        ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("pipeline-max_runners"))).performScrollTo().assertTextEquals("3")
        ui.onNodeWithText("Save limits").performScrollTo().performClick()
        ui.onNodeWithText("Confirm").assertIsNotEnabled()
        assertTrue(adminWrites.isEmpty())
        ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("CONFIRM")
        ui.onNodeWithText("Confirm").performClick()
        ui.waitUntil(10000) { adminWrites.any { it.first == "/admin/pipeline-settings" } }
        val body = adminWrites.first { it.first == "/admin/pipeline-settings" }.second
        assertEquals(3, body.getInt("max_runners")); assertEquals(12345, body.getInt("pi_bwlimit_kbps"))
        capture("native-pipeline-settings")
    }

    @Test fun playerConnectionMenuUsesActualRouteAndTimingMenuUpdates() {
        launchLoggedIn(); openMovie(); ui.onNodeWithText("Play").performClick(); waitForMovingPlayback(); pauseIfPlaying()
        ui.onNodeWithTag("media-route").assertTextContains("Server fallback", substring = true)
        ui.onNodeWithTag("media-route").performClick()
        ui.onNode(hasText("Server fallback") and hasAnyAncestor(isDialog())).assertExists()
        capture("cinema-connection-menu")
        ui.onNodeWithContentDescription("Back to player menu").performClick()
        ui.onNodeWithText("Synchronisation").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Increase Audio delay").performClick()
        ui.onNodeWithText("+100ms").assertExists()
        ui.onNodeWithContentDescription("Close player menu").performClick()
        ui.onNodeWithContentDescription("Play playback").assertExists()
        scenario!!.onActivity { activity ->
            // Android 15 enforces transparent system bars; the pixels behind them must be black.
            assertTrue(activity.window.statusBarColor in listOf(android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT))
            assertTrue(activity.window.navigationBarColor in listOf(android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT))
            assertEquals(android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, activity.window.attributes.layoutInDisplayCutoutMode)
        }
        val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        assertEquals(android.graphics.Color.BLACK, image.getPixel(image.width / 2, 3))
        assertEquals(android.graphics.Color.BLACK, image.getPixel(image.width / 2, image.height - 3))
        image.recycle()
        capture("cinema-player-paused")
    }

    @Test fun pausedRestorationKeepsPositionAndPauseThroughNetworkFallback() {
        launchLoggedIn(); openMovie(); ui.onNodeWithText("Play").performClick(); waitForMovingPlayback(); pauseIfPlaying()
        var before = 0L
        scenario!!.onActivity { activity -> before = checkNotNull(nativePlayer(activity.window.decorView)).currentPosition }
        failDirectStream.set(true); scenario!!.recreate()
        ui.waitUntil(20000) {
            var ready = false
            scenario!!.onActivity { activity -> nativePlayer(activity.window.decorView)?.let { player ->
                ready = usedVpsFallback.get() && !player.playWhenReady && player.playbackState == androidx.media3.common.Player.STATE_READY && kotlin.math.abs(player.currentPosition - before) <= 250
            } }
            ready
        }
        assertDecodedTestPattern()
        capture("cinema-paused-fallback-recovery")
    }

    @Test fun mobileRetryAndSourceEditUseTheWorkingWebEndpointsWithConfirmationAndCsrf() {
        role = "admin"; failedJob.set(true); launchLoggedIn()
        ui.onNodeWithContentDescription("Queue").performClick(); ui.onNodeWithContentDescription("Failed").performClick()
        ui.onNodeWithContentDescription("Retry job").performClick(); assertTrue(queueWrites.isEmpty())
        ui.onNode(hasText("Retry") and hasAnyAncestor(isDialog())).performClick()
        ui.waitUntil(10000) { queueWrites.size == 1 }; assertEquals("/queue/test-job/retry", queueWrites.first().first)
        failedJob.set(true); ui.onNodeWithContentDescription("Refresh queue").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("Retry job").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("Retry failed jobs").performClick()
        ui.onNode(hasText("Retry") and hasAnyAncestor(isDialog())).performClick()
        ui.waitUntil(10000) { queueWrites.size == 2 }; assertEquals("/queue/retry-all", queueWrites.last().first)
        ui.onNodeWithContentDescription("Queued").performClick()
        ui.onNodeWithContentDescription("Change download link").performClick()
        ui.onNode(hasSetTextAction()).performTextReplacement("javascript:invalid")
        ui.onNodeWithText("Restart job").assertIsNotEnabled()
        ui.onNode(hasSetTextAction()).performTextReplacement("https://example.invalid/replacement.mp4")
        capture("native-queue-edit-source")
        ui.onNodeWithText("Restart job").performClick()
        ui.waitUntil(10000) { queueWrites.size == 3 }
        assertEquals("/queue/test-job/edit", queueWrites.last().first)
        assertEquals("https://example.invalid/replacement.mp4", queueWrites.last().second.getString("download_url"))
        assertTrue(deletedJobs.isEmpty())
    }

    @Test fun aTapShowsControlsAndRestartsTheWholeTimeoutRatherThanTogglingThemAway() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("playback_local", 0).edit().putInt("controls_seconds", 8).commit()
        launchLoggedIn(); openMovie(); ui.onNodeWithText("Play").performClick(); waitForMovingPlayback()
        // The generated fixture is eight seconds. Slow it down so an end/rebuffer event
        // cannot legitimately reveal controls while testing the independent hide timer.
        scenario!!.onActivity { activity -> nativePlayer(activity.window.decorView)!!.setPlaybackSpeed(.1f) }
        fun tapBlankVideo() { ui.onNodeWithTag("player-gesture-zone").performTouchInput { click(androidx.compose.ui.geometry.Offset(4f, height * .45f)) } }
        tapBlankVideo(); ui.onNodeWithContentDescription("Pause playback").assertIsDisplayed()
        Thread.sleep(4500); tapBlankVideo(); Thread.sleep(4500)
        ui.onNodeWithContentDescription("Pause playback").assertIsDisplayed()
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("Pause playback").fetchSemanticsNodes().isEmpty() }
        tapBlankVideo(); ui.waitUntil(2000) { ui.onAllNodesWithContentDescription("Pause playback").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("Pause playback").performClick()
        capture("simple-player-resettable-controls")
    }
}
