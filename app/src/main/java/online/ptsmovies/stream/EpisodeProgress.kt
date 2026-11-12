package online.ptsmovies.stream

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class EpisodeEdit(val title: Long, val episode: Long, val position: Long, val duration: Long, val completed: Boolean)

/** One durable write for bulk season/title changes, not one growing JSON rewrite per episode. */
internal fun applyEpisodeEdits(current: List<JSONObject>, edits: List<EpisodeEdit>, now: Long): List<JSONObject> {
    val records = current.associateByTo(linkedMapOf()) { "${it.optLong("title_id")}_${it.optLong("episode_id")}" }
    edits.filter { it.title > 0 && it.episode >= 0 }.forEach { edit ->
        val key = "${edit.title}_${edit.episode}"
        val previous = records[key]
        records[key] = JSONObject().put("title_id", edit.title).put("episode_id", edit.episode)
            .put("position", edit.position.coerceIn(0, 604800)).put("duration", edit.duration.coerceIn(0, 604800))
            .put("completed", edit.completed).put("changed_at", maxOf(now, (previous?.optLong("changed_at") ?: 0) + 1))
            .put("event_id", java.util.UUID.randomUUID().toString()).put("pending", true)
    }
    return records.values.toList()
}

fun mergeEpisodeRecords(current: List<JSONObject>, sent: List<JSONObject>, remote: List<JSONObject>): List<JSONObject> {
    fun key(o: JSONObject) = "${o.optLong("title_id")}_${o.optLong("episode_id")}"
    val merged = remote.associateBy(::key).toMutableMap()
    current.forEach { local ->
        val acknowledged = sent.any { it.optString("event_id") == local.optString("event_id") }
        if (local.optBoolean("pending") && !acknowledged) merged[key(local)] = local
        else if (!acknowledged && key(local) !in merged) merged[key(local)] = local
    }
    return merged.values.toList()
}

/** Owner-scoped, offline-first episode records. Separate from legacy title resume. */
class EpisodeProgress(private val context: Context, val owner: Long) {
    private val prefs = context.getSharedPreferences("episode_progress_$owner", Context.MODE_PRIVATE)
    private val database = StateDatabase.get(context)
    init {
        database.migrate(owner, "episode_records", { prefs.getString("records", null) }, { if (prefs.contains("records")) prefs.edit().remove("records").commit() })
        if (database.read(owner, "episode_records") == null) database.write(owner, "episode_records", "[]")
    }
    private val lock get() = locks.getOrPut(owner) { Any() }
    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<Long, Any>()
        private val syncLocks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
    }
    private val legacy = ProgressJournal(context, owner)
    private data class RecordSnapshot(val source: String?, val records: List<JSONObject>)
    @Volatile private var parsed = RecordSnapshot(null, emptyList())
    private var completionSource: List<JSONObject>? = null
    private var completionLegacy = emptySet<String>()
    private var completionSnapshot = emptySet<String>()
    private fun entries(): List<JSONObject> {
        val source = database.read(owner, "episode_records") ?: "[]"
        val previous = parsed
        if (source == previous.source) return previous.records
        val records = JSONArray(source).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        parsed = RecordSnapshot(source, records)
        return records
    }
    private fun key(o: JSONObject) = "${o.optLong("title_id")}_${o.optLong("episode_id")}"
    fun completed(): Set<String> {
        val records = entries()
        val old = legacy.completed()
        if (completionSource !== records || completionLegacy != old) {
            val keys = records.mapTo(hashSetOf(), ::key)
            completionSnapshot = old.filterNot { it in keys }.toSet() + records.filter { it.optBoolean("completed") }.map(::key)
            completionSource = records; completionLegacy = old
        }
        return completionSnapshot
    }
    fun resume(title: Long, episode: Long): Resume? {
        return entries().firstOrNull { key(it) == "${title}_$episode" }?.let {
            if (it.optBoolean("completed")) Resume(episode, 0, it.optLong("duration")) else Resume(episode, it.optLong("position"), it.optLong("duration"))
        }
    }
    fun record(title: Long, episode: Long, position: Long, duration: Long, done: Boolean) = recordBatch(listOf(EpisodeEdit(title, episode, position, duration, done)))
    internal fun recordBatch(edits: List<EpisodeEdit>) = synchronized(lock) {
        if (owner <= 0 || edits.none { it.title > 0 && it.episode >= 0 }) return@synchronized
        val now = System.currentTimeMillis() + prefs.getLong("clock_offset", 0)
        val next = applyEpisodeEdits(entries(), edits, now)
        val serialized = JSONArray(next).toString()
        database.write(owner, "episode_records", serialized)
        parsed = RecordSnapshot(serialized, next)
    }
    fun status(): String = prefs.getString("status", "Not synced yet") ?: "Not synced yet"
    suspend fun sync(api: PtsApi) = withContext(Dispatchers.IO) {
        syncLocks.getOrPut(owner) { Mutex() }.withLock { syncRecords(api) }
    }
    private suspend fun syncRecords(api: PtsApi) {
        if (DeviceState(context).account()?.optLong("id") != owner) return
        try {
            val pending = synchronized(lock) { entries().filter { it.optBoolean("pending") }.take(100) }
            val response = if (pending.isNotEmpty()) api.json("/native/progress", "PUT", JSONObject().put("records", JSONArray(pending))) else api.json("/native/progress")
            if (DeviceState(context).account()?.optLong("id") != owner) return
            val array = response.getJSONArray("records")
            val remote = (0 until array.length()).map { array.getJSONObject(it) }
            synchronized(lock) {
                val current = entries()
                val merged = mergeEpisodeRecords(current, pending, remote)
                val server = response.optLong("server_time")
                val serialized = JSONArray(merged).toString()
                if (serialized != database.read(owner, "episode_records")) database.write(owner, "episode_records", serialized)
                parsed = RecordSnapshot(serialized, merged)
                prefs.edit()
                    .putLong("clock_offset", if (server > 0) server - System.currentTimeMillis() else prefs.getLong("clock_offset",0))
                    .putString("status", if (merged.any { it.optBoolean("pending") }) "More changes waiting to sync" else "Episode progress synced").commit()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { prefs.edit().putString("status", "Offline or sync unavailable · changes kept on this device").apply() }
    }
}
