package online.ptsmovies.stream

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReliabilityPolicyTest {
    private val title = Title(700, "movie", "Fixture", 2026, "", "", emptyList(), emptyList(), "hevc", "mp4")
    @Test fun redOnlyRequiresVerifiedLocalConnectivity() {
        assertEquals(ConnectionRoute.LOCAL, connectionRoute(true, false))
        assertEquals(ConnectionRoute.LOCAL, connectionRoute(true, true))
        assertEquals(ConnectionRoute.INTERNET, connectionRoute(false, true))
        assertEquals(ConnectionRoute.OFFLINE, connectionRoute(false, false))
    }
    @Test fun chargingAndWifiConstraintsHaveExplicitWaitReasons() {
        assertEquals("waiting_wifi", downloadWaitReason(true, false, true, false))
        assertEquals("waiting_charging", downloadWaitReason(true, true, true, false))
        assertNull(downloadWaitReason(true, true, true, true))
        assertNull(downloadWaitReason(false, false, false, false))
    }
    @Test fun exportingAndScheduledWaitsArePausableButCancellationIsNot() {
        listOf("exporting", "waiting_charging", "waiting_wifi").forEach { assertTrue(transferCanPause(it)); assertTrue(transferIsActive(it)) }
        assertFalse(transferCanPause("cancelling")); assertTrue(transferIsActive("cancelling"))
        assertFalse(transferIsActive("failed")); assertFalse(transferIsActive("paused"))
    }
    @Test fun internalExportReservesBothCopiesIncludingResume() {
        assertEquals(200L, transferReservation(100, 0, true))
        assertEquals(160L, transferReservation(100, 40, true))
        assertEquals(100L, transferReservation(100, 100, true))
        assertEquals(60L, transferReservation(100, 40, false))
    }
    @Test fun countersHaveNoTitleMetadataAndRestoreOnlyActiveRows() {
        val tasks = listOf(DownloadTask(title, null, "downloading", 40, 100), DownloadTask(title.copy(id = 701), null, "paused", 5, 100))
        val raw = compactCounters(tasks)
        assertFalse(raw.contains("Fixture")); assertEquals(1, JSONObject(raw).length())
        assertEquals(40L, restoreCounters(tasks.map { it.copy(bytes = 0) }, raw).first().bytes)
        assertEquals(0L, restoreCounters(tasks.map { it.copy(bytes = 0) }, raw).last().bytes)
    }
    @Test fun malformedCountersDoNotDestroyQueue() {
        val tasks = listOf(DownloadTask(title, null, "downloading", 3, 100))
        assertEquals(tasks, restoreCounters(tasks, "not json"))
        assertEquals(tasks, restoreCounters(tasks, """{"700_0":{"bytes":200,"total":100}}"""))
    }
    @Test fun exportRecoverySurvivesQueueReopen() {
        val task = DownloadTask(title, null, "failed", 100, 100, failure = "export", error = "Restore folder access")
        assertEquals(task, parseQueue(compactQueue(listOf(task))).single())
    }
    @Test fun successAndChunkedResponsesAreBounded() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            for (response in listOf(MockResponse().setBody("x".repeat(100)), MockResponse().setChunkedBody("x".repeat(100), 7))) {
                server.enqueue(response)
                try { OkHttpClient().newCall(Request.Builder().url(server.url("/fixture")).build()).awaitJson(32); fail("Must reject oversize response") }
                catch (_: Exception) { }
            }
        } finally { server.shutdown() }
    }
    @Test fun validBoundedResponseStillParses() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            assertTrue(OkHttpClient().newCall(Request.Builder().url(server.url("/fixture")).build()).awaitJson(32).getBoolean("ok"))
        } finally { server.shutdown() }
    }
    @Test fun libraryHasLargerBoundThanOrdinaryConfiguration() {
        assertTrue(jsonResponseLimit("/stream/library") > jsonResponseLimit("/auth/config"))
        assertEquals(jsonResponseLimit("/queue"), jsonResponseLimit("/queue?stage=done"))
    }
}
