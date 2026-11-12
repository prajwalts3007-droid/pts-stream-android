package online.ptsmovies.stream

import java.text.Normalizer
import java.util.Locale

enum class LibrarySort(val label: String) {
    RELEVANCE("Relevance"), RECENT("Recently added"), TITLE_ASC("Title · A–Z"), TITLE_DESC("Title · Z–A"), YEAR_DESC("Year · newest"), YEAR_ASC("Year · oldest")
}

private val latinMarks = Regex("(?<=[a-z])\\p{M}+")
private val searchPunctuation = Regex("[^\\p{L}\\p{N}\\p{M}]+")
private val searchSpace = Regex("\\s+")
fun searchWords(text: String): String = Normalizer.normalize(text.take(8000), Normalizer.Form.NFKD).lowercase(Locale.ROOT)
    .replace(latinMarks, "").replace(searchPunctuation, " ").trim().replace(searchSpace, " ")

/** Bounded edit distance, not a network lookup or an unconstrained fuzzy scan. */
fun closeSearchWord(query: String, word: String): Boolean {
    if (word.startsWith(query)) return true
    if (query.length < 3 || query.length > 32 || word.length > 40) return false
    val limit = if (query.length >= 6) 2 else 1
    if (kotlin.math.abs(query.length - word.length) > limit) return false
    var previous = IntArray(word.length + 1) { it }
    for (i in query.indices) {
        val row = IntArray(word.length + 1); row[0] = i + 1
        for (j in word.indices) row[j + 1] = minOf(row[j] + 1, previous[j + 1] + 1, previous[j] + if (query[i] == word[j]) 0 else 1)
        if (row.minOrNull()!! > limit) return false
        previous = row
    }
    return previous.last() <= limit
}

data class SearchEntry(val title: Title, val name: String, val episodes: List<String>, val genres: String, val added: Long = addedMillis(title.addedAt))
fun indexLibrary(titles: List<Title>): List<SearchEntry> = titles.map { title ->
    SearchEntry(title, searchWords(title.title), title.episodes.map { searchWords("${it.title} s${it.season} e${it.episode} s${it.season.toString().padStart(2, '0')}e${it.episode.toString().padStart(2, '0')} s${it.season}e${it.episode}") }, searchWords(title.genres.joinToString(" ")))
}

fun searchScore(entry: SearchEntry, query: String, check: () -> Unit = {}): Int {
    check()
    if (query.isBlank()) return 1
    if (entry.name == query) return 10000
    if (entry.name.startsWith(query)) return 9000
    if (entry.name.contains(query)) return 8000
    val tokens = query.split(' ').take(10)
    if (tokens.all { entry.name.contains(it) }) return 7500
    if (entry.episodes.any { it.contains(query) || tokens.all { token -> it.contains(token) } }) return 6500
    if (entry.genres.contains(query)) return 4500
    val nameWords = entry.name.split(' ')
    if (tokens.all { token -> nameWords.any { closeSearchWord(token, it) } }) return 3500
    // Mixed series name + episode name queries are useful, too.
    if (entry.episodes.any { episode -> check(); tokens.all { token -> (nameWords + episode.split(' ')).any { closeSearchWord(token, it) } } }) return 2500
    return 0
}

fun addedMillis(value: String): Long = runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrElse {
    runCatching { java.time.LocalDateTime.parse(value.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0)
}

fun searchLibrary(index: List<SearchEntry>, text: String, sort: LibrarySort, check: () -> Unit = {}): List<Title> {
    val query = searchWords(text.take(160))
    val results = index.mapIndexedNotNull { order, entry -> check(); searchScore(entry, query, check).takeIf { it > 0 }?.let { Triple(entry, it, order) } }
    val comparator = when (sort) {
        LibrarySort.RELEVANCE -> compareByDescending<Triple<SearchEntry, Int, Int>> { it.second }.thenBy { it.third }
        LibrarySort.RECENT -> compareByDescending<Triple<SearchEntry, Int, Int>> { it.first.added }.thenBy { it.third }
        LibrarySort.TITLE_ASC -> compareBy<Triple<SearchEntry, Int, Int>> { it.first.name }.thenBy { it.first.title.id }
        LibrarySort.TITLE_DESC -> compareByDescending<Triple<SearchEntry, Int, Int>> { it.first.name }.thenBy { it.first.title.id }
        LibrarySort.YEAR_DESC -> compareByDescending<Triple<SearchEntry, Int, Int>> { it.first.title.year }.thenBy { it.first.name }
        LibrarySort.YEAR_ASC -> compareBy<Triple<SearchEntry, Int, Int>> { if (it.first.title.year > 0) it.first.title.year else Int.MAX_VALUE }.thenBy { it.first.name }
    }
    return results.sortedWith(comparator).map { it.first.title }
}
