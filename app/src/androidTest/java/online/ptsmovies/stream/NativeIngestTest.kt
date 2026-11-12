package online.ptsmovies.stream

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import okhttp3.CookieJar
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class NativeIngestTest {
    @get:Rule val ui = createComposeRule()
    private val server = MockWebServer()
    @After fun close() {
        val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        if (screenshot != null) saveAuditCapture("native-ingest-final-state", screenshot)
        server.shutdown()
    }

    @Test fun zipInspectionSelectionAndDuplicateReplacementMatchWebWithoutBlindForce() {
        val writes = ConcurrentLinkedQueue<Pair<String, JSONObject>>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath.removePrefix("/api/v1")
                fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
                if (path == "/auth/csrf") return json("""{"csrf_token":"fixture-csrf"}""")
                if (request.method != "POST" || request.getHeader("X-CSRF-Token") != "fixture-csrf") return json("{}", 403)
                val payload = JSONObject(request.body.readUtf8()); writes.add(path to payload)
                return when (path) {
                    "/queue/zip/preflight" -> json("""{"members":[{"name":"Season 1/Generated.S01E01.mkv"},{"name":"Season 1/Generated.S01E02.mkv"}]}""")
                    "/queue" -> if (!payload.optBoolean("force")) json("""{"message":"Already added","duplicates":[{"label":"S01E01","where":"Pi library"}]}""", 409) else json("""{"count":1,"replaced":1}""", 202)
                    else -> json("{}", 404)
                }
            }
        }
        server.start(); val base = server.url("/").toString()
        val result = JSONObject("""{"imdb_id":"tt1234567","title":"Generated ingest series","year":2026,"type":"series"}""")
        var queued = false
        ui.setContent { PtsTheme {
            val api = remember { PtsApi(base, CookieJar.NO_COOKIES) }
            NativeIngest(api, "Generated", {}, listOf(result), false, onQueued = { queued = true })
        } }
        ui.onNodeWithText("Generated ingest series").performScrollTo().performClick()
        ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("ingest-zip-source"))).performScrollTo().performTextReplacement("https://example.invalid/generated.zip")
        ui.onNodeWithText("Inspect ZIP").performScrollTo().performClick()
        ui.waitUntil(10000) { writes.size == 1 }
        ui.onNodeWithTag("native-ingest").performScrollToNode(hasTestTag("ingest-source-count"))
        ui.waitUntil(10000) { ui.onAllNodesWithText("2 sources ready").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(queued)
        ui.onNodeWithTag("native-ingest").performScrollToNode(hasContentDescription("Select S01E02"))
        ui.onNodeWithContentDescription("Select S01E02").performScrollTo().performClick()
        ui.onNodeWithTag("native-ingest").performScrollToNode(hasText("Add selected to queue"))
        ui.onNodeWithText("Add selected to queue").performClick()
        assertEquals(1, writes.size)
        ui.onNode(hasText("Add") and hasAnyAncestor(isDialog())).performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Already added").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, writes.size); assertFalse(queued)
        val first = writes.last().second
        assertFalse(first.getBoolean("force")); assertEquals(1, first.getJSONArray("items").length())
        assertEquals("Season 1/Generated.S01E01.mkv", first.getJSONArray("items").getJSONObject(0).getString("zip_member"))
        ui.onNode(hasText("Replace") and hasAnyAncestor(isDialog())).performClick()
        ui.waitUntil(10000) { queued }
        assertEquals(3, writes.size); assertTrue(writes.last().second.getBoolean("force"))
    }
}
