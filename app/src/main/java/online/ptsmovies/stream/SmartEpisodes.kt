package online.ptsmovies.stream

fun smartEpisodes(title: Title, current: Episode, count: Int, completed: Set<String>, excluded: Set<String>): List<Episode> {
    if (title.kind != "series" || count !in 1..3) return emptyList()
    val ordered = title.episodes.sortedWith(compareBy({ it.season }, { it.episode }))
    val index = ordered.indexOfFirst { it.id == current.id }
    if (index < 0) return emptyList()
    return ordered.drop(index + 1).filter {
        it.container != "hls" && "${title.id}_${it.id}" !in completed
    }.take(count).filter { "${title.id}_${it.id}" !in excluded }
}
