package online.ptsmovies.stream

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun EpisodeDownloadDialog(title: Title, savedKeys: Set<String>, watched: Set<String>, onStart: (List<Episode>) -> Unit, onClose: () -> Unit) {
    val episodes = remember(title) { title.episodes.sortedWith(compareBy({ it.season }, { it.episode })).filter { it.container != "hls" } }
    var season by remember { mutableIntStateOf(episodes.firstOrNull()?.season ?: 1) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    val visible = episodes.filter { it.season == season }
    val available = visible.filter { "${title.id}_${it.id}" !in savedKeys }
    val chosen = episodes.filter { it.id in selected }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = CardShape, color = Pts.card) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Download episodes", style = MaterialTheme.typography.titleLarge, color = Pts.text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Season", Modifier.weight(1f), color = Pts.text)
                    PtsSecondaryButton("$season", Modifier.width(80.dp)) {
                        val seasons = episodes.map { it.season }.distinct(); season = seasons.getOrNull(seasons.indexOf(season) + 1) ?: seasons.firstOrNull() ?: season
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ selected = selected + available.map { it.id } }) { Text("Whole season") }
                    TextButton({ selected = selected + available.filter { "${title.id}_${it.id}" !in watched }.map { it.id } }) { Text("Unwatched") }
                    TextButton({ selected = emptySet() }) { Text("Clear") }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    visible.forEach { ep ->
                        val saved = "${title.id}_${ep.id}" in savedKeys
                        Row(Modifier.fillMaxWidth().clickable(enabled = !saved) { selected = if (ep.id in selected) selected - ep.id else selected + ep.id }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(ep.id in selected || saved, { if (!saved) selected = if (it) selected + ep.id else selected - ep.id }, enabled = !saved)
                            Text("E${ep.episode} · ${ep.title}", style = MaterialTheme.typography.bodySmall, color = Pts.text, maxLines = 2)
                        }
                    }
                }
                val known = chosen.sumOf { it.size }
                val estimate = chosen.sumOf { if (it.size > 0) it.size else (it.duration * 250000).toLong() }
                Text("${chosen.size} selected · ${if (chosen.any { it.size <= 0 }) "~" else ""}${formatBytes(if (estimate > 0) estimate else known)}", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClose, Modifier.weight(1f)) { Text("Cancel") }
                    Button({ onStart(chosen) }, Modifier.weight(1f), enabled = chosen.isNotEmpty()) { Text("Download") }
                }
            }
        }
    }
}
