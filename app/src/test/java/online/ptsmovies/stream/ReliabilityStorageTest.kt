package online.ptsmovies.stream

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import okhttp3.CookieJar
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReliabilityStorageTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val title = Title(800, "movie", "Synthetic", 2026, "", "", emptyList(), emptyList(), "hevc", "mp4")
    @Test fun oldUploadCannotAcknowledgeOrOverwriteNewEdit() {
        val settings = PlaybackSettings(context, 72001)
        settings.edit(JSONObject().put("audio_lang", "en")); val sent = settings.snapshot()
        settings.edit(JSONObject().put("audio_lang", "hi"))
        assertFalse(settings.acknowledge(sent)); assertFalse(settings.acceptRemote(sent, JSONObject().put("audio_lang", "en")))
        assertEquals("hi", JSONObject(settings.snapshot().value).getString("audio_lang")); assertTrue(settings.snapshot().pending)
    }
    @Test fun editsDuringPreferenceFetchRemainPending() {
        val settings = PlaybackSettings(context, 72002)
        settings.edit(JSONObject().put("autoplay", false)); val sent = settings.snapshot(); assertTrue(settings.acknowledge(sent))
        settings.edit(JSONObject().put("subtitle_lang", "ta"))
        assertFalse(settings.acceptRemote(sent, JSONObject().put("autoplay", true)))
        val current = JSONObject(settings.snapshot().value)
        assertFalse(current.getBoolean("autoplay")); assertEquals("ta", current.getString("subtitle_lang"))
    }
    @Test fun successfulSettingsRoundTripIsDurableAndOwnerScoped() {
        val settings = PlaybackSettings(context, 72003)
        settings.edit(JSONObject().put("audio_lang", "hi").put("autoplay", false))
        val sent = settings.snapshot(); assertTrue(settings.acknowledge(sent)); assertTrue(settings.acceptRemote(sent, JSONObject(sent.value)))
        assertEquals("Synced", PlaybackSettings(context, 72003).status())
        assertEquals("hi", JSONObject(PlaybackSettings(context, 72003).snapshot().value).getString("audio_lang"))
        assertFalse(JSONObject(PlaybackSettings(context, 72004).snapshot().value).has("audio_lang"))
    }
    @Test fun batchedSaveRetainsUnknownServerPreferences() {
        val settings = PlaybackSettings(context, 72005)
        settings.edit(JSONObject().put("other_setting", "keep"))
        val result = settings.edit(JSONObject().put("audio_lang", "en").put("subtitle_lang", "hi").put("autoplay", false).put("zoom_mode", "remember"))
        assertEquals(5, result.length()); assertEquals("keep", result.getString("other_setting"))
    }
    @Test fun lowMemoryAndChargingAreOptInAndKeepDeletionOptIn() {
        val prefs = NativePreferences(context)
        assertFalse(prefs.lowMemory()); assertFalse(prefs.downloads().chargingOnly); assertFalse(prefs.downloads().removeWatched)
        prefs.downloads(DownloadOptions(concurrent = 3, chargingOnly = true)); prefs.lowMemory(true)
        assertEquals(1, prefs.downloads().concurrent); assertTrue(prefs.downloads().chargingOnly)
        prefs.lowMemory(false); assertEquals(3, prefs.downloads().concurrent)
    }
    @Test fun selectedPrimaryFolderRequiresSpaceForTwoCopies() {
        assertTrue(primaryExport("content://com.android.externalstorage.documents/tree/primary%3AMovies"))
        assertFalse(primaryExport("content://com.android.externalstorage.documents/tree/ABCD-1234%3AMovies"))
        assertFalse(primaryExport(null)); assertFalse(primaryExport("content://other.provider/tree/primary%3AMovies"))
    }
    @Test fun completeStagedVideoCanFinishPrivatelyWithoutNetworkOrHlsPreparation() = runBlocking(Dispatchers.IO) {
        val owner = 72006L; val root = File(context.filesDir, "media/$owner").apply { mkdirs() }
        File(root, "800_0.mp4").writeText("complete fixture"); File(root, "800_0.complete").writeText("16")
        val store = OfflineStore(context, PtsApi("https://unused.invalid", CookieJar.NO_COOKIES), owner)
        assertTrue(store.hasStaged("800_0"))
        val result = store.save(title.copy(container = "hls"), null, resume = true) { }
        assertEquals(16L, result.bytes); assertNull(result.documentUri); assertFalse(store.hasStaged("800_0"))
        assertEquals(result.bytes, store.list().single().bytes)
    }
    @Test fun interruptedBodyResumesValidatedBytesOnFallback() = runBlocking(Dispatchers.IO) {
        val server = MockWebServer(); val requests = AtomicInteger(); val bytes = "x".repeat(32768)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (requests.incrementAndGet() == 1) return MockResponse().setHeader("ETag", "\"fixture\"").setBody(bytes).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                val offset = request.getHeader("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull() ?: 0
                return if (offset > 0) MockResponse().setResponseCode(206).setHeader("ETag", "\"fixture\"").setHeader("Content-Range", "bytes $offset-${bytes.length - 1}/${bytes.length}").setBody(bytes.substring(offset))
                else MockResponse().setHeader("ETag", "\"fixture\"").setBody(bytes)
            }
        }
        server.start()
        try {
            val store = OfflineStore(context, PtsApi(server.url("/").toString().trimEnd('/'), CookieJar.NO_COOKIES), 72007)
            val saved = store.save(title, null, resume = true) { }
            assertEquals(bytes, saved.file.readText())
            server.takeRequest(); assertTrue(server.takeRequest().getHeader("Range")?.startsWith("bytes=") == true)
        } finally { server.shutdown() }
    }
    @Test fun unavailableDocumentsHaveRecoveryEntriesWithoutLosingIndex() {
        val owner = 72008L; val db = StateDatabase.get(context)
        db.write(owner, "offline_index", """[{"key":"800_0","title_id":800,"label":"Fixture","bytes":10,"document_uri":"content://unavailable.invalid/tree/x/document/y"}]""")
        val store = OfflineStore(context, PtsApi("https://unused.invalid", CookieJar.NO_COOKIES), owner)
        val saved = store.list(); assertTrue(saved.isEmpty()); assertEquals("Fixture", store.unavailable(saved).single().label)
        assertTrue(db.read(owner, "offline_index")!!.contains("unavailable.invalid"))
    }
    @Test fun repeatedServiceStartsHoldExactlyOneLeaseAndDestroyReleasesIt() {
        val owner = 72009L; val db = StateDatabase.get(context)
        DeviceState(context).account(JSONObject().put("id", owner).put("role", "user"))
        NativePreferences(context).downloads(DownloadOptions(wifiOnly = true))
        db.write(owner, "download_queue", compactQueue(listOf(DownloadTask(title, null, "queued"))))
        val controller = Robolectric.buildService(DownloadService::class.java).create()
        val service = controller.get()
        repeat(8) { service.onStartCommand(Intent(context, DownloadService::class.java).putExtra("owner", owner), 0, it + 1) }
        val repo = DeviceDownloads.forOwner(context, ApiProvider.create(), owner)
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(25)
            if (repo.serviceOwnerCount == 1 && repo.state.value.tasks.single().status == "waiting_wifi") {
                // Let all serialized starts finish, not only the first acquisition.
                repeat(30) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
                break
            }
        }
        assertEquals(1, repo.serviceOwnerCount)
        controller.destroy(); assertEquals(0, repo.serviceOwnerCount)
        runBlocking(Dispatchers.IO) { withTimeout(5000) { while (repo.state.value.tasks.any { transferIsActive(it.status) }) delay(20) } }
        assertTrue(repo.releaseIfIdle())
    }
}
