package online.ptsmovies.stream

fun normalizeLanguage(code: String): String {
    val language = code.lowercase(java.util.Locale.ROOT).substringBefore('-').trim()
    return mapOf("eng" to "en", "hin" to "hi", "tam" to "ta", "tel" to "te", "kan" to "kn", "mal" to "ml", "mar" to "mr", "ben" to "bn")[language] ?: language
}
fun titleLanguages(value: org.json.JSONObject): List<String> {
    val found = mutableSetOf<String>()
    fun add(code: String) { normalizeLanguage(code).takeIf { it.isNotBlank() && it != "und" && it != "null" }?.let(found::add) }
    val explicit = value.optJSONArray("languages")
    repeat(explicit?.length() ?: 0) { add(explicit!!.optString(it)) }
    fun tracks(o: org.json.JSONObject) {
        val audio = o.optJSONArray("audio") ?: runCatching { org.json.JSONArray(o.optString("audio")) }.getOrNull()
        repeat(audio?.length() ?: 0) { i -> audio!!.optJSONObject(i)?.let { add(it.optString("lang", it.optString("language"))) } }
    }
    tracks(value)
    val episodes = value.optJSONArray("episodes")
    repeat(episodes?.length() ?: 0) { episodes!!.optJSONObject(it)?.let(::tracks) }
    return found.sorted()
}

fun relatedTitles(title: Title, library: List<Title>): List<Title> = library.asSequence()
    .filter { it.id != title.id && it.kind == title.kind }
    .sortedWith(compareByDescending<Title> { candidate -> candidate.genres.count { genre -> title.genres.any { it.equals(genre, true) } } }
        .thenBy { if (title.year > 0 && it.year > 0) kotlin.math.abs(it.year - title.year) else Int.MAX_VALUE }
        .thenBy { it.title.lowercase(java.util.Locale.ROOT) }).take(8).toList()

data class LibraryFilter(val year: Int = 0, val language: String = "", val unwatched: Boolean = false, val downloaded: Boolean = false)

fun filterLibrary(titles: List<Title>, filter: LibraryFilter, completed: Set<String>, saved: Set<String>): List<Title> = titles.filter { title ->
    val done = if (title.kind == "movie") "${title.id}_0" in completed
        else title.episodes.isNotEmpty() && title.episodes.all { "${title.id}_${it.id}" in completed }
    (filter.year == 0 || title.year == filter.year) &&
        (filter.language.isBlank() || title.languages.any { it.equals(filter.language, true) }) &&
        (!filter.unwatched || !done) && (!filter.downloaded || saved.any { it.substringBefore('_') == title.id.toString() })
}
