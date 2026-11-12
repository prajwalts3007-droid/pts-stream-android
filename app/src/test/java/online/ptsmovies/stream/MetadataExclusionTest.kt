package online.ptsmovies.stream

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MetadataExclusionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun title(name: String) = Title(8123, "series", name, 2013, "", "", emptyList(), listOf(Episode(812301, 1, 1, "Episode 1", 60.0, "mp4")), "hevc", "mp4")
    private fun seed(t: Title): String {
        val key = "${t.id}_${normalizedName(t.title)}_${t.year}"
        val record = JSONObject().put("checked", System.currentTimeMillis())
            .put("source", JSONObject().put("provider", "TVmaze").put("id", "1").put("name", t.title).put("year", t.year))
            .put("episodes", JSONArray().put(JSONObject().put("season", 1).put("episode", 1).put("name", "Cached name")))
        context.getSharedPreferences("episode_names", 0).edit().putString(key, record.toString()).commit()
        return key
    }

    @Test fun removesExcludedCacheWithoutChangingOtherMatchesOrCatalogueNames() {
        val excluded = title("Peaky Blinders"); val other = title("Another show")
        val excludedKey = seed(excluded); val otherKey = seed(other)
        val metadata = EpisodeMetadata(context) { error("No lookup should run") }
        assertNull(metadata.cached(excluded))
        val prefs = context.getSharedPreferences("episode_names", 0)
        assertFalse(prefs.contains(excludedKey)); assertTrue(prefs.contains(otherKey))
        assertEquals("Cached name", metadata.cached(other)?.episodes?.single()?.name)
        assertEquals("Episode 1", applyEpisodeNames(excluded, metadata.cached(excluded)).episodes.single().title)
    }

    @Test fun excludedSourceCannotBeFetchedAgain() = runBlocking {
        var requests = 0
        val metadata = EpisodeMetadata(context) { requests++; "[]" }
        try {
            metadata.choose(title("Peaky Blinders"), MetadataMatch("TVmaze", "1", "Peaky Blinders"))
            fail("Excluded source must be refused")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, requests)
    }

    @Test fun cachedGoogleConfigurationDistinguishesUnknownFromDisabled() {
        val prefs = NativePreferences(context)
        assertEquals(false to "", prefs.googleConfig())
        prefs.googleConfig(true, "example.apps.googleusercontent.com")
        assertEquals(true to "example.apps.googleusercontent.com", NativePreferences(context).googleConfig())
        prefs.googleConfig(true, "")
        assertEquals(true to "", NativePreferences(context).googleConfig())
    }
}
