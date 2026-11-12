package online.ptsmovies.stream

import android.content.Context
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal object PosterImages {
    @Volatile private var instance: ImageLoader? = null
    fun loader(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext)
            .memoryCache { MemoryCache.Builder(context.applicationContext).maxSizeBytes(
                minOf(12 * 1024 * 1024, (context.getSystemService(android.app.ActivityManager::class.java).memoryClass * 1024L * 1024 / 20).toInt())).build() }
            .diskCache { DiskCache.Builder().directory(context.applicationContext.cacheDir.resolve("public-posters")).maxSizeBytes(64L * 1024 * 1024).build() }
            // Public artwork never borrows the account/media client's cookies or LAN routing.
            .okHttpClient(OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build())
            .build().also { instance = it }
    }
    fun trim(level: Int) { instance?.memoryCache?.trimMemory(level) }
}

@Composable
internal fun PtsPosterImage(url: String?, description: String?, modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop, hero: Boolean = false, alpha: Float = 1f) {
    if (url.isNullOrBlank() || !url.startsWith("https://")) return
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val motion = ptsMotionEnabled()
    BoxWithConstraints(modifier) {
        val width = posterDecodeWidth((maxWidth.value * density).roundToInt(), hero)
        val height = if (constraints.hasBoundedHeight) (maxHeight.value * density).roundToInt().coerceIn(96, 2304) else width * 3 / 2
        val request = remember(url, width, height, motion) {
            ImageRequest.Builder(context.applicationContext).data(posterRequestUrl(url, width)).size(width, height)
                .precision(Precision.INEXACT).scale(Scale.FILL).crossfade(motionMillis(motion, 120)).build()
        }
        AsyncImage(request, description, imageLoader = PosterImages.loader(context), modifier = Modifier.fillMaxSize(), contentScale = contentScale, alpha = alpha)
    }
}
