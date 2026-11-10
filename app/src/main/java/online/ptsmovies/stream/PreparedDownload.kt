package online.ptsmovies.stream

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Uses the web preparation contract. The server still enforces owner/role/CSRF and two slots. */
suspend fun prepareOfflineMp4(api: PtsApi, title: Title, episode: Episode?, onStatus: (String) -> Unit = {}): String {
    var result = api.json("/stream/download/prepare/${if (episode == null) "movie" else "episode"}/${episode?.id ?: title.id}", "POST", JSONObject())
    while (true) {
        currentCoroutineContext().ensureActive()
        when (result.optString("state")) {
            "ready" -> {
                val path = result.getString("url")
                require(Regex("/api/v1/stream/download/[a-zA-Z0-9_-]+/file").matches(path) ||
                    Regex("/api/v1/stream/original/(movie|episode)/[0-9]+").matches(path)) { "Untrusted prepared download URL" }
                return api.resolveUrl(path)
            }
            "failed" -> error(result.optString("message").ifBlank { "Pi MP4 preparation failed" })
            "queued", "preparing", "running", "downloading" -> onStatus(result.optString("message", "Preparing MP4 on Pi"))
            else -> error("Unknown preparation state; no media was downloaded")
        }
        val id = result.getString("id")
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "Invalid preparation identifier" }
        delay(2000)
        result = api.json("/stream/download/$id")
    }
}
