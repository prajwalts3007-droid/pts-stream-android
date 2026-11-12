package online.ptsmovies.stream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Main-thread request gate: one flight, at most one follow-up, obsolete tickets cannot unlock a newer owner. */
internal class RefreshGate {
    private var sequence = 0L
    private var flight: Long? = null
    private var pending = false
    fun begin(queueLatest: Boolean = false): Long? {
        if (flight != null) { if (queueLatest) pending = true; return null }
        return (++sequence).also { flight = it }
    }
    fun finish(ticket: Long): Boolean {
        if (flight != ticket) return false
        flight = null
        return pending.also { pending = false }
    }
    fun reset() { sequence++; flight = null; pending = false }
}

/** Per-account, bounded LRU; equal immutable libraries reuse the expensive episode index. */
internal class LibraryIndexCache(private val capacity: Int = 3,
    private val build: (List<Title>) -> List<SearchEntry> = ::indexLibrary) {
    init { require(capacity in 1..8) }
    private data class Entry(val titles: List<Title>, val index: List<SearchEntry>)
    private val entries = mutableListOf<Entry>()
    private val mutex = Mutex()
    suspend fun get(titles: List<Title>): List<SearchEntry> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val found = entries.indexOfFirst { it.titles == titles }
            if (found >= 0) return@withLock entries.removeAt(found).also { entries.add(0, it) }.index
            val result = build(titles)
            currentCoroutineContext().ensureActive()
            entries.add(0, Entry(titles, result))
            while (entries.size > capacity) entries.removeAt(entries.lastIndex)
            result
        }
    }
}
