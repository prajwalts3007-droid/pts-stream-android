package online.ptsmovies.stream

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val LocalPtsMotion = staticCompositionLocalOf<Boolean?> { null }
@Composable @ReadOnlyComposable internal fun ptsMotionEnabled() = LocalPtsMotion.current ?: true
internal fun motionMillis(enabled: Boolean, milliseconds: Int) = if (enabled) milliseconds else 0

/** One observer at the root theme; nested player/dialog themes inherit the same policy. */
@Composable
internal fun rememberSystemMotion(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = ValueAnimator.areAnimatorsEnabled() }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return enabled
}

@Composable
private fun RefreshGlyph(refreshing: Boolean, modifier: Modifier = Modifier) {
    val motion = ptsMotionEnabled()
    val rotation = if (refreshing && motion) {
        val transition = rememberInfiniteTransition(label = "refresh-active")
        val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(850, easing = LinearEasing)), label = "refresh-rotation")
        angle
    } else 0f
    Icon(PtsIcons.Refresh, null, modifier.graphicsLayer { rotationZ = rotation }, tint = if (refreshing) Pts.accent else Pts.muted)
}

@Composable
fun PtsRefreshAction(description: String, refreshing: Boolean, onClick: () -> Unit) {
    PtsPressable(onClick, Modifier.size(40.dp).clip(CircleShape).background(if (refreshing) Pts.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
        .semantics { contentDescription = description; stateDescription = if (refreshing) "Refreshing" else "Ready" }, enabled = !refreshing) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { RefreshGlyph(refreshing, Modifier.size(22.dp)) }
    }
}

@Composable
fun PtsRefreshButton(label: String, refreshing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PtsPressable(onClick, modifier.semantics { stateDescription = if (refreshing) "Refreshing" else "Ready" }, enabled = !refreshing) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(ButtonShape).background(Pts.card).border(2.dp, if (refreshing) Pts.accent else Pts.line2, ButtonShape)
            .padding(horizontal = 8.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            RefreshGlyph(refreshing, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(label, color = Pts.text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
