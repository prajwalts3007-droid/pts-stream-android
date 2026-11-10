package online.ptsmovies.stream

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.provider.OpenableColumns
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.util.Rational
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.datasource.DefaultDataSource
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    private val api = ApiProvider.create()
    private val scope get() = lifecycleScope
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
    private var inPip by mutableStateOf(false)
    private var downloadJob: Job? = null
    private var downloading by mutableStateOf("")
    private var downloadPercent by mutableIntStateOf(0)
    private var savedVideos by mutableStateOf(emptyList<SavedVideo>())
    private var fileJob: Job? = null
    private var fileGeneration = 0

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
    private var relayJobs by mutableStateOf(emptyList<JSONObject>())
    private var restoredNavigation: Bundle? = null
    private var fileClipboard by mutableStateOf<FileClipboard?>(null)
    private var fileEditor by mutableStateOf<FileEditor?>(null)
    private var fileBusy by mutableStateOf(false)
    private var fileStatus by mutableStateOf("")
    private var fileOperation: Job? = null
    private var uploadDestination: Pair<String, String>? = null
    private var pendingFileDownload: Pair<String, String>? = null
    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pendingFileDownload
        pendingFileDownload = null
        if (uri != null && source != null && admin()) saveFileDownload(source.first, source.second, uri)
    }
    private val clearedProgress = mutableSetOf<Long>()
    private var playerFullscreen by mutableStateOf(false)
    private var playbackPrefs by mutableStateOf(JSONObject())
    private val pickUploads = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val destination = uploadDestination
        uploadDestination = null
        if (destination != null && admin() && uris.isNotEmpty()) uploadFiles(uris, destination.first, destination.second)
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredNavigation = savedInstanceState
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        window.statusBarColor = android.graphics.Color.rgb(17, 19, 45)
        window.navigationBarColor = android.graphics.Color.rgb(32, 35, 67)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        scope.launch { restoreSession() }
        setContent {
            PtsTheme {
                PtsBackground(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    when {
                        loading -> PtsLoading()
                        account == null -> LoginScreen(startupError, busy = false) { user, pass, remember -> signIn(user, pass, remember) }
                        playing && selected != null -> key(selected!!.id, episode?.id) {
                            PlayerScreen(selected!!, episode, offline.find(selected!!, episode)) {
                                playing = false; episode = null; playerFullscreen = false
                                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
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
            savedVideos = offline.list()
            mode = if (account!!.optString("role") == "admin") "Hub" else "Stream"
            startupError = ""
            refreshLibrary()
            loadPlaybackPrefs()
            restoreNavigation()
            if (account!!.optString("role") == "admin") {
                loadMetrics()
                loadQueue()
                loadSettings()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            startupError = if (e is ApiException && e.status == 401) "" else "Could not restore your session. Check your connection and sign in again."
            account = null
            library = null
        } finally { loading = false }
    }

    private fun signIn(user: String, password: String, remember: Boolean) {
        scope.launch {
            loading = true
            startupError = ""
            try {
                account = api.login(user, password, remember)
                offline = OfflineStore(this@MainActivity, api, account!!.optLong("id"))
                savedVideos = offline.list()
                mode = if (account!!.optString("role") == "admin") "Hub" else "Stream"
                toast("Welcome back, " + account!!.optString("name").ifBlank { account!!.optString("username") }, ok = true)
                refreshLibrary()
                loadPlaybackPrefs()
                if (account!!.optString("role") == "admin") {
                    loadMetrics(); loadQueue(); loadSettings()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                startupError = e.message ?: "Sign in failed"
            } finally { loading = false }
        }
    }

    private suspend fun refreshLibrary() {
        try {
            library = api.library()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            toast("Library unavailable: ${e.message}")
        }
    }

    private fun signOut() {
        downloadJob?.cancel()
        searchJob?.cancel()
        fileJob?.cancel()
        fileOperation?.cancel()
        loading = true
        scope.launch {
            try { api.logout() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Local cookies are cleared even if the server is unreachable. */ }
            finally {
                account = null; library = null; selected = null; profileOpen = false; downloadsOpen = false
                queueJobs = emptyList(); queueDone = emptyList(); metrics = null; users = emptyList()
                savedVideos = emptyList(); message = ""; startupError = ""
                fileClipboard = null; fileEditor = null; fileStatus = ""; clearedProgress.clear()
                relayJobs = emptyList()
                loading = false
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
                savedVideos = offline.list()
                toast("${ep?.title ?: title.title} is ready offline", ok = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                toast(e.message ?: "Offline download failed")
            } finally { downloading = "" }
        }
    }

    private fun play(title: Title, ep: Episode?) {
        clearedProgress.remove(title.id)
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
        fileJob?.cancel()
        val generation = ++fileGeneration
        filesLoading = true
        fileJob = scope.launch {
            try {
                val entries = api.json("/admin/files?path=${java.net.URLEncoder.encode(path, "UTF-8")}&device=$device").optJSONArray("entries").objects()
                if (generation == fileGeneration && device == filesDevice && path == filesPath) filesEntries = entries
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { filesEntries = emptyList(); toast("Files: ${e.message}") }
            finally { if (generation == fileGeneration) filesLoading = false }
        }
    }

    private fun loadOps() {
        scope.launch {
            try {
                val next = api.json("/admin/files/ops").optJSONArray("ops").objects()
                val finished = next.any { op -> op.optString("state") != "running" && ops.none { it.optString("id") == op.optString("id") && it.optString("state") == op.optString("state") } }
                ops = next
                if (finished && mode == "Files" && filesTab == "Browse") loadFiles()
            } catch (_: Exception) { }
        }
    }

    private fun loadRelay() {
        scope.launch { try { relayJobs = api.json("/relay").optJSONArray("jobs").objects() } catch (_: Exception) { } }
    }

    private fun loadPlaybackPrefs() {
        scope.launch { try { val result = api.json("/stream/prefs"); playbackPrefs = result.optJSONObject("prefs") ?: result } catch (_: Exception) { } }
    }

    private fun markPlayed(title: Title) {
        scope.launch {
            try {
                api.json("/stream/progress/clear", "POST", JSONObject().put("title_id", title.id))
                clearedProgress.add(title.id)
                library = library?.let { it.copy(progress = it.progress - title.id) }
                toast("Marked as played · removed from Continue watching", true)
            } catch (e: Exception) { toast(e.message ?: "Could not clear progress") }
        }
    }

    private fun playerPreference(key: String, value: Any) {
        val snapshot = JSONObject(playbackPrefs.toString()).put(key, value)
        playbackPrefs = snapshot
        scope.launch { try { api.json("/stream/prefs", "PUT", snapshot) } catch (e: Exception) { toast("Could not save playback preference") } }
    }

    private fun fullscreen(enabled: Boolean) {
        playerFullscreen = enabled
        requestedOrientation = if (enabled) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun runFileCommand(command: FileCommand) {
        if (!admin() || fileBusy) return
        val user = account?.optLong("id")
        if (command.action == "cut" || command.action == "copy") {
            fileClipboard = FileClipboard(command.action, command.device, command.names.map { filePath(command.directory, it) })
            return
        }
        val clipboard = fileClipboard
        fileBusy = true
        fileOperation = scope.launch {
            try {
                if (command.action == "edit") {
                    val path = filePath(command.directory, command.names.single())
                    val data = api.json("/admin/files/content?device=${command.device}&path=${java.net.URLEncoder.encode(path, "UTF-8")}")
                    if (account?.optLong("id") == user) fileEditor = FileEditor(command.device, path, data.optString("content"))
                } else {
                    api.executeFile(command, clipboard)
                    if (command.action == "paste" && clipboard?.mode == "cut" && clipboard == fileClipboard) fileClipboard = null
                    toast(if (command.action in setOf("zip", "unzip", "paste")) "${command.action.replaceFirstChar { it.uppercase() }} started · see Transfers" else "${command.action.replaceFirstChar { it.uppercase() }} completed", true)
                    if (command.device == filesDevice && command.directory == filesPath) loadFiles()
                    loadOps()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(e.message ?: "File operation failed") }
            finally { fileBusy = false }
        }
    }

    private fun saveFileEditor(content: String) {
        val editor = fileEditor ?: return
        if (fileBusy || !admin()) return
        fileBusy = true
        fileOperation = scope.launch {
            try {
                api.json("/admin/files/content", "PUT", JSONObject().put("device", editor.device).put("path", editor.path).put("content", content))
                fileEditor = null; loadFiles(); toast("File saved", true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(e.message ?: "Save failed") }
            finally { fileBusy = false }
        }
    }

    private fun uploadFiles(uris: List<android.net.Uri>, device: String, directory: String) {
        if (fileBusy) return
        if (uris.size > 100) { toast("Choose up to 100 files"); return }
        fileBusy = true
        fileOperation = scope.launch {
            try {
                for (uri in uris) {
                    val metadata = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            check(cursor.moveToFirst()) { "Cannot inspect upload" }
                            check(!cursor.isNull(1)) { "Upload size must be known" }
                            cursor.getString(0) to cursor.getLong(1)
                        } ?: error("Cannot inspect upload")
                    }
                    fileStatus = "Uploading ${metadata.first}"
                    api.upload(device, directory, metadata.first, metadata.second,
                        { contentResolver.openInputStream(uri) ?: error("Cannot open upload") }) { percent ->
                        runOnUiThread { fileStatus = "Uploading ${metadata.first} · $percent%" }
                    }
                }
                if (device == filesDevice && directory == filesPath) loadFiles()
                toast("Uploads completed", true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(e.message ?: "Upload failed") }
            finally { fileBusy = false; fileStatus = "" }
        }
    }

    private fun downloadFile(name: String) {
        if (!admin() || fileBusy) return
        pendingFileDownload = filesDevice to filePath(filesPath, name)
        saveDocument.launch(name)
    }

    private fun saveFileDownload(device: String, path: String, destination: android.net.Uri) {
        fileBusy = true
        fileOperation = scope.launch {
            try {
                fileStatus = "Saving ${path.substringAfterLast('/')}"
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    api.http.newCall(okhttp3.Request.Builder().url(api.fileDownloadUrl(device, path)).build()).execute().use { response ->
                        if (!response.isSuccessful) throw java.io.IOException("Download HTTP ${response.code}")
                        val body = response.body ?: throw java.io.IOException("Empty response")
                        val expected = body.contentLength()
                        val written = body.byteStream().use { input ->
                            (contentResolver.openOutputStream(destination, "w") ?: error("Cannot open destination")).use { output -> input.copyTo(output) }
                        }
                        if (expected >= 0 && written != expected) throw java.io.IOException("Incomplete file")
                    }
                }
                toast("File saved to the selected location", true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(e.message ?: "Download failed") }
            finally { fileBusy = false; fileStatus = "" }
        }
    }

    private fun cancelStage(stage: String, ids: List<String>) {
        scope.launch {
            var removed = 0
            for (id in ids) {
                try { api.json("/queue/${java.net.URLEncoder.encode(id, "UTF-8")}", "DELETE"); removed++ }
                catch (e: Exception) { toast("Stopped after $removed/${ids.size}: ${e.message}"); break }
            }
            if (removed == ids.size) toast(if (stage == "done") "Cleared $removed completed records · Pi files kept" else "Cancelled $removed ${stage.replace('_', ' ')} job${if (removed == 1) "" else "s"}", ok = true)
            loadQueue()
        }
    }

    private fun routeBack() {
        when {
            playing -> { playing = false; episode = null }
            profileOpen -> profileOpen = false
            downloadsOpen -> downloadsOpen = false
            selected != null -> selected = null
            mode == "Files" && filesTab == "Browse" && filesPath.isNotBlank() -> filesPath = filesPath.substringBeforeLast('/', "")
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
        fileEditor?.let { editor -> FileEditorDialog(editor, fileBusy, { saveFileEditor(it) }, { fileEditor = null }) }
        LaunchedEffect(mode, hubTab, user.optLong("id")) {
            if (mode == "Hub" && admin()) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (hubTab == "Dashboard") loadMetrics()
                    if (hubTab == "Dashboard" || hubTab == "Queue") loadQueue()
                    delay(8000)
                }
            }
        }
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
            if (downloading.isNotBlank()) Row(Modifier.fillMaxWidth().background(Pts.soft).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Saving offline · $downloadPercent%", style = MaterialTheme.typography.labelSmall, color = Pts.accent)
                    Text(downloading, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Pts.text)
                    PtsProgressBar(downloadPercent / 100f, height = 4.dp)
                }
                PtsIconAction(PtsIcons.Close, "Cancel download", { downloadJob?.cancel() }, tint = Pts.muted)
            }
            Box(Modifier.weight(1f)) {
                when {
                    profileOpen -> ProfileScreen(user, savedVideos.size, { profileOpen = false; downloadsOpen = true }, {
                        checkForUpdate(this@MainActivity, scope) { toast(it) }
                    }, { enterMode(it) }, { signOut() })
                    downloadsOpen -> DownloadsScreen(savedVideos, { item ->
                        val title = library?.titles?.firstOrNull { it.id == item.titleId }
                        if (title == null) toast("This title is no longer in the library")
                        else play(title, title.episodes.firstOrNull { item.key.endsWith("_${it.id}") })
                    }, { item ->
                        try { offline.remove(item); savedVideos = offline.list(); toast("Download deleted", ok = true) }
                        catch (e: Exception) { toast(e.message ?: "Could not delete download") }
                    })
                    mode == "Hub" && admin() -> when (hubTab) {
                        "Dashboard" -> DashboardScreen(name, metrics, metricsTarget, { metricsTarget = it }, { loadMetrics() }, queueJobs, queueDone) { hubTab = "Queue" }
                        "Queue" -> QueueScreen(queueJobs, queueDone, queueStage, { queueStage = it }, { loadQueue() }, { id ->
                            scope.launch { try { api.json("/queue/${java.net.URLEncoder.encode(id, "UTF-8")}", "DELETE"); loadQueue() } catch (e: Exception) { toast(e.message ?: "Delete failed") } }
                        }) { stage, ids -> cancelStage(stage, ids) }
                        "Download" -> SearchScreen(searchQuery, { search(it) }, searchResults, searchLoading)
                        else -> SettingsScreen(user, users, githubConfigured, { checkForUpdate(this@MainActivity, scope) { toast(it) } }, { signOut() })
                    }
                    mode == "Files" && admin() -> if (filesTab == "Transfers") {
                        LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { loadOps(); loadRelay(); delay(4000) } } }
                        TransfersScreen(ops, onCancel = { id ->
                            scope.launch { try { api.json("/admin/files/ops/cancel", "POST", JSONObject().put("id", id)); loadOps() } catch (e: Exception) { toast(e.message ?: "Cancel failed") } }
                        }, onClear = {
                            scope.launch { try { api.json("/admin/files/ops/clear", "POST", JSONObject()); loadOps(); toast("Finished transfers cleared", true) } catch (e: Exception) { toast(e.message ?: "Clear failed") } }
                        }, relay = relayJobs, onRelayAction = { job, active ->
                            scope.launch { try {
                                val id = java.net.URLEncoder.encode(job.optString("id"), "UTF-8")
                                api.json("/relay/$id" + (if (active) "/cancel" else ""), if (active) "POST" else "DELETE", if (active) JSONObject() else null)
                                loadRelay()
                            } catch (e: Exception) { toast(e.message ?: "Could not update transfer") } }
                        }, onLink = { link, copy ->
                            val uri = android.net.Uri.parse(link)
                            if (uri.scheme == "https" && !uri.host.isNullOrBlank()) {
                                if (copy) { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Transfer link", link)); toast("Link copied", true) }
                                else try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) } catch (_: Exception) { toast("No browser available") }
                            }
                        })
                    } else {
                        LaunchedEffect(filesDevice, filesPath) { loadFiles() }
                        LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { loadOps(); delay(4000) } } }
                        FilesScreen(filesDevice, filesPath, filesEntries, filesLoading,
                            onDevice = { filesDevice = it; filesPath = "" },
                            onPath = { filesPath = it },
                            onRefresh = { loadFiles() },
                            onDownload = { downloadFile(it) },
                            onDelete = { runFileCommand(FileCommand("delete", filesDevice, filesPath, listOf(it.optString("name")))) },
                            onMkdir = { runFileCommand(FileCommand("mkdir", filesDevice, filesPath, value = it)) },
                            onCommand = { runFileCommand(it) }, clipboard = fileClipboard,
                            onClearClipboard = { fileClipboard = null },
                            onUpload = { uploadDestination = filesDevice to filesPath; pickUploads.launch(arrayOf("*/*")) },
                            busy = fileBusy, status = fileStatus)
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
                            TitleScreen(t, lib.progress[t.id], t.id in lib.watch, savedVideos.map { it.key }.toSet(), downloading,
                                onBack = { selected = null },
                                onPlay = { ep -> play(t, ep) },
                                onToggleWatch = { toggleWatch(t) },
                                onDownload = { ep -> startDownload(t, ep) }, onMarkPlayed = { markPlayed(t) })
                        } else when (streamTab) {
                            "Home" -> StreamHomeScreen(lib, { selected = it }, { title ->
                                val resume = lib.progress[title.id]
                                val next = if (title.kind == "series") title.episodes.firstOrNull { it.id == resume?.episodeId }
                                    ?: title.episodes.minWithOrNull(compareBy({ it.season }, { it.episode })) else null
                                play(title, next)
                            }, { toggleWatch(it) }) { streamTab = it }
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
                Box(Modifier.testTag("section-dock").pointerInput(mode) {
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
        (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }

    // ---------- player ----------

    @Composable
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    private fun PlayerScreen(title: Title, ep: Episode?, saved: SavedVideo?, onBack: () -> Unit) {
        val context = LocalContext.current
        var fallback by remember(title.id, ep?.id) { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var showTracks by remember { mutableStateOf(false) }
        var showEpisodes by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var speed by remember { mutableFloatStateOf(playbackPrefs.optDouble("speed", 1.0).toFloat().coerceIn(.5f, 2f)) }
        var fill by remember { mutableStateOf(false) }
        var controls by remember { mutableStateOf(true) }
        var position by remember { mutableLongStateOf(0L) }
        var duration by remember { mutableLongStateOf(0L) }
        var playingNow by remember { mutableStateOf(true) }
        var seekPreview by remember { mutableStateOf<Long?>(null) }
        var interaction by remember { mutableIntStateOf(0) }
        var muted by remember { mutableStateOf(false) }
        var buffering by remember { mutableStateOf(true) }
        var ended by remember { mutableStateOf(false) }
        var cancelNext by remember { mutableStateOf(false) }
        var nextCountdown by remember { mutableIntStateOf(5) }
        var autoplay by remember { mutableStateOf(playbackPrefs.optBoolean("autoplay", true)) }
        val downloaded = savedVideos.any { it.key == "${title.id}_${ep?.id ?: 0L}" }
        val nextEpisode = remember(title, ep) { title.episodes.sortedWith(compareBy({ it.season }, { it.episode }))
            .firstOrNull { ep != null && (it.season > ep.season || (it.season == ep.season && it.episode > ep.episode)) } }
        fun touchControls() { controls = true; interaction++ }
        fun leavePlayer() { if (playerFullscreen) fullscreen(false) else onBack() }
        fun selectEpisode(next: Episode) { clearedProgress.remove(title.id); episode = next; showEpisodes = false }
        var resumeAt by remember(title.id, ep?.id) {
            mutableLongStateOf(resumePosition(library?.progress?.get(title.id), ep?.id ?: 0L, ep?.duration?.toLong() ?: 0L) * 1000)
        }
        val hevcAvailable = remember { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) }
        } }
        val source = saved?.file?.toURI()?.toString() ?: api.streamUrl(title, ep, fallback)
        val exo = remember(source) {
            ExoPlayer.Builder(context).setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(context, OkHttpDataSource.Factory(api.http).setUserAgent("PTS-Library-Android"))
                )).build().apply {
                setMediaItem(MediaItem.fromUri(source))
                setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setHandleAudioBecomingNoisy(true)
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(playbackPrefs.optString("audio_lang").ifBlank { null })
                    .setPreferredTextLanguage(playbackPrefs.optString("subtitle_lang").ifBlank { null }).build()
                if (resumeAt > 0) seekTo(resumeAt)
                setPlaybackSpeed(speed)
                prepare(); playWhenReady = true
            }
        }
        BackHandler { leavePlayer() }
        DisposableEffect(exo) {
            player = exo
            val originalBrightness = window.attributes.screenBrightness
            val listener = object : Player.Listener {
                override fun onPlayerError(exception: PlaybackException) {
                    if (saved == null && !fallback) {
                        resumeAt = exo.currentPosition.coerceAtLeast(0)
                        fallback = true
                        controls = true
                    } else {
                        val http = (exception.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                        error = "${exception.errorCodeName}${http?.let { " · HTTP $it" }.orEmpty()}. Retry or select another audio track."
                        controls = true
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    playingNow = state == Player.STATE_READY && exo.playWhenReady
                    buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                    ended = state == Player.STATE_ENDED
                    if (state == Player.STATE_ENDED) controls = true
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { playingNow = isPlaying }
            }
            exo.addListener(listener)
            onDispose {
                val p = exo.currentPosition / 1000
                val d = exo.duration.coerceAtLeast(0) / 1000
                if (title.id !in clearedProgress && d > 0) {
                    library = library?.let { it.copy(progress = it.progress + (title.id to Resume(ep?.id ?: 0L, p, d))) }
                    scope.launch { try { api.progress(title.id, ep?.id, p, d) } catch (_: Exception) { } }
                }
                exo.removeListener(listener); exo.release()
                window.attributes = window.attributes.apply { screenBrightness = originalBrightness }
                if (player === exo) player = null
            }
        }
        LaunchedEffect(exo) {
            while (true) {
                if (!exo.isPlaying && exo.playbackState == Player.STATE_IDLE) delay(200) else delay(500)
                duration = exo.duration.coerceAtLeast(0)
                buffering = exo.playbackState == Player.STATE_BUFFERING || exo.playbackState == Player.STATE_IDLE
                if (seekPreview == null) position = exo.currentPosition.coerceAtLeast(0)
                playingNow = exo.isPlaying
            }
        }
        LaunchedEffect(exo) {
            while (true) {
                delay(15000)
                if (exo.isPlaying && title.id !in clearedProgress) try { api.progress(title.id, ep?.id, exo.currentPosition / 1000, exo.duration.coerceAtLeast(0) / 1000) } catch (_: Exception) { }
            }
        }
        LaunchedEffect(controls, playingNow, interaction, seekPreview != null, showTracks, showEpisodes, showSettings) {
            if (controls && playingNow && seekPreview == null && !showTracks && !showEpisodes && !showSettings) { delay(4500); controls = false }
        }
        LaunchedEffect(ended, autoplay, cancelNext) {
            if (ended && autoplay && !cancelNext && nextEpisode != null) {
                for (seconds in 5 downTo 1) { nextCountdown = seconds; delay(1000) }
                selectEpisode(nextEpisode)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF060716))) {
          val landscape = maxWidth > maxHeight || inPip || playerFullscreen
          Column(Modifier.fillMaxSize()) {
            if (!inPip) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                PtsIconAction(PtsIcons.Back, "Back", { leavePlayer() }, tint = Color.White)
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(title.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (ep != null) Text("S${ep.season}E${ep.episode} · ${ep.title.ifBlank { "Episode ${ep.episode}" }}",
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFFD3D6ED), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (Build.VERSION.SDK_INT >= 26) PtsIconAction(PtsIcons.Tv, "Picture in picture", {
                    try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) } catch (_: Exception) { }
                }, tint = Color.White)
                PtsIconAction(PtsIcons.Subtitles, "Audio and subtitles", { showTracks = true; controls = true }, tint = Color.White)
                if (ep != null) PtsIconAction(PtsIcons.Series, "Choose episode", { showEpisodes = true; touchControls() }, tint = Color.White)
                PtsIconAction(PtsIcons.Fit, if (playerFullscreen) "Exit fullscreen" else "Fullscreen", { fullscreen(!playerFullscreen); touchControls() }, tint = Color.White)
            }
            Box((if (landscape) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)).background(Color.Black)) {
                AndroidView(factory = { ctx -> (android.view.LayoutInflater.from(ctx).inflate(R.layout.native_player, null, false) as PlayerView).apply {
                    player = exo
                    useController = false
                    keepScreenOn = true
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
                                exo.seekTo((exo.currentPosition + (dx / size.width * 120000).roundToLong()).coerceIn(0, total))
                                touchControls()
                            }
                            abs(dy) > 110f && abs(dy) > abs(dx) -> {
                                if (down.position.x > size.width / 2f) {
                                    val audio = context.getSystemService(AUDIO_SERVICE) as AudioManager
                                    val steps = (abs(dy) / 90f).toInt().coerceIn(1, 8)
                                    repeat(steps) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (dy < 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0) }
                                } else {
                                    val attributes = window.attributes
                                    val current = attributes.screenBrightness.takeIf { it >= 0f } ?: .5f
                                    attributes.screenBrightness = (current - dy / size.height).coerceIn(.05f, 1f)
                                    window.attributes = attributes
                                }
                                touchControls()
                            }
                            else -> {
                                val now = System.currentTimeMillis()
                                if (now - lastTap < 320) {
                                    val step = if (down.position.x < size.width / 2f) -10_000L else 10_000L
                                    exo.seekTo((exo.currentPosition + step).coerceIn(0, total)); lastTap = 0; touchControls()
                                } else { lastTap = now; controls = !controls }
                            }
                        }
                    }
                })
                androidx.compose.animation.AnimatedVisibility(visible = controls && !inPip, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC040514), Color(0x11040514), Color(0xE6040514))))) {
                        Row(Modifier.align(Alignment.Center).offset(y = (-16).dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                            PlayerSeekButton(false) { exo.seekTo((exo.currentPosition - 10000).coerceAtLeast(0)); touchControls() }
                            Box(Modifier.size(60.dp).background(Color(0xFFFAFAFF), androidx.compose.foundation.shape.CircleShape)
                                .clickable { if (exo.isPlaying) exo.pause() else { if (ended) exo.seekTo(0); exo.play() }; touchControls() }, contentAlignment = Alignment.Center) {
                                Icon(if (playingNow) PtsIcons.Pause else PtsIcons.Play, if (playingNow) "Pause playback" else "Play playback", Modifier.size(30.dp), tint = Pts.onAccent)
                            }
                            PlayerSeekButton(true) { exo.seekTo((exo.currentPosition + 10000).coerceAtMost(duration.coerceAtLeast(0))); touchControls() }
                        }
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val shown = seekPreview ?: position
                            Slider(
                                value = shown.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                                onValueChange = { seekPreview = it.toLong(); touchControls() },
                                onValueChangeFinished = { seekPreview?.let { exo.seekTo(it) }; seekPreview = null; touchControls() },
                                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                                colors = SliderDefaults.colors(thumbColor = Pts.accent, activeTrackColor = Pts.accent, inactiveTrackColor = Color.White.copy(alpha = .35f)),
                                thumb = { Box(Modifier.size(14.dp).background(Pts.accent, androidx.compose.foundation.shape.CircleShape)) },
                                track = {
                                    val fraction = (shown.toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f)
                                    Box(Modifier.fillMaxWidth().height(4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(99.dp)).background(Color.White.copy(alpha = .28f))) {
                                        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Pts.accent))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(28.dp)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${clock(shown)} / ${clock(duration)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE7E8FF), modifier = Modifier.weight(1f))
                                Text(if (saved != null) "Offline" else if (fallback) "VPS fallback" else "Pi stream", style = MaterialTheme.typography.labelSmall, color = Pts.accent)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                PlayerTextControl("${if (speed % 1f == 0f) speed.toInt().toString() else speed}×", "Playback settings") { showSettings = true; touchControls() }
                                PlayerTextControl(if (muted) "Unmute" else "Mute", "Mute audio") { muted = !muted; exo.volume = if (muted) 0f else 1f; touchControls() }
                                PlayerTextControl("Audio / CC", "Audio tracks") { showTracks = true; touchControls() }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PtsIconAction(PtsIcons.Fit, if (fill) "Fit video" else "Fill video", { fill = !fill }, tint = Color.White, size = 40.dp)
                                }
                            }
                        }
                    }
                }
                if (buffering && error.isBlank()) CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Pts.accent)
                if (error.isNotBlank() && !inPip) Column(Modifier.align(Alignment.Center).fillMaxWidth().background(Color.Black.copy(alpha = .9f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Playback interrupted", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(error, style = MaterialTheme.typography.bodySmall, color = Pts.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PtsPrimaryButton("Retry", Modifier.weight(1f)) { error = ""; exo.prepare(); exo.play(); touchControls() }
                        PtsSecondaryButton("Audio / CC", Modifier.weight(1f)) { showTracks = true }
                    }
                }
                if (landscape && !inPip && ended && nextEpisode != null && !cancelNext) Box(Modifier.align(Alignment.TopEnd).padding(12.dp).widthIn(max = 300.dp)) {
                    UpNextCard(nextEpisode, if (autoplay) nextCountdown else null, { selectEpisode(nextEpisode) }, { cancelNext = true })
                }
            }
            if (!landscape) {
             if (!hevcAvailable) PtsErrorBanner("This device may not support HEVC playback", modifier = Modifier.padding(12.dp))
             Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(title.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(
                    listOfNotNull(
                        ep?.let { "S${it.season}E${it.episode} · ${it.title.ifBlank { "Episode ${it.episode}" }}" },
                        if (saved != null) "Stored on this device" else if (fallback) "Playing from VPS fallback" else "Playing directly from the Pi",
                        formatDuration(ep?.duration?.toLong() ?: 0).takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFD3D6ED)
                )
                ShortSynopsis(title.synopsis)
                if (ended && nextEpisode != null && !cancelNext) UpNextCard(nextEpisode, if (autoplay) nextCountdown else null, { selectEpisode(nextEpisode) }, { cancelNext = true })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PtsSecondaryButton("Mark as played", Modifier.weight(1f), icon = PtsIcons.Check) { markPlayed(title) }
                    PtsSecondaryButton(if (downloaded) "Downloaded" else "Save offline", Modifier.weight(1f), icon = if (downloaded) PtsIcons.Check else PtsIcons.Download, enabled = downloading.isBlank() && !downloaded) { startDownload(title, ep) }
                }
                if (downloading.isNotBlank()) {
                    Text("Saving $downloading · $downloadPercent%", style = MaterialTheme.typography.bodySmall, color = Pts.accent)
                    PtsProgressBar(downloadPercent / 100f)
                    PtsSecondaryButton("Cancel download", Modifier.fillMaxWidth()) { downloadJob?.cancel() }
                }
                if (title.kind == "series") SeasonEpisodes(title, ep, library?.progress?.get(title.id), savedVideos.map { it.key }.toSet(), downloading,
                    onPlay = { selectEpisode(it) }, onDownload = { startDownload(title, it) })
                val related = library?.titles.orEmpty().filter { it.id != title.id && it.genres.any { g -> g in title.genres } }.take(8)
                if (related.isNotEmpty()) PtsPosterShelf("Related", related, library?.progress.orEmpty(), library?.watch.orEmpty(),
                    { play(it, if (it.kind == "series") it.episodes.firstOrNull() else null) }, { toggleWatch(it) })
             }
            }
          }
        }
        if (showEpisodes) androidx.compose.ui.window.Dialog(onDismissRequest = { showEpisodes = false }) {
            PtsCard(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), padding = 16.dp) {
                SeasonEpisodes(title, ep, library?.progress?.get(title.id), savedVideos.map { it.key }.toSet(), downloading,
                    onPlay = { selectEpisode(it) }, onDownload = { startDownload(title, it) })
                PtsSecondaryButton("Close", Modifier.fillMaxWidth()) { showEpisodes = false }
            }
        }
        if (showSettings) androidx.compose.ui.window.Dialog(onDismissRequest = { showSettings = false }) {
            PtsCard(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text("Playback settings", style = MaterialTheme.typography.titleLarge, color = Pts.text)
                PtsSecondaryButton(if (autoplay) "Autoplay next: On" else "Autoplay next: Off", Modifier.fillMaxWidth()) {
                    autoplay = !autoplay; playerPreference("autoplay", autoplay)
                }
                listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { rate ->
                    PtsSecondaryButton("${rate}×", Modifier.fillMaxWidth(), icon = if (rate == speed) PtsIcons.Check else null) {
                        speed = rate; exo.setPlaybackSpeed(rate); playerPreference("speed", rate); showSettings = false; touchControls()
                    }
                }
                PtsSecondaryButton("Close", Modifier.fillMaxWidth()) { showSettings = false }
            }
        }
        if (showTracks) {
            val audioGroups = exo.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
            val textGroups = exo.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
            androidx.compose.ui.window.Dialog(onDismissRequest = { showTracks = false }) {
                Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).background(Pts.card, androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                    .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Audio and subtitles", style = MaterialTheme.typography.titleLarge, color = Pts.text)
                    Text("Audio", style = MaterialTheme.typography.labelMedium, color = Pts.muted)
                    if (audioGroups.isEmpty()) Text("No alternate audio tracks", style = MaterialTheme.typography.bodySmall, color = Pts.muted)
                    audioGroups.forEach { group ->
                        (0 until group.length).forEach { index ->
                            val format = group.getTrackFormat(index)
                            val label = format.label?.takeIf { it.isNotBlank() } ?: format.language?.let { Locale.forLanguageTag(it).displayLanguage } ?: "Track ${index + 1}"
                            PtsSecondaryButton(listOfNotNull(label, format.sampleMimeType?.substringAfter('/'), format.channelCount.takeIf { it > 0 }?.let { "${it}ch" }).joinToString(" · "), Modifier.fillMaxWidth(),
                                icon = if (group.isTrackSelected(index)) PtsIcons.Check else null, enabled = group.isTrackSupported(index)) {
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                                format.language?.let { playerPreference("audio_lang", it) }
                                showTracks = false
                            }
                        }
                    }
                    Text("Subtitles", style = MaterialTheme.typography.labelMedium, color = Pts.muted)
                    PtsSecondaryButton("Off", Modifier.fillMaxWidth()) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
                        playerPreference("subtitle_lang", "")
                        showTracks = false
                    }
                    textGroups.forEach { group ->
                        (0 until group.length).forEach { index ->
                            val format = group.getTrackFormat(index)
                            val label = format.label?.takeIf { it.isNotBlank() } ?: format.language?.let { Locale.forLanguageTag(it).displayLanguage } ?: "Subtitle ${index + 1}"
                            PtsSecondaryButton(label.replaceFirstChar { it.uppercase() }, Modifier.fillMaxWidth(), icon = if (group.isTrackSelected(index)) PtsIcons.Check else null, enabled = group.isTrackSupported(index)) {
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                                format.language?.let { playerPreference("subtitle_lang", it) }
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
            try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("mode", mode)
        outState.putString("hubTab", hubTab)
        outState.putString("filesTab", filesTab)
        outState.putString("streamTab", streamTab)
        outState.putString("filesPath", filesPath)
        outState.putString("filesDevice", filesDevice)
        outState.putLong("selected", selected?.id ?: 0L)
        super.onSaveInstanceState(outState)
    }

    private fun restoreNavigation() {
        restoredNavigation?.let { state ->
            mode = if (admin()) state.getString("mode") ?: mode else "Stream"
            hubTab = state.getString("hubTab") ?: "Dashboard"
            filesTab = state.getString("filesTab") ?: "Browse"
            streamTab = state.getString("streamTab") ?: "Home"
            filesPath = state.getString("filesPath").orEmpty()
            filesDevice = state.getString("filesDevice") ?: "vps"
            selected = library?.titles?.firstOrNull { it.id == state.getLong("selected") }
        }
        restoredNavigation = null
    }

    override fun onStop() {
        if (!isInPictureInPictureMode) player?.pause()
        super.onStop()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
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
