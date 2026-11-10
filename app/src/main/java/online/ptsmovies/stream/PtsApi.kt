package online.ptsmovies.stream

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

const val BASE = "https://ptsmovies.online"

data class Episode(val id: Long, val season: Int, val episode: Int, val title: String, val duration: Double, val container: String)
data class Title(
    val id: Long, val kind: String, val title: String, val year: Int, val poster: String,
    val synopsis: String, val genres: List<String>, val episodes: List<Episode>, val codec: String, val container: String
)
data class Resume(val episodeId: Long, val seconds: Long)
data class Library(val titles: List<Title>, val watch: Set<Long>, val progress: Map<Long, Resume>)

class PtsApi {
    private val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }
    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: okhttp3.HttpUrl, received: List<Cookie>) {
                received.forEach { cookies.setCookie(url.toString(), it.toString()) }
                cookies.flush()
            }
            override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> =
                cookies.getCookie(url.toString())?.split(';')?.mapNotNull { Cookie.parse(url, it.trim()) } ?: emptyList()
        })
        .followRedirects(true).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).build()

    private fun request(path: String, body: JSONObject? = null, method: String = if (body == null) "GET" else "POST"): JSONObject {
        val url = BASE + "/api/v1" + path
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (method != "GET") {
            // A new CSRF token is issued for the current browser-like cookie session.
            val token = get("/auth/csrf").optString("csrf_token")
            builder.header("X-CSRF-Token", token)
            if (method == "DELETE") builder.delete()
            else builder.method(method, (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${text.take(180)}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun get(path: String) = request(path)
    suspend fun json(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) { request(path, body, method) }
    suspend fun me(): JSONObject = withContext(Dispatchers.IO) { get("/auth/me").getJSONObject("user") }
    suspend fun login(user: String, password: String): JSONObject = withContext(Dispatchers.IO) {
        request("/auth/login", JSONObject().put("username", user).put("password", password).put("remember", true)).getJSONObject("user")
    }
    suspend fun logout() = withContext(Dispatchers.IO) {
        try { request("/auth/logout", JSONObject()) } finally {
            cookies.removeAllCookies(null)
            cookies.flush()
        }
    }
    suspend fun library(): Library = withContext(Dispatchers.IO) {
        val result = get("/stream/library")
        fun JSONArray?.longs(): Set<Long> = (0 until (this?.length() ?: 0)).map { this!!.optLong(it) }.toSet()
        val list = result.optJSONArray("titles") ?: JSONArray()
        val titles = (0 until list.length()).map { i ->
            val t = list.getJSONObject(i)
            val eps = t.optJSONArray("episodes") ?: JSONArray()
            val genres = t.optJSONArray("genres") ?: JSONArray()
            Title(t.getLong("id"), t.optString("kind"), t.optString("title"), t.optInt("year"),
                t.optString("poster"), t.optString("synopsis"),
                (0 until genres.length()).map { genres.optString(it) },
                (0 until eps.length()).map { n ->
                    val e = eps.getJSONObject(n)
                    Episode(e.getLong("id"), e.optInt("season"), e.optInt("episode"), e.optString("title"), e.optDouble("duration"), e.optString("container"))
                }, t.optString("video_codec"), t.optString("container"))
        }
        val progress = mutableMapOf<Long, Resume>()
        val values = result.optJSONArray("progress") ?: JSONArray()
        for (i in 0 until values.length()) {
            val p = values.optJSONObject(i) ?: continue
            progress[p.optLong("title_id")] = Resume(p.optLong("episode_id"), p.optLong("position"))
        }
        Library(titles, result.optJSONArray("watch").longs(), progress)
    }
    suspend fun watch(title: Long, on: Boolean) = withContext(Dispatchers.IO) {
        request("/stream/watch", JSONObject().put("id", title).put("on", on)); Unit
    }
    suspend fun progress(title: Long, episode: Long?, position: Long, duration: Long) = withContext(Dispatchers.IO) {
        request("/stream/progress", JSONObject().put("title_id", title).put("episode_id", episode ?: 0)
            .put("position", position).put("duration", duration)); Unit
    }
    fun streamUrl(title: Title, episode: Episode? = null, fallback: Boolean = false): String =
        "$BASE/api/v1/stream/${if (episode?.container == "hls" || (episode == null && title.container == "hls")) "hls" else "file"}/${if (episode == null) "movie" else "episode"}/${episode?.id ?: title.id}" +
            (if (episode?.container == "hls" || (episode == null && title.container == "hls")) "/master.m3u8" else "") +
            if (fallback) "?via=vps" else ""
}
