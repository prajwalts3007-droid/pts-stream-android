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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val LocalPtsMotion = staticCompositionLocalOf<Boolean?> { null }
@Composable @ReadOnlyComposable internal fun ptsMotionEnabled() = LocalPtsMotion.current ?: true
internal fun motionMillis(enabled: Boolean, milliseconds: Int) = if (enabled) milliseconds else 0

/** Entry only: never cross-compose two screens, repeat their effects, or recreate a decoder. */
@Composable
internal fun Modifier.ptsPageMotion(route: Any): Modifier {
    val motion = ptsMotionEnabled()
    val reveal = remember { Animatable(1f) }
    val distance = with(LocalDensity.current) { 8.dp.toPx() }
    LaunchedEffect(route, motion) {
        if (motion) { reveal.snapTo(0f); reveal.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
        else reveal.snapTo(1f)
    }
    return graphicsLayer {
        alpha = if (motion) reveal.value else 1f
        translationY = if (motion) distance * (1f - reveal.value) else 0f
    }
}

/** Equal-height actions on normal screens; full-width stacked actions on narrow/large-text layouts. */
@Composable
internal fun PtsButtonPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (stackActions(maxWidth.value, fontScale)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { first(Modifier.fillMaxWidth()); second(Modifier.fillMaxWidth()) }
        } else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            first(Modifier.weight(1f).fillMaxHeight()); second(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
internal fun PtsPagerDot(index: Int, selected: Boolean, onClick: () -> Unit) {
    val width by animateDpAsState(if (selected) 24.dp else 9.dp, tween(motionMillis(ptsMotionEnabled(), 180)), label = "pager-dot-width")
    PtsPressable(onClick, Modifier.size(48.dp).semantics { contentDescription = "Featured page ${index + 1}"; this.selected = selected }) {
        Box(Modifier.align(Alignment.Center).width(width).height(9.dp).clip(CircleShape).background(if (selected) Pts.accent else Pts.line2))
    }
}

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
    PtsPressable(onClick, Modifier.size(48.dp).clip(CircleShape).background(if (refreshing) Pts.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
        .semantics { contentDescription = description; stateDescription = if (refreshing) "Refreshing" else "Ready" }, enabled = !refreshing) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { RefreshGlyph(refreshing, Modifier.size(22.dp)) }
    }
}

@Composable
fun PtsRefreshButton(label: String, refreshing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PtsPressable(onClick, modifier.semantics { stateDescription = if (refreshing) "Refreshing" else "Ready" }, enabled = !refreshing) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(ButtonShape).background(Pts.card).border(2.dp, if (refreshing) Pts.accent else Pts.line2, ButtonShape)
            .padding(horizontal = 8.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            RefreshGlyph(refreshing, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(label, color = Pts.text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
