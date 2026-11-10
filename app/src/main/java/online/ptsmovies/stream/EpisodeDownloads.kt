package online.ptsmovies.stream

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun EpisodeDownloadDialog(title: Title, savedKeys: Set<String>, watched: Set<String>, onStart: (List<Episode>) -> Unit, onClose: () -> Unit) {
    val episodes = remember(title) { title.episodes.sortedWith(compareBy({ it.season }, { it.episode })).filter { it.container != "hls" } }
    val seasons = remember(episodes) { episodes.map { it.season }.distinct() }
    var season by remember(title.id) { mutableIntStateOf(seasons.firstOrNull() ?: 1) }
    var selected by remember(title.id) { mutableStateOf(emptySet<Long>()) }
    val visible = episodes.filter { it.season == season }
    val available = visible.filter { "${title.id}_${it.id}" !in savedKeys }
    val chosen = episodes.filter { it.id in selected && "${title.id}_${it.id}" !in savedKeys }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), contentAlignment = Alignment.Center) {
            val limit = maxHeight * .92f
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = limit), shape = CardShape, color = Pts.card) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Download episodes", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Pts.text)
                        PtsIconAction(PtsIcons.Close, "Close download selection", onClose)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title.title, Modifier.weight(1f).padding(end = 8.dp), maxLines = 2, style = MaterialTheme.typography.bodySmall, color = Pts.muted)
                        SeasonDropdown(seasons, season, { season = it }, "download-season-selector")
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        TextButton({ selected = selected + available.map { it.id } }) { Text("Whole season") }
                        TextButton({ selected = selected + available.filter { "${title.id}_${it.id}" !in watched }.map { it.id } }) { Text("Unwatched") }
                        TextButton({ selected = emptySet() }) { Text("Clear") }
                    }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        if (visible.isEmpty()) item { Text("No downloadable episodes", color = Pts.muted) }
                        items(visible, key = { it.id }) { ep ->
                            val saved = "${title.id}_${ep.id}" in savedKeys
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !saved) { selected = if (ep.id in selected) selected - ep.id else selected + ep.id }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(ep.id in selected || saved, { if (!saved) selected = if (it) selected + ep.id else selected - ep.id }, enabled = !saved)
                                Text("E${ep.episode} · ${ep.title}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Pts.text, maxLines = 2)
                                if (saved) Icon(PtsIcons.Download, "Downloaded", Modifier.size(20.dp), tint = Pts.accent)
                            }
                        }
                    }
                    val estimate = chosen.sumOf { if (it.size > 0) it.size else (it.duration * 250000).toLong() }
                    Text("${chosen.size} selected · ${if (chosen.any { it.size <= 0 }) "~" else ""}${formatBytes(estimate)}", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClose, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
                        Button({ onStart(chosen) }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = chosen.isNotEmpty()) { Text("Download") }
                    }
                }
            }
        }
    }
}
