package online.ptsmovies.stream

import okhttp3.*
import okio.buffer
import java.util.concurrent.atomic.AtomicLong

enum class MediaRoute(val label: String) {
    CONNECTING("Connecting"), LAN("Local Pi"), TUNNEL("Pi via Internet"), VPS("Server fallback"), INTERNET("Internet"), OFFLINE("Offline")
}
data class TrafficSnapshot(val route: MediaRoute, val bytes: Map<MediaRoute, Long>, val bytesPerSecond: Long) {
    val total get() = bytes.values.sum()
}

/** Per-playback/transfer counters. No credentials, URLs, filenames or account data are retained. */
class MediaTelemetry {
    private val totals = MediaRoute.entries.associateWith { AtomicLong() }
    @Volatile private var route = MediaRoute.CONNECTING
    private var lastTime = System.nanoTime()
    private var lastBytes = 0L
    fun accepted(value: MediaRoute) { route = value }
    fun read(value: MediaRoute, count: Long) { if (count > 0) { route = value; totals.getValue(value).addAndGet(count) } }
    @Synchronized fun snapshot(): TrafficSnapshot {
        val values = totals.mapValues { it.value.get() }; val bytes = values.values.sum(); val now = System.nanoTime()
        val speed = ((bytes - lastBytes) * 1_000_000_000L / (now - lastTime).coerceAtLeast(1)).coerceAtLeast(0)
        lastBytes = bytes; lastTime = now
        return TrafficSnapshot(route, values, speed)
    }
}

class TrackedCallFactory(private val client: Call.Factory, private val telemetry: MediaTelemetry) : Call.Factory {
    override fun newCall(request: Request): Call = client.newCall(request.newBuilder().tag(MediaTelemetry::class.java, telemetry).build())
}

internal fun trackMediaResponse(response: Response, original: Request, local: Boolean): Response {
    val telemetry = original.tag(MediaTelemetry::class.java) ?: return response
    val body = response.body ?: return response
    if (!response.isSuccessful) return response
    val route = when {
        local -> MediaRoute.LAN
        response.request.url.host == "media.ptsmovies.online" -> MediaRoute.TUNNEL
        response.request.url.encodedPath.startsWith("/api/v1/stream/") -> MediaRoute.VPS
        else -> MediaRoute.INTERNET
    }
    // Manifest traffic must not overwrite the route of actual audio/video segments.
    val manifest = body.contentType()?.subtype?.contains("mpegurl", true) == true || response.request.url.encodedPath.endsWith(".m3u8")
    if (manifest) return response
    telemetry.accepted(route)
    val source = object : okio.ForwardingSource(body.source()) {
        override fun read(sink: okio.Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { telemetry.read(route, it) }
    }.buffer()
    return response.newBuilder().body(object : ResponseBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun source() = source
    }).build()
}
