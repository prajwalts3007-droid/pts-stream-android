package online.ptsmovies.stream

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Offline edits keep the latest value per title. Never replay another owner's journal. */
class ProgressJournal(context: Context, owner: Long) {
    private val prefs = context.getSharedPreferences("progress_journal_$owner", Context.MODE_PRIVATE)
    private val lock = Any()
    fun completed(): Set<String> = prefs.getStringSet("completed", emptySet())!!.toSet()
    fun markCompleted(key: String) { prefs.edit().putStringSet("completed", completed() + key).apply() }
    fun clearedTitles(): Set<Long> = prefs.getStringSet("cleared_titles", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
    fun begin(title: Long) = synchronized(lock) {
        prefs.edit().putStringSet("cleared_titles", (clearedTitles() - title).map { it.toString() }.toSet()).commit()
        Unit
    }
    fun pending(): List<JSONObject> = synchronized(lock) {
        val a = runCatching { JSONArray(prefs.getString("pending", "[]")) }.getOrDefault(JSONArray())
        (0 until a.length()).map { a.getJSONObject(it) }
    }
    fun put(title: Long, episode: Long?, position: Long, duration: Long, clear: Boolean = false) = synchronized(lock) {
        if (clear) prefs.edit().putStringSet("cleared_titles", (clearedTitles() + title).map { it.toString() }.toSet()).commit()
        val value = JSONObject().put("title_id", title).put("episode_id", episode ?: 0).put("position", position)
            .put("duration", duration).put("clear", clear).put("revision", java.util.UUID.randomUUID().toString())
        prefs.edit().putString("pending", JSONArray(pending().filterNot { it.optLong("title_id") == title } + value).toString()).commit()
        Unit
    }
    suspend fun flush(api: PtsApi) {
        for (value in pending()) {
            if (value.optBoolean("clear")) api.json("/stream/progress/clear", "POST", JSONObject().put("title_id", value.getLong("title_id")))
            else api.progress(value.getLong("title_id"), value.optLong("episode_id"), value.optLong("position"), value.optLong("duration"))
            synchronized(lock) {
                prefs.edit().putString("pending", JSONArray(pending().filterNot { it.optString("revision") == value.optString("revision") }).toString()).commit()
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
