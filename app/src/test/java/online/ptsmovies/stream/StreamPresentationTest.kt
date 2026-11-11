package online.ptsmovies.stream

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class StreamPresentationTest {
    private fun title(id: Long, kind: String = "movie", added: String = "") = Title(id, kind, "Title $id", 2026, "", "", emptyList(), emptyList(), "hevc", "mp4", addedAt = added)

    @Test fun homeContainsFiveRealFeaturedTitles() {
        val titles=(1L..30).map { title(it) }
        assertEquals(titles.take(5), homeShelves(titles, emptyMap()).featured)
    }
    @Test fun smallLibraryDoesNotDuplicateTitlesToFillFeatured() { assertEquals(2, homeShelves(listOf(title(1), title(2)), emptyMap()).featured.size) }
    @Test fun recentMovieAndShowShelvesHaveTenIndependentItems() {
        val titles=(1L..25).flatMap { listOf(title(it), title(it+100, "series")) }
        val shelves=homeShelves(titles, emptyMap())
        assertEquals(10,shelves.movies.size); assertEquals(10,shelves.shows.size)
        assertTrue(shelves.movies.all { it.kind=="movie" }); assertTrue(shelves.shows.all { it.kind=="series" })
    }
    @Test fun recentShelvesUseActualDatesNotTitleIdentifiers() {
        val old=title(999,added="2026-01-01T00:00:00Z"); val new=title(1,added="2026-11-11 00:00:00")
        assertEquals(listOf(new,old),homeShelves(listOf(old,new),emptyMap()).movies)
    }
    @Test fun missingDatesKeepTheServerOrder() {
        val titles=listOf(title(20),title(2),title(300))
        assertEquals(titles,recentTitles(titles))
    }
    @Test fun continueWatchingExcludesFinishedAndBarelyStartedItems() {
        val titles=(1L..4).map { title(it) }
        val progress=mapOf(1L to Resume(0,60,600),2L to Resume(0,595,600),3L to Resume(0,2,600),4L to Resume(0,100,0))
        assertEquals(listOf(titles.first()),homeShelves(titles,progress).continuing)
    }
    @Test fun defaultFiltersHaveNoIndicator() { assertFalse(libraryFiltersActive(LibraryFilter(),LibrarySort.RELEVANCE)) }
    @Test fun everyFilterAndGenreActivatesIndicator() {
        listOf(LibraryFilter(year=2026),LibraryFilter(language="en"),LibraryFilter(unwatched=true),LibraryFilter(downloaded=true)).forEach { assertTrue(libraryFiltersActive(it,LibrarySort.RELEVANCE)) }
        assertTrue(libraryFiltersActive(LibraryFilter(),LibrarySort.RELEVANCE,"Drama"))
    }
    @Test fun nonDefaultSortAloneActivatesIndicatorAndResetClearsIt() {
        LibrarySort.entries.filter { it!=LibrarySort.RELEVANCE }.forEach { assertTrue(libraryFiltersActive(LibraryFilter(),it)) }
        assertFalse(libraryFiltersActive(LibraryFilter(),LibrarySort.RELEVANCE,""))
    }
    @Test fun audioNamesOnlyUseLanguageMetadata() {
        assertEquals("English",trackLanguageName("eng",Locale.ENGLISH)); assertEquals("Hindi",trackLanguageName("hin",Locale.ENGLISH))
        assertEquals("French",trackLanguageName("fra",Locale.ENGLISH)); assertEquals("German",trackLanguageName("ger",Locale.ENGLISH))
        assertEquals("Portuguese",trackLanguageName("pt-BR",Locale.ENGLISH))
    }
    @Test fun missingAndTechnicalLabelsNeverBecomeInventedLanguages() {
        listOf(null,"","und","null","zxx","AAC 5.1 English","audio/mp4a-latm").forEach { assertEquals("Unknown language",trackLanguageName(it,Locale.ENGLISH)) }
    }
    @Test fun onlyAdminCanNavigateOutsideStream() {
        listOf(null,"user","","moderator").forEach { role -> listOf("Hub","Files","Stream","Unknown").forEach { assertEquals("Stream",streamOnlyMode(role,it)) } }
        assertEquals("Files",streamOnlyMode("admin","Files")); assertEquals("Hub",streamOnlyMode("admin","Hub"))
    }
    @Test fun streamHasItsOwnBrandWithoutRenamingOtherSections() {
        assertEquals("PTS Stream",sectionBrand("Stream")); assertEquals("PTSFiles",sectionBrand("Files")); assertEquals("PTS Library",sectionBrand("Hub"))
    }
    @Test fun posterDecodesAreBoundedAndBucketed() {
        assertEquals(128,posterDecodeWidth(1,false)); assertEquals(320,posterDecodeWidth(300,false))
        assertEquals(768,posterDecodeWidth(99999,false)); assertEquals(1280,posterDecodeWidth(99999,true))
    }
    @Test fun onlyKnownPublicCdnPathsAreResized() {
        assertEquals("https://image.tmdb.org/t/p/w342/test.jpg",posterRequestUrl("https://image.tmdb.org/t/p/original/test.jpg",256))
        assertEquals("https://image.tmdb.org/t/p/w780/test.jpg",posterRequestUrl("https://image.tmdb.org/t/p/w500/test.jpg",768))
    }
    @Test fun signedUnknownAndNonHttpsPosterUrlsAreNeverRewritten() {
        listOf("https://image.tmdb.org/t/p/original/a.jpg?sig=private","https://other.invalid/t/p/original/a.jpg","http://image.tmdb.org/t/p/original/a.jpg").forEach { assertEquals(it,posterRequestUrl(it,256)) }
    }
    @Test fun manualPreparationRequiresMatchingSignedInOwnerAndKnownRole() {
        assertTrue(canRequestOfflinePreparation(1,1,"user")); assertTrue(canRequestOfflinePreparation(1,1,"admin"))
        assertFalse(canRequestOfflinePreparation(1,2,"admin")); assertFalse(canRequestOfflinePreparation(0,0,"user")); assertFalse(canRequestOfflinePreparation(1,1,"disabled"))
    }
    @Test fun passwordConfirmationIsRequiredAndPasswordsAreNotTrimmed() {
        assertTrue(validPasswordChange("old password"," new pass "," new pass "))
        assertFalse(validPasswordChange("old","1234567","1234567")); assertFalse(validPasswordChange("old","12345678","different"))
        assertFalse(validPasswordChange("samepassword","samepassword","samepassword")); assertFalse(validPasswordChange("","12345678","12345678"))
    }
}
