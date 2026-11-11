package online.ptsmovies.stream

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryFeaturesTest {
    private val first = Episode(101,1,1,"First",100.0,"mp4")
    private val second = first.copy(id=102,episode=2)
    private val third = first.copy(id=103,season=2,episode=1)
    private val series = Title(100,"series","Series",2025,"","Description",listOf("Drama"),listOf(first,second,third),"hevc","mp4",listOf("en","hi"))
    private val movie = series.copy(id=200,kind="movie",title="Movie",year=2026,episodes=emptyList(),languages=listOf("ta"))

    @Test fun languageParsingUsesTrackMetadataWithoutInventingMissingLanguages() {
        assertEquals(listOf("en","hi"),titleLanguages(JSONObject("""{"audio":[{"lang":"hin"}],"episodes":[{"audio":[{"language":"eng"},{"lang":"und"}]}]}""")))
        assertEquals(emptyList<String>(),titleLanguages(JSONObject("""{"title":"A Hindi-sounding title"}""")))
    }
    @Test fun languageMetadataSurvivesOfflineRoundTrip() { assertEquals(series,parseTitle(series.toJson())) }
    @Test fun yearAndLanguageFiltersCombine() {
        assertEquals(listOf(series),filterLibrary(listOf(series,movie),LibraryFilter(year=2025,language="hi"),emptySet(),emptySet()))
        assertTrue(filterLibrary(listOf(series),LibraryFilter(language="ta"),emptySet(),emptySet()).isEmpty())
    }
    @Test fun unwatchedSeriesRequiresEveryEpisodeCompleted() {
        assertEquals(listOf(series),filterLibrary(listOf(series),LibraryFilter(unwatched=true),setOf("100_101"),emptySet()))
        assertTrue(filterLibrary(listOf(series),LibraryFilter(unwatched=true),setOf("100_101","100_102","100_103"),emptySet()).isEmpty())
    }
    @Test fun downloadedFilterUsesExactTitleIdentity() {
        assertEquals(listOf(movie),filterLibrary(listOf(series,movie),LibraryFilter(downloaded=true),emptySet(),setOf("200_0","1000_101")))
    }
    @Test fun relatedNeverIncludesItselfOrDifferentKinds() {
        assertEquals(listOf(series.copy(id=300)),relatedTitles(series,listOf(series,movie,series.copy(id=300))))
    }
    @Test fun smartDownloadsAreOffByDefaultAndBounded() {
        assertTrue(smartEpisodes(series,first,0,emptySet(),emptySet()).isEmpty())
        assertTrue(smartEpisodes(series,first,99,emptySet(),emptySet()).isEmpty())
        assertEquals(listOf(second,third),smartEpisodes(series,first,3,emptySet(),emptySet()))
    }
    @Test fun smartDownloadsSkipWatchedAndNeverRetryPausedOrFailedWork() {
        assertEquals(listOf(third),smartEpisodes(series,first,2,setOf("100_102"),emptySet()))
        assertEquals(listOf(third),smartEpisodes(series,first,2,emptySet(),setOf("100_102")))
    }
    @Test fun smartDownloadsNeverAutomaticallyPrepareHls() {
        val title=series.copy(episodes=listOf(first,second.copy(container="hls"),third))
        assertEquals(listOf(third),smartEpisodes(title,first,3,emptySet(),emptySet()))
    }
    @Test fun validatesSrtAndVttContentNotJustExtension() {
        assertEquals("srt",validateSubtitle("1\n00:00:01,000 --> 00:00:03,000\nHello".toByteArray()).first)
        assertEquals("vtt",validateSubtitle("WEBVTT\n\n00:01.000 --> 00:03.000\nHello".toByteArray()).first)
    }
    @Test fun acceptsBomAndUtf16ButRejectsBinaryAndOversizedSubtitles() {
        assertEquals("srt",validateSubtitle(("\uFEFF1\n00:00:01,000 --> 00:00:03,000\nHello").toByteArray(Charsets.UTF_16LE)).first)
        for (bytes in listOf(byteArrayOf(0,1,2),ByteArray(MAX_SUBTITLE_BYTES+1),"Not a subtitle".toByteArray())) {
            try { validateSubtitle(bytes); fail("Invalid subtitles accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun syncAcknowledgesSentEditAndKeepsConcurrentLocalChange() {
        val sent=JSONObject("""{"title_id":100,"episode_id":101,"event_id":"first-event","pending":true,"completed":false}""")
        val newer=JSONObject(sent.toString()).put("event_id","second-event").put("completed",true)
        val remote=JSONObject(sent.toString()).put("pending",false)
        val merged=mergeEpisodeRecords(listOf(newer),listOf(sent),listOf(remote))
        assertEquals("second-event",merged.single().getString("event_id"));assertTrue(merged.single().getBoolean("pending"))
    }
    @Test fun staleOfflineEditCannotUndoAcknowledgedNewerRemoteCompletion() {
        val stale=JSONObject("""{"title_id":100,"episode_id":101,"event_id":"first-event","pending":true,"completed":false}""")
        val remote=JSONObject(stale.toString()).put("event_id","remote-newer").put("pending",false).put("completed",true)
        val merged=mergeEpisodeRecords(listOf(stale),listOf(stale),listOf(remote))
        assertTrue(merged.single().getBoolean("completed"));assertFalse(merged.single().getBoolean("pending"))
    }
}
