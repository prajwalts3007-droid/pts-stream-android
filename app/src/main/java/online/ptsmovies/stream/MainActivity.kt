package online.ptsmovies.stream

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.Intent
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
import androidx.compose.ui.graphics.luminance
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
    private val device by lazy { DeviceState(this) }
    private val images by lazy { coil.ImageLoader.Builder(applicationContext).okHttpClient(api.http).build() }
    private var downloads: DownloadRepository? = null
    private var downloadWatch: Job? = null
    private var downloadTasks by mutableStateOf(emptyList<DownloadTask>())
    private var updatesOpen by mutableStateOf(false)
    private var editProfile by mutableStateOf(false)
    private var navigationRestored = false
    private var restoredPosition = -1L
    private var restoredAutoplay = true
    private var resumeAfterBackground = false
    private var foregroundSync: Job? = null
    private var sessionGeneration = 0
    private val selectPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            try {
                val bytes = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                    require(options.outWidth > 0 && options.outHeight > 0) { "Choose an image file" }
                    val max = maxOf(options.outWidth, options.outHeight)
                    val sampled = android.graphics.BitmapFactory.Options().apply { inSampleSize = maxOf(1, max / 512) }
                    val bitmap = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, sampled) } ?: error("Cannot read image")
                    val scale = minOf(1f, 512f / maxOf(bitmap.width, bitmap.height))
                    val resized = android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                    val output = java.io.ByteArrayOutputStream()
                    resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 86, output)
                    if (resized !== bitmap) resized.recycle(); bitmap.recycle()
                    output.toByteArray().also { require(it.size <= 1024 * 1024) { "Photo is too large" } }
                }
                account = api.uploadAvatar(bytes); device.account(account!!); toast("Profile photo synced", true)
            } catch (e: Exception) { toast(e.message ?: "Photo upload failed") }
        }
    }

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
        startAccountSync()
        setContent {
            PtsTheme(device.theme) {
                val bar = Pts.bg
                val light = MaterialTheme.colorScheme.background.luminance() > .5f
                SideEffect {
                    window.statusBarColor = android.graphics.Color.rgb((bar.red * 255).toInt(), (bar.green * 255).toInt(), (bar.blue * 255).toInt())
                    window.navigationBarColor = window.statusBarColor
                    WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = light && !playing; isAppearanceLightNavigationBars = light && !playing }
                }
                PtsBackground(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    when {
                        loading -> PtsLoading()
                        account == null -> LoginScreen(startupError, busy = false) { user, pass, remember -> signIn(user, pass, remember) }
                        playing && selected != null -> key(selected!!.id, episode?.id) {
                          PtsTheme("dark") {
                            PlayerScreen(selected!!, episode, offline.find(selected!!, episode)) {
                                playing = false; episode = null; playerFullscreen = false
                                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                          }
                        }
                        else -> AppShell()
                    }
                }
            }
        }
    }

    private suspend fun restoreSession() {
        val generation = sessionGeneration
        loading = true
        val cached = device.account()
        if (cached != null) {
            account = cached
            library = device.library(cached.optLong("id"))
            attachDownloads()
            mode = "Stream"
            restoreNavigation()
            loading = false
        }
        try {
            val verified = api.me()
            if (generation != sessionGeneration) return
            if (cached != null && cached.optLong("id") != verified.optLong("id")) {
                downloads?.pauseAll(); device.clearSession(); library = null; selected = null; episode = null
                playing = false; profileOpen = false; downloadsOpen = false; updatesOpen = false; navigationRestored = false
            }
            account = verified
            device.account(account!!)
            attachDownloads()
            if (cached == null) mode = "Stream"
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
            if (e is ApiException && e.status == 401) { account = null; library = null; device.clearSession(); downloads?.pauseAll(); playing = false; selected = null; downloadsOpen = false; profileOpen = false; updatesOpen = false; navigationRestored = false }
            else if (cached == null) { startupError = "Could not restore your session. Check your connection and sign in again."; account = null; library = null }
            else toast("Offline · showing the saved library and device downloads")
        } finally { loading = false }
    }

    private fun signIn(user: String, password: String, remember: Boolean) {
        scope.launch {
            loading = true
            startupError = ""
            try {
                account = api.login(user, password, remember)
                device.account(account!!)
                attachDownloads()
                mode = "Stream"
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
        val owner = account?.optLong("id") ?: return
        val generation = sessionGeneration
        try {
            val result = api.library()
            if (generation != sessionGeneration || owner != account?.optLong("id")) return
            val current = selected?.id?.takeIf { playing }
            library = if (current != null && library?.progress?.containsKey(current) == true) result.copy(progress = result.progress + (current to library!!.progress.getValue(current))) else result
            account?.let { device.library(it.optLong("id"), library!!) }
            selected?.let { old -> library?.titles?.firstOrNull { it.id == old.id }?.let { selected = it } }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            toast("Library unavailable: ${e.message}")
        }
    }

    private fun signOut() {
        sessionGeneration++
        persistNavigation()
        downloads?.pauseAll()
        downloadWatch?.cancel()
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
                device.clearSession(); navigationRestored = false; updatesOpen = false
                queueJobs = emptyList(); queueDone = emptyList(); metrics = null; users = emptyList()
                savedVideos = emptyList(); downloadTasks = emptyList(); downloading = ""; message = ""; startupError = ""
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
        profileOpen = false; downloadsOpen = false; updatesOpen = false; selected = null; playing = false
        persistNavigation()
    }

    private fun selectTab(name: String) {
        when (mode) { "Hub" -> hubTab = name; "Files" -> filesTab = name; else -> streamTab = name }
        selected = null; profileOpen = false; updatesOpen = false; downloadsOpen = name == "Downloads"
        persistNavigation()
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
        try {
            downloads?.enqueue(title, ep) ?: error("Sign in before downloading")
            playing = false; playerFullscreen = false
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            selected = null; profileOpen = false; updatesOpen = false; mode = "Stream"; streamTab = "Downloads"; downloadsOpen = true
            persistNavigation()
        } catch (e: Exception) { toast(e.message ?: "Cannot start download") }
    }

    private fun attachDownloads() {
        val user = account ?: return
        val repo = DeviceDownloads.forOwner(this, api, user.optLong("id"))
        offline = repo.store
        if (downloads === repo && downloadWatch?.isActive == true) return
        downloads = repo; downloadWatch?.cancel(); repo.refresh()
        savedVideos = repo.state.value.saved; downloadTasks = repo.state.value.tasks
        downloadWatch = scope.launch {
            repo.state.collect { snapshot ->
                savedVideos = snapshot.saved; downloadTasks = snapshot.tasks
                val active = snapshot.tasks.firstOrNull { it.status == "downloading" }
                downloading = active?.label.orEmpty(); downloadPercent = active?.progress?.percent ?: 0
            }
        }
    }

    private fun openUpdates() { profileOpen = false; downloadsOpen = false; updatesOpen = true; NativeUpdates.get(this).check() }

    private fun play(title: Title, ep: Episode?) {
        clearedProgress.remove(title.id)
        episode = ep
        selected = title
        playing = true
        profileOpen = false; downloadsOpen = false
        restoredPosition = -1L; restoredAutoplay = true
        persistNavigation()
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
            updatesOpen -> updatesOpen = false
            profileOpen -> profileOpen = false
            downloadsOpen -> { downloadsOpen = false; streamTab = "Home" }
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
        if (editProfile) EditProfileDialog(user, onSave = { name, email ->
            scope.launch { try {
                account = api.json("/auth/me", "PUT", JSONObject().put("name", name).put("email", email)).getJSONObject("user")
                device.account(account!!); editProfile = false; toast("Profile synced", true)
            } catch (e: Exception) { toast(e.message ?: "Could not save profile") } }
        }, onClose = { editProfile = false })
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
                }, avatar = api.imageUrl(user.optString("avatar")), imageLoader = images
            )
            if (downloading.isNotBlank() && !downloadsOpen) Row(Modifier.fillMaxWidth().background(Pts.soft).clickable { mode = "Stream"; downloadsOpen = true; profileOpen = false; updatesOpen = false; streamTab = "Downloads" }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Saving offline · $downloadPercent%", style = MaterialTheme.typography.labelSmall, color = Pts.accent)
                    Text(downloading, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Pts.text)
                    PtsProgressBar(downloadPercent / 100f, height = 4.dp)
                }
                PtsIconAction(PtsIcons.Close, "Pause download", { downloadTasks.firstOrNull { it.status == "downloading" }?.let { downloads?.pause(it.key) } }, tint = Pts.muted)
            }
            Box(Modifier.weight(1f)) {
                when {
                    updatesOpen -> NativeUpdateScreen(NativeUpdates.get(this@MainActivity), { NativeUpdates.get(this@MainActivity).install(this@MainActivity) }, { updatesOpen = false })
                    profileOpen -> ProfileScreen(user, savedVideos.size, { profileOpen = false; downloadsOpen = true }, {
                        openUpdates()
                    }, { enterMode(it) }, { signOut() }, avatar = api.imageUrl(user.optString("avatar")), imageLoader = images,
                        theme = device.theme, onTheme = { device.theme(it) }, onEdit = { editProfile = true },
                        onPhoto = { selectPhoto.launch("image/*") }, onRemovePhoto = {
                            scope.launch { try { account = api.json("/auth/avatar", "DELETE").getJSONObject("user"); device.account(account!!); toast("Photo removed", true) }
                                catch (e: Exception) { toast(e.message ?: "Could not remove photo") } }
                        })
                    downloadsOpen || (mode == "Stream" && streamTab == "Downloads" && selected == null) -> DownloadsScreen(savedVideos, { item ->
                        val title = library?.titles?.firstOrNull { it.id == item.titleId }
                        val stored = title ?: downloadTasks.firstOrNull { it.key == item.key }?.title
                        if (stored == null) toast("Saved title metadata is unavailable")
                        else play(stored, stored.episodes.firstOrNull { item.key.endsWith("_${it.id}") })
                    }, { item ->
                        try { downloads?.remove(item); toast("Download deleted", ok = true) }
                        catch (e: Exception) { toast(e.message ?: "Could not delete download") }
                    }, tasks = downloadTasks, onPause = { downloads?.pause(it) }, onResume = { downloads?.resume(it) }, onCancel = { downloads?.cancel(it) })
                    mode == "Hub" && admin() -> when (hubTab) {
                        "Dashboard" -> DashboardScreen(name, metrics, metricsTarget, { metricsTarget = it }, { loadMetrics() }, queueJobs, queueDone) { hubTab = "Queue" }
                        "Queue" -> QueueScreen(queueJobs, queueDone, queueStage, { queueStage = it }, { loadQueue() }, { id ->
                            scope.launch { try { api.json("/queue/${java.net.URLEncoder.encode(id, "UTF-8")}", "DELETE"); loadQueue() } catch (e: Exception) { toast(e.message ?: "Delete failed") } }
                        }) { stage, ids -> cancelStage(stage, ids) }
                        "Download" -> SearchScreen(searchQuery, { search(it) }, searchResults, searchLoading)
                        else -> SettingsScreen(user, users, githubConfigured, { openUpdates() }, { signOut() })
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
                            }, { toggleWatch(it) }, onSeeAll = { streamTab = it }, onMarkPlayed = { markPlayed(it) })
                            "Movies" -> BrowseScreen("Movies", lib.titles.filter { it.kind == "movie" }, lib.progress, lib.watch,
                                browseQueries["Movies"].orEmpty(), { browseQueries["Movies"] = it }, { selected = it }, { toggleWatch(it) }, device.view("Movies"), { device.view("Movies", it) })
                            "Series" -> BrowseScreen("Series", lib.titles.filter { it.kind == "series" }, lib.progress, lib.watch,
                                browseQueries["Series"].orEmpty(), { browseQueries["Series"] = it }, { selected = it }, { toggleWatch(it) }, device.view("Series"), { device.view("Series", it) })
                            else -> BrowseScreen("Watchlist", lib.titles, lib.progress, lib.watch,
                                browseQueries["Watchlist"].orEmpty(), { browseQueries["Watchlist"] = it }, { selected = it }, { toggleWatch(it) }, device.view("Watchlist"), { device.view("Watchlist", it) })
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
            if (!profileOpen && !updatesOpen) {
                val pages = when (mode) {
                    "Hub" -> listOf(DockItem("Dashboard", PtsIcons.Home), DockItem("Ingest", PtsIcons.Download, "Download"), DockItem("Queue", PtsIcons.Queue), DockItem("Settings", PtsIcons.Settings))
                    "Files" -> listOf(DockItem("Browse", PtsIcons.Browse), DockItem("Transfers", PtsIcons.Transfers))
                    else -> listOf(
                        DockItem("Home", PtsIcons.Home),
                        DockItem("Movies", PtsIcons.Movies),
                        DockItem("TV Shows", PtsIcons.Tv, "Series"),
                        DockItem("Downloads", PtsIcons.Download),
                        DockItem("My List", PtsIcons.Watchlist, "Watchlist")
                    )
                }
                val current = if (downloadsOpen) "Downloads" else when (mode) { "Hub" -> hubTab; "Files" -> filesTab; else -> streamTab }
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
    private fun PlayerScreen(title: Title, ep: Episode?, initialSaved: SavedVideo?, onBack: () -> Unit) {
        val saved = remember(title.id, ep?.id) { initialSaved }
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
        var firstFrame by remember { mutableStateOf(false) }
        var gestureFeedback by remember { mutableStateOf("") }
        var showGestureHelp by remember { mutableStateOf(false) }
        LaunchedEffect(gestureFeedback) { if (gestureFeedback.isNotBlank()) { delay(1500); gestureFeedback = "" } }
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
            mutableLongStateOf(if (restoredPosition >= 0) restoredPosition.also { restoredPosition = -1L }
                else resumePosition(library?.progress?.get(title.id), ep?.id ?: 0L, ep?.duration?.toLong() ?: 0L) * 1000)
        }
        val startPlaying = remember { restoredAutoplay.also { restoredAutoplay = true } }
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
                    .setPreferredTextLanguage(playbackPrefs.optString("subtitle_lang").ifBlank { null })
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, playbackPrefs.optString("subtitle_lang").isBlank()).build()
                if (resumeAt > 0) seekTo(resumeAt)
                setPlaybackSpeed(speed)
            }
        }
        BackHandler { leavePlayer() }
        DisposableEffect(exo) {
            player = exo
            val originalBrightness = window.attributes.screenBrightness
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame = true }
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
            // Start after the native view is composed and the listener is installed,
            // rather than racing the first decode against TextureView attachment.
            firstFrame = false
            exo.prepare()
            exo.playWhenReady = startPlaying
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
          DisposableEffect(landscape) {
              val bars = WindowCompat.getInsetsController(window, window.decorView)
              if (landscape && !inPip) {
                  bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                  bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
              }
              onDispose { bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
          }
          Column(Modifier.fillMaxSize()) {
            Box((if (landscape) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)).testTag("player-surface").background(Color.Black)) {
                AndroidView(factory = { ctx -> (android.view.LayoutInflater.from(ctx).inflate(R.layout.native_player, null, false) as PlayerView).apply {
                    player = exo
                    useController = false
                    keepScreenOn = true
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                } }, update = { it.player = exo; it.keepScreenOn = playingNow || buffering; it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
                    modifier = Modifier.fillMaxSize())
                 if (!inPip) PlayerGestures(this@MainActivity, exo.currentPosition, duration,
                     onTap = { controls = !controls; interaction++ },
                     onSeekPreview = { seekPreview = it; touchControls() },
                     onSeek = { exo.seekTo(it); touchControls() },
                     onFeedback = { gestureFeedback = it; touchControls() })
                androidx.compose.animation.AnimatedVisibility(visible = controls && !inPip, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                     Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC040514), Color(0x11040514), Color(0xE6040514))))) {
                         Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                             PtsIconAction(PtsIcons.Back, "Back", { leavePlayer() }, tint = Color.White)
                             Text(ep?.let { "S${it.season}E${it.episode} · ${title.title}" } ?: title.title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                             if (ep != null) PtsIconAction(PtsIcons.Series, "Choose episode", { showEpisodes = true; touchControls() }, tint = Color.White)
                             PtsIconAction(PtsIcons.Subtitles, "Audio and subtitles", { showTracks = true; touchControls() }, tint = Color.White)
                             PtsIconAction(PtsIcons.More, "Player options", { showSettings = true; touchControls() }, tint = Color.White)
                         }
                         Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            PlayerSeekButton(false) { exo.seekTo((exo.currentPosition - 10000).coerceAtLeast(0)); touchControls() }
                             Box(Modifier.size(48.dp).background(Color(0xFFFAFAFF), androidx.compose.foundation.shape.CircleShape)
                                .clickable { if (exo.isPlaying) exo.pause() else { if (ended) exo.seekTo(0); exo.play() }; touchControls() }, contentAlignment = Alignment.Center) {
                                Icon(if (playingNow) PtsIcons.Pause else PtsIcons.Play, if (playingNow) "Pause playback" else "Play playback", Modifier.size(30.dp), tint = Pts.onAccent)
                            }
                            PlayerSeekButton(true) { exo.seekTo((exo.currentPosition + 10000).coerceAtMost(duration.coerceAtLeast(0))); touchControls() }
                        }
                         Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
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
                             Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                 Text("${clock(shown)} / ${clock(duration)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE7E8FF), modifier = Modifier.weight(1f))
                                 PlayerTextControl("${if (speed % 1f == 0f) speed.toInt().toString() else speed}×", "Playback settings") { showSettings = true; touchControls() }
                                 PlayerTextControl(if (muted) "Unmute" else "Mute", "Mute audio") { muted = !muted; exo.volume = if (muted) 0f else 1f; touchControls() }
                                 PtsIconAction(PtsIcons.Fit, if (playerFullscreen) "Exit fullscreen" else "Fullscreen", { fullscreen(!playerFullscreen); touchControls() }, tint = Color.White)
                 }
                         }
                     }
                 }
                 if (gestureFeedback.isNotBlank() && !inPip) Text(gestureFeedback,
                     Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .9f), RowShape).padding(horizontal = 16.dp, vertical = 10.dp).testTag("gesture-feedback"),
                     style = MaterialTheme.typography.labelLarge, color = Color.White)
                if ((buffering || !firstFrame) && error.isBlank()) CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Pts.accent)
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (saved != null) "Offline" else if (fallback) "VPS fallback" else "Pi stream", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Pts.accent)
                    PlayerTextControl("Gesture guide", "Gesture guide") { showGestureHelp = true }
                }
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
                    PtsSecondaryButton("Open Downloads", Modifier.fillMaxWidth()) { playing = false; downloadsOpen = true; mode = "Stream"; streamTab = "Downloads" }
                }
                if (title.kind == "series") SeasonEpisodes(title, ep, library?.progress?.get(title.id), savedVideos.map { it.key }.toSet(), downloading,
                    onPlay = { selectEpisode(it) }, onDownload = { startDownload(title, it) })
                val related = library?.titles.orEmpty().filter { it.id != title.id && it.genres.any { g -> g in title.genres } }.take(8)
                if (related.isNotEmpty()) PtsPosterShelf("Related", related, library?.progress.orEmpty(), library?.watch.orEmpty(),
                    { play(it, if (it.kind == "series") it.episodes.firstOrNull() else null) }, { toggleWatch(it) })
             }
            }
          }
          if (message.isNotBlank() && !inPip) Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
              if (messageOk) PtsOkToast(message) { message = "" }
              else PtsErrorToast(message) { message = "" }
          }
        }
        if (showGestureHelp) PtsConfirmDialog("Player gestures", "Double-tap left/right: −10/+10 seconds. Swipe horizontally: seek. Swipe vertically on the left: brightness; on the right: volume. Changes appear live while you drag.", "Got it",
            onConfirm = { showGestureHelp = false }, onDismiss = { showGestureHelp = false })
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
                PtsSecondaryButton("Picture in picture", Modifier.fillMaxWidth()) {
                    showSettings = false
                    try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) } catch (e: Exception) { toast("PiP unavailable: ${e.message}") }
                }
                PtsSecondaryButton(if (fill) "Fit video" else "Fill video", Modifier.fillMaxWidth()) { fill = !fill }
                PtsSecondaryButton("Gesture guide", Modifier.fillMaxWidth()) { showSettings = false; showGestureHelp = true }
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

    override fun onDestroy() { downloadWatch?.cancel(); super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        persistNavigation()
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
        if (navigationRestored) return
        val nav = device.navigation()
        if (nav.optLong("owner") == account?.optLong("id")) {
            mode = if (admin()) nav.optString("mode", "Hub") else "Stream"
            hubTab = nav.optString("hub", "Dashboard"); filesTab = nav.optString("files", "Browse")
            streamTab = nav.optString("stream", "Home")
            filesPath = nav.optString("path"); filesDevice = nav.optString("device", "vps")
            selected = library?.titles?.firstOrNull { it.id == nav.optLong("title") }
                ?: nav.optJSONObject("selected")?.let { runCatching { parseTitle(it) }.getOrNull() }
            episode = selected?.episodes?.firstOrNull { it.id == nav.optLong("episode") }
            downloadsOpen = nav.optBoolean("downloads") || intent?.getBooleanExtra("open_downloads", false) == true
            profileOpen = nav.optBoolean("profile"); updatesOpen = nav.optBoolean("updates")
            playing = !downloadsOpen && !updatesOpen && !profileOpen && nav.optBoolean("playing") && selected != null
            restoredPosition = nav.optLong("position", -1L); restoredAutoplay = nav.optBoolean("autoplay", true)
            val queries = nav.optJSONObject("queries")
            queries?.keys()?.forEach { browseQueries[it] = queries.optString(it) }
        }
        navigationRestored = true
        restoredNavigation = null
    }

    private fun persistNavigation() {
        val user = account ?: return
        val exo = player
        val pos = exo?.currentPosition?.coerceAtLeast(0) ?: restoredPosition
        val queries = JSONObject(); browseQueries.forEach { (key, value) -> queries.put(key, value) }
        device.navigation(JSONObject().put("owner", user.optLong("id")).put("mode", mode).put("hub", hubTab)
            .put("files", filesTab).put("stream", streamTab).put("path", filesPath).put("device", filesDevice)
            .put("title", selected?.id ?: 0).put("selected", selected?.toJson()).put("episode", episode?.id ?: 0)
            .put("downloads", downloadsOpen).put("profile", profileOpen).put("updates", updatesOpen)
            .put("playing", playing).put("position", pos).put("autoplay", resumeAfterBackground || exo?.isPlaying == true)
            .put("queries", queries))
        library?.let { device.library(user.optLong("id"), it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.getBooleanExtra("open_downloads", false)) { playing = false; mode = "Stream"; downloadsOpen = true; profileOpen = false; updatesOpen = false; streamTab = "Downloads" }
    }

    override fun onStart() {
        super.onStart()
        if (resumeAfterBackground) { player?.play(); resumeAfterBackground = false }
    }

    private fun startAccountSync() {
        foregroundSync = scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (account != null && !loading) {
                        try {
                            val user = api.me()
                            if (user.optLong("id") != account?.optLong("id")) { downloads?.pauseAll(); device.clearSession(); account = null; library = null; playing = false; continue }
                            account = user; device.account(user); if (!admin() && mode != "Stream") enterMode("Stream")
                            refreshLibrary(); loadPlaybackPrefs(); downloads?.refresh()
                        } catch (e: CancellationException) { throw e }
                        catch (e: ApiException) { if (e.status == 401) { downloads?.pauseAll(); device.clearSession(); account = null; library = null; playing = false } }
                        catch (_: Exception) { /* A temporary outage must not destroy the saved session/UI. */ }
                    }
                    delay(30000)
                }
            }
        }
    }

    override fun onStop() {
        if (!isInPictureInPictureMode) {
            resumeAfterBackground = player?.isPlaying == true
            persistNavigation()
            player?.pause()
        } else persistNavigation()
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
