package online.ptsmovies.stream

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

data class IngestEpisode(val season: Int, val episode: Int, val link: String = "", val member: String = "", val selected: Boolean = true) {
    fun json() = JSONObject().put("season", season).put("episode", episode).put("download_url", link.trim()).apply {
        if (member.isNotBlank()) put("zip_member", member)
    }
}

private val numberedEpisodes = listOf(
    Regex("(?:^|[^a-z0-9])s[ ._\\-]*(\\d{1,3})[ ._\\-]*e[ ._\\-]*(\\d{1,3})(?!\\d)", RegexOption.IGNORE_CASE),
    Regex("(?:^|[^\\d])(\\d{1,3})x(\\d{1,3})(?!\\d)", RegexOption.IGNORE_CASE),
    Regex("(?:^|[^a-z0-9])season[ ._\\-]*(\\d{1,3})[ ._\\-]*episode[ ._\\-]*(\\d{1,3})(?!\\d)", RegexOption.IGNORE_CASE))

/** Same bounds/conflict rules as the deployed web episodeFromPath. Never guess order. */
fun episodeNumbers(path: String): Pair<Int, Int> {
    val label = path.substringAfterLast('/').substringBeforeLast('.', path.substringAfterLast('/'))
    val numbered = numberedEpisodes.flatMap { it.findAll(label).toList() }
    val folders = Regex("/(?:season[ ._\\-]*|s)(\\d{1,3})(?=/|$)", RegexOption.IGNORE_CASE).findAll(path).map { it.groupValues[1].toInt() }.toList()
    val episode = Regex("(?:^|[^a-z0-9])(?:episode|ep)[ ._\\-]*(\\d{1,3})(?!\\d)|^(\\d{1,3})(?:[ ._\\-]|$)", RegexOption.IGNORE_CASE).find(label)
        ?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toInt()
    val s = numbered.firstOrNull()?.groupValues?.get(1)?.toInt() ?: folders.firstOrNull()
    val e = numbered.firstOrNull()?.groupValues?.get(2)?.toInt() ?: episode
    require(s != null && s in 1..100 && e != null && e in 1..500) { "Episode filename needs a season and episode, for example S02E05" }
    require(numbered.all { it.groupValues[1].toInt() == s && it.groupValues[2].toInt() == e } && folders.all { it == s } && (episode == null || episode == e)) { "Conflicting season or episode numbers" }
    return s to e
}

fun parseEpisodeLinks(text: String): List<IngestEpisode> {
    require(text.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "links.txt exceeds 2 MiB" }
    val lines = text.removePrefix("\uFEFF").lines().map(String::trim).filter { it.isNotBlank() && !it.startsWith('#') }
    require(lines.size <= 500) { "Maximum 500 entries" }
    val seen = mutableSetOf<Pair<Int, Int>>()
    val result = lines.mapNotNull { line ->
        val tab = line.indexOf('\t'); val link = if (tab < 0) line else line.substring(tab + 1).trim()
        require(link.length <= 4096 && validSourceLink(link)) { "Invalid direct link" }
        val uri = URI(link)
        val path = if (tab < 0) URLDecoder.decode(uri.rawPath.replace("+", "%2B"), "UTF-8") else "/" + line.substring(0, tab).trim()
        if (tab >= 0) require(uri.host == "pixeldrain.com" && Regex("/api/file/[A-Za-z0-9]{4,32}").matches(uri.path) && !path.contains("..") && path.none { it.code < 32 || it.code == 127 }) { "Invalid member/direct link pair" }
        if (!Regex("(?i).*\\.(mp4|m4v|mkv|mov|webm|avi|ts)$").matches(path)) return@mapNotNull null
        val (s, e) = episodeNumbers(path)
        require(seen.add(s to e)) { "Repeated S${s}E${e}" }
        IngestEpisode(s, e, link)
    }
    require(result.isNotEmpty()) { "No numbered video episodes found" }
    return result.sortedWith(compareBy({ it.season }, { it.episode }))
}

fun zipEpisodeLinks(link: String, response: JSONObject): List<IngestEpisode> {
    require(validSourceLink(link) && URI(link).scheme == "https") { "ZIP requires public HTTPS and byte ranges" }
    val entries = response.getJSONArray("members")
    require(entries.length() in 1..500) { "ZIP has no video members or exceeds 500 entries" }
    val seen = mutableSetOf<Pair<Int, Int>>()
    return (0 until entries.length()).mapNotNull { i ->
        val name = entries.getJSONObject(i).getString("name")
        require(!name.startsWith('/') && !name.contains('\\') && name.split('/').none { it == ".." } && name.none { it.code < 32 }) { "Unsafe ZIP member" }
        val numbers = runCatching { episodeNumbers("/$name") }.getOrNull() ?: return@mapNotNull null
        require(seen.add(numbers)) { "ZIP has ambiguous duplicate episode numbers" }
        IngestEpisode(numbers.first, numbers.second, link, name)
    }.also { require(it.isNotEmpty()) { "ZIP filenames need season/episode numbers" } }.sortedWith(compareBy({ it.season }, { it.episode }))
}

fun ingestPayload(title: JSONObject, items: List<JSONObject>, force: Boolean = false): JSONObject {
    require(items.isNotEmpty() && items.size <= 500) { "Choose 1–500 sources" }
    require(items.all { validSourceLink(it.optString("download_url")) }) { "Invalid source link" }
    return JSONObject().apply {
        listOf("imdb_id", "title", "year", "end_year", "type", "poster", "stars").forEach { if (title.has(it)) put(it, title.get(it)) }
        require(optString("title").isNotBlank() && optString("type") in listOf("movie", "series")) { "Select a matched movie or series" }
        put("items", JSONArray(items)); put("force", force)
    }
}
