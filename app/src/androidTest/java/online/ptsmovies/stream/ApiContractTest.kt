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
        val baseUrl = server.url("/").toString()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            api = PtsApi(baseUrl, CookieJar.NO_COOKIES)
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

    @Test fun updateChannelExcludesPrereleases() {
        assertNull(parseReleaseUpdate(JSONObject("""{"tag_name":"v3-rc1","prerelease":true}"""), 1))
        assertNull(parseReleaseUpdate(JSONObject("""{"tag_name":"v1","prerelease":false}"""), 1))
    }

    @Test fun updateAssetMustBelongToExactRelease() {
        val url = "https://github.com/${BuildConfig.APP_REPOSITORY}/releases/download/v3/PTS-Stream-v3.apk"
        val release = JSONObject("""{"tag_name":"v3","assets":[{"name":"PTS-Stream-v3.apk","browser_download_url":"$url"}]}""")
        assertEquals(3, parseReleaseUpdate(release, 1)?.code)
        release.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://github.com/other/repo/file.apk")
        try { parseReleaseUpdate(release, 1); fail("Untrusted repository must be refused") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun insufficientStorageDoesNotRetryThroughVps() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = OfflineStore(context, api, 90003)
        server.enqueue(MockResponse().setBody("").setHeader("Content-Length", Long.MAX_VALUE))
        try { store.save(movie, null) {}; fail("Oversized download must be refused") }
        catch (e: java.io.IOException) { assertTrue(e.message.orEmpty().contains("Insufficient device storage")) }
        assertEquals(1, server.requestCount)
        assertTrue(store.list().isEmpty())
    }

    @Test fun failedSourcesLeaveNoPartialOrSavedCopy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = OfflineStore(context, api, 90004)
        repeat(2) { server.enqueue(MockResponse().setResponseCode(403)) }
        try { store.save(movie, null) {}; fail("Failed sources cannot produce an offline copy") }
        catch (e: java.io.IOException) { assertTrue(e.message.orEmpty().contains("403")) }
        assertEquals(2, server.requestCount)
        assertTrue(store.list().isEmpty())
        assertFalse(java.io.File(context.filesDir, "media/90004/90001_0.part").exists())
        assertFalse(request().path.orEmpty().contains("via=vps"))
        assertTrue(request().path.orEmpty().endsWith("?via=vps"))
    }

    @Test fun zipAndUnzipMatchWebUiBodies() = runBlocking {
        repeat(2) { server.enqueue(json("""{"csrf_token":"fixture"}""")); server.enqueue(json("{}")) }
        api.executeFile(FileCommand("zip", "pi", "Series/Season 1", listOf("one.mp4", "two.mp4")))
        request()
        val zip = request()
        assertEquals("/api/v1/admin/files/zip", zip.path)
        val zipBody = JSONObject(zip.body.readUtf8())
        assertEquals("pi", zipBody.getString("device"))
        assertEquals("Series/Season 1", zipBody.getString("dir"))
        assertEquals("one.mp4", zipBody.getJSONArray("names").getString(0))
        api.executeFile(FileCommand("unzip", "vps", "Downloads", listOf("sample.zip")))
        request()
        val unzip = request()
        assertEquals("/api/v1/admin/files/unzip", unzip.path)
        assertEquals("Downloads/sample.zip", JSONObject(unzip.body.readUtf8()).getString("path"))
    }

    @Test fun pasteKeepsSourceDeviceAndDestinationSnapshot() = runBlocking {
        server.enqueue(json("""{"csrf_token":"fixture"}""")); server.enqueue(json("{}"))
        api.executeFile(FileCommand("paste", "vps", "target"), FileClipboard("cut", "pi", listOf("source/a.txt")))
        request()
        val paste = request()
        assertEquals("/api/v1/admin/files/paste", paste.path)
        val body = JSONObject(paste.body.readUtf8())
        assertEquals("move", body.getString("mode"))
        assertEquals("pi", body.getString("from_device"))
        assertEquals("vps", body.getString("to_device"))
        assertEquals("target", body.getString("target"))
        assertEquals("source/a.txt", body.getJSONArray("paths").getString(0))
    }

    @Test fun renameAndNewFileValidateNamesBeforeSending() = runBlocking {
        for (bad in listOf("..", ".", "../escape", "a/b", "a\\b", "")) assertFalse(validEntryName(bad))
        try { api.executeFile(FileCommand("rename", "pi", "dir", listOf("old"), "../escape")); fail("Unsafe name") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, server.requestCount)
        server.enqueue(json("""{"csrf_token":"fixture"}""")); server.enqueue(json("{}"))
        api.executeFile(FileCommand("rename", "pi", "dir", listOf("old"), "new.txt"))
        request()
        val rename = request()
        val body = JSONObject(rename.body.readUtf8())
        assertEquals("dir/old", body.getString("path"))
        assertEquals("new.txt", body.getString("new_name"))
    }

    @Test fun uploadUsesWebMultipartContract() = runBlocking {
        server.enqueue(json("""{"csrf_token":"fixture"}""")); server.enqueue(json("{}"))
        val bytes = "synthetic text".toByteArray()
        api.upload("pi", "Test", "test.txt", bytes.size.toLong(), { bytes.inputStream() }) {}
        request()
        val upload = request()
        assertEquals("/api/v1/admin/files/upload", upload.path)
        assertEquals("fixture", upload.getHeader("X-CSRF-Token"))
        val body = upload.body.readUtf8()
        assertTrue(body.contains("name=\"device\""))
        assertTrue(body.contains("name=\"path\""))
        assertTrue(body.contains("name=\"file\"; filename=\"test.txt\""))
        assertTrue(body.contains("synthetic text"))
    }

    @Test fun uploadOverLimitIsRefusedWithoutRequest() = runBlocking {
        try { api.upload("vps", "", "big.bin", 64L * 1024 * 1024 + 1, { byteArrayOf().inputStream() }) {}; fail("Over limit") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, server.requestCount)
        assertTrue(api.fileDownloadUrl("pi", "dir/a b.txt").startsWith(server.url("/").toString()))
        assertTrue(api.fileDownloadUrl("pi", "dir/a b.txt").contains("device=pi&path=dir%2Fa+b.txt"))
    }
}
