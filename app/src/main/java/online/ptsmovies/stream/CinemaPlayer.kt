@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package online.ptsmovies.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

data class PlayerAppearance(val subtitleSize: Float = 1f, val subtitleColor: String = "white", val outline: Boolean = true,
    val subtitlePosition: String = "bottom", val controlsSeconds: Int = 12, val nextDownloadedOnly: Boolean = false)

fun loadPlayerAppearance(context: android.content.Context): PlayerAppearance {
    val p = context.getSharedPreferences("playback_local", android.content.Context.MODE_PRIVATE)
    return PlayerAppearance(p.getFloat("subtitle_size", 1f).coerceIn(.8f, 1.3f), p.getString("subtitle_color", "white") ?: "white",
        p.getBoolean("subtitle_outline", true), p.getString("subtitle_position", "bottom") ?: "bottom",
        p.getInt("controls_seconds", 12).takeIf { it in listOf(0, 8, 12, 20) } ?: 12, p.getBoolean("next_downloaded_only", false))
}
fun savePlayerAppearance(context: android.content.Context, value: PlayerAppearance) {
    context.getSharedPreferences("playback_local", android.content.Context.MODE_PRIVATE).edit()
        .putFloat("subtitle_size", value.subtitleSize).putString("subtitle_color", value.subtitleColor)
        .putBoolean("subtitle_outline", value.outline).putString("subtitle_position", value.subtitlePosition)
        .putInt("controls_seconds", value.controlsSeconds).putBoolean("next_downloaded_only", value.nextDownloadedOnly).apply()
}

fun controlsShouldHide(playing: Boolean, buffering: Boolean, seeking: Boolean, menu: Boolean, seconds: Int) =
    playing && !buffering && !seeking && !menu && seconds > 0

@Composable
fun CinemaIcon(icon: ImageVector, description: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, modifier.size(48.dp), enabled = enabled) {
        Icon(icon, description, Modifier.size(30.dp), tint = Color.White.copy(alpha = if (enabled) 1f else .35f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CinemaControls(title: String, playing: Boolean, position: Long, duration: Long, buffered: Long, fullscreen: Boolean,
    muted: Boolean, route: String, episodes: Boolean, previousEnabled: Boolean, nextEnabled: Boolean,
    onBack: () -> Unit, onToggle: () -> Unit, onSeek: (Long) -> Unit, onPreview: (Long?) -> Unit,
    onOptions: () -> Unit, onTracks: () -> Unit, onEpisodes: () -> Unit, onPip: () -> Unit, onLock: () -> Unit,
    onMute: () -> Unit, onFullscreen: () -> Unit, onConnection: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    Box(Modifier.fillMaxSize().testTag("cinema-controls").background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent, Color(0xE6000000))))) {
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CinemaIcon(PtsIcons.Back, "Back", onClick = onBack)
            Text(title, Modifier.weight(1f).padding(horizontal = 6.dp), color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CinemaIcon(PtsIcons.Pip, "Picture in picture", onClick = onPip)
            CinemaIcon(PtsIcons.Settings, "Player options", onClick = onOptions)
        }
        Box(Modifier.fillMaxSize().padding(top = 48.dp, bottom = 132.dp), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.size(64.dp).clickable { onSeek((position - 10000).coerceAtLeast(0)) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(PtsIcons.Retry, "Back 10 seconds", Modifier.size(36.dp), tint = Color.White)
                Text("10", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
            IconButton(onToggle, Modifier.size(72.dp).background(Color.White.copy(alpha = .16f), RoundedCornerShape(36.dp))) {
                Icon(if (playing) PtsIcons.Pause else PtsIcons.Play, if (playing) "Pause playback" else "Play playback", Modifier.size(48.dp), tint = Color.White)
            }
            Column(Modifier.size(64.dp).clickable { onSeek((position + 10000).coerceAtMost(duration.coerceAtLeast(0))) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(PtsIcons.Refresh, "Forward 10 seconds", Modifier.size(36.dp), tint = Color.White)
                Text("10", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${playerClock(position)} / ${playerClock(duration)}", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.labelMedium)
                TextButton(onConnection, Modifier.testTag("media-route")) { Text(route, maxLines = 1, color = Pts.accent, style = MaterialTheme.typography.labelMedium) }
            }
            Box(Modifier.fillMaxWidth()) {
                var target by remember { mutableLongStateOf(position) }
                LinearProgressIndicator(progress = { (buffered.toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().align(Alignment.Center), color = Color.White.copy(alpha = .5f), trackColor = Color.White.copy(alpha = .15f))
                Slider(position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()), { target = it.toLong(); onPreview(target) },
                    modifier = Modifier.fillMaxWidth().height(36.dp), onValueChangeFinished = { onSeek(target); onPreview(null) }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Pts.accent, activeTrackColor = Pts.accent, inactiveTrackColor = Color.Transparent))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                CinemaIcon(PtsIcons.Lock, "Lock player", onClick = onLock)
                if (episodes) {
                    CinemaIcon(PtsIcons.Previous, "Previous episode", enabled = previousEnabled, onClick = onPrevious)
                    CinemaIcon(PtsIcons.Series, "Choose episode", onClick = onEpisodes)
                    CinemaIcon(PtsIcons.Next, "Next episode", enabled = nextEnabled, onClick = onNext)
                } else CinemaIcon(if (muted) PtsIcons.Muted else PtsIcons.Volume, "Mute audio", onClick = onMute)
                CinemaIcon(PtsIcons.Subtitles, "Audio and subtitles", onClick = onTracks)
                CinemaIcon(PtsIcons.Fit, if (fullscreen) "Exit fullscreen" else "Fullscreen", onClick = onFullscreen)
            }
        }
    }
}

@Composable
fun PlayerOptions(appearance: PlayerAppearance, onAppearance: (PlayerAppearance) -> Unit, speed: Float, onSpeed: (Float) -> Unit,
    timing: PlaybackTiming, onTiming: (Int, Int) -> Unit, traffic: TrafficSnapshot, bufferMs: Long, fill: Boolean, autoplay: Boolean,
    onFit: () -> Unit, onZoomReset: () -> Unit, onAutoplay: () -> Unit, onTracks: () -> Unit, onSleep: () -> Unit,
    onLock: () -> Unit, onClose: () -> Unit, initialPage: String = "", timingRevision: Int = 0) {
    var page by remember { mutableStateOf(initialPage) }
    val sync = remember(timingRevision) { timing.audioDelayMs to timing.subtitleDelayMs }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .25f))) {
            val wide = maxWidth > maxHeight
            Surface(Modifier.align(if (wide) Alignment.CenterEnd else Alignment.BottomCenter).widthIn(max = 420.dp).fillMaxWidth().fillMaxHeight(if (wide) 1f else .84f), color = Color(0xFF171820), shape = RoundedCornerShape(topStart = 20.dp, topEnd = if (wide) 0.dp else 20.dp)) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (page.isNotBlank()) CinemaIcon(PtsIcons.Back, "Back to player menu", onClick = { page = "" })
                        Text(page.ifBlank { "Player options" }, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleLarge)
                        CinemaIcon(PtsIcons.Close, "Close player menu", onClick = onClose)
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (page) {
                            "Speed" -> listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { rate ->
                                PlayerMenuRow(PtsIcons.Speed, "${rate}×", if (speed == rate) "Selected" else "") { onSpeed(rate) }
                            }
                            "Subtitles" -> {
                                Text("Size", color = Color.White)
                                PtsSegmented(listOf("0.8" to "Small", "1.0" to "Medium", "1.3" to "Large"), appearance.subtitleSize.toString(), { onAppearance(appearance.copy(subtitleSize = it.toFloat())) })
                                Text("Colour", color = Color.White)
                                PtsSegmented(listOf("white" to "White", "yellow" to "Yellow", "cyan" to "Cyan"), appearance.subtitleColor, { onAppearance(appearance.copy(subtitleColor = it)) })
                                PreferenceSwitch("Outline", appearance.outline) { onAppearance(appearance.copy(outline = it)) }
                                PtsSegmented(listOf("bottom" to "Bottom", "middle" to "Middle", "top" to "Top"), appearance.subtitlePosition, { onAppearance(appearance.copy(subtitlePosition = it)) })
                                PlayerMenuRow(PtsIcons.Subtitles, "Choose audio / subtitle track") { onTracks() }
                            }
                            "Synchronisation" -> {
                                TimingControl("Audio delay", sync.first, 2000) { onTiming(it, sync.second) }
                                TimingControl("Subtitle delay", sync.second, 10000) { onTiming(sync.first, it) }
                                Text("Positive values delay the selected track relative to video. Changes reset timing at the current position.", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                                TextButton({ onTiming(0, 0) }) { Text("Reset timing") }
                            }
                            "Controls" -> {
                                Text("Hide controls after", color = Color.White)
                                listOf(8, 12, 20, 0).forEach { seconds -> PlayerMenuRow(PtsIcons.Clock, if (seconds == 0) "Never" else "$seconds seconds", if (appearance.controlsSeconds == seconds) "Selected" else "") { onAppearance(appearance.copy(controlsSeconds = seconds)) } }
                                PreferenceSwitch("Next downloaded episode only", appearance.nextDownloadedOnly) { onAppearance(appearance.copy(nextDownloadedOnly = it)) }
                            }
                            "Connection" -> {
                                Text(traffic.route.label, color = Pts.accent, style = MaterialTheme.typography.titleLarge)
                                Text("${formatBytes(traffic.bytesPerSecond)}/s · ${formatBytes(traffic.total)} read", color = Color.White)
                                Text("Buffered ${playerClock(bufferMs)}", color = Color.White)
                                MediaRoute.entries.filter { it !in listOf(MediaRoute.CONNECTING) }.forEach { route -> Text("${route.label}: ${formatBytes(traffic.bytes[route] ?: 0)}", color = Pts.muted) }
                                Text("Media payload only, not account/metadata traffic or network overhead. A filled buffer can reduce the measured rate to zero.", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                            }
                            else -> {
                                PlayerMenuRow(PtsIcons.Subtitles, "Audio and subtitles", "Tracks") { onTracks() }
                                PlayerMenuRow(PtsIcons.Subtitles, "Subtitle appearance") { page = "Subtitles" }
                                PlayerMenuRow(PtsIcons.Speed, "Speed", "${speed}×") { page = "Speed" }
                                PlayerMenuRow(PtsIcons.Clock, "Synchronisation") { page = "Synchronisation" }
                                PlayerMenuRow(PtsIcons.Fit, "Aspect ratio", if (fill) "Fill" else "Fit") { onFit() }
                                PlayerMenuRow(PtsIcons.Fit, "Reset zoom") { onZoomReset() }
                                PlayerMenuRow(PtsIcons.Settings, "Controls") { page = "Controls" }
                                PlayerMenuRow(PtsIcons.Info, "Connection", traffic.route.label) { page = "Connection" }
                                PlayerMenuRow(PtsIcons.Series, "Autoplay next", if (autoplay) "On" else "Off") { onAutoplay() }
                                PlayerMenuRow(PtsIcons.Clock, "Sleep timer") { onSleep() }
                                PlayerMenuRow(PtsIcons.Lock, "Lock screen") { onLock() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerMenuRow(icon: ImageVector, title: String, value: String = "", action: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = action).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(26.dp), tint = Color.White)
        Text(title, Modifier.weight(1f).padding(horizontal = 12.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
        if (value.isNotEmpty()) Text(value, color = Pts.accent, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TimingControl(label: String, value: Int, limit: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Color.White)
        TextButton({ onChange((value - 100).coerceAtLeast(-limit)) }, Modifier.semantics { contentDescription = "Decrease $label" }) { Text("−") }
        Text("${if (value > 0) "+" else ""}${value}ms", color = Pts.accent)
        TextButton({ onChange((value + 100).coerceAtMost(limit)) }, Modifier.semantics { contentDescription = "Increase $label" }) { Text("+") }
    }
}
