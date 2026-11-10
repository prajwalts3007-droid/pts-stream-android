package online.ptsmovies.stream

import androidx.compose.animation.animateContentSize
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
fun UpNextCard(episode: Episode, countdown: Int?, onPlay: () -> Unit, onCancel: () -> Unit) {
    PtsCard(Modifier.fillMaxWidth()) {
        Text("Up next · S${episode.season}E${episode.episode}", color = Pts.accent, style = MaterialTheme.typography.titleMedium)
        Text(episode.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Pts.text)
        if (countdown != null) Text("Playing in $countdown seconds", style = MaterialTheme.typography.bodySmall, color = Pts.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PtsPrimaryButton("Play next", Modifier.weight(1f), onClick = onPlay)
            PtsSecondaryButton("Cancel", Modifier.weight(1f), onClick = onCancel)
        }
    }
}

@Composable
fun ShortSynopsis(text: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }
    if (text.isBlank()) return
    Column(modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    onDownload: (Episode) -> Unit
) {
    val seasons = remember(title) { title.episodes.map { it.season }.distinct().sorted() }
    var season by rememberSaveable(title.id) { mutableIntStateOf(active?.season
        ?: title.episodes.firstOrNull { it.id == resume?.episodeId }?.season ?: seasons.firstOrNull() ?: 1) }
    var picker by remember { mutableStateOf(false) }
    LaunchedEffect(active?.id) { active?.let { season = it.season } }
    if (picker) Dialog(onDismissRequest = { picker = false }) {
        PtsCard(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text("Choose season", style = MaterialTheme.typography.titleLarge, color = Pts.text)
            seasons.forEach { s ->
                PtsSecondaryButton("Season $s", Modifier.fillMaxWidth(), icon = if (s == season) PtsIcons.Check else null) {
                    season = s; picker = false
                }
            }
        }
    }
    val episodes = title.episodes.filter { it.season == season }.sortedBy { it.episode }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Episodes", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Pts.text)
            PtsSecondaryButton("Season $season", Modifier.widthIn(max = 160.dp).testTag("season-selector"), icon = PtsIcons.ChevronDown) { picker = true }
        }
        Text("${episodes.size} episodes", style = MaterialTheme.typography.bodySmall, color = Pts.muted)
        if (episodes.isEmpty()) PtsEmpty("No episodes available", icon = PtsIcons.Series)
        episodes.forEach { ep ->
            Column {
                if (ep.id == active?.id) Text("NOW PLAYING", Modifier.padding(bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = Pts.accent)
                EpisodeRow(title, ep, resume?.takeIf { it.episodeId == ep.id }, "${title.id}_${ep.id}" in savedKeys,
                    downloading, { onPlay(ep) }, { onDownload(ep) })
                if (ep.description.isNotBlank()) ShortSynopsis(ep.description, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}
