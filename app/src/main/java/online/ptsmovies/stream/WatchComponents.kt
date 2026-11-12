package online.ptsmovies.stream

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Same start/end guards as web plResumePosition; completed clips must not reopen on their final frame. */
fun resumePosition(resume: Resume?, episodeId: Long, duration: Long = 0L): Long {
    if (resume == null || resume.episodeId != episodeId || resume.seconds <= 5) return 0L
    val total = duration.takeIf { it > 0 } ?: resume.duration
    return if (total > 0 && resume.seconds >= total - 20) 0L else resume.seconds
}

@Composable
fun PlayerTextControl(label: String, description: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 40.dp).semantics { contentDescription = description }.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Pts.text)
    }
}

@Composable
fun PlayerSeekButton(forward: Boolean, onClick: () -> Unit) {
    Column(Modifier.size(48.dp).semantics { contentDescription = if (forward) "Forward 10 seconds" else "Back 10 seconds" }
        .clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if (forward) PtsIcons.Refresh else PtsIcons.Retry, null, Modifier.size(28.dp), tint = Pts.text)
        Text("10s", style = MaterialTheme.typography.labelSmall, color = Pts.text)
    }
}

@Composable
fun WatchActionBar(downloaded: Boolean, completed: Boolean, series: Boolean, onComplete: () -> Unit, onSave: () -> Unit, onSeasonDownload: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val items = listOf(Triple(PtsIcons.Check, if (completed) "Completed" else "Mark completed", onComplete),
            Triple(if (downloaded) PtsIcons.Check else PtsIcons.Download, if (downloaded) "Downloaded" else "Save offline", onSave)) +
            if (series) listOf(Triple(PtsIcons.Queue, "Download episodes", onSeasonDownload)) else emptyList()
        items.forEach { (icon, label, action) ->
            Column(Modifier.weight(1f).heightIn(min = 66.dp).clickable(enabled = label != "Downloaded", onClick = action).padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(23.dp), tint = Pts.accent)
                Text(label, style = MaterialTheme.typography.labelSmall, color = Pts.text, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
fun UpNextCard(episode: Episode, countdown: Int?, onPlay: () -> Unit, onCancel: () -> Unit) {
    PtsCard(Modifier.fillMaxWidth()) {
        Text("Up next · S${episode.season}E${episode.episode}", color = Pts.accent, style = MaterialTheme.typography.titleMedium)
        Text(episode.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Pts.text)
        if (countdown != null) Text("Playing in $countdown seconds", style = MaterialTheme.typography.bodySmall, color = Pts.muted)
        PtsButtonPair(first = { PtsPrimaryButton("Play next", it, onClick = onPlay) }, second = { PtsSecondaryButton("Cancel", it, onClick = onCancel) })
    }
}

@Composable
fun ShortSynopsis(text: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }
    if (text.isBlank()) return
    Column(modifier.animateContentSize(tween(motionMillis(ptsMotionEnabled(), 180))), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, Modifier.testTag("watch-synopsis"), style = MaterialTheme.typography.bodyMedium,
            color = Pts.muted, maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis, onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow })
        if (overflow || expanded) Text(if (expanded) "Less" else "More",
            Modifier.clickable { expanded = !expanded }.padding(vertical = 6.dp),
            color = Pts.accent, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SeasonEpisodes(
    title: Title,
    active: Episode? = null,
    resume: Resume? = null,
    savedKeys: Set<String> = emptySet(),
    downloading: String = "",
    onPlay: (Episode) -> Unit,
    onDownload: (Episode) -> Unit, completed: Set<String> = emptySet(), onCompleted: ((Episode, Boolean) -> Unit)? = null
) {
    val seasons = remember(title) { title.episodes.map { it.season }.distinct().sorted() }
    var season by rememberSaveable(title.id) { mutableIntStateOf(active?.season
        ?: title.episodes.firstOrNull { it.id == resume?.episodeId }?.season ?: seasons.firstOrNull() ?: 1) }
    LaunchedEffect(active?.id) { active?.let { season = it.season } }
    LaunchedEffect(seasons) { if (season !in seasons) season = seasons.firstOrNull() ?: 1 }
    val episodes = remember(title.episodes, season) { title.episodes.filter { it.season == season }.sortedBy { it.episode } }
    Column(Modifier.fillMaxWidth().ptsPageMotion("${title.id}/$season"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Episodes", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Pts.text)
            SeasonDropdown(seasons, season, { season = it })
        }
        Text("${episodes.size} episodes", style = MaterialTheme.typography.bodySmall, color = Pts.muted)
        if (episodes.isEmpty()) PtsEmpty("No episodes available", icon = PtsIcons.Series)
        episodes.forEach { ep ->
            key(ep.id) { Column {
                if (ep.id == active?.id) Text("NOW PLAYING", Modifier.padding(bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = Pts.accent)
                EpisodeRow(title, ep, resume?.takeIf { it.episodeId == ep.id }, "${title.id}_${ep.id}" in savedKeys,
                    downloading, { onPlay(ep) }, { onDownload(ep) }, "${title.id}_${ep.id}" in completed,
                    onCompleted?.let { action -> { action(ep, "${title.id}_${ep.id}" !in completed) } })
            } }
        }
    }
}

@Composable
fun SeasonDropdown(seasons: List<Int>, selected: Int, onSelect: (Int) -> Unit, tag: String = "season-selector") {
    var expanded by remember { mutableStateOf(false) }
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(motionMillis(ptsMotionEnabled(), 150)), label = "season-chevron")
    Box {
        OutlinedButton({ expanded = true }, Modifier.heightIn(min = 48.dp).testTag(tag), enabled = seasons.isNotEmpty()) {
            Text("Season $selected", maxLines = 1)
            Icon(PtsIcons.ChevronDown, null, Modifier.padding(start = 8.dp).size(20.dp).graphicsLayer { rotationZ = angle })
        }
        DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 280.dp)) {
            seasons.forEach { season -> DropdownMenuItem(text = { Text("Season $season") },
                leadingIcon = { if (season == selected) Icon(PtsIcons.Check, "Selected season") },
                onClick = { onSelect(season); expanded = false }) }
        }
    }
}
