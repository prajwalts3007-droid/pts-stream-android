package online.ptsmovies.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ---------- formatting ----------

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return if (unit == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

fun formatRate(bps: Long): String = if (bps <= 0) "—" else formatBytes(bps) + "/s"

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    if (seconds < 60) return "${seconds}s"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun JSONArray?.objects(): List<JSONObject> =
    (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }

private fun JSONObject.array(key: String): List<JSONObject> = optJSONArray(key).objects()

// ---------- login ----------

@Composable
fun LoginScreen(message: String, busy: Boolean, onLogin: (String, String, Boolean) -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(true) }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val viewportHeight = maxHeight
        Box(Modifier.align(Alignment.BottomStart).offset(x = (-125).dp, y = 90.dp).size(250.dp)
            .border(27.dp, Pts.accent.copy(alpha = .09f), CircleShape))
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewportHeight)
                .padding(horizontal = 24.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(74.dp).background(Pts.accent, RoundedCornerShape(23.dp))
                    .padding(0.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.fillMaxSize().background(Pts.accent, RoundedCornerShape(23.dp))
                        .padding(2.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFB9B5FF), Pts.accent)),
                            RoundedCornerShape(21.dp)
                        )
                )
                Text("PTS", fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 24.sp, color = Pts.onAccent)
            }
            Text("PTS Library", style = MaterialTheme.typography.headlineLarge, color = Pts.text, modifier = Modifier.padding(top = 14.dp))
            Text("Your library, everywhere", style = MaterialTheme.typography.bodyMedium, color = Pts.muted, modifier = Modifier.padding(top = 3.dp, bottom = 24.dp))
            PtsCard(Modifier.fillMaxWidth(), padding = 22.dp) {
                Text("Welcome back", style = MaterialTheme.typography.headlineMedium, color = Pts.text)
                Spacer(Modifier.height(4.dp))
                PtsField(user, { user = it }, "Username or email", Modifier.fillMaxWidth(), keyboardType = KeyboardType.Email, enabled = !busy)
                PtsField(pass, { pass = it }, "Password", Modifier.fillMaxWidth(), password = true, enabled = !busy)
                Row(Modifier.fillMaxWidth().toggleable(value = keep, role = Role.Checkbox, onValueChange = { keep = it }), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(21.dp).background(if (keep) Pts.accent else Pts.soft, RoundedCornerShape(5.dp))
                            .then(if (!keep) Modifier.padding(1.dp).background(Pts.card, RoundedCornerShape(4.dp)) else Modifier),
                        contentAlignment = Alignment.Center
                    ) { if (keep) Icon(PtsIcons.Check, null, Modifier.size(15.dp), tint = Pts.onAccent) }
                    Text("Keep me signed in", style = MaterialTheme.typography.bodyMedium, color = Pts.text, modifier = Modifier.padding(start = 9.dp))
                }
                if (message.isNotBlank()) PtsErrorBanner(message)
                PtsPrimaryButton(if (busy) "Signing in…" else "Sign in", Modifier.fillMaxWidth(), enabled = !busy && user.isNotBlank() && pass.isNotBlank()) {
                    onLogin(user.trim(), pass, keep)
                }
                Text("Sessions are stored only on this device. The app never stores your password.",
                    style = MaterialTheme.typography.labelSmall, color = Pts.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------- streaming ----------

@Composable
fun StreamHomeScreen(
    library: Library,
    onOpen: (Title) -> Unit,
    onPlay: (Title) -> Unit,
    onToggleWatch: (Title) -> Unit,
    onSeeAll: (String) -> Unit,
    onMarkPlayed: (Title) -> Unit = {}
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(library.titles, query) { library.titles.filter { it.title.contains(query.trim(), true) || it.genres.any { g -> g.contains(query.trim(), true) } } }
    val featured = remember(library.titles) { library.titles.filter { it.poster.startsWith("https://") }.ifEmpty { library.titles }.take(4) }
    val continueWatching = remember(library) {
        library.titles.filter { val p = library.progress[it.id]; p != null && p.duration > 0 && p.seconds > 5 && p.seconds < p.duration - 15 }
    }
    val movies = library.titles.filter { it.kind == "movie" }
    val series = library.titles.filter { it.kind == "series" }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item { PtsPage("Stream") }
        item { PtsSearchField(query, { query = it }, "Search movies and series") }
        if (query.isNotBlank()) {
            item { Text("${results.size} results", style = MaterialTheme.typography.bodySmall, color = Pts.muted) }
            if (results.isEmpty()) item { PtsEmpty("No matching titles", actionLabel = "Clear search", icon = PtsIcons.Search) { query = "" } }
            results.chunked(2).forEach { pair -> item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { t -> PtsPosterCard(t, library.progress[t.id], t.id in library.watch, Modifier.weight(1f), width = null,
                        onOpen = { onOpen(t) }, onToggleWatch = { onToggleWatch(t) }) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            } }
        } else if (library.titles.isEmpty()) {
            item { PtsEmpty("Your library is empty", icon = PtsIcons.Film) }
        } else {
            if (featured.isNotEmpty()) {
                item {
                    val pager = rememberPagerState(pageCount = { featured.size })
                    val pagerScope = rememberCoroutineScope()
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        HorizontalPager(state = pager, pageSpacing = 12.dp, modifier = Modifier.fillMaxWidth()) { index ->
                            val t = featured[index]
                            PtsHero(t, t.id in library.watch, { onOpen(t) }, { onPlay(t) }, { onToggleWatch(t) })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            repeat(featured.size) { index ->
                                val on = pager.currentPage == index
                                Box(
                                    Modifier.padding(horizontal = 4.dp).height(9.dp)
                                        .width(if (on) 24.dp else 9.dp)
                                        .background(if (on) Pts.accent else Pts.line2, RoundedCornerShape(99.dp))
                                        .clickable { pagerScope.launch { pager.animateScrollToPage(index) } }
                                )
                            }
                        }
                    }
                }
            }
            if (continueWatching.isNotEmpty()) item {
                PtsPosterShelf("Continue watching", continueWatching, library.progress, library.watch, onOpen, onToggleWatch, onMarkPlayed)
            }
            if (movies.isNotEmpty()) item {
                Column {
                    PtsSectionHeader("Movies") {
                        Text("See all", style = MaterialTheme.typography.labelLarge, color = Pts.accent,
                            modifier = Modifier.clickable { onSeeAll("Movies") }.padding(start = 8.dp))
                    }
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        items(movies, key = { it.id }) { t ->
                            PtsPosterCard(t, library.progress[t.id], t.id in library.watch, onOpen = { onOpen(t) }, onToggleWatch = { onToggleWatch(t) })
                        }
                    }
                }
            }
            if (series.isNotEmpty()) item {
                Column {
                    PtsSectionHeader("TV Shows") {
                        Text("See all", style = MaterialTheme.typography.labelLarge, color = Pts.accent,
                            modifier = Modifier.clickable { onSeeAll("Series") }.padding(start = 8.dp))
                    }
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        items(series, key = { it.id }) { t ->
                            PtsPosterCard(t, library.progress[t.id], t.id in library.watch, onOpen = { onOpen(t) }, onToggleWatch = { onToggleWatch(t) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BrowseScreen(
    tab: String,
    titles: List<Title>,
    progress: Map<Long, Resume>,
    watch: Set<Long>,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (Title) -> Unit,
    onToggleWatch: (Title) -> Unit,
    initialView: String = "grid",
    onViewChange: (String) -> Unit = {}
) {
    var view by rememberSaveable(tab) { mutableStateOf(initialView) }
    var genre by remember(tab) { mutableStateOf("") }
    val genres = remember(tab, titles) {
        when (tab) {
            "Movies" -> titles.flatMap { it.genres }.distinct().sorted()
            "Series" -> titles.flatMap { it.genres }.distinct().sorted()
            else -> emptyList()
        }.take(10)
    }
    val filtered = remember(titles, genre, query, watch, tab) {
        titles.filter { t ->
            (tab != "Watchlist" || t.id in watch) &&
                (genre.isBlank() || t.genres.any { it.equals(genre, true) }) &&
                (query.isBlank() || t.title.contains(query, true) || t.genres.any { it.contains(query, true) })
        }
    }
    val label = when (tab) { "Movies" -> "Movies"; "Series" -> "TV Shows"; "Watchlist" -> "My List"; else -> tab }
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (view == "list") 1 else 2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PtsPage(label)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PtsSearchField(query, onQuery, "Search", Modifier.weight(1f))
                PtsIconAction(if (view == "grid") PtsIcons.List else PtsIcons.Grid, if (view == "grid") "Switch to list view" else "Switch to grid view", {
                    view = if (view == "grid") "list" else "grid"; onViewChange(view)
                }, tint = Pts.accent)
            }
        }
        if (genres.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    PtsGenreChip("All", genre.isBlank()) { genre = "" }
                    genres.forEach { g -> PtsGenreChip(g, genre == g) { genre = if (genre == g) "" else g } }
                }
            }
        }
        if (query.isNotBlank() || genre.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }) {
            PtsSecondaryButton("Clear search and filters", Modifier.fillMaxWidth()) { genre = ""; onQuery("") }
        }
        if (filtered.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PtsEmpty(
                    if (tab == "Watchlist") "Nothing saved yet" else "No matching titles",
                    if (tab == "Watchlist") "Use the + button on any poster to add it to My List." else "Try a different search or genre.",
                    actionLabel = if (tab == "Watchlist") null else "Clear filters",
                    icon = if (tab == "Watchlist") PtsIcons.Watchlist else PtsIcons.Search
                ) { genre = ""; onQuery("") }
            }
        }
        items(filtered, key = { it.id }) { t ->
            if (view == "grid") PtsPosterCard(t, progress[t.id], t.id in watch, width = null, onOpen = { onOpen(t) }, onToggleWatch = { onToggleWatch(t) })
            else PtsTitleListRow(t, t.id in watch, { onOpen(t) }, { onToggleWatch(t) })
        }
    }
}

@Composable
private fun PtsGenreChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (active) Pts.accent else Pts.card)
            .border(2.dp, if (active) Pts.accent else Pts.line, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (active) Pts.onAccent else Pts.muted)
    }
}

@Composable
fun TitleScreen(
    title: Title,
    resume: Resume?,
    marked: Boolean,
    savedKeys: Set<String>,
    downloading: String,
    onBack: () -> Unit,
    onPlay: (Episode?) -> Unit,
    onToggleWatch: () -> Unit,
    onDownload: (Episode?) -> Unit,
    onMarkPlayed: () -> Unit = {}
) {
    val episodes = remember(title) { title.episodes.sortedWith(compareBy({ it.season }, { it.episode })) }
    LazyColumn(
        modifier = Modifier.testTag("title-details"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                PtsIconAction(PtsIcons.Back, "Back", onBack)
                Text("Details", style = MaterialTheme.typography.labelLarge, color = Pts.muted, modifier = Modifier.padding(start = 2.dp))
            }
        }
        item {
            PtsCard(Modifier.fillMaxWidth(), padding = 14.dp) {
                Row {
                    Box(Modifier.width(116.dp).aspectRatio(2f / 3f).clip(PosterShape).background(Pts.soft)
                        .border(1.dp, Pts.line, PosterShape)) {
                        if (title.poster.startsWith("https://")) AsyncImage(title.poster, "Poster", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(title.title.take(1).uppercase(), fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 36.sp, color = Pts.accent)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(title.title, style = MaterialTheme.typography.headlineSmall, color = Pts.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(title.year.takeIf { it > 0 }?.toString(), if (title.kind == "series") "TV Show" else "Movie", formatDuration(title.episodes.firstOrNull()?.duration?.toLong() ?: 0)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Pts.muted
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            title.genres.take(2).forEach { g -> PtsPill(g) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val resumeAt = resume?.takeIf { resumePosition(it, if (title.kind == "movie") 0L else it.episodeId) > 0 }
                    PtsPrimaryButton(
                        if (resumeAt != null) "Resume" else if (title.kind == "series") "Play series" else "Play",
                        Modifier.weight(1f), icon = PtsIcons.Play,
                        enabled = title.kind == "movie" || episodes.isNotEmpty()
                    ) { onPlay(if (title.kind == "movie") null else episodes.firstOrNull { it.id == resume?.episodeId } ?: episodes.firstOrNull()) }
                    PtsSecondaryButton(if (marked) "In list" else "My list", Modifier.weight(1f), icon = if (marked) PtsIcons.Check else PtsIcons.Plus) { onToggleWatch() }
                }
                if (resume != null && resume.seconds > 0) {
                    PtsProgressBar(if (resume.duration > 0) resume.seconds.toFloat() / resume.duration else 0f, tint = Pts.spark)
                    Text("${formatDuration(resume.seconds)} watched", style = MaterialTheme.typography.labelSmall, color = Pts.muted)
                }
            }
        }
        if (title.synopsis.isNotBlank()) item {
            PtsCard(Modifier.fillMaxWidth()) {
                Text("About", style = MaterialTheme.typography.titleMedium, color = Pts.text)
                ShortSynopsis(title.synopsis)
            }
        }
        if (title.kind == "series") {
            item { PtsSecondaryButton("Download episodes", Modifier.fillMaxWidth(), icon = PtsIcons.Download) { onDownload(null) } }
            item {
                SeasonEpisodes(title, resume = resume, savedKeys = savedKeys, downloading = downloading,
                    onPlay = { onPlay(it) }, onDownload = { onDownload(it) })
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PtsSecondaryButton(
                        if ("${title.id}_0" in savedKeys) "Downloaded" else "Save offline",
                        Modifier.fillMaxWidth(), icon = if ("${title.id}_0" in savedKeys) PtsIcons.Check else PtsIcons.Download,
                        enabled = "${title.id}_0" !in savedKeys
                    ) { onDownload(null) }
                }
            }
        }
        if (resume != null) item {
            PtsSecondaryButton("Mark completed", Modifier.fillMaxWidth(), icon = PtsIcons.Check, onClick = onMarkPlayed)
        }
    }
}

@Composable
internal fun EpisodeRow(
    title: Title,
    ep: Episode,
    resume: Resume?,
    saved: Boolean,
    downloading: String,
    onPlay: () -> Unit,
    onDownload: () -> Unit
) {
    PtsCard(Modifier.fillMaxWidth(), padding = 10.dp, onClick = onPlay) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Pts.soft)
                    .border(1.dp, Pts.line, RoundedCornerShape(13.dp)).clickable(onClick = onPlay),
                contentAlignment = Alignment.Center
            ) { Icon(PtsIcons.Play, "Play episode", Modifier.size(22.dp), tint = Pts.accent) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("E${ep.episode} · ${ep.title.ifBlank { title.title }}", style = MaterialTheme.typography.titleSmall, color = Pts.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(formatDuration(ep.duration.toLong()).takeIf { it.isNotBlank() }, if (saved) "On this device" else null).joinToString(" · ").ifBlank { "Ready to stream" },
                    style = MaterialTheme.typography.labelSmall, color = if (saved) Pts.ok else Pts.muted
                )
                if (resume != null && resume.seconds > 0) PtsProgressBar(
                    if (resume.duration > 0) resume.seconds.toFloat() / resume.duration else 0f, height = 4.dp, tint = Pts.spark
                )
            }
            PtsIconAction(
                if (saved) PtsIcons.Check else PtsIcons.Download,
                if (saved) "Saved" else "Save offline",
                onDownload,
                tint = if (saved) Pts.ok else if (downloading.isBlank()) Pts.text else Pts.muted,
                size = 36.dp
            )
        }
    }
}

@Composable
fun DownloadsScreen(saved: List<SavedVideo>, onPlay: (SavedVideo) -> Unit, onDelete: (SavedVideo) -> Unit,
    tasks: List<DownloadTask> = emptyList(), onPause: (String) -> Unit = {}, onResume: (String) -> Unit = {}, onCancel: (String) -> Unit = {},
    titles: List<Title> = emptyList(), imageLoader: coil.ImageLoader? = null, onBack: () -> Unit = {}) {
    CompactDownloads(saved, tasks, onPlay, onDelete, onPause, onResume, onCancel, titles, imageLoader, onBack)
}

@Composable
fun ProfileScreen(user: JSONObject, savedCount: Int, onDownloads: () -> Unit, onUpdate: () -> Unit, onSection: (String) -> Unit, onSignOut: () -> Unit,
    avatar: String? = null, imageLoader: coil.ImageLoader? = null, theme: String = "dark", onTheme: (String) -> Unit = {},
    onEdit: () -> Unit = {}, onPhoto: () -> Unit = {}, onRemovePhoto: () -> Unit = {}, onPlayback: () -> Unit = {}, onDownloadSettings: () -> Unit = {}) {
    val name = user.optString("name").ifBlank { user.optString("username") }
    val role = user.optString("role")
    LazyColumn(
        modifier = Modifier.testTag("profile-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PtsPage("Profile") }
        item {
            PtsCard(Modifier.fillMaxWidth(), padding = 20.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(60.dp).background(Pts.soft, CircleShape).border(2.dp, Pts.line2, CircleShape), contentAlignment = Alignment.Center) {
                        Text(name.take(1).uppercase(), fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 24.sp, color = Pts.accent)
                        if (avatar != null && imageLoader != null) AsyncImage(avatar, "Profile photo", imageLoader = imageLoader,
                            modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(name, style = MaterialTheme.typography.titleLarge, color = Pts.text)
                        Text("@" + user.optString("username"), style = MaterialTheme.typography.bodySmall, color = Pts.muted)
                        PtsPill(role.ifBlank { "user" }, active = role == "admin")
                    }
                }
            }
        }
        item {
            PtsCard(Modifier.fillMaxWidth()) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium, color = Pts.text)
                PtsSegmented(listOf("system" to "System", "light" to "Light", "dark" to "Dark"), theme, onTheme)
                PtsSecondaryButton("Edit profile", Modifier.fillMaxWidth(), onClick = onEdit)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PtsSecondaryButton("Change photo", Modifier.weight(1f), onClick = onPhoto)
                    if (avatar != null) PtsSecondaryButton("Remove photo", Modifier.weight(1f), onClick = onRemovePhoto)
                }
            }
        }
        if (role == "admin") {
            item {
                PtsCard(Modifier.fillMaxWidth()) {
                    Text("Sections", style = MaterialTheme.typography.titleMedium, color = Pts.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PtsSecondaryButton("Hub", Modifier.weight(1f)) { onSection("Hub") }
                        PtsSecondaryButton("Files", Modifier.weight(1f)) { onSection("Files") }
                        PtsSecondaryButton("Stream", Modifier.weight(1f)) { onSection("Stream") }
                    }
                }
            }
        }
        item {
            PtsCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                MenuRow(PtsIcons.Download, "My Downloads", if (savedCount > 0) "$savedCount on this device" else "Nothing saved") { onDownloads() }
                MenuDivider()
                MenuRow(PtsIcons.Settings, "Downloads & network", "") { onDownloadSettings() }
                MenuDivider()
                MenuRow(PtsIcons.Play, "Playback", "") { onPlayback() }
                MenuDivider()
                MenuRow(PtsIcons.Refresh, "Check for updates", "Signed releases only") { onUpdate() }
                MenuDivider()
                MenuRow(PtsIcons.Shield, "Session", "Signed in · ${role.ifBlank { "user" }}") { }
                MenuDivider()
                MenuRow(PtsIcons.Info, "About", "PTS Library ${BuildConfig.VERSION_NAME}") { }
            }
        }
        item {
            PtsSecondaryButton("Sign out", Modifier.fillMaxWidth(), icon = PtsIcons.Logout, danger = true) { onSignOut() }
        }
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(21.dp), tint = Pts.accent)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Pts.text)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = Pts.muted)
        }
        Icon(PtsIcons.ChevronRight, null, Modifier.size(17.dp), tint = Pts.line2)
    }
}

@Composable
private fun MenuDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Pts.line))
}

// ---------- hub ----------

@Composable
fun DashboardScreen(
    userName: String,
    metrics: JSONObject?,
    target: String,
    onTarget: (String) -> Unit,
    onRefresh: () -> Unit,
    queue: List<JSONObject>,
    completed: List<JSONObject>,
    onQueue: () -> Unit
) {
    val host = metrics?.optJSONObject(target)
    val online = host?.optBoolean("online") == true
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PtsPage("Dashboard") {
                PtsIconAction(PtsIcons.Refresh, "Refresh metrics", onRefresh, tint = Pts.muted)
            }
        }
        item {
            PtsCard(Modifier.fillMaxWidth()) {
                PtsSegmented(listOf("vps" to "VPS", "pi" to "Pi"), target, onTarget)
            }
        }
        if (host == null || !online) {
            item {
                PtsEmpty("No metrics for this device", "The agent is offline or has not reported yet. Refresh after it reconnects.", actionLabel = "Refresh", icon = PtsIcons.Info) { onRefresh() }
            }
        } else {
            item {
                val cpu = host.optDouble("cpu_percent").toFloat() / 100f
                val mem = host.optDouble("mem_percent").toFloat() / 100f
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PtsStatTile("CPU", "${(cpu * 100).toInt()}%",
                        "${host.optInt("cpu_cores")} cores · load ${String.format(Locale.US, "%.2f", host.optDouble("load1"))}",
                        cpu, Pts.accent, Modifier.weight(1f).fillMaxHeight())
                    PtsStatTile("Memory", "${(mem * 100).toInt()}%",
                        "${formatBytes(host.optLong("mem_used_bytes"))} / ${formatBytes(host.optLong("mem_total_bytes"))}",
                        mem, Pts.spark, Modifier.weight(1f).fillMaxHeight())
                }
            }
            item {
                val disk = host.optDouble("disk_percent").toFloat() / 100f
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PtsStatTile("Storage" + host.optString("disk_path").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        "${(disk * 100).toInt()}%",
                        "${formatBytes(host.optLong("disk_used_bytes"))} / ${formatBytes(host.optLong("disk_total_bytes"))}",
                        disk, Pts.ok, Modifier.weight(1f).fillMaxHeight())
                    PtsNetworkTile(
                        formatRate(host.optLong("net_rx_bps")), formatRate(host.optLong("net_tx_bps")),
                        "↓${formatBytes(host.optLong("net_rx_total_bytes"))}  ↑${formatBytes(host.optLong("net_tx_total_bytes"))}",
                        Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

private val stageOrder = listOf("queued", "dispatched", "downloading", "compressing", "uploading", "to_pi", "failed", "done")
private fun stageLabel(stage: String) = when (stage) {
    "to_pi" -> "To Pi"; "done" -> "Completed"; "dispatched" -> "Starting"; else -> stage.replaceFirstChar { it.uppercase() }
}

@Composable
fun QueueScreen(
    jobs: List<JSONObject>,
    completed: List<JSONObject>,
    stage: String,
    onStage: (String) -> Unit,
    onRefresh: () -> Unit,
    onDelete: (String) -> Unit,
    onCancelStage: (String, List<String>) -> Unit,
    onRetry: (String?) -> Unit = {},
    onEdit: (JSONObject, String) -> Unit = { _, _ -> },
    busy: Boolean = false
) {
    var confirmStage by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<List<String>>(emptyList()) }
    var deleteJob by remember { mutableStateOf<JSONObject?>(null) }
    var clearCompleted by remember { mutableStateOf<List<String>?>(null) }
    var retryJob by remember { mutableStateOf<JSONObject?>(null) }
    var retryAll by remember { mutableStateOf(false) }
    var editJob by remember { mutableStateOf<JSONObject?>(null) }
    if (retryAll || retryJob != null) PtsConfirmDialog("Retry failed jobs", if (retryAll)
        "Re-queue all failed jobs with their existing download links?" else "Retry ${retryJob!!.optString("title")}?",
        "Retry", confirmEnabled = !busy,
        onConfirm = { val id = retryJob?.optString("job_id"); retryAll = false; retryJob = null; onRetry(id) },
        onDismiss = { retryAll = false; retryJob = null })
    editJob?.let { job ->
        var url by remember(job.optString("job_id")) { mutableStateOf(job.optString("download_url")) }
        PtsConfirmDialog("Change download link", "${job.optString("title")}\nThe job restarts from the beginning with the new link, and the file on the Pi is overwritten.",
            "Restart job", destructive = true, confirmEnabled = validSourceLink(url) && !busy,
            onConfirm = { editJob = null; onEdit(job, url.trim()) }, onDismiss = { editJob = null }) {
            PtsField(url, { url = it }, "Download link", Modifier.fillMaxWidth())
        }
    }
    clearCompleted?.let { ids ->
        PtsConfirmDialog("Clear completed?", "Remove ${ids.size} completed records? Files already on the Pi are kept.", "Clear",
            onConfirm = { clearCompleted = null; onCancelStage("done", ids) }, onDismiss = { clearCompleted = null })
    }
    deleteJob?.let { job ->
        PtsConfirmDialog("Remove job?", "Remove ${job.optString("title")} from the queue? This cannot be undone.",
            "Remove", destructive = true,
            onConfirm = { onDelete(job.optString("job_id")); deleteJob = null }, onDismiss = { deleteJob = null })
    }
    val visible = if (stage == "done") completed else jobs.filter { it.optString("stage", "queued") == stage }
    val stageName = stageLabel(stage)
    if (confirmStage != null) {
        val confirmationName = stageLabel(confirmStage!!)
        var phrase by remember(confirmStage) { mutableStateOf("") }
        PtsConfirmDialog(
            "Cancel ${stageLabel(confirmStage!!).lowercase()} jobs",
            "Delete the ${snapshot.size} job${if (snapshot.size == 1) "" else "s"} shown when you opened this dialog? This cannot be undone. Type ${confirmationName.uppercase()} to confirm.",
            "Delete ${snapshot.size}",
            destructive = true,
            confirmEnabled = phrase.equals(confirmationName, true) && snapshot.isNotEmpty(),
            onConfirm = { val s = confirmStage!!; val ids = snapshot; confirmStage = null; onCancelStage(s, ids) },
            onDismiss = { confirmStage = null }
        ) {
            PtsField(phrase, { phrase = it }, "${confirmationName.uppercase()} to confirm", Modifier.fillMaxWidth())
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            PtsPage("Queue") {
                PtsIconAction(PtsIcons.Refresh, "Refresh queue", onRefresh, tint = Pts.muted)
            }
        }
        item {
            val icons = listOf(PtsIcons.StageQueued, PtsIcons.StageStarting, PtsIcons.Download, PtsIcons.StageCompress, PtsIcons.Upload, PtsIcons.StagePi, PtsIcons.StageFailed, PtsIcons.Check)
            WebIconTabs(stageOrder.mapIndexed { index, s ->
                WebTab(s, stageLabel(s), icons[index], if (s == "done") completed.size else jobs.count { it.optString("stage", "queued") == s })
            } + WebTab("retry", "Retry failed jobs", PtsIcons.Retry, jobs.count { it.optString("stage") == "failed" }, enabled = !busy && jobs.any { it.optString("stage") == "failed" }), stage,
                { if (it == "retry") retryAll = true else onStage(it) }, "queue-stage-row", fit = true)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stageName, style = MaterialTheme.typography.titleLarge, color = Pts.text, modifier = Modifier.weight(1f))
                Text("${visible.size}", style = MaterialTheme.typography.titleMedium, color = Pts.accent)
                if (stage == "done" && completed.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    PtsSecondaryButton("Clear completed", Modifier.widthIn(max = 176.dp)) {
                        clearCompleted = completed.mapNotNull { it.optString("job_id").takeIf(String::isNotEmpty) }.distinct()
                    }
                }
                if (stage != "done" && visible.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    PtsSecondaryButton("Cancel all", Modifier.widthIn(max = 132.dp), danger = true) {
                        snapshot = visible.mapNotNull { it.optString("job_id").takeIf(String::isNotEmpty) }
                        confirmStage = stage
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            item { PtsEmpty("No ${stageName.lowercase()} jobs", icon = PtsIcons.Queue) }
        }
        items(visible) { job ->
            QueueJobRow(
                job = job,
                onDelete = { deleteJob = job }, onRetry = { retryJob = job }, onEdit = { editJob = job }, busy = busy
            )
        }
    }
}

@Composable
private fun StageTile(label: String, count: Int, active: Boolean, stage: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = when (stage) { "failed" -> Pts.danger; "done" -> Pts.ok; else -> Pts.accent }
    Box(
        modifier.height(58.dp).clip(RoundedCornerShape(13.dp))
            .background(if (active) Pts.accentSoft else Pts.card)
            .border(2.dp, if (active) Pts.accent else Pts.line, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) Pts.accent else Pts.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count", fontFamily = Nunito, fontWeight = FontWeight.Black, fontSize = 15.sp, color = if (count > 0) tint else Pts.muted)
        }
    }
}

@Composable
private fun QueueJobRow(job: JSONObject, onDelete: () -> Unit, onRetry: () -> Unit, onEdit: () -> Unit, busy: Boolean) {
    val stage = job.optString("stage", "queued")
    val progress = (job.optDouble("progress", 0.0) / 100).toFloat().takeIf { it.isFinite() } ?: 0f
    val failed = stage == "failed"
    val done = stage == "done"
    val tags = buildList {
        if (job.optInt("season") > 0) add("S%02dE%02d".format(job.optInt("season"), job.optInt("episode")))
        if (job.optInt("year") > 0) add(job.optInt("year").toString())
        add(if (job.optString("type") == "series") "series" else "movie")
    }
    PtsCard(Modifier.fillMaxWidth(), padding = 13.dp, borderColor = when { failed -> Pts.danger.copy(alpha = .7f); done -> Pts.ok.copy(alpha = .6f); else -> Pts.line }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(job.optString("title").ifBlank { job.optString("job_id") }, style = MaterialTheme.typography.titleSmall, color = Pts.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("· ${tags.joinToString(" · ")}", style = MaterialTheme.typography.labelSmall, color = Pts.muted, maxLines = 1)
            }
            if (!busy) {
                if (failed) PtsIconAction(PtsIcons.Retry, "Retry job", onRetry, size = 40.dp)
                PtsIconAction(PtsIcons.Edit, "Change download link", onEdit, size = 40.dp)
                PtsIconAction(PtsIcons.Close, "Remove", onDelete, tint = Pts.danger, size = 40.dp)
            }
        }
        PtsProgressBar(if (done) 1f else progress, tint = when { failed -> Pts.danger; done -> Pts.ok; stage == "downloading" -> Pts.spark; else -> Pts.accent })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stageLabel(stage), style = MaterialTheme.typography.labelMedium, color = when { failed -> Pts.danger; done -> Pts.ok; else -> Pts.text })
            Text(
                " · " + listOfNotNull(
                    job.optString("message").takeIf { it.isNotBlank() },
                    job.optString("error").takeIf { it.isNotBlank() },
                    if (progress > 0) "${(progress * 100).toInt()}%" else null
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = Pts.muted, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun validSourceLink(value: String): Boolean = runCatching {
    val uri = java.net.URI(value.trim())
    uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)

@Composable
fun SearchScreen(query: String, onQuery: (String) -> Unit, results: List<JSONObject>, loading: Boolean) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PtsPage("Ingest") }
        item { PtsSearchField(query, onQuery, "Title or IMDb ID") }
        if (loading) item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                PtsProgressBar(0f)
                Text("Searching…", style = MaterialTheme.typography.labelSmall, color = Pts.muted)
            }
        }
        if (query.length >= 2 && results.isEmpty() && !loading) item {
            PtsEmpty("No results", "Try the full title or an IMDb ID.", icon = PtsIcons.Search)
        }
        items(results) { result ->
            PtsCard(Modifier.fillMaxWidth(), padding = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(54.dp).height(80.dp).clip(RoundedCornerShape(9.dp)).background(Pts.soft).border(1.dp, Pts.line, RoundedCornerShape(9.dp))) {
                        val poster = result.optString("poster")
                        if (poster.startsWith("https://")) AsyncImage(poster, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(result.optString("title").take(1).uppercase(), fontFamily = Nunito, fontWeight = FontWeight.Black, color = Pts.accent)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(result.optString("title"), style = MaterialTheme.typography.titleSmall, color = Pts.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(result.optString("year").takeIf { it.isNotBlank() }, result.optString("type").takeIf { it.isNotBlank() }).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = Pts.muted
                        )
                    }
                    PtsPill(result.optString("type").ifBlank { "title" })
                }
            }
        }
    }
}

@Composable
fun TransfersScreen(ops: List<JSONObject>, onCancel: (String) -> Unit, onClear: () -> Unit = {},
    relay: List<JSONObject> = emptyList(), onRelayAction: (JSONObject, Boolean) -> Unit = { _, _ -> }, onLink: (String, Boolean) -> Unit = { _, _ -> }) {
    var clear by remember { mutableStateOf(false) }
    var relayConfirm by remember { mutableStateOf<Pair<JSONObject, Boolean>?>(null) }
    relayConfirm?.let { (job, active) -> PtsConfirmDialog(if (active) "Cancel ZIP transfer?" else "Remove ZIP transfer?",
        job.optString("filename"), if (active) "Cancel transfer" else "Remove", destructive = true,
        onConfirm = { relayConfirm = null; onRelayAction(job, active) }, onDismiss = { relayConfirm = null }) }
    if (clear) PtsConfirmDialog("Clear transfers?", "Remove finished transfer records? Running operations and files are kept.", "Clear",
        onConfirm = { clear = false; onClear() }, onDismiss = { clear = false })
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PtsPage("Transfers") }
        if (ops.any { it.optString("state") != "running" }) item {
            PtsSecondaryButton("Clear", Modifier.fillMaxWidth()) { clear = true }
        }
        if (ops.isEmpty()) item { PtsEmpty("No transfers", icon = PtsIcons.Transfers) }
        items(ops) { op ->
            val running = op.optString("state") == "running"
            PtsCard(Modifier.fillMaxWidth(), padding = 13.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Pts.soft), contentAlignment = Alignment.Center) {
                        Icon(if (op.optString("kind") == "upload") PtsIcons.Upload else PtsIcons.Transfers, null, Modifier.size(19.dp), tint = Pts.accent)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(op.optString("detail").ifBlank { op.optString("kind") }, style = MaterialTheme.typography.titleSmall, color = Pts.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${op.optString("kind").replaceFirstChar { it.uppercase() }} · ${op.optString("state")}", style = MaterialTheme.typography.labelSmall, color = if (running) Pts.accent else Pts.muted)
                    }
                    if (running) PtsIconAction(PtsIcons.Close, "Cancel transfer", { onCancel(op.optString("id")) }, tint = Pts.danger, size = 34.dp)
                    else PtsPill(op.optString("state"), active = op.optString("state") == "done")
                }
                if (running) PtsProgressBar((op.optDouble("pct") / 100).toFloat())
                if (op.optString("err").isNotBlank()) Text(op.optString("err"), style = MaterialTheme.typography.labelSmall, color = Pts.danger)
            }
        }
        if (relay.isNotEmpty()) {
            item { PtsSectionHeader("Previous ZIP transfers", relay.size) }
            items(relay) { job ->
                val stage = job.optString("stage", "queued")
                val active = stage in setOf("queued", "starting", "downloading", "transferring")
                PtsCard(Modifier.fillMaxWidth(), padding = 13.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(job.optString("filename").ifBlank { "ZIP transfer" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Pts.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        PtsIconAction(PtsIcons.Close, if (active) "Cancel ZIP transfer" else "Remove ZIP transfer", { relayConfirm = job to active }, tint = Pts.danger)
                    }
                    PtsPill(stage, active = active, danger = stage == "failed")
                    PtsProgressBar(if (stage == "done") 1f else (job.optDouble("progress", 0.0) / 100).toFloat())
                    Text(job.optString("error").ifBlank { job.optString("message") }, style = MaterialTheme.typography.bodySmall, color = Pts.muted)
                    val link = job.optString("manifest_url").ifBlank { job.optString("pixeldrain_id").takeIf { it.isNotBlank() }?.let { "https://pixeldrain.com/u/" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty() }
                    if (stage == "done" && link.startsWith("https://")) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PtsSecondaryButton("Open link", Modifier.weight(1f)) { onLink(link, false) }
                        PtsSecondaryButton("Copy", Modifier.weight(1f)) { onLink(link, true) }
                    }
                }
            }
        }
    }
}

// ---------- settings ----------
