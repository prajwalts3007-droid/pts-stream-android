package online.ptsmovies.stream

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Offline edits keep the latest value per title. Never replay another owner's journal. */
class ProgressJournal(private val context: Context, private val owner: Long) {
    private val prefs = context.getSharedPreferences("progress_journal_$owner", Context.MODE_PRIVATE)
    private val database = StateDatabase.get(context)
    init {
        database.migrate(owner, "progress_pending", { prefs.getString("pending", null) }, { if (prefs.contains("pending")) prefs.edit().remove("pending").commit() })
        if (database.read(owner, "progress_pending") == null) database.write(owner, "progress_pending", "[]")
    }
    private val lock get() = locks.getOrPut(owner) { Any() }
    companion object { private val locks = java.util.concurrent.ConcurrentHashMap<Long, Any>() }
    private var parsedSource: String? = null
    private var parsedPending = emptyList<JSONObject>()
    fun completed(): Set<String> = prefs.getStringSet("completed", emptySet())!!.toSet()
    fun markCompleted(key: String) = synchronized(lock) { prefs.edit().putStringSet("completed", completed() + key).apply() }
    fun clearedTitles(): Set<Long> = prefs.getStringSet("cleared_titles", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
    fun begin(title: Long) = synchronized(lock) {
        prefs.edit().putStringSet("cleared_titles", (clearedTitles() - title).map { it.toString() }.toSet()).commit()
        Unit
    }
    fun pending(): List<JSONObject> = synchronized(lock) {
        val source = database.read(owner, "progress_pending") ?: "[]"
        if (source != parsedSource) {
            val a = runCatching { JSONArray(source) }.getOrDefault(JSONArray())
            parsedPending = (0 until a.length()).map { a.getJSONObject(it) }
            parsedSource = source
        }
        parsedPending
    }
    fun put(title: Long, episode: Long?, position: Long, duration: Long, clear: Boolean = false) = synchronized(lock) {
        if (clear) prefs.edit().putStringSet("cleared_titles", (clearedTitles() + title).map { it.toString() }.toSet()).commit()
        val value = JSONObject().put("title_id", title).put("episode_id", episode ?: 0).put("position", position)
            .put("duration", duration).put("clear", clear).put("revision", java.util.UUID.randomUUID().toString())
        database.write(owner, "progress_pending", JSONArray(pending().filterNot { it.optLong("title_id") == title } + value).toString())
        Unit
    }
    suspend fun flush(api: PtsApi) = withContext(Dispatchers.IO) { flushPending(api) }
    private suspend fun flushPending(api: PtsApi) {
        for (value in pending()) {
            if (DeviceState(context).account()?.optLong("id") != owner) return
            if (value.optBoolean("clear")) api.json("/stream/progress/clear", "POST", JSONObject().put("title_id", value.getLong("title_id")))
            else api.progress(value.getLong("title_id"), value.optLong("episode_id"), value.optLong("position"), value.optLong("duration"))
            synchronized(lock) {
                database.write(owner, "progress_pending", JSONArray(pending().filterNot { it.optString("revision") == value.optString("revision") }).toString())
            }
        }
    }
    fun overlay(library: Library): Library {
        val progress = library.progress.toMutableMap()
        pending().forEach { value ->
            val id = value.optLong("title_id")
            if (value.optBoolean("clear")) progress.remove(id)
            else progress[id] = Resume(value.optLong("episode_id"), value.optLong("position"), value.optLong("duration"))
        }
        return library.copy(progress = progress)
    }
}
