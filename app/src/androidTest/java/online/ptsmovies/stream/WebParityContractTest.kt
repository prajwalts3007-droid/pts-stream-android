package online.ptsmovies.stream

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebParityContractTest {
    private fun rejects(block: () -> Unit) { try { block(); fail("Unsafe/ambiguous input was accepted") } catch (_: IllegalArgumentException) { } }
    @Test fun linksImportMatchesWebNumberingAndRejectsAmbiguity() {
        assertEquals(2 to 5, episodeNumbers("/Season 2/Show.S02E05.mkv"))
        assertEquals(2 to 5, episodeNumbers("/Season 2/05 - Name.mp4"))
        assertEquals(2 to 5, episodeNumbers("/Show.2x05.mp4"))
        rejects { episodeNumbers("/Season 1/Show.S02E05.mkv") }
        rejects { episodeNumbers("/Show.S01E01.S01E02.mp4") }
        rejects { episodeNumbers("/Show.mp4") }
        rejects { parseEpisodeLinks("https://example.invalid/Show.S01E01.mp4\nhttps://example.invalid/Other.S01E01.mkv") }
        rejects { parseEpisodeLinks("a".repeat(2 * 1024 * 1024 + 1)) }
        rejects { parseEpisodeLinks("../escape.S01E01.mp4\thttps://pixeldrain.com/api/file/abcd1234") }
        val imported = parseEpisodeLinks("\uFEFF# generated\nhttps://example.invalid/Show.S02E02.mp4\nhttps://example.invalid/Show.S01E01.mp4\nhttps://example.invalid/readme.txt")
        assertEquals(listOf(1 to 1, 2 to 2), imported.map { it.season to it.episode })
        assertEquals("https://pixeldrain.com/api/file/abcd1234", parseEpisodeLinks("Show.S02E05.mp4\thttps://pixeldrain.com/api/file/abcd1234").single().link)
    }

    @Test fun zipPreflightMapsOnlySafeUniqueNumberedMembersAndRetainsExactMemberPath() {
        val zip = "https://example.invalid/source.zip"
        val rows = zipEpisodeLinks(zip, JSONObject("""{"members":[{"name":"Season 2/Show.S02E05.mkv"},{"name":"unnumbered.mp4"}]}"""))
        assertEquals("Season 2/Show.S02E05.mkv", rows.single().json().getString("zip_member"))
        assertEquals(zip, rows.single().link)
        rejects { zipEpisodeLinks(zip, JSONObject("""{"members":[{"name":"../Show.S01E01.mp4"}]}""")) }
        rejects { zipEpisodeLinks(zip, JSONObject("""{"members":[{"name":"Show.S01E01.mp4"},{"name":"other.S01E01.mp4"}]}""")) }
        rejects { zipEpisodeLinks("http://example.invalid/source.zip", JSONObject()) }
        val title = JSONObject("""{"imdb_id":"tt1234567","title":"Generated","year":2026,"type":"series"}""")
        assertFalse(ingestPayload(title, rows.map { it.json() }).getBoolean("force"))
        assertTrue(ingestPayload(title, rows.map { it.json() }, true).getBoolean("force"))
        assertFalse(validSourceLink("javascript:alert(1)")); assertFalse(validSourceLink("https://password@example.invalid/file"))
    }

    @Test fun scrapedEpisodeTablesUseSeasonNumbersNotOverallOrderOrCastRows() {
        val html = """<table class="infobox"><tr><td class="summary">Not an episode</td></tr></table>
            <h3>Season 1 (2020)</h3><table class="wikiepisodetable"><tr class="vevent"><th>1</th><td class="summary">"First &amp; Last"<sup class="reference">[1]</sup></td></tr></table>
            <h3>Season 2 (2023)</h3><table class="wikiepisodetable"><tr class="vevent"><th>9</th><td>1</td><td class="summary">"New Season"</td></tr></table>"""
        assertEquals(listOf(EpisodeName(1, 1, "First & Last"), EpisodeName(2, 1, "New Season")), parseWikipediaNames(html))
        assertTrue(parseWikipediaNames("<table><tr><td>1</td><td>Actor name</td></tr></table>").isEmpty())
        assertTrue(parseTvmazeNames(JSONArray("""[{"season":1,"number":1,"name":"Episode 1"},{"season":1,"number":2,"name":"TBA"}]""")).isEmpty())
    }

    @Test fun namesOverlayPreservesActualNamesAndOnlyMapsExactSeasonEpisode() {
        val original = Title(91001, "series", "Example Series", 2020, "", "", emptyList(), listOf(
            Episode(1, 1, 1, "E1 - Example Series", 8.0, "mp4"), Episode(2, 1, 2, "Manually corrected", 8.0, "mp4"), Episode(3, 2, 1, "Example Series", 8.0, "mp4")), "", "mp4")
        val names = EpisodeNames(MetadataMatch("Wikipedia", "Example", "Example"), listOf(EpisodeName(1, 1, "Original first"), EpisodeName(1, 2, "Other name")))
        val overlaid = applyEpisodeNames(original, names)
        assertEquals(listOf("Original first", "Manually corrected", "Example Series"), overlaid.episodes.map { it.title })
        assertEquals("Corrected source", applyEpisodeNames(overlaid, names.copy(episodes = listOf(EpisodeName(1, 1, "Corrected source"))), names).episodes.first().title)
        assertTrue(isGeneratedEpisodeName("S01E01 · Example Series", "Example Series"))
        assertFalse(isGeneratedEpisodeName("An original name", "Example Series"))
    }

    @Test fun metadataCacheDoesNotNeedAccountCredentialsAndRejectsSameNameWrongYear() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("episode_names", 0).edit().clear().commit()
        val title = Title(91002, "series", "Example Series", 2020, "", "", emptyList(), listOf(Episode(1, 1, 1, "Example Series", 8.0, "mp4")), "", "mp4")
        val provider = EpisodeMetadata(context) { url -> when {
            url.contains("search/shows") -> """[{"show":{"id":123,"name":"Example Series","premiered":"2005-01-01"}}]"""
            url.contains("api.php") -> """{"query":{"search":[]}}"""
            url.contains("suggestion") -> """{"d":[]}"""
            else -> error("Wrong-year show must not be requested")
        } }
        assertEquals(title, provider.resolve(title)); assertNull(provider.cached(title))
        val manual = MetadataMatch("TVmaze", "123", "Verified manually", 2020)
        val chosen = EpisodeMetadata(context) { """[{"season":1,"number":1,"name":"Original first","summary":"<p>Plain <b>summary</b></p>"}]""" }.choose(title, manual)
        val offline = EpisodeMetadata(context) { error("Cache must not use network") }
        assertEquals("Original first", offline.resolve(title).episodes.first().title)
        assertEquals("Plain summary", chosen.episodes.first().description)
        assertEquals(manual.url, offline.cached(title)!!.source.url)
    }
}
