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
import okhttp3.RequestBody
import okhttp3.MultipartBody
import okio.BufferedSink
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

const val BASE = "https://ptsmovies.online"

data class Episode(val id: Long, val season: Int, val episode: Int, val title: String, val duration: Double, val container: String, val description: String = "", val size: Long = 0)
data class Title(
    val id: Long, val kind: String, val title: String, val year: Int, val poster: String,
    val synopsis: String, val genres: List<String>, val episodes: List<Episode>, val codec: String, val container: String
)
data class Resume(val episodeId: Long, val seconds: Long, val duration: Long = 0L)
data class Library(val titles: List<Title>, val watch: Set<Long>, val progress: Map<Long, Resume>)

class PtsApi(private val baseUrl: String = BASE, cookieJar: CookieJar? = null) {
    private val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }
    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar ?: object : CookieJar {
            override fun saveFromResponse(url: okhttp3.HttpUrl, received: List<Cookie>) {
                received.forEach { cookies.setCookie(url.toString(), it.toString()) }
                cookies.flush()
            }
            override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> =
                cookies.getCookie(url.toString())?.split(';')?.mapNotNull { Cookie.parse(url, it.trim()) } ?: emptyList()
        })
        .addInterceptor(MediaRouting()).followRedirects(false).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).build()

    private fun request(path: String, body: JSONObject? = null, method: String = if (body == null) "GET" else "POST"): JSONObject {
        val url = baseUrl.trimEnd('/') + "/api/v1" + path
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
            if (!response.isSuccessful) throw ApiException(response.code, "HTTP ${response.code}: ${text.take(180)}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun get(path: String) = request(path)
    suspend fun json(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) { request(path, body, method) }
    suspend fun me(): JSONObject = withContext(Dispatchers.IO) { get("/auth/me").getJSONObject("user") }
    suspend fun login(user: String, password: String, remember: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        request("/auth/login", JSONObject().put("username", user).put("password", password).put("remember", remember)).getJSONObject("user")
    }
    suspend fun logout() = withContext(Dispatchers.IO) {
        try { request("/auth/logout", JSONObject()) } finally {
            cookies.removeAllCookies(null)
            cookies.flush()
        }
    }
    suspend fun uploadAvatar(bytes: ByteArray): JSONObject = withContext(Dispatchers.IO) {
        require(bytes.size <= 1024 * 1024) { "Profile photo exceeds 1 MiB" }
        val token = get("/auth/csrf").optString("csrf_token")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("avatar", "avatar.jpg", bytes.toRequestBody("image/jpeg".toMediaType())).build()
        http.newCall(Request.Builder().url(resolveUrl("/api/v1/auth/avatar")).header("X-CSRF-Token", token).post(body).build()).execute().use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw ApiException(it.code, "Photo upload HTTP ${it.code}: ${text.take(160)}")
            JSONObject(text).getJSONObject("user")
        }
    }
    suspend fun library(): Library = withContext(Dispatchers.IO) {
        parseLibrary(get("/stream/library"))
    }
    suspend fun watch(title: Long, on: Boolean) = withContext(Dispatchers.IO) {
        request("/stream/watch", JSONObject().put("id", title).put("on", on)); Unit
    }
    suspend fun progress(title: Long, episode: Long?, position: Long, duration: Long) = withContext(Dispatchers.IO) {
        request("/stream/progress", JSONObject().put("title_id", title).put("episode_id", episode ?: 0)
            .put("position", position).put("duration", duration)); Unit
    }
    suspend fun upload(device: String, path: String, name: String, size: Long, open: () -> InputStream, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        require(size in 0..64L * 1024 * 1024) { "Maximum upload size is 64 MiB per file" }
        require(validEntryName(name)) { "Invalid upload name" }
        val coroutine = currentCoroutineContext()
        val file = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buffer = ByteArray(65536)
                    var written = 0L
                    while (true) {
                        coroutine.ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        written += n
                        if (written > size || written > 64L * 1024 * 1024) throw IOException("Upload source changed or exceeded limit")
                        sink.write(buffer, 0, n)
                        progress(if (size > 0) (written * 100 / size).toInt() else 100)
                    }
                    if (written != size) throw IOException("Upload source size changed")
                }
            }
        }
        val token = get("/auth/csrf").optString("csrf_token")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("device", device)
            .addFormDataPart("path", path).addFormDataPart("file", name, file).build()
        http.newBuilder().writeTimeout(5, TimeUnit.MINUTES).readTimeout(5, TimeUnit.MINUTES).build()
            .newCall(Request.Builder().url(baseUrl.trimEnd('/') + "/api/v1/admin/files/upload").header("X-CSRF-Token", token).post(body).build())
            .execute().use { response ->
                if (!response.isSuccessful) throw ApiException(response.code, "Upload HTTP ${response.code}: ${response.body?.string().orEmpty().take(180)}")
            }
    }
    fun streamUrl(title: Title, episode: Episode? = null, fallback: Boolean = false): String =
        "${baseUrl.trimEnd('/')}/api/v1/stream/${if (episode?.container == "hls" || (episode == null && title.container == "hls")) "hls" else "file"}/${if (episode == null) "movie" else "episode"}/${episode?.id ?: title.id}" +
            (if (episode?.container == "hls" || (episode == null && title.container == "hls")) "/master.m3u8" else "") +
            if (fallback) "?via=vps" else ""
    fun fileDownloadUrl(device: String, path: String): String =
        baseUrl.trimEnd('/') + "/api/v1/admin/files/download?device=$device&path=" + java.net.URLEncoder.encode(path, "UTF-8")
    fun resolveUrl(path: String) = baseUrl.trimEnd('/') + path
}

class ApiException(val status: Int, message: String) : IOException(message)
