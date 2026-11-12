package online.ptsmovies.stream

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellation reaches the socket, including response-body reading and CSRF requests. */
internal suspend fun Call.awaitJson(): JSONObject = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use {
                    val text = it.body?.string().orEmpty()
                    if (!it.isSuccessful) throw ApiException(it.code, "HTTP ${it.code}: ${text.take(180)}", runCatching { JSONObject(text) }.getOrNull())
                    if (text.isBlank()) JSONObject() else JSONObject(text)
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        }
    })
}
