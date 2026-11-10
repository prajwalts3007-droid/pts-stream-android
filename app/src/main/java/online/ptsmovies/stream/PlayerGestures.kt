package online.ptsmovies.stream

import android.app.Activity
import android.media.AudioManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import kotlin.math.abs
import kotlin.math.roundToInt

/** Gestures update continuously after touch slop; seek commits once, never on a plain tap. */
@Composable
fun PlayerGestures(activity: Activity, position: Long, duration: Long, onTap: () -> Unit,
    onSeekPreview: (Long?) -> Unit, onSeek: (Long) -> Unit, onFeedback: (String) -> Unit) {
    val currentPosition by rememberUpdatedState(position)
    val currentDuration by rememberUpdatedState(duration)
    val tap by rememberUpdatedState(onTap)
    val preview by rememberUpdatedState(onSeekPreview)
    val seek by rememberUpdatedState(onSeek)
    val feedback by rememberUpdatedState(onFeedback)
    Box(Modifier.fillMaxSize().testTag("player-gesture-zone")
        .pointerInput(Unit) {
            detectTapGestures(onTap = { tap() }, onDoubleTap = { offset ->
                val delta = if (offset.x < size.width / 2f) -10000L else 10000L
                seek((currentPosition + delta).coerceIn(0, currentDuration.coerceAtLeast(0)))
                feedback(if (delta < 0) "−10 seconds" else "+10 seconds")
            })
        }.pointerInput(Unit) {
            val audio = activity.getSystemService(Activity.AUDIO_SERVICE) as AudioManager
            var dx = 0f; var dy = 0f; var mode = ""; var right = false
            var startPosition = 0L; var startVolume = 0; var startBrightness = .5f
            var target = 0L
            detectDragGestures(onDragStart = { start ->
                dx = 0f; dy = 0f; mode = ""; right = start.x >= size.width / 2f
                startPosition = currentPosition; target = startPosition
                startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                startBrightness = activity.window.attributes.screenBrightness.takeIf { it >= 0f }
                    ?: runCatching { android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(.5f)
            }, onDragCancel = { preview(null) }, onDragEnd = {
                if (mode == "seek") seek(target)
                preview(null)
            }) { change, amount ->
                change.consume(); dx += amount.x; dy += amount.y
                if (mode.isBlank() && maxOf(abs(dx), abs(dy)) >= viewConfiguration.touchSlop) mode = if (abs(dx) > abs(dy)) "seek" else if (right) "volume" else "brightness"
                when (mode) {
                    "seek" -> {
                        target = (startPosition + (dx / size.width * minOf(currentDuration, 120000L)).toLong()).coerceIn(0, currentDuration.coerceAtLeast(0))
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
                        val value = (startBrightness - dy / size.height).coerceIn(.02f, 1f)
                        activity.window.attributes = activity.window.attributes.apply { screenBrightness = value }
                        feedback("Brightness ${(value * 100).roundToInt()}%")
                    }
                }
            }
        })
}

fun playerClock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(java.util.Locale.US, seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(java.util.Locale.US, seconds / 60, seconds % 60)
}
