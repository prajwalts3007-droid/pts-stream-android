package online.ptsmovies.stream

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.CookieJar
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Host Android runtime tests only: no emulator, no production account or media. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun title(id: Long) = Title(id, "series", "Synthetic", 2026, "", "", emptyList(),
        (1..24).map { Episode(id * 100 + it, 1, it, "Episode $it", 30.0, "mp4") }, "hevc", "mp4")

    @Test fun legacyLibraryMigratesBeforeRemovingLargePreference() {
        val owner = 71001L; val expected = Library(listOf(title(1)), setOf(1), mapOf(1L to Resume(101, 12, 30)))
        val prefs = context.getSharedPreferences("native_ui", 0)
        prefs.edit().putString("library_$owner", expected.toJson().toString()).putString("theme", "dark").commit()
        assertEquals(expected, DeviceState(context).library(owner))
        assertFalse(prefs.contains("library_$owner")); assertEquals("dark", prefs.getString("theme", ""))
        assertEquals(expected, DeviceState(context).library(owner))
    }
    @Test fun legacyPendingProgressIsNotLostOnMigrationOrReopen() {
        val owner = 71002L; val prefs = context.getSharedPreferences("progress_journal_$owner", 0)
        val row = JSONObject().put("title_id", 1).put("episode_id", 101).put("position", 12).put("duration", 30).put("revision", "fixture")
        prefs.edit().putString("pending", JSONArray().put(row).toString()).commit()
        assertEquals(12L, ProgressJournal(context, owner).pending().single().getLong("position"))
        assertFalse(prefs.contains("pending"))
        assertEquals("fixture", ProgressJournal(context, owner).pending().single().getString("revision"))
    }
    @Test fun episodeRecordsRemainOwnerScopedAfterMigration() {
        val owner = 71003L
        context.getSharedPreferences("episode_progress_$owner", 0).edit().putString("records", JSONArray().put(JSONObject()
            .put("title_id", 1).put("episode_id", 101).put("position", 12).put("duration", 30).put("completed", false).put("pending", true)).toString()).commit()
        assertEquals(12L, EpisodeProgress(context, owner).resume(1, 101)?.seconds)
        assertNull(EpisodeProgress(context, owner + 1).resume(1, 101))
        assertFalse(context.getSharedPreferences("episode_progress_$owner", 0).contains("records"))
    }
    @Test fun committedMigrationIsIdempotentIfSourceCleanupWasInterrupted() {
        val db = StateDatabase.get(context); val owner = 71005L
        db.write(owner, "migration-fixture", "committed")
        var cleared = false
        val result = db.migrate(owner, "migration-fixture", { "stale source" }, { cleared = true })
        assertEquals("committed", result); assertTrue(cleared)
    }
    @Test fun failedSourceReadDoesNotMarkMigrationCompleteOrClearSource() {
        val db = StateDatabase.get(context); var cleared = false
        try { db.migrate(71006, "migration-fixture", { error("Synthetic interrupted read") }, { cleared = true }); fail("Expected failure") } catch (_: IllegalStateException) { }
        assertFalse(cleared); assertNull(db.read(71006, "migration-fixture"))
    }
    @Test fun savedEpisodesShareParsedTitleAndLegacyFilesStayInPlace() {
        val owner = 71007L; val root = File(context.filesDir, "media/$owner").apply { mkdirs() }
        val rows = JSONArray()
        listOf(101, 102).forEach { id -> File(root, "1_$id.mp4").writeBytes(byteArrayOf(1, 2, 3)); rows.put(JSONObject().put("key", "1_$id").put("title_id", 1).put("title", "Synthetic").put("season", 1).put("episode", id - 100).put("label", "Episode").put("bytes", 3)) }
        context.getSharedPreferences("offline_index_$owner", 0).edit().putString("items", rows.toString()).putString("title_1", title(1).toJson().toString()).commit()
        val store = OfflineStore(context, PtsApi("https://example.invalid", CookieJar.NO_COOKIES), owner)
        val saved = store.list()
        assertEquals(2, saved.size); assertSame(saved[0].metadata, saved[1].metadata)
        assertTrue(saved.all { it.file.isFile && it.documentUri == null })
        assertFalse(context.getSharedPreferences("offline_index_$owner", 0).contains("items"))
    }
    @Test fun inaccessibleSharedDocumentRemainsIndexed() {
        val owner = 71008L; val db = StateDatabase.get(context)
        val missing = JSONObject().put("key", "1_101").put("title_id", 1).put("bytes", 10).put("document_uri", "content://unavailable.invalid/tree/x/document/y")
        db.write(owner, "offline_index", JSONArray().put(missing).toString())
        val store = OfflineStore(context, PtsApi("https://example.invalid", CookieJar.NO_COOKIES), owner)
        assertTrue(store.list().isEmpty())
        assertEquals(1, JSONArray(db.read(owner, "offline_index")).length())
    }
    @Test fun smallResumeUpdateDoesNotRewriteCatalogue() {
        val owner = 71009L; val device = DeviceState(context); val db = StateDatabase.get(context)
        device.library(owner, Library(listOf(title(1)), emptySet(), emptyMap()))
        val before = db.read(owner, "catalogue")
        db.resume(owner, 1, 101, 12, 30, false)
        assertEquals(before, db.read(owner, "catalogue")); assertEquals(12L, device.library(owner)?.progress?.get(1)?.seconds)
    }
    @Test fun newCatalogueReconcilesOldAcknowledgedResumeWithoutMaskingRemoteProgress() {
        val owner = 71010L; val db = StateDatabase.get(context)
        db.resume(owner, 1, 101, 12, 300, false)
        val remote = Library(listOf(title(1)), emptySet(), mapOf(1L to Resume(101, 99, 300)))
        db.catalogue(owner, remote, System.currentTimeMillis() + 1)
        assertEquals(99L, DeviceState(context).library(owner)?.progress?.get(1)?.seconds)
    }
    @Test fun unsupportedQueueMigrationKeepsLegacySource() {
        val owner = 71011L; val source = "{\"version\":99,\"titles\":{},\"tasks\":[]}"
        val prefs = context.getSharedPreferences("download_jobs_$owner", 0)
        prefs.edit().putString("jobs", source).commit()
        try { DownloadRepository(context, PtsApi("https://example.invalid", CookieJar.NO_COOKIES), owner, foreground = {}); fail("Expected explicit migration failure") }
        catch (_: IllegalArgumentException) { }
        assertEquals(source, prefs.getString("jobs", null)); assertNull(StateDatabase.get(context).read(owner, "download_queue"))
    }
    @Test fun repositoryIsNotReleasedWhileAnyUiOwnerIsAttached() = runBlocking(Dispatchers.IO) {
        val repo = DownloadRepository(context, PtsApi("https://example.invalid", CookieJar.NO_COOKIES), 71012, foreground = {})
        repo.attachUi(); repo.attachUi()
        assertFalse(repo.releaseIfIdle()); repo.detachUi(); assertFalse(repo.releaseIfIdle())
        repo.detachUi(); assertTrue(repo.releaseIfIdle())
    }
    @Test fun serviceOwnershipPreventsReclamationUntilDetached() = runBlocking(Dispatchers.IO) {
        val repo = DownloadRepository(context, PtsApi("https://example.invalid", CookieJar.NO_COOKIES), 71013, foreground = {})
        repo.attachService(); assertFalse(repo.releaseIfIdle())
        repo.detachService(); assertTrue(repo.releaseIfIdle())
    }
    @Test fun serviceTeardownReleasesOwnershipBeforeScopeTeardown() = runBlocking(Dispatchers.IO) {
        val repo = DownloadRepository(context, PtsApi("https://example.invalid", CookieJar.NO_COOKIES), 71015, foreground = {})
        repo.attachService(); assertFalse(repo.releaseIfIdle())
        repo.detachService()
        withTimeout(5_000) { while (!repo.releaseIfIdle()) delay(20) }
        assertTrue(repo.releaseIfIdle())
    }
    @Test fun bulkEnqueuePersistsOneCheckpointAndSharesMetadata() = runBlocking(Dispatchers.IO) {
        val owner = 71014L; val db = StateDatabase.get(context)
        val repo = DownloadRepository(context, PtsApi("https://example.invalid", CookieJar.NO_COOKIES), owner, foreground = {})
        db.writableDatabase.execSQL("CREATE TABLE fixture_queue_writes (value INTEGER)")
        db.writableDatabase.execSQL("CREATE TRIGGER fixture_queue_checkpoint AFTER INSERT ON documents WHEN NEW.owner=$owner AND NEW.name='download_queue' BEGIN INSERT INTO fixture_queue_writes VALUES(1); END")
        val title = title(1); repo.enqueueSeason(title, title.episodes)
        assertEquals(24, repo.state.value.tasks.size)
        db.readableDatabase.rawQuery("SELECT count(*) FROM fixture_queue_writes", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        assertFalse(repo.releaseIfIdle()); repo.pauseAll(); assertTrue(repo.releaseIfIdle())
    }
}
