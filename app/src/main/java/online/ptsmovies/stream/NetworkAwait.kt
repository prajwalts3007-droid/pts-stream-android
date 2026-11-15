package online.ptsmovies.stream

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellation reaches the socket, including response-body reading and CSRF requests. */
internal fun ResponseBody.boundedText(limit: Int): String {
    require(limit > 0)
    if (contentLength() > limit) throw IOException("Server response exceeds the safe size limit")
    return byteStream().use { it.readBytesBounded(limit).toString(contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8) }
}

internal fun jsonResponseLimit(path: String) = when {
    path.substringBefore('?') == "/stream/library" -> 16 * 1024 * 1024
    path.startsWith("/queue") || path.startsWith("/admin/files") -> 8 * 1024 * 1024
    else -> 4 * 1024 * 1024
}

internal suspend fun Call.awaitImageBytes(limit: Int): ByteArray = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            try {
                val bytes = response.use {
                    check(it.isSuccessful && it.body?.contentType()?.type == "image") { "Artwork unavailable" }
                    it.body!!.byteStream().use { input -> input.readBytesBounded(limit) }
                }
                if (continuation.isActive) continuation.resume(bytes)
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        }
    })
}

internal suspend fun Call.awaitJson(limit: Int = 4 * 1024 * 1024): JSONObject = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use {
                    val text = it.body?.boundedText(if (it.isSuccessful) limit else minOf(limit, 256 * 1024)).orEmpty()
                    if (!it.isSuccessful) throw ApiException(it.code, "HTTP ${it.code}: ${text.take(180)}", runCatching { JSONObject(text) }.getOrNull())
                    if (text.isBlank()) JSONObject() else JSONObject(text)
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        }
    })
}
