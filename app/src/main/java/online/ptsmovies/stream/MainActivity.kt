package online.ptsmovies.stream

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    private val api = PtsApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var offline: OfflineStore

    private var account by mutableStateOf<JSONObject?>(null)
    private var library by mutableStateOf<Library?>(null)
    private var loading by mutableStateOf(true)
    private var startupError by mutableStateOf("")
    private var message by mutableStateOf("")
    private var messageOk by mutableStateOf(false)

    private var mode by mutableStateOf("Stream")
    private var hubTab by mutableStateOf("Dashboard")
    private var filesTab by mutableStateOf("Browse")
    private var streamTab by mutableStateOf("Home")
    private val browseQueries = mutableStateMapOf<String, String>()
    private var profileOpen by mutableStateOf(false)
    private var downloadsOpen by mutableStateOf(false)
    private var selected by mutableStateOf<Title?>(null)

    private var episode by mutableStateOf<Episode?>(null)
    private var playing by mutableStateOf(false)
    private var player: ExoPlayer? = null
    private var downloadJob: Job? = null
    private var downloading by mutableStateOf("")
    private var downloadPercent by mutableIntStateOf(0)

    private var metrics by mutableStateOf<JSONObject?>(null)
    private var metricsTarget by mutableStateOf("vps")
    private var queueJobs by mutableStateOf(emptyList<JSONObject>())
    private var queueDone by mutableStateOf(emptyList<JSONObject>())
    private var queueStage by mutableStateOf("queued")
    private var users by mutableStateOf(emptyList<JSONObject>())
    private var githubConfigured by mutableStateOf(false)

    private var searchQuery by mutableStateOf("")
    private var searchResults by mutableStateOf(emptyList<JSONObject>())
    private var searchLoading by mutableStateOf(false)
    private var searchJob: Job? = null

    private var filesDevice by mutableStateOf("vps")
    private var filesPath by mutableStateOf("")
    private var filesEntries by mutableStateOf(emptyList<JSONObject>())
    private var filesLoading by mutableStateOf(false)
    private var ops by mutableStateOf(emptyList<JSONObject>())

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        window.statusBarColor = android.graphics.Color.rgb(17, 19, 45)
        window.navigationBarColor = android.graphics.Color.rgb(32, 35, 67)
        scope.launch { restoreSession() }
        setContent {
            PtsTheme {
                PtsBackground {
                    when {
                        loading -> PtsLoading()
                        account == null -> LoginScreen(startupError, busy = false) { user, pass -> signIn(user, pass) }
                        playing && selected != null -> PlayerScreen(selected!!, episode, offline.find(selected!!, episode)) {
                            playing = false
                            episode = null
                        }
                        else -> AppShell()
                    }
                }
            }
        }
    }

    private suspend fun restoreSession() {
        loading = true
        try {
            account = api.me()
            offline = OfflineStore(this@MainActivity, api, account!!.optLong("id"))
            mode = if (account!!.optString("role") == "admin") "Hub" else "Stream"
            startupError = ""
            refreshLibrary()
            if (account!!.optString("role") == "admin") {
                loadMetrics()
                loadQueue()
                loadSettings()
            }
        } catch (e: Exception) {
            startupError = ""
            library = null
        } finally { loading = false }
    }

    private fun signIn(user: String, password: String) {
        scope.launch {
            loading = true
            startupError = ""
            try {
                account = api.login(user, password)
                offline = OfflineStore(this@MainActivity, api, account!!.optLong("id"))
                mode = if (account!!.optString("role") == "admin") "Hub" else "Stream"
                toast("Welcome back, " + account!!.optString("name").ifBlank { account!!.optString("username") }, ok = true)
                refreshLibrary()
                if (account!!.optString("role") == "admin") {
                    loadMetrics(); loadQueue(); loadSettings()
                }
            } catch (e: Exception) {
                startupError = e.message ?: "Sign in failed"
            } finally { loading = false }
        }
    }

    private suspend fun refreshLibrary() {
        try {
            library = api.library()
        } catch (e: Exception) {
            message = "Library unavailable: ${e.message}"
        }
    }

    private fun signOut() {
        downloadJob?.cancel()
        scope.launch {
            try { api.logout() } finally {
                account = null; library = null; selected = null; profileOpen = false; downloadsOpen = false
                queueJobs = emptyList(); queueDone = emptyList(); metrics = null; users = emptyList()
            }
        }
    }

    private fun toast(text: String, ok: Boolean = false) {
        message = text
        messageOk = ok
    }

    private fun admin(): Boolean = account?.optString("role") == "admin"

    private fun enterMode(next: String) {
        if (!admin() && next != "Stream") return
        mode = next
        profileOpen = false; downloadsOpen = false; selected = null; playing = false
    }

    private fun selectTab(name: String) {
        when (mode) { "Hub" -> hubTab = name; "Files" -> filesTab = name; else -> streamTab = name }
        selected = null; profileOpen = false; downloadsOpen = false
    }

    private fun swipeMode(forward: Boolean) {
        if (!admin()) return
        enterMode(when {
            forward -> when (mode) { "Hub" -> "Files"; "Files" -> "Stream"; else -> "Hub" }
            else -> when (mode) { "Hub" -> "Stream"; "Stream" -> "Files"; else -> "Hub" }
        })
    }

    private fun toggleWatch(title: Title) {
        val lib = library ?: return
        scope.launch {
            try {
                api.watch(title.id, title.id !in lib.watch)
                refreshLibrary()
                toast(if (title.id in lib.watch) "Removed from My List" else "Added to My List", ok = true)
            } catch (e: Exception) { toast(e.message ?: "Could not update My List") }
        }
    }

    private fun startDownload(title: Title, ep: Episode?) {
        if (downloading.isNotBlank()) return
        downloadJob = scope.launch {
            downloading = ep?.let { "S${it.season}E${it.episode} · ${it.title.ifBlank { title.title }}" } ?: title.title
            downloadPercent = 0
            try {
                offline.save(title, ep) { value -> runOnUiThread { downloadPercent = value } }
                toast("${ep?.title ?: title.title} is ready offline", ok = true)
            } catch (e: Exception) {
                toast(e.message ?: "Offline download failed")
            } finally { downloading = "" }
        }
    }

    private fun play(title: Title, ep: Episode?) {
        episode = ep
        selected = title
        playing = true
        profileOpen = false; downloadsOpen = false
    }

    private fun loadMetrics() {
        scope.launch {
            try { metrics = api.json("/system/metrics") } catch (e: Exception) { toast("Metrics: ${e.message}") }
        }
    }

    private fun loadQueue() {
        scope.launch {
            try {
                val data = api.json("/queue")
                queueJobs = data.optJSONArray("jobs").objects()
                queueDone = data.optJSONArray("completed").objects()
            } catch (e: Exception) { toast("Queue: ${e.message}") }
        }
    }

    private fun loadSettings() {
        scope.launch {
            try { users = api.json("/admin/users").optJSONArray("users").objects() } catch (_: Exception) { }
            try { githubConfigured = api.json("/admin/github").optBoolean("configured", true) } catch (_: Exception) { githubConfigured = false }
        }
    }

    private fun search(query: String) {
        searchQuery = query
        searchJob?.cancel()
        if (query.trim().length < 2) { searchResults = emptyList(); searchLoading = false; return }
        searchJob = scope.launch {
            delay(380)
            searchLoading = true
            try {
                searchResults = api.json("/imdb/search?q=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}").optJSONArray("results").objects()
            } catch (e: Exception) { toast("Search: ${e.message}") }
            finally { searchLoading = false }
        }
    }

    private fun loadFiles(device: String = filesDevice, path: String = filesPath) {
        filesLoading = true
        scope.launch {
            try {
                filesEntries = api.json("/admin/files?path=${java.net.URLEncoder.encode(path, "UTF-8")}&device=$device").optJSONArray("entries").objects()
            } catch (e: Exception) { toast("Files: ${e.message}") }
            finally { filesLoading = false }
        }
    }

    private fun loadOps() {
        scope.launch {
            try { ops = api.json("/admin/files/ops").optJSONArray("ops").objects() } catch (_: Exception) { }
        }
    }

    private fun mkdir(name: String) {
        scope.launch {
            try {
                api.json("/admin/files/mkdir", "POST", JSONObject().put("device", filesDevice).put("path", filesPath).put("name", name))
                loadFiles()
                toast("Folder created", ok = true)
            } catch (e: Exception) { toast(e.message ?: "Could not create folder") }
        }
    }

    private fun deleteFile(entry: JSONObject) {
        val target = if (filesPath.isBlank()) entry.optString("name") else "${filesPath}/${entry.optString("name")}"
        scope.launch {
            try {
                api.json("/admin/files/delete", "POST", JSONObject().put("device", filesDevice).put("paths", org.json.JSONArray().put(target)))
                loadFiles()
                toast("Deleted ${entry.optString("name")}", ok = true)
            } catch (e: Exception) { toast(e.message ?: "Could not delete file") }
        }
    }

    private fun downloadFile(name: String) {
        val target = if (filesPath.isBlank()) name else "${filesPath}/$name"
        scope.launch {
            try {
                val folder = java.io.File(getExternalFilesDir(null), "admin-files").apply { mkdirs() }
                val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100).ifBlank { "pts-file" }
                val temp = java.io.File(folder, "$safe.part")
                val ok = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val url = "$BASE/api/v1/admin/files/download?path=${java.net.URLEncoder.encode(target, "UTF-8")}&device=$filesDevice"
                    api.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) throw java.io.IOException("Download HTTP ${response.code}")
                        val body = response.body ?: throw java.io.IOException("Empty response")
                        val expected = body.contentLength()
                        val written = body.byteStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                        if (written <= 0 || (expected >= 0 && written != expected)) { temp.delete(); throw java.io.IOException("Incomplete file") }
                        if (!temp.renameTo(java.io.File(folder, safe))) throw java.io.IOException("Cannot save file")
                        true
                    }
                }
                if (ok) toast("Saved to app-private files", ok = true)
            } catch (e: Exception) { toast(e.message ?: "Download failed") }
        }
    }

    private fun cancelStage(stage: String, ids: List<String>) {
        scope.launch {
            var removed = 0
            for (id in ids) {
                try { api.json("/queue/${java.net.URLEncoder.encode(id, "UTF-8")}", "DELETE"); removed++ }
                catch (e: Exception) { toast("Stopped after $removed/${ids.size}: ${e.message}"); break }
            }
            if (removed == ids.size) toast("Cancelled $removed ${stage.replace('_', ' ')} job${if (removed == 1) "" else "s"}", ok = true)
            loadQueue()
        }
    }

    private fun routeBack() {
        when {
            playing -> { playing = false; episode = null }
            profileOpen -> profileOpen = false
            downloadsOpen -> downloadsOpen = false
            selected != null -> selected = null
            admin() && mode != "Hub" -> enterMode("Hub")
            else -> {
                val first = when (mode) { "Hub" -> "Dashboard"; "Files" -> "Browse"; else -> "Home" }
                if (mode == "Hub" && hubTab != first) hubTab = first
                else if (mode == "Files" && filesTab != first) filesTab = first
                else if (mode == "Stream" && streamTab != first) streamTab = first
                else finish()
            }
        }
    }

    @Composable
    private fun AppShell() {
        BackHandler { routeBack() }
        val user = account ?: return
        val name = user.optString("name").ifBlank { user.optString("username") }
        Column(Modifier.fillMaxSize()) {
            PtsTopBar(
                title = when (mode) { "Files" -> "PTSFiles"; "Stream" -> "PTSStream"; else -> "PTS Library" },
                initial = name.take(1).uppercase(),
                onAvatar = { profileOpen = true; downloadsOpen = false },
                onTitle = {
                    if (admin() && mode != "Hub") enterMode("Hub") else if (selected != null) selected = null
                }
            )
            Box(Modifier.weight(1f)) {
                when {
                    profileOpen -> ProfileScreen(user, offline.list().size, { profileOpen = false; downloadsOpen = true }, {
                        checkForUpdate(this@MainActivity, scope) { toast(it) }
                    }, { enterMode(it) }, { signOut() })
                    downloadsOpen -> DownloadsScreen(offline.list(), { item ->
                        val title = library?.titles?.firstOrNull { it.id == item.titleId }
                        if (title == null) toast("This title is no longer in the library")
                        else play(title, title.episodes.firstOrNull { item.key.endsWith("_${it.id}") })
                    }, { item ->
                        try { offline.remove(item); toast("Download deleted", ok = true) }
                        catch (e: Exception) { toast(e.message ?: "Could not delete download") }
                    })
                    mode == "Hub" && admin() -> when (hubTab) {
                        "Dashboard" -> DashboardScreen(name, metrics, metricsTarget, { metricsTarget = it }, { loadMetrics() }, queueJobs, queueDone) { hubTab = "Queue" }
                        "Queue" -> QueueScreen(queueJobs, queueDone, queueStage, { queueStage = it }, { loadQueue() }, { id ->
                            scope.launch { try { api.json("/queue/${java.net.URLEncoder.encode(id, "UTF-8")}/retry", "POST"); loadQueue(); toast("Retry requested", ok = true) } catch (e: Exception) { toast(e.message ?: "Retry failed") } }
                        }, { id -> toast("Edit the source link from the web admin for job $id") }, { id ->
                            scope.launch { try { api.json("/queue/${java.net.URLEncoder.encode(id, "UTF-8")}", "DELETE"); loadQueue() } catch (e: Exception) { toast(e.message ?: "Delete failed") } }
                        }) { stage, ids -> cancelStage(stage, ids) }
                        "Download" -> SearchScreen(searchQuery, { search(it) }, searchResults, searchLoading)
                        else -> SettingsScreen(user, users, githubConfigured, { checkForUpdate(this@MainActivity, scope) { toast(it) } }, { signOut() })
                    }
                    mode == "Files" && admin() -> if (filesTab == "Transfers") {
                        LaunchedEffect(Unit) { while (true) { loadOps(); delay(4000) } }
                        TransfersScreen(ops) { id ->
                            scope.launch { try { api.json("/admin/files/ops/cancel", "POST", JSONObject().put("id", id)); loadOps() } catch (e: Exception) { toast(e.message ?: "Cancel failed") } }
                        }
                    } else {
                        LaunchedEffect(filesDevice, filesPath) { loadFiles() }
                        FilesScreen(filesDevice, filesPath, filesEntries, filesLoading,
                            onDevice = { filesDevice = it; filesPath = "" },
                            onPath = { filesPath = it },
                            onRefresh = { loadFiles() },
                            onDownload = { downloadFile(it) },
                            onDelete = { deleteFile(it) },
                            onMkdir = { mkdir(it) })
                    }
                    else -> {
                        val lib = library
                        if (lib == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                PtsEmpty("Library unavailable", startupError.ifBlank { "Check your connection and try again." }, actionLabel = "Retry", icon = PtsIcons.Refresh) {
                                    scope.launch { loading = true; refreshLibrary(); loading = false }
                                }
                            }
                        } else if (selected != null) {
                            val t = selected!!
                            TitleScreen(t, lib.progress[t.id], t.id in lib.watch, offline.list().map { it.key }.toSet(), downloading,
                                onBack = { selected = null },
                                onPlay = { ep -> play(t, ep) },
                                onToggleWatch = { toggleWatch(t) },
                                onDownload = { ep -> startDownload(t, ep) })
                        } else when (streamTab) {
                            "Home" -> StreamHomeScreen(lib, { selected = it }, { play(it, if (it.kind == "series") it.episodes.minByOrNull { e -> e.season * 1000 + e.episode } else null) }, { toggleWatch(it) }) { streamTab = it }
                            "Movies" -> BrowseScreen("Movies", lib.titles.filter { it.kind == "movie" }, lib.progress, lib.watch,
                                browseQueries["Movies"].orEmpty(), { browseQueries["Movies"] = it }, { selected = it }, { toggleWatch(it) })
                            "Series" -> BrowseScreen("Series", lib.titles.filter { it.kind == "series" }, lib.progress, lib.watch,
                                browseQueries["Series"].orEmpty(), { browseQueries["Series"] = it }, { selected = it }, { toggleWatch(it) })
                            else -> BrowseScreen("Watchlist", lib.titles, lib.progress, lib.watch,
                                browseQueries["Watchlist"].orEmpty(), { browseQueries["Watchlist"] = it }, { selected = it }, { toggleWatch(it) })
                        }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = message.isNotBlank(),
                    enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp)
                ) {
                    if (messageOk) PtsOkToast(message) { message = "" }
                    else PtsErrorToast(message) { message = "" }
                }
            }
            if (!profileOpen && !downloadsOpen) {
                val pages = when (mode) {
                    "Hub" -> listOf(DockItem("Dashboard", PtsIcons.Home), DockItem("Download", PtsIcons.Download), DockItem("Queue", PtsIcons.Queue), DockItem("Settings", PtsIcons.Settings))
                    "Files" -> listOf(DockItem("Browse", PtsIcons.Browse), DockItem("Transfers", PtsIcons.Transfers))
                    else -> listOf(
                        DockItem("Home", PtsIcons.Home),
                        DockItem("Movies", PtsIcons.Movies),
                        DockItem("TV Shows", PtsIcons.Tv, "Series"),
                        DockItem("My List", PtsIcons.Watchlist, "Watchlist")
                    )
                }
                val current = when (mode) { "Hub" -> hubTab; "Files" -> filesTab; else -> streamTab }
                var swipe by remember(mode) { mutableFloatStateOf(0f) }
                Box(Modifier.pointerInput(mode) {
                    detectHorizontalDragGestures(onDragStart = { swipe = 0f }, onDragEnd = {
                        if (admin() && abs(swipe) >= size.width * 0.3f) swipeMode(swipe > 0)
                        swipe = 0f
                    }) { change, amount -> change.consume(); swipe += amount }
                }) {
                    PtsDock(pages, current, { selectTab(it) })
                }
            }
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        (0 until (this?.length() ?: 0)).mapNotNull { optJSONObject(it) }

    // ---------- player ----------

    @Composable
    private fun PlayerScreen(title: Title, ep: Episode?, saved: SavedVideo?, onBack: () -> Unit) {
        val context = LocalContext.current
        var fallback by remember(title.id, ep?.id) { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var showTracks by remember { mutableStateOf(false) }
        var speed by remember { mutableFloatStateOf(1f) }
        var fill by remember { mutableStateOf(false) }
        var controls by remember { mutableStateOf(true) }
        var position by remember { mutableLongStateOf(0L) }
        var duration by remember { mutableLongStateOf(0L) }
        var playingNow by remember { mutableStateOf(true) }
        var seekPreview by remember { mutableStateOf<Long?>(null) }
        var resumeAt by remember(title.id, ep?.id) {
            mutableLongStateOf(library?.progress?.get(title.id)?.takeIf { it.episodeId == (ep?.id ?: 0L) }?.seconds?.times(1000) ?: 0L)
        }
        val hevcAvailable = remember { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) }
        } }
        val source = saved?.file?.toURI()?.toString() ?: api.streamUrl(title, ep, fallback)
        val exo = remember(source) {
            ExoPlayer.Builder(context).setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    OkHttpDataSource.Factory(api.http).setUserAgent("PTS-Library-Android")
                )).build().apply {
                setMediaItem(MediaItem.fromUri(source))
                if (resumeAt > 0) seekTo(resumeAt)
                setPlaybackSpeed(speed)
                prepare(); playWhenReady = true
            }
        }
        BackHandler { onBack() }
        DisposableEffect(exo) {
            player = exo
            val listener = object : Player.Listener {
                override fun onPlayerError(exception: PlaybackException) {
                    if (saved == null && !fallback) {
                        resumeAt = exo.currentPosition.coerceAtLeast(0)
                        fallback = true
                        controls = true
                    } else error = "Playback failed: ${exception.errorCodeName}"
                }
                override fun onPlaybackStateChanged(state: Int) {
                    playingNow = state == Player.STATE_READY && exo.playWhenReady
                    if (state == Player.STATE_ENDED && ep != null) {
                        val next = title.episodes.firstOrNull { it.season == ep.season && it.episode == ep.episode + 1 }
                            ?: title.episodes.firstOrNull { it.season > ep.season }
                        if (next != null) { episode = next; resumeAt = 0; fallback = false }
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { playingNow = isPlaying }
            }
            exo.addListener(listener)
            onDispose {
                val p = exo.currentPosition / 1000
                val d = exo.duration.coerceAtLeast(0) / 1000
                scope.launch { try { api.progress(title.id, ep?.id, p, d) } catch (_: Exception) { } }
                exo.removeListener(listener); exo.release()
                if (player === exo) player = null
            }
        }
        LaunchedEffect(exo) {
            while (true) {
                if (!exo.isPlaying && exo.playbackState == Player.STATE_IDLE) delay(200) else delay(500)
                duration = exo.duration.coerceAtLeast(0)
                if (seekPreview == null) position = exo.currentPosition.coerceAtLeast(0)
                playingNow = exo.isPlaying
            }
        }
        LaunchedEffect(exo) {
            while (true) {
                delay(15000)
                if (exo.isPlaying) try { api.progress(title.id, ep?.id, exo.currentPosition / 1000, exo.duration.coerceAtLeast(0) / 1000) } catch (_: Exception) { }
            }
        }
        LaunchedEffect(controls, playingNow) {
            if (controls && playingNow) { delay(4500); controls = false }
        }
        Column(Modifier.fillMaxSize().background(Color(0xFF060716))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                PtsIconAction(PtsIcons.Back, "Back", onBack, tint = Color.White)
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(title.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (ep != null) Text("S${ep.season}E${ep.episode} · ${ep.title.ifBlank { "Episode ${ep.episode}" }}",
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFFD3D6ED), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (Build.VERSION.SDK_INT >= 26) PtsIconAction(PtsIcons.Tv, "Picture in picture", {
                    try { enterPictureInPictureMode(PictureInPictureParams.Builder().build()) } catch (_: Exception) { }
                }, tint = Color.White)
                PtsIconAction(PtsIcons.Subtitles, "Audio and subtitles", { showTracks = true; controls = true }, tint = Color.White)
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                AndroidView(factory = { ctx -> PlayerView(ctx).apply {
                    player = exo
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                } }, update = { it.player = exo; it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
                    modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().pointerInput(exo) {
                    var lastTap = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var dx = 0f; var dy = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            dx += change.positionChange().x; dy += change.positionChange().y
                            change.consume()
                        }
                        val total = duration.coerceAtLeast(1)
                        when {
                            abs(dx) > 55f && abs(dx) > abs(dy) -> {
                                exo.seekTo((exo.currentPosition + (dx * 140).roundToLong()).coerceIn(0, total))
                                controls = true
                            }
                            abs(dy) > 110f && abs(dy) > abs(dx) && down.position.x > size.width / 2f -> {
                                val audio = context.getSystemService(AUDIO_SERVICE) as AudioManager
                                val steps = (abs(dy) / 90f).toInt().coerceIn(1, 8)
                                repeat(steps) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (dy < 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0) }
                                controls = true
                            }
                            else -> {
                                val now = System.currentTimeMillis()
                                if (now - lastTap < 320) {
                                    val step = if (down.position.x < size.width / 2f) -10_000L else 10_000L
                                    exo.seekTo((exo.currentPosition + step).coerceIn(0, total)); lastTap = 0; controls = true
                                } else { lastTap = now; controls = !controls }
                            }
                        }
                    }
                })
                AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC040514), Color(0x11040514), Color(0xE6040514))))) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val shown = seekPreview ?: position
                            Slider(
                                value = shown.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                                onValueChange = { seekPreview = it.toLong(); controls = true },
                                onValueChangeFinished = { seekPreview?.let { exo.seekTo(it) }; seekPreview = null },
                                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                                colors = SliderDefaults.colors(thumbColor = Pts.accent, activeTrackColor = Pts.accent, inactiveTrackColor = Color.White.copy(alpha = .35f)),
                                modifier = Modifier.fillMaxWidth().height(28.dp)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${clock(shown)} / ${clock(duration)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE7E8FF), modifier = Modifier.weight(1f))
                                Text(if (saved != null) "Offline" else if (fallback) "VPS fallback" else "Pi stream", style = MaterialTheme.typography.labelSmall, color = Pts.accent)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PtsIconAction(PtsIcons.Retry, "Back 10 seconds", { exo.seekTo((exo.currentPosition - 10000).coerceAtLeast(0)) }, tint = Color.White, size = 40.dp)
                                    Box(Modifier.size(50.dp).background(Color(0xFFFAFAFF), androidx.compose.foundation.shape.CircleShape)
                                        .clickable { if (exo.isPlaying) exo.pause() else exo.play(); controls = true }, contentAlignment = Alignment.Center) {
                                        Icon(if (playingNow) PtsIcons.Pause else PtsIcons.Play, "Play or pause", Modifier.size(26.dp), tint = Pts.onAccent)
                                    }
                                    PtsIconAction(PtsIcons.Retry, "Forward 10 seconds", { exo.seekTo((exo.currentPosition + 10000).coerceAtMost(duration.coerceAtLeast(0))) }, tint = Color.White, size = 40.dp)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.clickable { speed = if (speed >= 2f) 0.5f else (speed + 0.25f); exo.setPlaybackSpeed(speed) }
                                        .background(Color.White.copy(alpha = .12f), androidx.compose.foundation.shape.RoundedCornerShape(99.dp))
                                        .padding(horizontal = 9.dp, vertical = 6.dp)) {
                                        Text("${if (speed % 1f == 0f) speed.toInt().toString() else speed}×", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                    }
                                    PtsIconAction(PtsIcons.Fit, if (fill) "Fit video" else "Fill video", { fill = !fill }, tint = Color.White, size = 40.dp)
                                }
                            }
                        }
                    }
                }
            }
            if (!hevcAvailable) PtsErrorBanner("This device may not support HEVC playback", modifier = Modifier.padding(12.dp))
            if (error.isNotBlank()) PtsErrorBanner(error, modifier = Modifier.padding(12.dp))
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(title.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(
                    listOfNotNull(
                        ep?.let { "S${it.season}E${it.episode} · ${it.title.ifBlank { "Episode ${it.episode}" }}" },
                        if (saved != null) "Stored on this device" else if (fallback) "Playing from VPS fallback" else "Playing directly from the Pi",
                        formatDuration(ep?.duration?.toLong() ?: 0).takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFD3D6ED)
                )
                if (title.synopsis.isNotBlank()) Text(title.synopsis, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB9BEE0), maxLines = 5, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PtsSecondaryButton("Audio / CC", Modifier.weight(1f), icon = PtsIcons.Subtitles) { showTracks = true }
                    PtsSecondaryButton(if (fill) "Fit screen" else "Fill screen", Modifier.weight(1f), icon = PtsIcons.Fit) { fill = !fill }
                }
            }
        }
        if (showTracks) {
            val audioGroups = exo.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
            val textGroups = exo.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
            androidx.compose.ui.window.Dialog(onDismissRequest = { showTracks = false }) {
                Column(Modifier.fillMaxWidth().background(Pts.card, androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                    .padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Audio and subtitles", style = MaterialTheme.typography.titleLarge, color = Pts.text)
                    Text("Audio", style = MaterialTheme.typography.labelMedium, color = Pts.muted)
                    if (audioGroups.isEmpty()) Text("No alternate audio tracks", style = MaterialTheme.typography.bodySmall, color = Pts.muted)
                    audioGroups.forEach { group ->
                        (0 until group.length).forEach { index ->
                            val label = group.getTrackFormat(index).language ?: "Track ${index + 1}"
                            PtsSecondaryButton(label.replaceFirstChar { it.uppercase() }, Modifier.fillMaxWidth()) {
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                                showTracks = false
                            }
                        }
                    }
                    Text("Subtitles", style = MaterialTheme.typography.labelMedium, color = Pts.muted)
                    PtsSecondaryButton("Off", Modifier.fillMaxWidth()) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
                        showTracks = false
                    }
                    textGroups.forEach { group ->
                        (0 until group.length).forEach { index ->
                            val label = group.getTrackFormat(index).language ?: "Subtitle ${index + 1}"
                            PtsSecondaryButton(label.replaceFirstChar { it.uppercase() }, Modifier.fillMaxWidth()) {
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                                showTracks = false
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    PtsPrimaryButton("Close", Modifier.fillMaxWidth()) { showTracks = false }
                }
            }
        }
    }

    private fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && playing && player?.isPlaying == true) {
            try { enterPictureInPictureMode(PictureInPictureParams.Builder().build()) } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        player?.release()
        player = null
        super.onDestroy()
    }
}

@Composable
private fun PtsOkToast(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(message) { delay(3800); onDismiss() }
    Row(
        Modifier.fillMaxWidth().shadow(14.dp, RowShape).clip(RowShape).background(Pts.card)
            .border(2.dp, Pts.ok.copy(alpha = .65f), RowShape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(PtsIcons.Check, null, Modifier.size(18.dp), tint = Pts.ok)
        Text(message, Modifier.weight(1f).padding(start = 9.dp), style = MaterialTheme.typography.bodySmall, color = Pts.text)
        PtsIconAction(PtsIcons.Close, "Dismiss", onDismiss, tint = Pts.muted, size = 28.dp)
    }
}

@Composable
private fun PtsErrorToast(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(message) { delay(5200); onDismiss() }
    Row(
        Modifier.fillMaxWidth().shadow(14.dp, RowShape).clip(RowShape).background(Pts.card)
            .border(2.dp, Pts.danger.copy(alpha = .7f), RowShape).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(PtsIcons.Info, null, Modifier.size(18.dp), tint = Pts.danger)
        Text(message, Modifier.weight(1f).padding(start = 9.dp), style = MaterialTheme.typography.bodySmall, color = Pts.text)
        PtsIconAction(PtsIcons.Close, "Dismiss", onDismiss, tint = Pts.muted, size = 28.dp)
    }
}
