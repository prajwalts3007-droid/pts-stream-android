package online.ptsmovies.stream

import android.app.Activity
import android.media.AudioManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.testTag
import kotlin.math.abs
import kotlin.math.roundToInt

/** Gestures update continuously after touch slop; seek commits once, never on a plain tap. */
@Composable
fun PlayerGestures(activity: Activity, position: Long, duration: Long, onTap: () -> Unit,
    onSeekPreview: (Long?) -> Unit, onSeek: (Long) -> Unit, onFeedback: (String) -> Unit,
    zoom: Float = 1f, onZoom: (Float) -> Unit = {}, onZoomEnd: () -> Unit = {}, enabled: Boolean = true,
    onTouch: (Boolean) -> Unit = {}, content: @Composable BoxScope.() -> Unit = {}) {
    val currentPosition by rememberUpdatedState(position)
    val currentDuration by rememberUpdatedState(duration)
    val tap by rememberUpdatedState(onTap)
    val preview by rememberUpdatedState(onSeekPreview)
    val seek by rememberUpdatedState(onSeek)
    val feedback by rememberUpdatedState(onFeedback)
    val currentZoom by rememberUpdatedState(zoom)
    val changeZoom by rememberUpdatedState(onZoom)
    val endZoom by rememberUpdatedState(onZoomEnd)
    val touch by rememberUpdatedState(onTouch)
    val gestures = Modifier.fillMaxSize().testTag("player-gesture-zone")
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touch(true)
                try { do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed }) }
                finally { touch(false) }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onTap = { tap() }, onDoubleTap = { offset ->
                val delta = if (offset.x < size.width / 2f) -10000L else 10000L
                seek((currentPosition + delta).coerceIn(0, currentDuration.coerceAtLeast(0)))
                feedback(if (delta < 0) "−10 seconds" else "+10 seconds")
            })
        }.pointerInput(Unit) {
            val audio = activity.getSystemService(Activity.AUDIO_SERVICE) as AudioManager
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false)
                var dx = 0f; var dy = 0f; var mode = ""; var distance = 0f; var zoomValue = currentZoom
                val right = first.position.x >= size.width / 2f
                val startPosition = currentPosition; var target = startPosition
                val startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val startBrightness = activity.window.attributes.screenBrightness.takeIf { it >= 0f }
                    ?: runCatching { android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(.5f)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val down = event.changes.filter { it.pressed }
                    if (down.isEmpty()) break
                    if (down.size >= 2) {
                        val next = (down[0].position - down[1].position).getDistance().coerceAtLeast(1f)
                        if (mode != "zoom") { mode = "zoom"; preview(null); distance = next }
                        else if (distance > 0) { zoomValue = (zoomValue * next / distance).coerceIn(.5f, 3f); changeZoom(zoomValue) }
                        distance = next
                        down.forEach { it.consume() }
                        continue
                    }
                    if (mode == "zoom") { down.forEach { it.consume() }; continue }
                    val change = event.changes.firstOrNull { it.id == first.id } ?: continue
                    if (mode.isBlank() && change.isConsumed) continue
                    dx += change.positionChange().x; dy += change.positionChange().y
                    if (mode.isBlank() && maxOf(abs(dx), abs(dy)) >= viewConfiguration.touchSlop) mode = if (abs(dx) > abs(dy)) "seek" else if (right) "volume" else "brightness"
                    if (mode.isNotBlank()) change.consume()
                    when (mode) {
                    "seek" -> {
                        target = (startPosition + (dx / size.width * currentDuration).toLong()).coerceIn(0, (currentDuration - 1000).coerceAtLeast(0))
                        preview(target)
                        feedback("Seek ${playerClock(target)} · ${if (target >= startPosition) "+" else "−"}${abs(target - startPosition) / 1000}s")
                    }
                    "volume" -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val value = (startVolume - dy / size.height * max).roundToInt().coerceIn(0, max)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                        feedback("Volume ${value * 100 / max.coerceAtLeast(1)}%")
                    }
                    "brightness" -> {
                        val value = (startBrightness - dy / size.height).coerceIn(.12f, 1f)
                        activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }
                        feedback("Brightness ${(value * 100).roundToInt()}%")
                    }
                    }
                }
                if (mode == "seek") seek(target)
                if (mode == "zoom") endZoom()
                if (mode == "seek") preview(null)
            }
        }
    Box(if (enabled) gestures else Modifier.fillMaxSize(), content = content)
}

fun playerClock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(java.util.Locale.US, seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(java.util.Locale.US, seconds / 60, seconds % 60)
}
