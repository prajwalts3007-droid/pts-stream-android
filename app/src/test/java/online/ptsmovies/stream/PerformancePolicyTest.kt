package online.ptsmovies.stream

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

class PerformancePolicyTest {
    private fun title(id: Long = 1) = Title(id, "series", "Title $id", 2026, "", "", emptyList(),
        (1..24).map { Episode(id * 100 + it, 1, it, "Episode $it", 2400.0, "mp4", "", 1000) }, "hevc", "mp4")

    @Test fun gateAllowsOnlyOneFlight() {
        val gate = RefreshGate(); val ticket = gate.begin()!!
        repeat(100) { assertNull(gate.begin()) }
        assertFalse(gate.finish(ticket)); assertNotNull(gate.begin())
    }
    @Test fun manualRefreshesCoalesceIntoOneFollowUp() {
        val gate = RefreshGate(); val ticket = gate.begin()!!
        repeat(100) { assertNull(gate.begin(queueLatest = true)) }
        assertTrue(gate.finish(ticket))
        assertFalse(gate.finish(gate.begin()!!))
    }
    @Test fun oldOwnerCompletionCannotUnlockNewFlight() {
        val gate = RefreshGate(); val old = gate.begin()!!
        gate.begin(true); gate.reset(); val current = gate.begin()!!
        assertFalse(gate.finish(old)); assertNull(gate.begin()); assertFalse(gate.finish(current))
    }
    @Test fun repeatedCompletionCannotReplayPendingWork() {
        val gate = RefreshGate(); val ticket = gate.begin()!!; gate.begin(true)
        assertTrue(gate.finish(ticket)); assertFalse(gate.finish(ticket))
    }
    @Test fun cacheReusesIndexAfterLeavingScreen() = runBlocking {
        val builds = AtomicInteger(); val cache = LibraryIndexCache { builds.incrementAndGet(); indexLibrary(it) }
        val titles = listOf(title()); val first = cache.get(titles)
        assertSame(first, cache.get(titles)); assertEquals(1, builds.get())
    }
    @Test fun equalParsedLibraryReusesIndex() = runBlocking {
        val builds = AtomicInteger(); val cache = LibraryIndexCache { builds.incrementAndGet(); indexLibrary(it) }
        val first = cache.get(listOf(title()))
        assertSame(first, cache.get(listOf(title().copy()))); assertEquals(1, builds.get())
    }
    @Test fun episodeMetadataChangesInvalidateIndex() = runBlocking {
        val cache = LibraryIndexCache(); val first = cache.get(listOf(title()))
        val changed = title().let { it.copy(episodes = it.episodes.map { ep -> ep.copy(title = "Changed ${ep.episode}") }) }
        assertNotSame(first, cache.get(listOf(changed)))
        assertTrue(searchLibrary(cache.get(listOf(changed)), "Changed").isNotEmpty())
    }
    @Test fun cacheIsBoundedAndLeastRecentlyUsed() = runBlocking {
        val builds = AtomicInteger(); val cache = LibraryIndexCache(2) { builds.incrementAndGet(); indexLibrary(it) }
        cache.get(listOf(title(1))); cache.get(listOf(title(2))); cache.get(listOf(title(1))); cache.get(listOf(title(3)))
        assertEquals(3, builds.get()); cache.get(listOf(title(2))); assertEquals(4, builds.get())
    }
    @Test fun ownerCachesNeverShareEntries() = runBlocking {
        val a = LibraryIndexCache(); val b = LibraryIndexCache()
        assertNotSame(a.get(listOf(title())), b.get(listOf(title())))
    }
    @Test fun concurrentRequestsBuildOnce() = runBlocking {
        val builds = AtomicInteger(); val cache = LibraryIndexCache { builds.incrementAndGet(); indexLibrary(it) }
        val results = (1..20).map { async(Dispatchers.Default) { cache.get(listOf(title())) } }.awaitAll()
        assertEquals(1, builds.get()); results.forEach { assertSame(results.first(), it) }
    }
    @Test fun failedBuildDoesNotPoisonCache() = runBlocking {
        var shouldFail = true
        val cache = LibraryIndexCache { if (shouldFail) error("Synthetic failure") else indexLibrary(it) }
        try { cache.get(listOf(title())); fail("Expected failure") } catch (_: IllegalStateException) { }
        shouldFail = false; assertEquals(1, cache.get(listOf(title())).size)
    }
    @Test(expected = IllegalArgumentException::class) fun cacheCapacityMustBeBounded() { LibraryIndexCache(100) }
    @Test fun libraryRoundTripRetainsUpgradeData() {
        val before = Library(listOf(title()), setOf(1), mapOf(1L to Resume(101, 600, 2400)))
        assertEquals(before, parseLibrary(JSONObject(before.toJson().toString())))
    }
    @Test fun freshEmptyLibraryNeedsNoPreseededState() {
        assertEquals(Library(emptyList(), emptySet(), emptyMap()), parseLibrary(JSONObject()))
    }
    @Test fun bulkEpisodeUpdatesPreserveOtherTitlesAndLatestEdits() {
        val other = JSONObject().put("title_id", 2).put("episode_id", 10).put("position", 60)
        val edits = (1L..193).map { EpisodeEdit(1, it, 0, 2400, true) }
        val result = applyEpisodeEdits(listOf(other), edits, 100)
        assertEquals(194, result.size); assertSame(other, result.first())
        assertTrue(result.drop(1).all { it.optBoolean("completed") && it.optBoolean("pending") })
        assertEquals(193, result.drop(1).map { it.optString("event_id") }.toSet().size)
    }
    @Test fun bulkChangesRemainMonotonicAndDoNotMutateSentRecords() {
        val old = applyEpisodeEdits(emptyList(), listOf(EpisodeEdit(1, 7, 60, 300, false)), 200).single()
        val changed = applyEpisodeEdits(listOf(old), listOf(EpisodeEdit(1, 7, 0, 300, true)), 100).single()
        assertEquals(201L, changed.optLong("changed_at")); assertFalse(old.optBoolean("completed"))
        assertNotEquals(old.optString("event_id"), changed.optString("event_id"))
    }
    @Test fun invalidBulkKeysAreRejectedAndPositionsStayBounded() {
        val result = applyEpisodeEdits(emptyList(), listOf(EpisodeEdit(0, 1, 0, 0, false), EpisodeEdit(1, -1, 0, 0, false), EpisodeEdit(1, 0, -4, 9999999, false)), 10)
        assertEquals(1, result.size); assertEquals(0L, result.single().optLong("position")); assertEquals(604800L, result.single().optLong("duration"))
    }
}
