package online.ptsmovies.stream

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
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
import org.junit.Test
import java.util.concurrent.TimeUnit

class ApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PtsApi
    private val movie = Title(90001, "movie", "Synthetic rights-cleared fixture", 2026, "", "", emptyList(), emptyList(), "hevc", "mp4")

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            api = PtsApi(server.url("/").toString(), CookieJar.NO_COOKIES)
        }
    }
    @After fun close() { server.shutdown() }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun request() = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))

    @Test fun loginCarriesCsrfAndRememberChoice() = runBlocking {
        server.enqueue(json("""{"csrf_token":"synthetic-token"}"""))
        server.enqueue(json("""{"user":{"id":1,"role":"user"}}"""))
        assertEquals("user", api.login("viewer", "synthetic-password", false).getString("role"))
        assertEquals("/api/v1/auth/csrf", request().path)
        val login = request()
        assertEquals("POST", login.method)
        assertEquals("synthetic-token", login.getHeader("X-CSRF-Token"))
        assertFalse(JSONObject(login.body.readUtf8()).getBoolean("remember"))
    }

    @Test fun libraryPreservesResumeDuration() = runBlocking {
        server.enqueue(json("""{"titles":[{"id":1,"kind":"series","title":"Fixture","episodes":[{"id":2,"season":1,"episode":1,"duration":2400}]}],"watch":[1],"progress":[{"title_id":1,"episode_id":2,"position":900,"duration":2400}]}"""))
        val library = api.library()
        assertEquals(2400L, library.progress[1L]?.duration)
        assertEquals(setOf(1L), library.watch)
        assertEquals(2L, library.titles.single().episodes.single().id)
    }

    @Test fun perJobDeleteNeverUsesBulkEndpoint() = runBlocking {
        server.enqueue(json("""{"csrf_token":"fixture"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        api.json("/queue/synthetic-job", "DELETE")
        request()
        val deletion = request()
        assertEquals("DELETE", deletion.method)
        assertEquals("/api/v1/queue/synthetic-job", deletion.path)
        assertEquals("fixture", deletion.getHeader("X-CSRF-Token"))
    }

    @Test fun authorizationErrorsRemainIdentifiable() = runBlocking {
        server.enqueue(json("""{"error":"Unauthorized"}""").setResponseCode(401))
        try { api.me(); fail("Expected 401") } catch (e: ApiException) { assertEquals(401, e.status) }
    }

    @Test fun hlsAndFallbackUrlsAreUnambiguous() {
        val ep = Episode(7, 1, 1, "Fixture", 60.0, "hls")
        assertTrue(api.streamUrl(movie, ep).endsWith("/hls/episode/7/master.m3u8"))
        assertTrue(api.streamUrl(movie, null, true).endsWith("/file/movie/90001?via=vps"))
    }

    @Test fun offlineCopyIsCompleteAndRemovable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = OfflineStore(context, api, 90001)
        store.list().forEach { store.remove(it) }
        val payload = ByteArray(4096) { (it % 127).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        val saved = store.save(movie, null) {}
        assertEquals(payload.size.toLong(), saved.bytes)
        assertArrayEquals(payload, saved.file.readBytes())
        assertEquals(saved.key, store.find(movie, null)?.key)
        store.remove(saved)
        assertNull(store.find(movie, null))
    }

    @Test fun hlsOfflineRefusalDoesNotDownloadAnything() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = OfflineStore(context, api, 90002)
        try { store.save(movie.copy(container = "hls"), null) {}; fail("HLS must require preparation") }
        catch (e: java.io.IOException) { assertTrue(e.message.orEmpty().contains("MP4 preparation")) }
        assertEquals(0, server.requestCount)
    }
}
