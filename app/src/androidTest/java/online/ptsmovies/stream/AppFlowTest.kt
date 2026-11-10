package online.ptsmovies.stream

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
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

/** Runs the real Activity against a loopback API and a generated HEVC test pattern. */
class AppFlowTest {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val notifications = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    private lateinit var server: MockWebServer
    private var scenario: ActivityScenario<MainActivity>? = null
    private val signedIn = AtomicBoolean(false)
    private val adminCalls = AtomicInteger(0)
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
                return when (path) {
                    "/auth/me" -> json(user)
                    "/auth/logout" -> { signedIn.set(false); json("{}") }
                    "/stream/library" -> json("""{"titles":[{"id":90001,"kind":"movie","title":"Synthetic HEVC","year":2026,"synopsis":"Generated test pattern and tone. No third-party media.","video_codec":"hevc","container":"mp4","genres":["Test"],"episodes":[]}],"watch":[],"progress":[]}""")
                    "/stream/watch", "/stream/progress" -> json("{}")
                    "/queue" -> json("""{"jobs":[{"job_id":"test-job","title":"Synthetic queue item","type":"movie","stage":"queued","progress":0}],"completed":[]}""")
                    "/system/metrics" -> json("{}")
                    "/admin/users" -> json("""{"users":[]}""")
                    "/admin/github" -> json("""{"configured":true}""")
                    "/admin/files" -> json("""{"entries":[{"name":"Samples","is_dir":true,"size":0}]}""")
                    "/stream/file/movie/90001" -> {
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
        ApiProvider.override = { PtsApi(server.url("/").toString(), CookieJar.NO_COOKIES) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val store = OfflineStore(context, ApiProvider.create(), 90011)
            store.list().forEach { store.remove(it) }
        }
    }

    @After fun close() {
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
        ui.onNodeWithText("Movies").performClick()
        ui.onNodeWithText("Synthetic HEVC").performClick()
        waitForText("Details")
    }

    private fun capture(name: String) {
        ui.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "audit-screens").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
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
        ui.waitUntil(20000) { ui.onAllNodesWithText(" / 0:08", substring = true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("Pause playback").performClick()
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
        ui.waitUntil(20000) { ui.onAllNodesWithText(" / 0:08", substring = true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Offline").assertExists()
        capture("app-hevc-offline-player")
        pressBack()
        // Playback returns to title details. Open the offline list again to remove the copy.
        ui.onNodeWithContentDescription("Open profile").performClick()
        ui.onNodeWithText("My Downloads").performClick()
        ui.onNodeWithContentDescription("Delete").performClick()
        ui.onNodeWithText("Delete").performClick()
        waitForText("No offline videos")
    }
}
