package online.ptsmovies.stream

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Owner-scoped, offline-first episode records. Separate from legacy title resume. */
class EpisodeProgress(private val context: Context, val owner: Long) {
    private val prefs = context.getSharedPreferences("episode_progress_$owner", Context.MODE_PRIVATE)
    private val lock get() = locks.getOrPut(owner) { Any() }
    companion object { private val locks = java.util.concurrent.ConcurrentHashMap<Long, Any>() }
    private fun entries(): List<JSONObject> = runCatching { JSONArray(prefs.getString("records", "[]")) }.getOrDefault(JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun key(o: JSONObject) = "${o.optLong("title_id")}_${o.optLong("episode_id")}"
    fun completed(): Set<String> = synchronized(lock) {
        val records = entries()
        ProgressJournal(context, owner).completed().filter { old -> records.none { key(it) == old } }.toSet() + records.filter { it.optBoolean("completed") }.map(::key)
    }
    fun resume(title: Long, episode: Long): Resume? = synchronized(lock) {
        entries().firstOrNull { key(it) == "${title}_$episode" }?.let {
            if (it.optBoolean("completed")) Resume(episode, 0, it.optLong("duration")) else Resume(episode, it.optLong("position"), it.optLong("duration"))
        }
    }
    fun record(title: Long, episode: Long, position: Long, duration: Long, done: Boolean) = synchronized(lock) {
        if (owner <= 0 || title <= 0 || episode < 0) return@synchronized
        val all = entries(); val previous = all.firstOrNull { key(it) == "${title}_$episode" }
        val now = System.currentTimeMillis() + prefs.getLong("clock_offset", 0)
        val value = JSONObject().put("title_id", title).put("episode_id", episode).put("position", position.coerceIn(0, 604800))
            .put("duration", duration.coerceIn(0, 604800)).put("completed", done)
            .put("changed_at", maxOf(now, (previous?.optLong("changed_at") ?: 0) + 1))
            .put("event_id", java.util.UUID.randomUUID().toString()).put("pending", true)
        prefs.edit().putString("records", JSONArray(all.filterNot { key(it) == key(value) } + value).toString()).commit()
    }
    fun status(): String = prefs.getString("status", "Not synced yet") ?: "Not synced yet"
    suspend fun sync(api: PtsApi) {
        if (DeviceState(context).account()?.optLong("id") != owner) return
        try {
            val pending = synchronized(lock) { entries().filter { it.optBoolean("pending") }.take(100) }
            val response = if (pending.isNotEmpty()) api.json("/native/progress", "PUT", JSONObject().put("records", JSONArray(pending))) else api.json("/native/progress")
            if (DeviceState(context).account()?.optLong("id") != owner) return
            val array = response.getJSONArray("records")
            val remote = (0 until array.length()).map { array.getJSONObject(it) }
            synchronized(lock) {
                val current = entries()
                val merged = remote.associateBy(::key).toMutableMap()
                current.forEach { local ->
                    val wasSent = pending.any { it.optString("event_id") == local.optString("event_id") }
                    if (local.optBoolean("pending") && !wasSent) merged[key(local)] = local
                    else if (!wasSent && key(local) !in merged) merged[key(local)] = local
                }
                val server = response.optLong("server_time")
                prefs.edit().putString("records", JSONArray(merged.values.toList()).toString())
                    .putLong("clock_offset", if (server > 0) server - System.currentTimeMillis() else prefs.getLong("clock_offset",0))
                    .putString("status", if (merged.values.any { it.optBoolean("pending") }) "More changes waiting to sync" else "Episode progress synced").commit()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { prefs.edit().putString("status", "Offline or sync unavailable · changes kept on this device").apply() }
    }
}
