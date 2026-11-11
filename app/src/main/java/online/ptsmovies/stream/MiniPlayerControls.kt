package online.ptsmovies.stream

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun MiniPlayerControls(title: String, detail: String, playing: Boolean, progress: Float,
    onExpand: () -> Unit, onToggle: () -> Unit, onClose: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("in-app-mini-player")) {
        val videoWidth = if (maxWidth < 340.dp) 88.dp else 108.dp
        Box(Modifier.width(videoWidth).fillMaxHeight().semantics { contentDescription = "Expand playing video" }.clickable(onClick = onExpand))
        Row(Modifier.fillMaxSize().padding(start = videoWidth + 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onExpand).padding(horizontal = 4.dp)) {
                Text(title, color = Pts.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, color = Pts.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onToggle, Modifier.size(48.dp)) { Icon(if (playing) PtsIcons.Pause else PtsIcons.Play, if (playing) "Pause mini-player" else "Play mini-player", tint = Pts.text) }
            IconButton(onExpand, Modifier.size(48.dp)) { Icon(PtsIcons.Fit, "Expand mini-player", tint = Pts.text) }
            IconButton(onClose, Modifier.size(48.dp)) { Icon(PtsIcons.Close, "Close mini-player", tint = Pts.text) }
        }
        LinearProgressIndicator(progress = { progress.coerceIn(0f,1f) }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp), color = Pts.accent, trackColor = Pts.line)
    }
}
