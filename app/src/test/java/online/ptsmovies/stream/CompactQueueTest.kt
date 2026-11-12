package online.ptsmovies.stream

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class CompactQueueTest {
    private fun title() = Title(1, "series", "Synthetic", 2026, "", "", emptyList(),
        (1..193).map { Episode(it.toLong(), 1, it, "Episode $it", 30.0, "mp4") }, "hevc", "mp4")
    @Test fun entireSeriesMetadataIsStoredOnceNotPerTask() {
        val title = title(); val tasks = title.episodes.map { DownloadTask(title, it) }
        val compact = compactQueue(tasks); val legacy = JSONArray(tasks.map { it.json() }).toString()
        assertEquals(1, JSONObject(compact).getJSONObject("titles").length())
        assertTrue(compact.length < legacy.length / 10)
        val reopened = parseQueue(compact)
        assertEquals(193, reopened.size); assertSame(reopened.first().title, reopened.last().title)
    }
    @Test fun v14QueueLoadsWithoutDroppingPositionsOrStatuses() {
        val title = title(); val task = DownloadTask(title, title.episodes.first(), "paused", 123, 1000, error = "fixture")
        val reopened = parseQueue(JSONArray().put(task.json()).toString()).single()
        assertEquals(task, reopened); assertEquals(task, parseQueue(compactQueue(listOf(reopened))).single())
    }
    @Test fun destinationIsCapturedPerTaskAndSurvivesReopen() {
        val title = title(); val task = DownloadTask(title, title.episodes.first(), destination = "content://provider/tree/folder")
        assertEquals(task.destination, parseQueue(compactQueue(listOf(task))).single().destination)
        assertNull(parseQueue(compactQueue(listOf(task.copy(destination = null)))).single().destination)
    }
    @Test fun mixedOldAndNewShowSnapshotsKeepEveryQueuedEpisode() {
        val full = title(); val short = full.copy(episodes = full.episodes.take(1))
        val tasks = listOf(DownloadTask(short, short.episodes.first()), DownloadTask(full, full.episodes.last()))
        val legacy = parseQueue(JSONArray(tasks.map { it.json() }).toString())
        val compact = parseQueue(compactQueue(tasks))
        listOf(legacy, compact).forEach { rows -> assertEquals(193, rows.first().title.episodes.size); assertSame(rows.first().title, rows.last().title); assertEquals(193L, rows.last().episode?.id) }
    }
    @Test(expected = IllegalArgumentException::class) fun unknownVersionIsRejectedRatherThanErasingQueue() { parseQueue("{\"version\":99,\"titles\":{},\"tasks\":[]}") }
    @Test fun obsoleteSearchStopsCooperatively() {
        var checks = 0
        try { searchLibrary(indexLibrary(listOf(title())), "unmatched words", LibrarySort.RELEVANCE) { checks++; if (checks > 3) throw java.util.concurrent.CancellationException() }; fail("Expected cancellation") } catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(4, checks)
    }
    @Test fun offlineEpisodeCanPlayEvenIfNewCatalogueNoLongerListsIt() {
        val saved = SavedVideo("1_193", 1, "Synthetic", 1, 193, "Episode 193", 10, java.io.File("unused-fixture"), metadata = title().copy(episodes = emptyList()))
        assertEquals(193L, saved.titleMetadata().episodes.single().id)
    }
}
