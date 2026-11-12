package online.ptsmovies.stream

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage

@Composable
fun CompactDownloads(saved: List<SavedVideo>, tasks: List<DownloadTask>, onPlay: (SavedVideo) -> Unit,
    onDelete: (SavedVideo) -> Unit, onPause: (String) -> Unit, onResume: (String) -> Unit, onCancel: (String) -> Unit,
    titles: List<Title> = emptyList(), imageLoader: coil.ImageLoader? = null, onBack: () -> Unit = {}) {
    var folder by rememberSaveable { mutableStateOf<Long?>(null) }
    var season by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf<List<SavedVideo>>(emptyList()) }
    val taskTitles = tasks.map { it.title }.distinctBy { it.id }
    val metadata = remember(titles, taskTitles, saved) { (saved.map { it.titleMetadata() } + taskTitles + titles).associateBy { it.id } }
    val ids = (saved.map { it.titleId } + tasks.filter { it.status != "complete" }.map { it.title.id }).distinct()
    fun back() { if (season != null) season = null else if (folder != null) folder = null else onBack() }
    BackHandler(folder != null || season != null) { back() }
    if (confirm.isNotEmpty()) PtsConfirmDialog(if (confirm.size == 1) "Delete download?" else "Delete ${confirm.size} downloads?",
        if (confirm.size == 1) confirm.single().label else metadata[confirm.first().titleId]?.title.orEmpty(), "Delete", destructive = true,
        onConfirm = { val snapshot = confirm; confirm = emptyList(); snapshot.forEach(onDelete) }, onDismiss = { confirm = emptyList() })
    val selectedTitle = metadata[folder]
    val motion = ptsMotionEnabled()
    LazyColumn(Modifier.fillMaxSize().ptsPageMotion("$folder/$season"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PtsIconAction(PtsIcons.Back, "Back", { back() })
                Text(if (folder == null) "My Downloads" else selectedTitle?.title.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Pts.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val selection = saved.filter { it.titleId == folder && (season == null || it.season == season) }
                if (selection.isNotEmpty()) PtsIconAction(PtsIcons.Trash, "Delete folder", { confirm = selection })
            }
        }
        if (ids.isEmpty()) item { PtsEmpty("No offline videos", icon = PtsIcons.Download) }
        if (folder == null) items(ids, key = { "title-$it" }) { id ->
            val videos = saved.filter { it.titleId == id }
            val pending = tasks.filter { it.title.id == id && it.status != "complete" }
            val title = metadata[id]
            val series = title?.kind == "series" || videos.any { it.season > 0 }
            PtsCard(Modifier.fillMaxWidth().animateItem(fadeInSpec = motionSpec(motion, 160), placementSpec = motionPlacementSpec(motion, 220), fadeOutSpec = motionSpec(motion, 100)), padding = 10.dp, onClick = if (series) ({ folder = id }) else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadPoster(videos.firstOrNull()?.poster ?: title?.poster, imageLoader)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(title?.title ?: videos.firstOrNull()?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, color = Pts.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (series) "${videos.size} episodes · ${formatBytes(videos.sumOf { it.bytes })}" else formatBytes(videos.sumOf { it.bytes }), style = MaterialTheme.typography.bodySmall, color = Pts.muted)
                        if (pending.isNotEmpty() && series) Text("${pending.size} downloading or paused", style = MaterialTheme.typography.labelSmall, color = Pts.accent)
                    }
                    if (series) PtsIconAction(PtsIcons.ChevronRight, "Open series downloads", { folder = id })
                    else videos.firstOrNull()?.let { video ->
                        PtsIconAction(PtsIcons.Play, "Play", { onPlay(video) })
                        PtsIconAction(PtsIcons.Trash, "Delete", { confirm = listOf(video) })
                    }
                }
                if (!series) pending.forEach { TransferRow(it, onPause, onResume, onCancel) }
            }
        }
        else if (season == null) {
            val seasons = (saved.filter { it.titleId == folder }.map { it.season } + tasks.filter { it.title.id == folder && it.status != "complete" }.mapNotNull { it.episode?.season }).distinct().sorted()
            items(seasons) { number ->
                val count = saved.count { it.titleId == folder && it.season == number }
                PtsCard(Modifier.fillMaxWidth(), padding = 14.dp, onClick = { season = number }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(PtsIcons.Browse, null, tint = Pts.accent)
                        Text("Season $number", Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall, color = Pts.text)
                        Text("$count", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                        PtsIconAction(PtsIcons.ChevronRight, "Open season $number", { season = number })
                    }
                }
            }
        } else {
            item { Text("Season $season", style = MaterialTheme.typography.titleMedium, color = Pts.muted) }
            items(tasks.filter { it.title.id == folder && it.episode?.season == season && it.status != "complete" }, key = { "task-${it.key}" }) { task ->
                PtsCard(Modifier.fillMaxWidth(), padding = 12.dp) { Text("E${task.episode?.episode} · ${task.episode?.title.orEmpty()}", color = Pts.text, style = MaterialTheme.typography.titleSmall); TransferRow(task, onPause, onResume, onCancel) }
            }
            items(saved.filter { it.titleId == folder && it.season == season }.sortedBy { it.episode }, key = { it.key }) { video ->
                PtsCard(Modifier.fillMaxWidth(), padding = 10.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DownloadPoster(video.poster ?: selectedTitle?.poster, imageLoader, compact = true)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text("E${video.episode} · ${video.label}", style = MaterialTheme.typography.titleSmall, color = Pts.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(formatBytes(video.bytes), style = MaterialTheme.typography.bodySmall, color = Pts.muted)
                        }
                        PtsIconAction(PtsIcons.Play, "Play", { onPlay(video) })
                        PtsIconAction(PtsIcons.Trash, "Delete", { confirm = listOf(video) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadPoster(model: Any?, imageLoader: coil.ImageLoader?, compact: Boolean = false) {
    Box(Modifier.width(if (compact) 40.dp else 54.dp).height(if (compact) 58.dp else 78.dp).clip(RoundedCornerShape(7.dp)).background(Pts.soft), contentAlignment = Alignment.Center) {
        Icon(PtsIcons.Film, null, tint = Pts.muted)
        if (model != null && model != "") {
            if (model is String && model.startsWith("https://")) PtsPosterImage(model, "Download poster", Modifier.fillMaxSize())
            else if (imageLoader == null) AsyncImage(model, "Download poster", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else AsyncImage(model, "Download poster", imageLoader, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun TransferRow(task: DownloadTask, onPause: (String) -> Unit, onResume: (String) -> Unit, onCancel: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        PtsPill(if (task.status == "waiting_wifi") "Waiting for Wi-Fi" else task.status, active = task.status == "downloading", danger = task.status == "failed")
        PtsProgressBar(task.progress.percent / 100f, height = 4.dp)
        Text("${task.progress.percent}% · ${formatBytes(task.bytes)} / ${if (task.total > 0) formatBytes(task.total) else "…"}", style = MaterialTheme.typography.bodySmall, color = Pts.muted)
        if (task.status == "downloading") Text("${formatBytes(task.speed)}/s · ETA ${task.progress.eta?.let { if (it == 0L) "<1s" else formatDuration(it) } ?: "…"}", color = Pts.accent, style = MaterialTheme.typography.bodySmall)
        if (task.status == "downloading" && task.route.isNotEmpty()) Text(task.route, style = MaterialTheme.typography.labelSmall, color = Pts.accent)
        if (task.error.isNotBlank()) Text(task.error, color = Pts.danger, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton({ if (task.status in listOf("downloading", "preparing", "queued", "waiting_wifi")) onPause(task.key) else onResume(task.key) }) { Text(if (task.status in listOf("downloading", "preparing", "queued", "waiting_wifi")) "Pause" else "Resume") }
            TextButton({ onCancel(task.key) }) { Text("Cancel", color = Pts.danger) }
        }
    }
}
