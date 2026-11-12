package online.ptsmovies.stream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

data class EpisodeName(val season: Int, val episode: Int, val name: String, val description: String = "")
data class MetadataMatch(val provider: String, val id: String, val name: String, val year: Int = 0, val language: String = "", val imdb: String = "") {
    val url get() = if (provider == "TVmaze") "https://www.tvmaze.com/shows/$id" else "https://en.wikipedia.org/wiki/${encodeMetadata(id).replace("+", "_")}"
}
data class EpisodeNames(val source: MetadataMatch, val episodes: List<EpisodeName>)
fun encodeMetadata(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
private val metadataPunctuation = Regex("\\p{M}|[^\\p{L}\\p{N}]")
private val episodePrefix = Regex("(?i)^(?:(?:s\\d+\\s*)?e(?:p(?:isode)?)?\\s*\\d+|episode\\s*\\d+)\\s*[-–—:·.]*\\s*")
private val episodeNumberOnly = Regex("(?i)^s\\d+e\\d+$")
fun normalizedName(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
    .lowercase(java.util.Locale.ROOT).replace(metadataPunctuation, "")

/**
 * User-requested source exclusions. Cached Peaky Blinders TVmaze details must
 * not be re-applied; catalogue names remain authoritative for that source.
 */
internal fun metadataSourceBlocked(title: String, provider: String) =
    normalizedName(title) == "peakyblinders" && provider == "TVmaze"

fun isGeneratedEpisodeName(name: String, series: String): Boolean {
    val trimmed = name.replace(episodePrefix, "")
    return name.isBlank() || trimmed.isBlank() || normalizedName(trimmed) == normalizedName(series) || episodeNumberOnly.matches(name.trim())
}

fun applyEpisodeNames(title: Title, metadata: EpisodeNames?, previous: EpisodeNames? = null): Title {
    if (metadata == null) return title
    fun index(names: List<EpisodeName>) = names.groupBy { it.season to it.episode }.mapValues { it.value.singleOrNull() }
    val names = index(metadata.episodes); val old = previous?.let { index(it.episodes) }.orEmpty()
    return title.copy(episodes = title.episodes.map { ep ->
    val match = names[ep.season to ep.episode]
    val imported = old[ep.season to ep.episode]
    if (match == null || (!isGeneratedEpisodeName(ep.title, title.title) && ep.title != imported?.name)) ep else ep.copy(title = match.name, description = ep.description.ifBlank { match.description })
})
}

fun parseTvmazeNames(array: JSONArray): List<EpisodeName> = (0 until array.length()).mapNotNull { i ->
    val ep = array.getJSONObject(i); val s = ep.optInt("season"); val e = ep.optInt("number"); val name = ep.optString("name")
    if (s !in 1..100 || e !in 1..500 || name.isBlank() || name == "null" || Regex("(?i)^(episode|tba|tbd)(?:[ .#-]*\\d+)?$").matches(name)) null
    else EpisodeName(s, e, name, Jsoup.parse(ep.optString("summary").takeUnless { it == "null" }.orEmpty()).text().take(4000))
}.groupBy { it.season to it.episode }.values.mapNotNull { it.singleOrNull() }

/** Parse only numbered episode tables, never cast/soundtrack lists or inferred row order. */
fun parseWikipediaNames(html: String): List<EpisodeName> {
    val doc = Jsoup.parse(html); doc.select("sup.reference, .mw-editsection").remove()
    var season = 1
    val result = mutableListOf<EpisodeName>()
    doc.select("h2, h3, h4, table.wikiepisodetable").forEach { element ->
        if (element.tagName() != "table") {
            Regex("(?i)\\bseason[ _]+(\\d{1,3})\\b").find(element.text())?.groupValues?.get(1)?.toIntOrNull()?.let { season = it }
        } else {
            element.select("tr.vevent").forEach { row ->
                val summary = row.selectFirst(".summary") ?: return@forEach
                val cells = row.children().filter { it.tagName() in listOf("td", "th") }
                val before = cells.takeWhile { it !== summary }
                // Last numeric cell before Title is episode-in-season, not overall-series number.
                val number = before.lastOrNull()?.text()?.trim()?.takeIf { Regex("\\d{1,3}").matches(it) }?.toIntOrNull() ?: return@forEach
                val name = summary.text().trim().trim('"', '“', '”').trim()
                if (season in 1..100 && number in 1..500 && name.isNotBlank()) {
                    val next = row.nextElementSibling()
                    val description = if (next?.hasClass("expand-child") == true) next.selectFirst(".description")?.text().orEmpty().take(4000) else ""
                    result += EpisodeName(season, number, name, description)
                }
            }
        }
    }
    return result.groupBy { it.season to it.episode }.values.mapNotNull { it.singleOrNull() }
}

/** Device-only, public metadata. Separate client: never sends PTS cookies or signed media URLs. */
class EpisodeMetadata(context: Context, private val fetchOverride: ((String) -> String)? = null) {
    private val prefs = context.getSharedPreferences("episode_names", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private fun key(title: Title) = "${title.id}_${normalizedName(title.title)}_${title.year}"
    private fun get(url: String): String {
        fetchOverride?.let { return it(url) }
        val uri = java.net.URI(url)
        require(uri.scheme == "https" && uri.host in listOf("api.tvmaze.com", "en.wikipedia.org", "v3.sg.media-imdb.com"))
        client.newCall(Request.Builder().url(url).header("User-Agent", "PTSLibrary/1.0 (https://github.com/prajwalts3007-droid/pts-stream-android; metadata lookup)").build()).execute().use { response ->
            check(response.isSuccessful) { "${uri.host}: HTTP ${response.code}" }
            return response.body!!.byteStream().use { it.readBytesBounded(3 * 1024 * 1024).toString(Charsets.UTF_8) }
        }
    }
    private fun blocked(title: Title, provider: String) = metadataSourceBlocked(title.title, provider)

    fun cached(title: Title): EpisodeNames? = runCatching {
        val record = JSONObject(prefs.getString(key(title), "")!!)
        val source = record.getJSONObject("source")
        val provider = source.getString("provider")
        if (blocked(title, provider)) {
            prefs.edit().remove(key(title)).apply()
            null
        } else EpisodeNames(MetadataMatch(provider, source.getString("id"), source.getString("name"), source.optInt("year")),
            record.getJSONArray("episodes").let { rows -> (0 until rows.length()).map { val e = rows.getJSONObject(it); EpisodeName(e.getInt("season"), e.getInt("episode"), e.getString("name"), e.optString("description")) } })
    }.getOrNull()
    private fun store(title: Title, names: EpisodeNames?) {
        val record = JSONObject().put("checked", System.currentTimeMillis())
        names?.let { data -> record.put("source", JSONObject().put("provider", data.source.provider).put("id", data.source.id).put("name", data.source.name).put("year", data.source.year))
            .put("episodes", JSONArray(data.episodes.map { JSONObject().put("season", it.season).put("episode", it.episode).put("name", it.name).put("description", it.description) })) }
        prefs.edit().putString(key(title), record.toString()).apply()
    }
    suspend fun candidates(query: String): List<MetadataMatch> = withContext(Dispatchers.IO) {
        val tv = runCatching { JSONArray(get("https://api.tvmaze.com/search/shows?q=${encodeMetadata(query)}")).let { rows ->
            (0 until rows.length()).take(6).map { val s = rows.getJSONObject(it).getJSONObject("show"); MetadataMatch("TVmaze", s.getInt("id").toString(), s.getString("name"), s.optString("premiered").take(4).toIntOrNull() ?: 0, s.optString("language"), s.optJSONObject("externals")?.optString("imdb").orEmpty()) }
        } }.getOrDefault(emptyList())
        val wiki = runCatching { JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=${encodeMetadata("$query television series")}"))
            .getJSONObject("query").getJSONArray("search").let { rows -> (0 until rows.length()).map { val s = rows.getJSONObject(it); MetadataMatch("Wikipedia", s.getString("title"), s.getString("title")) } }
        }.getOrDefault(emptyList())
        tv + wiki
    }
    suspend fun choose(title: Title, source: MetadataMatch): EpisodeNames = withContext(Dispatchers.IO) {
        require(!blocked(title, source.provider)) { "This metadata source does not match this title. Choose another source." }
        val names = if (source.provider == "TVmaze") {
            require(source.id.matches(Regex("[0-9]+")))
            parseTvmazeNames(JSONArray(get("https://api.tvmaze.com/shows/${source.id}/episodes")))
        } else {
            require(source.provider == "Wikipedia")
            parseWikipediaNames(get(source.url))
        }
        require(names.any { n -> title.episodes.any { it.season == n.season && it.episode == n.episode } }) { "This page has no matching numbered episode names. Try another source." }
        EpisodeNames(source, names).also { store(title, it) }
    }
    suspend fun resolve(title: Title): Title = withContext(Dispatchers.IO) {
        if (title.kind != "series" || title.episodes.none { isGeneratedEpisodeName(it.title, title.title) }) return@withContext title
        mutex.withLock {
            val record = runCatching { JSONObject(prefs.getString(key(title), "")!!) }.getOrNull()
            if (record != null && System.currentTimeMillis() - record.optLong("checked") < TimeUnit.DAYS.toMillis(if (record.has("source")) 7 else 1)) return@withLock applyEpisodeNames(title, cached(title))
            val found = candidates(title.title)
            val imdb = runCatching {
                val suggestions = JSONObject(get("https://v3.sg.media-imdb.com/suggestion/x/${encodeMetadata(title.title)}.json")).optJSONArray("d") ?: JSONArray()
                (0 until suggestions.length()).map { suggestions.getJSONObject(it) }.filter {
                    normalizedName(it.optString("l")) == normalizedName(title.title) && (title.year <= 0 || it.optInt("y") == title.year) && it.optString("qid") in listOf("tvSeries", "tvMiniSeries")
                }.singleOrNull()?.optString("id").orEmpty()
            }.getOrDefault("")
            // Automatic matching is strict: same show name and debut year, not first search result.
            val exact = found.filter { it.provider == "TVmaze" && !blocked(title, it.provider) && ((normalizedName(it.name) == normalizedName(title.title) && it.year == title.year && title.year > 0) || (imdb.startsWith("tt") && it.imdb == imdb)) }.singleOrNull()
            if (exact != null) runCatching { choose(title, exact) }.getOrNull()?.let { return@withLock applyEpisodeNames(title, it) }
            // Wikipedia infobox title/year must match before its public episode tables are accepted.
            for (candidate in found.filter { it.provider == "Wikipedia" }.take(2)) {
                val html = runCatching { get(candidate.url) }.getOrNull() ?: continue
                val doc = Jsoup.parse(html)
                val name = doc.selectFirst(".infobox .summary")?.text().orEmpty()
                val firstDate = doc.select(".infobox .bday, .infobox .dtstart").firstOrNull()?.text().orEmpty()
                val sameIdentity = imdb.startsWith("tt") && doc.select("a[href]").any { Regex("https?://(?:www\\.)?imdb\\.com/title/$imdb/?(?:[?#].*)?").matches(it.attr("href")) }
                if (!sameIdentity && (normalizedName(name) != normalizedName(title.title) || firstDate.take(4).toIntOrNull() != title.year || title.year <= 0)) continue
                val names = parseWikipediaNames(html)
                if (names.isNotEmpty()) { val data = EpisodeNames(candidate.copy(year = title.year), names); store(title, data); return@withLock applyEpisodeNames(title, data) }
            }
            if (cached(title) == null) store(title, null)
            applyEpisodeNames(title, cached(title))
        }
    }
}
