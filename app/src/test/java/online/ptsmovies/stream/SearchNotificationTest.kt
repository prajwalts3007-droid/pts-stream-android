package online.ptsmovies.stream

import org.junit.Assert.*
import org.junit.Test

class SearchNotificationTest {
    private val episode = Episode(101, 1, 2, "The Vanishing Signal", 120.0, "mp4")
    private val show = Title(10, "series", "Café Detectives", 2024, "", "", listOf("Mystery"), listOf(episode), "hevc", "mp4", addedAt = "2026-11-01T10:00:00Z")
    private val movie = show.copy(id = 20, kind = "movie", title = "The Vanishing Signal", year = 2026, episodes = emptyList(), addedAt = "2026-11-10 12:00:00")
    private fun search(query: String, sort: LibrarySort = LibrarySort.RELEVANCE) = searchLibrary(indexLibrary(listOf(show, movie)), query, sort)

    @Test fun exactTitleRanksAboveEpisodeNameMatches() { assertEquals(listOf(movie, show), search("The Vanishing Signal")) }
    @Test fun findsCaseAndLatinDiacriticVariants() { assertEquals(show, search("CAFE DETECTIVES").first()) }
    @Test fun preservesNonLatinSearchCharacters() { assertEquals("हिंदी कहानी", searchWords("हिंदी कहानी")) }
    @Test fun findsSmallTitleTyposWithoutReturningUnrelatedTitles() { assertEquals(listOf(show), search("cafe detectivs")) }
    @Test fun findsEpisodeNamesAndSeasonEpisodeIdentifiers() {
        assertTrue(show in search("vanishing")); assertEquals(listOf(show), search("s01e02")); assertEquals(listOf(show), search("s1 e2"))
    }
    @Test fun findsMixedSeriesAndEpisodeQuery() { assertEquals(listOf(show), search("detectives vanishing")) }
    @Test fun stillSearchesGenresAndRejectsUnrelatedTerms() { assertEquals(2, search("mystery").size); assertTrue(search("qzxvbanotherplanet").isEmpty()) }
    @Test fun shortQueriesDoNotFuzzyMatchEveryWord() { assertFalse(closeSearchWord("zx", "signal")); assertTrue(closeSearchWord("sig", "signal")) }
    @Test fun sortingWorksWithSearchAndBlankQueries() {
        assertEquals(listOf(movie, show), search("", LibrarySort.RECENT))
        assertEquals(listOf(show, movie), search("", LibrarySort.TITLE_ASC))
        assertEquals(listOf(movie, show), search("", LibrarySort.TITLE_DESC))
        assertEquals(listOf(movie, show), search("signal", LibrarySort.YEAR_DESC))
        assertEquals(listOf(show, movie), search("signal", LibrarySort.YEAR_ASC))
    }
    @Test fun unknownDatesPreserveBackendOrderAndUnknownYearsSortLast() {
        val a=show.copy(addedAt="", year=0); val b=movie.copy(addedAt="")
        assertEquals(listOf(a,b),searchLibrary(indexLibrary(listOf(a,b)),"",LibrarySort.RECENT))
        assertEquals(listOf(b,a),searchLibrary(indexLibrary(listOf(a,b)),"",LibrarySort.YEAR_ASC))
    }
    @Test fun addedTimestampSurvivesOfflineSerialization() { assertEquals(show,parseTitle(show.toJson())) }
    @Test fun downloadChildrenHaveCollisionFreeOwnerAndEpisodeIdentity() {
        assertNotEquals(downloadNoticeTag(1,"10_101"),downloadNoticeTag(1,"10_102"))
        assertNotEquals(downloadNoticeTag(1,"10_101"),downloadNoticeTag(2,"10_101"))
        assertEquals(downloadNoticeTag(1,"10_101"),downloadNoticeTag(1,"10_101"))
    }
    @Test fun downloadDisplayOrderDoesNotDependOnLastReportedProgress() {
        val a=DownloadTask(show,episode,status="downloading",bytes=80,total=100)
        val b=DownloadTask(show,episode.copy(id=102),status="downloading",bytes=10,total=100)
        assertEquals(listOf(a,b),visibleDownloadTasks(listOf(a,b),emptySet()))
        assertEquals(listOf(a.key,b.key),visibleDownloadTasks(listOf(a.copy(bytes=90),b.copy(bytes=70)),emptySet()).map { it.key })
        assertTrue(downloadNoticeSortKey(2)<downloadNoticeSortKey(10))
    }
    @Test fun progressBarsAreIndependentAndTerminalStateIsNotIndeterminate() {
        val a=DownloadTask(show,episode,status="downloading",bytes=20,total=100)
        assertEquals(20,downloadNoticePercent(a)); assertEquals(80,downloadNoticePercent(a.copy(bytes=80)))
        assertEquals(100,downloadNoticePercent(a.copy(status="complete",total=0)))
        assertFalse(transferIsActive("paused"));assertFalse(transferIsActive("complete"));assertTrue(transferIsActive("waiting_wifi"))
    }
    @Test fun oldCompletedJobsDoNotFloodNotificationsButTrackedCompletionRemains() {
        val done=DownloadTask(show,episode,status="complete")
        assertTrue(visibleDownloadTasks(listOf(done),emptySet()).isEmpty())
        assertEquals(listOf(done),visibleDownloadTasks(listOf(done),setOf(done.key)))
    }
    @Test fun removingAndAppendingQueueItemsNeverReusesSiblingSortKeys() {
        val original=appendDownloadOrder(emptyMap(),listOf("a","b","c"))
        val next=appendDownloadOrder(original,listOf("b","c","d"))
        assertEquals(original["b"],next["b"]);assertEquals(original["c"],next["c"])
        assertTrue(next.getValue("d")>next.getValue("c"))
    }
    @Test fun largeQueueHonorsAndroidNotificationCapacityAndPrioritizesRunningTasks() {
        val tasks=(1..70).map { DownloadTask(show,episode.copy(id=it.toLong()),status=if(it==70) "downloading" else "queued") }
        val visible=visibleDownloadTasks(tasks,emptySet())
        assertEquals(40,visible.size);assertTrue(visible.any { it.key=="10_70" })
    }
}
