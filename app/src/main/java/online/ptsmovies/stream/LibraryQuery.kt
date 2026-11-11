package online.ptsmovies.stream

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class IndexedTitles(val source: List<Title>, val index: List<SearchEntry>)
private data class QueryTitles(val source: List<Title>, val result: List<Title>)

/** Large episode indexes and fuzzy/sort/filter scans never run during UI composition. */
@Composable
internal fun rememberLibraryResults(titles: List<Title>, query: String, sort: LibrarySort, filter: LibraryFilter,
    completed: Set<String>, saved: Set<String>): List<Title>? {
    val indexed by produceState<IndexedTitles?>(null, titles) {
        value = withContext(Dispatchers.Default) { IndexedTitles(titles, indexLibrary(titles)) }
    }
    val computed by produceState<QueryTitles?>(null, indexed, query, sort, filter, completed, saved) {
        val current = indexed?.takeIf { it.source == titles } ?: return@produceState
        value = withContext(Dispatchers.Default) { QueryTitles(titles, filterLibrary(searchLibrary(current.index, query, sort), filter, completed, saved)) }
    }
    return computed?.takeIf { it.source == titles }?.result
        ?: titles.takeIf { query.isBlank() && sort == LibrarySort.RELEVANCE && filter == LibraryFilter() }
}
