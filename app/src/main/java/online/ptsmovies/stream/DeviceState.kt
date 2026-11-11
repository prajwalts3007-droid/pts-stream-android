package online.ptsmovies.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Device preferences are deliberately separate from server account/playback preferences. */
class DeviceState(context: Context) {
    private val prefs = context.getSharedPreferences("native_ui", Context.MODE_PRIVATE)
    var theme by mutableStateOf(prefs.getString("theme", "system") ?: "system")
        private set
    fun theme(value: String) { require(value in listOf("system", "light", "dark")); theme = value; prefs.edit().putString("theme", value).apply() }
    fun view(tab: String) = prefs.getString("view_$tab", "grid") ?: "grid"
    fun view(tab: String, value: String) { prefs.edit().putString("view_$tab", value).apply() }
    fun sort(tab: String) = prefs.getString("sort_$tab", LibrarySort.RELEVANCE.name) ?: LibrarySort.RELEVANCE.name
    fun sort(tab: String, value: String) { if (LibrarySort.entries.any { it.name == value }) prefs.edit().putString("sort_$tab", value).apply() }
    fun account(): JSONObject? = runCatching { JSONObject(prefs.getString("account", "")!!) }.getOrNull()
    fun account(user: JSONObject) { prefs.edit().putString("account", user.toString()).apply() }
    fun library(owner: Long): Library? = runCatching { parseLibrary(JSONObject(prefs.getString("library_$owner", "")!!)) }.getOrNull()
    fun library(owner: Long, value: Library) { prefs.edit().putString("library_$owner", value.toJson().toString()).apply() }
    fun librarySerialized(owner: Long, value: String) { prefs.edit().putString("library_$owner", value).apply() }
    fun navigation(): JSONObject = runCatching { JSONObject(prefs.getString("navigation", "{}")!!) }.getOrDefault(JSONObject())
    fun navigation(value: JSONObject) { prefs.edit().putString("navigation", value.toString()).apply() }
    fun clearSession() {
        val owner = account()?.optLong("id")
        prefs.edit().remove("account").remove("navigation").apply { if (owner != null) remove("library_$owner") }.commit()
    }
}

fun Episode.toJson() = JSONObject().put("id", id).put("season", season).put("episode", episode)
    .put("title", title).put("duration", duration).put("container", container).put("description", description).put("size", size)
fun Title.toJson() = JSONObject().put("id", id).put("kind", kind).put("title", title).put("year", year)
    .put("poster", poster).put("synopsis", synopsis).put("genres", JSONArray(genres)).put("video_codec", codec)
    .put("container", container).put("episodes", JSONArray(episodes.map { it.toJson() })).put("languages", JSONArray(languages)).put("added_at", addedAt)
fun parseEpisode(e: JSONObject) = Episode(e.getLong("id"), e.optInt("season"), e.optInt("episode"), e.optString("title"),
    e.optDouble("duration", 0.0), e.optString("container"), e.optString("description"), e.optLong("size"))
fun parseTitle(t: JSONObject): Title {
    val genres = t.optJSONArray("genres") ?: JSONArray()
    val episodes = t.optJSONArray("episodes") ?: JSONArray()
    return Title(t.getLong("id"), t.optString("kind"), t.optString("title"), t.optInt("year"), t.optString("poster"), t.optString("synopsis"),
        (0 until genres.length()).map { genres.optString(it) }, (0 until episodes.length()).map { parseEpisode(episodes.getJSONObject(it)) },
        t.optString("video_codec"), t.optString("container"), titleLanguages(t), t.optString("added_at"))
}
fun parseLibrary(result: JSONObject): Library {
    val titles = result.optJSONArray("titles") ?: JSONArray()
    val watch = result.optJSONArray("watch") ?: JSONArray()
    val progress = result.optJSONArray("progress") ?: JSONArray()
    return Library((0 until titles.length()).map { parseTitle(titles.getJSONObject(it)) },
        (0 until watch.length()).map { watch.optLong(it) }.toSet(),
        (0 until progress.length()).mapNotNull { i -> progress.optJSONObject(i)?.let {
            it.optLong("title_id") to Resume(it.optLong("episode_id"), it.optLong("position"), it.optLong("duration"))
        } }.toMap())
}
fun Library.toJson() = JSONObject().put("titles", JSONArray(titles.map { it.toJson() })).put("watch", JSONArray(watch.toList()))
    .put("progress", JSONArray(progress.map { (id, p) -> JSONObject().put("title_id", id).put("episode_id", p.episodeId).put("position", p.seconds).put("duration", p.duration) }))

fun PtsApi.imageUrl(value: String): String? = when {
    value.startsWith("https://") -> value
    value.startsWith("/") && !value.startsWith("//") -> resolveUrl(value)
    else -> null
}
