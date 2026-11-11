package online.ptsmovies.stream

import java.util.Locale

data class HomeShelves(val featured: List<Title>, val continuing: List<Title>, val movies: List<Title>, val shows: List<Title>)

fun recentTitles(titles: List<Title>): List<Title> = titles.withIndex()
    .map { Triple(it.value, addedMillis(it.value.addedAt), it.index) }
    .sortedWith(compareByDescending<Triple<Title, Long, Int>> { it.second }.thenBy { it.third }).map { it.first }

fun homeShelves(titles: List<Title>, progress: Map<Long, Resume>): HomeShelves {
    val recent = recentTitles(titles)
    return HomeShelves(titles.take(5), titles.filter { t -> progress[t.id]?.let { it.duration > 0 && it.seconds > 5 && it.seconds < it.duration - 15 } == true },
        recent.filter { it.kind == "movie" }.take(10), recent.filter { it.kind == "series" }.take(10))
}

fun libraryFiltersActive(filter: LibraryFilter, sort: LibrarySort, genre: String = "") =
    filter != LibraryFilter() || sort != LibrarySort.RELEVANCE || genre.isNotBlank()

/** Track labels are not trusted language metadata: they often contain codecs/bitrate. */
private val trackLanguages: Map<String, String> = buildMap {
    Locale.getISOLanguages().forEach { code ->
        put(code, code)
        runCatching { Locale.forLanguageTag(code).isO3Language }.getOrNull()?.let { put(it, code) }
    }
    putAll(mapOf("fre" to "fr", "ger" to "de", "chi" to "zh", "dut" to "nl", "cze" to "cs", "rum" to "ro", "gre" to "el"))
}
fun trackLanguageName(code: String?, locale: Locale = Locale.getDefault()): String {
    val normalized = code?.trim()?.replace('_', '-')?.let(::normalizeLanguage).orEmpty()
    if (normalized.isBlank() || normalized in setOf("und", "null", "zxx")) return "Unknown language"
    val language = Locale.forLanguageTag(trackLanguages[normalized] ?: return "Unknown language")
    return language.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

fun streamOnlyMode(role: String?, requested: String) = if (role == "admin" && requested in setOf("Hub", "Files", "Stream")) requested else "Stream"
fun canRequestOfflinePreparation(owner: Long, userId: Long, role: String?) = owner > 0 && owner == userId && role in setOf("user", "admin")
fun sectionBrand(mode: String) = when (mode) { "Stream" -> "PTS Stream"; "Files" -> "PTSFiles"; else -> "PTS Library" }

fun posterDecodeWidth(pixels: Int, hero: Boolean) = (((pixels.coerceIn(1, 4096) + 63) / 64) * 64).coerceIn(128, if (hero) 1280 else 768)

/** Resize only a documented public CDN path; never alter signed/unknown URLs. */
fun posterRequestUrl(url: String, pixels: Int): String = runCatching {
    val uri = java.net.URI(url)
    if (uri.scheme != "https" || uri.host != "image.tmdb.org" || uri.rawQuery != null || uri.rawUserInfo != null) return@runCatching url
    val match = Regex("^/t/p/(original|w[0-9]+)/").find(uri.rawPath) ?: return@runCatching url
    val size = if (pixels <= 342) "w342" else if (pixels <= 500) "w500" else "w780"
    url.replaceFirst(match.value, "/t/p/$size/")
}.getOrDefault(url)
