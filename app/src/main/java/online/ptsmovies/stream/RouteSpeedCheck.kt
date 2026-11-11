package online.ptsmovies.stream

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RouteSpeedSample(val route: MediaRoute, val bytes: Long, val bytesPerSecond: Long)

/** Explicit user-triggered comparison. At most 1 MiB body per route; no media is saved. */
internal suspend fun probeMediaRoute(factory: Call.Factory, url: String, preferLan: Boolean): RouteSpeedSample = suspendCancellableCoroutine { continuation ->
    val telemetry = MediaTelemetry()
    val started = System.nanoTime()
    val limit = 1024 * 1024L
    val request = Request.Builder().url(url).header("Range", "bytes=0-${limit - 1}").header("Accept-Encoding", "identity")
        .tag(MediaRoutePolicy::class.java, MediaRoutePolicy(preferLan)).tag(MediaTelemetry::class.java, telemetry).build()
    val call = factory.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("Media connection test unavailable")) }
        override fun onResponse(call: Call, response: Response) {
            try {
                val sample = response.use {
                    val body = it.body ?: throw IOException("No probe body")
                    val range = Regex("bytes 0-(\\d+)/(\\d+)").matchEntire(it.header("Content-Range").orEmpty())
                    val expected = if (it.code == 206 && range != null) range.groupValues[1].toLong() + 1 else if (it.code == 200) body.contentLength() else -1
                    require(expected in 1..limit) { "Server did not honor the bounded range test" }
                    val buffer = ByteArray(65536); var total = 0L
                    body.byteStream().use { input -> while (total < expected) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - total).toInt())
                        if (n < 0) break
                        total += n
                    } }
                    require(total == expected) { "Incomplete connection test" }
                    RouteSpeedSample(telemetry.snapshot().route, total, total * 1_000_000_000L / (System.nanoTime() - started).coerceAtLeast(1))
                }
                if (continuation.isActive) continuation.resume(sample)
            } catch (_: Exception) { if (continuation.isActive) continuation.resumeWithException(IOException("The source does not support this bounded connection test")) }
        }
    })
}
