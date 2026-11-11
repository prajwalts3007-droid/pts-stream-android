package online.ptsmovies.stream

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.runBlocking
import okhttp3.CookieJar
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Loopback contracts only. Compiled in CI; not executed without a device. */
class StreamingParityTest {
    @get:Rule val ui = createComposeRule()

    @Test fun profilePasswordUsesWebFieldsAndSessionCsrf() {
        val payload = AtomicReference<JSONObject>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/auth/csrf") return MockResponse().setBody("""{"csrf_token":"synthetic-csrf"}""")
                if (request.path == "/api/v1/auth/password" && request.method == "POST" && request.getHeader("X-CSRF-Token") == "synthetic-csrf") {
                    payload.set(JSONObject(request.body.readUtf8()))
                    return MockResponse().setBody("{}")
                }
                return MockResponse().setResponseCode(403).setBody("{}")
            }
        }
        server.start()
        try {
            val api = PtsApi(server.url("/").toString(), CookieJar.NO_COOKIES)
            val saved = mutableStateOf(false)
            ui.setContent { PtsTheme("dark") { if (!saved.value) PasswordDialog(api, { saved.value = true }, {}) } }
            ui.onAllNodes(hasSetTextAction())[0].performTextInput("synthetic-old")
            ui.onAllNodes(hasSetTextAction())[1].performTextInput("synthetic-new-password")
            ui.onAllNodes(hasSetTextAction())[2].performTextInput("synthetic-new-password")
            ui.onNodeWithText("Save password").performScrollTo().performClick()
            ui.waitUntil(5000) { saved.value }
            assertEquals("synthetic-old", payload.get().getString("current_password"))
            assertEquals("synthetic-new-password", payload.get().getString("new_password"))
            assertFalse(payload.get().has("confirmation"))
        } finally { server.shutdown() }
    }

    @Test fun serverPreparationPermissionDenialIsNotRetriedOrBypassed() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"csrf_token":"synthetic-csrf"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"Forbidden"}"""))
        server.start()
        try {
            val api = PtsApi(server.url("/").toString(), CookieJar.NO_COOKIES)
            val movie = Title(5,"movie","Synthetic",2026,"","",emptyList(),emptyList(),"hevc","hls")
            try { runBlocking { prepareOfflineMp4(api,movie,null) }; fail("Forbidden preparation must fail") }
            catch (e: ApiException) { assertEquals(403,e.status) }
            assertEquals(2,server.requestCount)
            assertEquals("/api/v1/auth/csrf",server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("/api/v1/stream/download/prepare/movie/5",request.path)
            assertEquals("synthetic-csrf",request.getHeader("X-CSRF-Token"))
        } finally { server.shutdown() }
    }
}
