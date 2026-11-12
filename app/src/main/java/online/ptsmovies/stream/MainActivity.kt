package online.ptsmovies.stream

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.Intent
import android.provider.OpenableColumns
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val api = ApiProvider.create()
    private val scope get() = lifecycleScope
    private lateinit var offline: OfflineStore
    private val device by lazy { DeviceState(this) }
    private val imagesDelegate = lazy { coil.ImageLoader.Builder(applicationContext).okHttpClient(api.http)
        .memoryCache { coil.memory.MemoryCache.Builder(applicationContext).maxSizeBytes(4 * 1024 * 1024).build() }.build() }
    private val images by imagesDelegate
    private var downloads: DownloadRepository? = null
    private var downloadWatch: Job? = null
    private var downloadTasks by mutableStateOf(emptyList<DownloadTask>())
    private var updatesOpen by mutableStateOf(false)
    private var editProfile by mutableStateOf(false)
    private var navigationRestored = false
    private var restoredPosition = -1L
    private var restoredAutoplay = true
    private var resumeAfterBackground = false
    private var backgroundPosition: Long? = null
    private var foregroundSync: Job? = null
    private var sessionGeneration = 0
    private var roleVerified by mutableStateOf(false)
    private var librarySave: Job? = null
    private var libraryRequest: Job? = null
    private var startupRestored = false
    private var nativeSettings by mutableStateOf("")
    private var episodeDownloadTitle by mutableStateOf<Title?>(null)
    private var sleepDeadline by mutableLongStateOf(0L)
    private var sleepAtEnd by mutableStateOf(false)
    private val preferenceMutex = Mutex()
    private val progressMutex = Mutex()
    private data class ProgressStores(val owner: Long, val journal: ProgressJournal, val episodes: EpisodeProgress)
    private var progressStores: ProgressStores? = null
    private fun stores(): ProgressStores? {
        val owner = account?.optLong("id") ?: return null
        return progressStores?.takeIf { it.owner == owner }
    }
    private suspend fun prepareStores(owner: Long) {
        val stores = withContext(Dispatchers.IO) { ProgressStores(owner, ProgressJournal(applicationContext, owner), EpisodeProgress(applicationContext, owner)).also { it.episodes.completed() } }
        if (owner == account?.optLong("id")) progressStores = stores
    }
    private fun durableEdit(edit: (ProgressStores) -> Unit, after: () -> Unit = {}) {
        val stores = stores() ?: return
        DurableWork.scope.launch {
            try {
                edit(stores)
                withContext(Dispatchers.Main) { if (!isDestroyed && account?.optLong("id") == stores.owner) { episodeProgressRevision++; after() } }
            } catch (e: Exception) { withContext(Dispatchers.Main) { if (!isDestroyed) toast("Could not save progress: ${e.message}") } }
        }
    }
    private var lastCatalogueAt = 0L
    private var destinationDialog by mutableStateOf(false)
    private var destinationAction: (() -> Unit)? = null
    private var destinationOwner = 0L
    private val pickDownloadFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val owner = destinationOwner
        if (uri != null && owner == account?.optLong("id")) try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            getSharedPreferences("download_destination_$owner", MODE_PRIVATE).edit().putString("tree", uri.toString()).putBoolean("chosen", true).apply()
            destinationAction?.invoke()
        } catch (_: Exception) { toast("Folder access was not granted. Choose another folder or private storage.") }
        destinationAction = null
    }
    private fun withDownloadDestination(action: () -> Unit) {
        val owner = account?.optLong("id") ?: return
        if (getSharedPreferences("download_destination_$owner", MODE_PRIVATE).getBoolean("chosen", false)) action()
        else { destinationOwner = owner; destinationAction = action; destinationDialog = true }
    }
    private fun enqueueEpisodes(title: Title, episodes: List<Episode>) = withDownloadDestination {
        val repository = downloads ?: return@withDownloadDestination
        scope.launch { try { withContext(Dispatchers.IO) { repository.enqueueSeason(title, episodes) }; toast("Downloads queued", true) } catch (e: Exception) { toast(e.message ?: "Cannot queue downloads") } }
    }
    private fun journal() = stores()?.journal
    private fun episodeProgress() = stores()?.episodes
    private val libraryRefresh = RefreshGate()
    private val opsRefresh = RefreshGate()
    private val relayRefresh = RefreshGate()
    private var episodeProgressRevision by mutableIntStateOf(0)
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
    private var playbackTitle by mutableStateOf<Title?>(null)
    private var miniPlayer by mutableStateOf(false)
    private var player: ExoPlayer? = null
    private var pipVideoView: android.view.View? = null
    private var inPip by mutableStateOf(false)
    private var downloading by mutableStateOf("")
    private var downloadPercent by mutableIntStateOf(0)
    private var savedVideos by mutableStateOf(emptyList<SavedVideo>())
    private var fileJob: Job? = null
    private var fileGeneration = 0
    private var filesEntriesKey by mutableStateOf("")
    private var filesCheckedAt = 0L

    private var metrics by mutableStateOf<JSONObject?>(null)
    private var metricsTarget by mutableStateOf("vps")
    private var queueJobs by mutableStateOf(emptyList<JSONObject>())
    private var queueBusy by mutableStateOf(false)
    private var queueRefreshing by mutableStateOf(false)
    private var metricsRefreshing by mutableStateOf(false)
    private var queueReloadPending = false
    private var googleClientId by mutableStateOf("")
    private val episodeNames by lazy { EpisodeMetadata(this) }
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
    private var subtitleRevision by mutableIntStateOf(0)
    private var subtitleTarget: Triple<Long, Long, Long>? = null
    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = subtitleTarget; subtitleTarget = null
        if (uri != null && target != null) scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= MAX_SUBTITLE_BYTES) { "Subtitle exceeds 2 MiB" }; out.write(buffer, 0, n) }; out.toByteArray()
                } }
                require(target.first == account?.optLong("id")) { "Account changed; choose the subtitle again" }
                withContext(Dispatchers.IO) { LocalSubtitles(this@MainActivity, target.first).save(target.second, target.third, bytes) }
                subtitleRevision++; toast("Subtitles imported for this video", true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(e.message ?: "Could not import subtitles") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        scope.launch { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { if (!startupRestored) { restoreSession(); startupRestored = true } } }
        scope.launch { try { val cfg = api.json("/auth/config"); googleClientId = if (cfg.optBoolean("google_enabled")) cfg.optString("google_client_id") else "" } catch (_: Exception) { } }
        startAccountSync()
        setContent {
            val dark = device.theme == "dark" || (device.theme == "system" && androidx.compose.foundation.isSystemInDarkTheme())
            val fullPlayer = playing && (!miniPlayer || inPip)
            val watching = fullPlayer || (dark && selected != null && mode == "Stream" && !profileOpen && !downloadsOpen && !updatesOpen)
            PtsTheme(if (watching) "watch" else device.theme) {
              val indexes = remember(account?.optLong("id")) { LibraryIndexCache() }
              CompositionLocalProvider(LocalLibraryIndexes provides indexes) {
                val bar = if (fullPlayer) Color.Black else Pts.bg
                val light = !fullPlayer && MaterialTheme.colorScheme.background.luminance() > .5f
                SideEffect {
                    window.statusBarColor = android.graphics.Color.rgb((bar.red * 255).toInt(), (bar.green * 255).toInt(), (bar.blue * 255).toInt())
                    window.navigationBarColor = window.statusBarColor
                    if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = if (fullPlayer) android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    }
                    WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
                }
                PtsBackground(if (fullPlayer) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing), decorative = !watching) {
                    if (account != null) DownloadLocationPicker()
                    episodeDownloadTitle?.let { title ->
                        EpisodeDownloadDialog(title, savedVideos.map { it.key }.toSet(), journal()?.completed().orEmpty(),
                            onStart = { episodes -> episodeDownloadTitle = null; enqueueEpisodes(title, episodes) },
                            onClose = { episodeDownloadTitle = null }, allowHls = account != null)
                    }
                    when {
                        loading -> PtsLoading()
                        account == null -> LoginScreen(startupError, busy = false, googleEnabled = googleClientId.isNotBlank(), onGoogle = { signInGoogle(it) }) { user, pass, remember -> signIn(user, pass, remember) }
                        !fullPlayer -> AppShell()
                    }
                    // Stable sibling/key: resizing to the in-app mini-player never
                    // removes PlayerScreen or owns another ExoPlayer instance.
                    if (playing && playbackTitle != null && account != null) key(playbackTitle!!.id, episode?.id) {
                        val compact = miniPlayer && !inPip
                        val dock = !profileOpen && !updatesOpen && !downloadsOpen && nativeSettings.isBlank() && WindowInsets.ime.getBottom(LocalDensity.current) == 0
                        val frame = if (compact) Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = if (dock) 88.dp else 4.dp)
                            .fillMaxWidth().height(80.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) else Modifier.fillMaxSize()
                        Box(frame) {
                            val saved = remember(savedVideos, playbackTitle!!.id, episode?.id) { savedVideos.firstOrNull { it.key == "${playbackTitle!!.id}_${episode?.id ?: 0}" } }
                            PlayerScreen(playbackTitle!!, episode, saved) { stopPlayback() }
                        }
                    }
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
            prepareStores(cached.optLong("id"))
            library = withContext(Dispatchers.IO) { device.library(cached.optLong("id"))?.let { ProgressJournal(this@MainActivity, cached.optLong("id")).overlay(it) } }
            attachDownloads()
            mode = "Stream"
            if (cached.optString("role") != "admin") restoreNavigation()
            loading = false
        }
        try {
            val verified = api.me()
            if (generation != sessionGeneration) return
            if (cached != null && cached.optLong("id") != verified.optLong("id")) {
                librarySave?.cancel(); filesEntries = emptyList(); filesEntriesKey = ""
                downloads?.pauseAll(); device.clearSession(); library = null; selected = null; episode = null
                playing = false; profileOpen = false; downloadsOpen = false; updatesOpen = false; navigationRestored = false
            }
            account = verified
            if (progressStores?.owner != verified.optLong("id")) prepareStores(verified.optLong("id"))
            roleVerified = true
            if (!admin()) restrictToStream()
            device.account(account!!)
            attachDownloads()
            if (cached == null) mode = "Stream"
            startupError = ""
            refreshLibrary()
            loadPlaybackPrefs()
            restoreNavigation()
            // Hub requests are started only by the visible Hub lifecycle, not login.
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
                finishSignIn(api.login(user, password, remember))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                startupError = e.message ?: "Sign in failed"
            } finally { loading = false }
        }
    }

    private suspend fun finishSignIn(user: JSONObject) {
        account = user; roleVerified = true; device.account(user); prepareStores(user.optLong("id")); attachDownloads(); mode = "Stream"
        if (!admin()) restrictToStream()
        toast("Welcome back, " + user.optString("name").ifBlank { user.optString("username") }, true)
        refreshLibrary(); loadPlaybackPrefs()
    }

    private fun signInGoogle(remember: Boolean) {
        if (googleClientId.isBlank() || loading) return
        scope.launch {
            loading = true; startupError = ""
            try {
                val option = com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption.Builder(googleClientId).build()
                val request = androidx.credentials.GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = androidx.credentials.CredentialManager.create(this@MainActivity).getCredential(this@MainActivity, request)
                val credential = result.credential
                require(credential is androidx.credentials.CustomCredential && credential.type == com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                val token = com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.createFrom(credential.data).idToken
                finishSignIn(api.googleLogin(token, remember))
            } catch (_: androidx.credentials.exceptions.GetCredentialCancellationException) { /* User cancelled; keep password login available. */ }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { startupError = "Google sign-in is unavailable on this device or its Android OAuth registration is missing. Use your password; no account was created locally." }
            finally { loading = false }
        }
    }

    private suspend fun refreshLibrary() {
        val owner = account?.optLong("id") ?: return
        val generation = sessionGeneration
        val ticket = libraryRefresh.begin(queueLatest = true) ?: return
        val requestJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        libraryRequest = requestJob
        val previousTitles = library?.titles
        val observedAt = System.currentTimeMillis()
        try {
            val fresh = api.library()
            val result = withContext(Dispatchers.IO) {
                ProgressJournal(this@MainActivity, owner).overlay(fresh).let { lib ->
                    val named = lib.titles.map { applyEpisodeNames(it, episodeNames.cached(it)) }
                    lib.copy(titles = if (previousTitles != null && previousTitles == named) previousTitles else named)
                }
            }
            if (generation != sessionGeneration || owner != account?.optLong("id")) return
            lastCatalogueAt = android.os.SystemClock.elapsedRealtime()
            val current = playbackTitle?.id?.takeIf { playing }
            val next = if (current != null && library?.progress?.containsKey(current) == true) result.copy(progress = result.progress + (current to library!!.progress.getValue(current))) else result
            if (next != library) { library = next; saveLibraryCache(owner, next, observedAt) }
            val repository = downloads
            if (previousTitles !== result.titles) scope.launch { if (repository?.owner == owner) { repository.store.hydrate(result.titles); repository.refresh() } }
            selected?.let { old -> library?.titles?.firstOrNull { it.id == old.id }?.let { selected = it } }
            playbackTitle?.let { old -> library?.titles?.firstOrNull { it.id == old.id }?.let { playbackTitle = it } }
            val progress = episodeProgress()
            val log = journal()
            scope.launch {
                if (generation == sessionGeneration && owner == account?.optLong("id")) {
                    try { progressMutex.withLock { if (generation == sessionGeneration && owner == account?.optLong("id")) log?.flush(api) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Offline edits remain durable; library display does not wait for uploads. */ }
                    if (generation != sessionGeneration || owner != account?.optLong("id")) return@launch
                    progress?.sync(api)
                    if (generation == sessionGeneration && owner == account?.optLong("id")) episodeProgressRevision++
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            toast("Library unavailable: ${e.message}")
        } finally {
            if (libraryRequest === requestJob) libraryRequest = null
            val again = libraryRefresh.finish(ticket)
            if (again && generation == sessionGeneration && owner == account?.optLong("id") && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) scope.launch { refreshLibrary() }
        }
    }

    private fun signOut() {
        val signedOutOwner = account?.optLong("id")
        stopService(Intent(this, JobAlertsService::class.java))
        sessionGeneration++
        destinationAction = null; destinationDialog = false; destinationOwner = 0
        libraryRefresh.reset(); opsRefresh.reset(); relayRefresh.reset(); progressStores = null
        roleVerified = false; librarySave?.cancel(); downloads?.detachUi()
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
                account = null; library = null; selected = null; playbackTitle = null; miniPlayer = false; playing = false; profileOpen = false; downloadsOpen = false
                device.clearSession(); signedOutOwner?.let { DownloadNotifications.clear(this@MainActivity, it) }; navigationRestored = false; updatesOpen = false
                queueJobs = emptyList(); queueDone = emptyList(); metrics = null; users = emptyList(); filesEntries = emptyList(); filesEntriesKey = ""
                savedVideos = emptyList(); downloadTasks = emptyList(); downloading = ""; message = ""; startupError = ""
                fileClipboard = null; fileEditor = null; fileStatus = ""; clearedProgress.clear()
                relayJobs = emptyList()
                playbackPrefs = JSONObject(); nativeSettings = ""; episodeDownloadTitle = null; sleepDeadline = 0; sleepAtEnd = false
                loading = false
            }
        }
    }

    private fun toast(text: String, ok: Boolean = false) {
        message = text
        messageOk = ok
    }

    private fun admin(): Boolean = roleVerified && account?.optString("role") == "admin"

    private fun restrictToStream() {
        mode = "Stream"
        fileJob?.cancel(); fileOperation?.cancel(); searchJob?.cancel(); fileGeneration++
        fileEditor = null; fileClipboard = null; filesEntries = emptyList(); filesEntriesKey = ""; filesLoading = false
        queueJobs = emptyList(); queueDone = emptyList(); metrics = null; users = emptyList(); ops = emptyList(); relayJobs = emptyList()
        opsRefresh.reset(); relayRefresh.reset()
        stopService(Intent(this, JobAlertsService::class.java))
    }

    private fun saveLibraryCache(owner: Long, value: Library, observedAt: Long = 0) {
        librarySave?.cancel()
        val generation = sessionGeneration
        librarySave = scope.launch(Dispatchers.IO) {
            if (generation == sessionGeneration && account?.optLong("id") == owner) device.library(owner, value, observedAt)
        }
    }

    private fun enterMode(next: String) {
        if (!admin() && next != "Stream") return
        mode = streamOnlyMode(if (admin()) "admin" else "user", next)
        profileOpen = false; downloadsOpen = false; updatesOpen = false; selected = null
        if (!miniPlayer) playing = false
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
            if (title.kind == "series" && ep == null) { episodeDownloadTitle = title; return }
            withDownloadDestination {
                val repository = downloads ?: return@withDownloadDestination
                scope.launch { try { withContext(Dispatchers.IO) { repository.enqueue(title, ep) }; toast("Download queued", true) } catch (e: Exception) { toast(e.message ?: "Cannot start download") } }
            }
        } catch (e: Exception) { toast(e.message ?: "Cannot start download") }
    }

    private fun openDeviceDownloads() {
        playing = false; playerFullscreen = false; requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        selected = null; profileOpen = false; updatesOpen = false; nativeSettings = ""; mode = "Stream"; streamTab = "Home"; downloadsOpen = true
        persistNavigation()
    }

    private suspend fun attachDownloads() {
        val user = account ?: return
        if (downloads?.owner == user.optLong("id") && downloadWatch?.isActive == true) return
        val repo = try { withContext(Dispatchers.IO) { DeviceDownloads.forUi(this@MainActivity, api, user.optLong("id")) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast("Downloads could not be opened; existing data was kept. ${e.message}"); return }
        if (user.optLong("id") != account?.optLong("id")) { repo.detachUi(); return }
        offline = repo.store
        val cached = getSharedPreferences("playback_${user.optLong("id")}", MODE_PRIVATE).getString("value", "{}")
        if (downloads === repo && downloadWatch?.isActive == true) return
        clearedProgress.clear(); clearedProgress.addAll(journal()?.clearedTitles().orEmpty())
        playbackPrefs = runCatching { JSONObject(cached!!) }.getOrDefault(JSONObject())
        downloads?.detachUi(); downloads = repo; downloadWatch?.cancel()
        savedVideos = repo.state.value.saved; downloadTasks = repo.state.value.tasks
        downloadWatch = scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { repo.state.collect { snapshot ->
                savedVideos = snapshot.saved; downloadTasks = snapshot.tasks
                val active = snapshot.tasks.firstOrNull { it.status == "downloading" }
                downloading = active?.label.orEmpty(); downloadPercent = active?.progress?.percent ?: 0
            } }
        }
    }

    private fun openUpdates() { profileOpen = false; downloadsOpen = false; updatesOpen = true; NativeUpdates.get(this).check() }

    private fun play(title: Title, ep: Episode?) {
        val resumeExistingPlayer = playing && playbackTitle?.id == title.id && episode?.id == ep?.id
        clearedProgress.remove(title.id)
        durableEdit({ it.journal.begin(title.id) })
        val named = applyEpisodeNames(title, episodeNames.cached(title))
        episode = named.episodes.firstOrNull { it.id == ep?.id } ?: ep
        selected = named
        playbackTitle = named; miniPlayer = false
        val activeEpisode = episode; val completed = episodeProgress()?.completed().orEmpty()
        scope.launch(Dispatchers.IO) { downloads?.smartNext(named, activeEpisode, completed) }
        playing = true
        profileOpen = false; downloadsOpen = false
        restoredPosition = -1L; restoredAutoplay = true
        if (resumeExistingPlayer) player?.play()
        persistNavigation()
    }

    private fun applyMetadataTitle(title: Title) {
        if (selected?.id == title.id && selected?.title == title.title && selected?.year == title.year) {
            selected = title
            episode = title.episodes.firstOrNull { it.id == episode?.id } ?: episode
            library = library?.let { lib -> lib.copy(titles = lib.titles.map { if (it.id == title.id) title else it }) }
            val repository = downloads
            val owner = account?.optLong("id")
            scope.launch { if (repository?.owner == owner) { repository?.store?.hydrate(listOf(title)); repository?.refresh() } }
        }
    }

    private fun stopPlayback() {
        playing = false; miniPlayer = false; playbackTitle = null; episode = null; playerFullscreen = false
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        persistNavigation()
    }

    private fun minimizePlayback() {
        if (!playing || inPip) return
        fullscreen(false)
        miniPlayer = true; selected = null; profileOpen = false; downloadsOpen = false; updatesOpen = false; nativeSettings = ""
        persistNavigation()
    }

    private fun expandPlayback() {
        if (!playing) return
        selected = playbackTitle; miniPlayer = false; persistNavigation()
    }

    private fun loadMetrics() {
        if (!admin() || metricsRefreshing) return
        metricsRefreshing = true
        val owner = account?.optLong("id"); val generation = sessionGeneration
        scope.launch {
            try { val result = api.json("/system/metrics"); if (admin() && owner == account?.optLong("id") && generation == sessionGeneration) metrics = result }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (admin() && generation == sessionGeneration) toast("Metrics: ${e.message}") }
            finally { metricsRefreshing = false }
        }
    }

    private fun loadQueue() {
        if (!admin()) return
        if (queueRefreshing) { queueReloadPending = true; return }
        queueRefreshing = true
        val owner = account?.optLong("id")
        val generation = sessionGeneration
        scope.launch {
            try {
                val data = api.json("/queue")
                if (generation != sessionGeneration || owner != account?.optLong("id") || !admin()) return@launch
                queueJobs = data.optJSONArray("jobs").objects()
                queueDone = data.optJSONArray("completed").objects()
            } catch (e: Exception) { if (generation == sessionGeneration && owner == account?.optLong("id")) toast("Queue: ${e.message}") }
            finally {
                queueRefreshing = false
                if (queueReloadPending) { queueReloadPending = false; if (admin()) loadQueue() }
            }
        }
    }

    private fun changeQueue(id: String?, link: String? = null) {
        if (!admin() || queueBusy) return
        queueBusy = true
        val owner = account?.optLong("id")
        scope.launch {
            try {
                val path = if (id == null) "/queue/retry-all" else "/queue/${java.net.URLEncoder.encode(id, "UTF-8")}/${if (link == null) "retry" else "edit"}"
                val response = api.json(path, "POST", link?.let { JSONObject().put("download_url", it) })
                if (owner == account?.optLong("id")) {
                    toast(if (id == null) "Retried ${response.optInt("retried")} failed jobs" else if (link == null) "Job re-queued" else "Job restarted with the new link", true)
                    loadQueue()
                }
            } catch (e: Exception) { if (owner == account?.optLong("id")) toast(e.message ?: "Queue action failed") }
            finally { queueBusy = false }
        }
    }

    private fun loadSettings() {
        if (!admin()) return
        scope.launch {
            try { users = api.json("/admin/users").optJSONArray("users").objects() } catch (_: Exception) { }
            try { githubConfigured = api.json("/admin/github").optBoolean("configured", true) } catch (_: Exception) { githubConfigured = false }
        }
    }

    private fun search(query: String) {
        if (!admin()) return
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

    private fun loadFiles(device: String = filesDevice, path: String = filesPath, force: Boolean = true) {
        if (!admin()) return
        val key = "$device/$path"
        if (!force && filesEntriesKey == key && android.os.SystemClock.elapsedRealtime() - filesCheckedAt < 15000) return
        fileJob?.cancel()
        val generation = ++fileGeneration
        val owner = account?.optLong("id"); val session = sessionGeneration
        if (filesEntriesKey != key) filesEntries = emptyList()
        filesLoading = true
        fileJob = scope.launch {
            try {
                val entries = api.json("/admin/files?path=${java.net.URLEncoder.encode(path, "UTF-8")}&device=$device").optJSONArray("entries").objects()
                if (admin() && owner == account?.optLong("id") && session == sessionGeneration && generation == fileGeneration && device == filesDevice && path == filesPath) {
                    filesEntries = entries; filesEntriesKey = key; filesCheckedAt = android.os.SystemClock.elapsedRealtime()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (admin() && session == sessionGeneration && generation == fileGeneration) toast("Files: ${e.message}") }
            finally { if (generation == fileGeneration) filesLoading = false }
        }
    }

    private fun loadOps() {
        if (!admin()) return
        val ticket = opsRefresh.begin(queueLatest = true) ?: return
        val owner = account?.optLong("id"); val generation = sessionGeneration
        scope.launch {
            try {
                val next = api.json("/admin/files/ops").optJSONArray("ops").objects()
                if (!admin() || owner != account?.optLong("id") || generation != sessionGeneration) return@launch
                val finished = next.any { op -> op.optString("state") != "running" && ops.none { it.optString("id") == op.optString("id") && it.optString("state") == op.optString("state") } }
                ops = next
                if (finished && mode == "Files" && filesTab == "Browse") loadFiles()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            finally { val again = opsRefresh.finish(ticket); if (again && admin() && generation == sessionGeneration && mode == "Files") loadOps() }
        }
    }

    private fun loadRelay() {
        if (!admin()) return
        val ticket = relayRefresh.begin(queueLatest = true) ?: return
        val owner = account?.optLong("id"); val generation = sessionGeneration
        scope.launch {
            try { val result = api.json("/relay").optJSONArray("jobs").objects(); if (admin() && owner == account?.optLong("id") && generation == sessionGeneration) relayJobs = result }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            finally { val again = relayRefresh.finish(ticket); if (again && admin() && generation == sessionGeneration && mode == "Files") loadRelay() }
        }
    }

    private fun loadPlaybackPrefs() {
        val owner = account?.optLong("id") ?: return
        scope.launch { preferenceMutex.withLock { try {
            val local = getSharedPreferences("playback_$owner", MODE_PRIVATE)
            if (owner != account?.optLong("id")) return@withLock
            if (local.getBoolean("pending", false)) {
                api.json("/stream/prefs", "PUT", JSONObject(local.getString("value", "{}")!!))
                local.edit().putBoolean("pending", false).apply()
            }
            val result = api.json("/stream/prefs")
            if (owner == account?.optLong("id")) { playbackPrefs = result.optJSONObject("prefs") ?: result; local.edit().putString("value", playbackPrefs.toString()).apply() }
        } catch (_: Exception) { } } }
    }

    private fun markPlayed(title: Title) {
        val active = episode?.takeIf { playbackTitle?.id == title.id && playing }
        durableEdit({ stores ->
            if (title.kind == "series" && active == null) stores.episodes.recordBatch(title.episodes.map { EpisodeEdit(title.id, it.id, 0, it.duration.toLong(), true) })
            else stores.episodes.record(title.id, active?.id ?: 0, 0, active?.duration?.toLong() ?: 0, true)
            stores.journal.markCompleted("${title.id}_${active?.id ?: 0}")
        })
        clearedProgress.add(title.id)
        recordProgress(title.id, episode?.id, 0, 0, clear = true)
        library = library?.let { it.copy(progress = it.progress - title.id) }
        toast("Marked as completed", true)
    }

    private fun recordProgress(title: Long, ep: Long?, position: Long, duration: Long, clear: Boolean = false) {
        val owner = account?.optLong("id") ?: return
        val log = journal() ?: return
        val progress = episodeProgress() ?: return
        library = library?.let { lib -> lib.copy(progress = if (clear) lib.progress - title else lib.progress + (title to Resume(ep ?: 0, position, duration))) }
        durableEdit({
            if (!clear) progress.record(title, ep ?: 0, position, duration, duration > 0 && position >= duration * 95 / 100)
            log.put(title, ep, position, duration, clear)
            StateDatabase.get(applicationContext).resume(owner, title, ep ?: 0, position, duration, clear)
        }, {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) scope.launch { progressMutex.withLock { if (owner == account?.optLong("id")) { try { log.flush(api) } catch (_: Exception) { }; progress.sync(api) } } }
        })
    }

    private fun markEpisode(title: Title, ep: Episode, completed: Boolean) {
        durableEdit({ it.episodes.record(title.id, ep.id, 0, ep.duration.toLong(), completed) }, { scope.launch { episodeProgress()?.sync(api); episodeProgressRevision++ } })
        toast(if (completed) "Episode marked completed" else "Episode marked unwatched", true)
    }

    private fun playerPreference(key: String, value: Any) {
        val snapshot = JSONObject(playbackPrefs.toString()).put(key, value)
        playbackPrefs = snapshot
        val owner = account?.optLong("id") ?: return
        getSharedPreferences("playback_$owner", MODE_PRIVATE).edit().putString("value", snapshot.toString()).putBoolean("pending", true).apply()
        loadPlaybackPrefs()
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
            playing && !miniPlayer -> stopPlayback()
            nativeSettings.isNotBlank() -> { nativeSettings = ""; profileOpen = true }
            updatesOpen -> updatesOpen = false
            profileOpen -> profileOpen = false
            downloadsOpen -> { downloadsOpen = false; profileOpen = true; streamTab = "Home" }
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
    private fun DownloadLocationPicker() {
        val user = account ?: return
        if (destinationDialog) androidx.compose.material3.AlertDialog(onDismissRequest = { destinationDialog = false; destinationAction = null },
            title = { Text("Download location") }, text = { Text("Choose once for new downloads. Private storage stays inside PTS; files in a selected folder are accessible outside the app. Existing downloads are not moved.") },
            confirmButton = { androidx.compose.material3.TextButton({ destinationDialog = false; pickDownloadFolder.launch(null) }) { Text("Choose folder") } },
            dismissButton = { androidx.compose.material3.TextButton({
                destinationDialog = false
                getSharedPreferences("download_destination_${user.optLong("id")}", MODE_PRIVATE).edit().remove("tree").putBoolean("chosen", true).apply()
                destinationAction?.invoke(); destinationAction = null
            }) { Text("Private storage") } })
    }

    @Composable
    private fun AppShell() {
        BackHandler { routeBack() }
        val user = account ?: return
        val completed = remember(user.optLong("id"), episodeProgressRevision) { episodeProgress()?.completed().orEmpty() }
        var about by remember { mutableStateOf(false) }
        var passwordOpen by remember { mutableStateOf(false) }
        if (passwordOpen && profileOpen) PasswordDialog(api, { passwordOpen = false; toast("Password updated", true) }, { passwordOpen = false })
        if (about && profileOpen) SourceCreditsDialog(library?.titles.orEmpty().mapNotNull { episodeNames.cached(it)?.source }.distinct(), { about = false })
        if (editProfile) EditProfileDialog(user, onSave = { name, email ->
            scope.launch { try {
                account = api.json("/auth/me", "PUT", JSONObject().put("name", name).put("email", email)).getJSONObject("user")
                device.account(account!!); editProfile = false; toast("Profile synced", true)
            } catch (e: Exception) { toast(e.message ?: "Could not save profile") } }
        }, onClose = { editProfile = false })
        if (admin()) fileEditor?.let { editor -> FileEditorDialog(editor, fileBusy, { saveFileEditor(it) }, { fileEditor = null }) }
        LaunchedEffect(mode, hubTab, user.optLong("id"), profileOpen, downloadsOpen, updatesOpen, nativeSettings) {
            if (mode == "Hub" && admin() && !profileOpen && !downloadsOpen && !updatesOpen && nativeSettings.isBlank()) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (hubTab == "Dashboard") loadMetrics()
                    if (hubTab == "Dashboard" || hubTab == "Queue") loadQueue()
                    delay(8000)
                }
            }
        }
        val name = user.optString("name").ifBlank { user.optString("username") }
        val streamUser = remember(user, roleVerified) { if (admin()) user else JSONObject(user.toString()).put("role", "user") }
        val movieTitles = remember(library?.titles) { library?.titles.orEmpty().filter { it.kind == "movie" } }
        val seriesTitles = remember(library?.titles) { library?.titles.orEmpty().filter { it.kind == "series" } }
        val savedKeys = remember(savedVideos) { savedVideos.map { it.key }.toSet() }
        val pageRoute = when {
            nativeSettings.isNotBlank() -> "preferences/$nativeSettings"
            updatesOpen -> "updates"
            profileOpen -> "profile"
            downloadsOpen -> "downloads"
            mode == "Hub" -> "hub/$hubTab"
            mode == "Files" -> "files/$filesTab/$filesDevice/$filesPath"
            selected != null -> "title/${selected!!.id}"
            else -> "stream/$streamTab"
        }
        Column(Modifier.fillMaxSize()) {
            PtsTopBar(
                title = sectionBrand(if (admin()) mode else "Stream"),
                initial = name.take(1).uppercase(),
                onAvatar = { profileOpen = true; downloadsOpen = false },
                onTitle = {
                    if (admin() && mode != "Hub") enterMode("Hub") else if (selected != null) selected = null
                }, avatar = api.imageUrl(user.optString("avatar")), imageLoader = images
            )
            Box(Modifier.weight(1f).ptsPageMotion(pageRoute)) {
                when {
                    nativeSettings == "downloads" -> DownloadSettingsScreen(NativePreferences(this@MainActivity), { nativeSettings = ""; profileOpen = true }, { scope.launch(Dispatchers.IO) { downloads?.reconcile() }; nativeSettings = ""; profileOpen = true },
                        onLocation = { destinationOwner = user.optLong("id"); destinationAction = null; destinationDialog = true },
                        location = if (getSharedPreferences("download_destination_${user.optLong("id")}", MODE_PRIVATE).getString("tree", null) == null) "Private app storage" else "Selected folder")
                    nativeSettings == "playback" -> PlaybackPreferencesScreen(playbackPrefs, { key, value -> playerPreference(key, value) }, { nativeSettings = ""; profileOpen = true })
                    nativeSettings == "sync" -> SyncAlertsScreen(streamUser, api) { nativeSettings = ""; profileOpen = true; episodeProgressRevision++ }
                    updatesOpen -> NativeUpdateScreen(NativeUpdates.get(this@MainActivity), { NativeUpdates.get(this@MainActivity).install(this@MainActivity) }, { updatesOpen = false })
                    profileOpen -> ProfileScreen(streamUser, savedVideos.size, { profileOpen = false; downloadsOpen = true }, {
                        openUpdates()
                    }, { enterMode(it) }, { signOut() }, avatar = api.imageUrl(user.optString("avatar")), imageLoader = images,
                        theme = device.theme, onTheme = { device.theme(it) }, onEdit = { editProfile = true },
                        onPhoto = { selectPhoto.launch("image/*") }, onRemovePhoto = {
                            scope.launch { try { account = api.json("/auth/avatar", "DELETE").getJSONObject("user"); device.account(account!!); toast("Photo removed", true) }
                                catch (e: Exception) { toast(e.message ?: "Could not remove photo") } }
                        }, onPlayback = { nativeSettings = "playback"; profileOpen = false }, onDownloadSettings = { nativeSettings = "downloads"; profileOpen = false }, onAbout = { about = true }, onSync = { nativeSettings = "sync"; profileOpen = false }, onPassword = { passwordOpen = true })
                    downloadsOpen -> DownloadsScreen(savedVideos, { item ->
                        val title = library?.titles?.firstOrNull { it.id == item.titleId }
                        val stored = title ?: item.titleMetadata()
                        play(stored, stored.episodes.firstOrNull { item.key.endsWith("_${it.id}") })
                    }, { item ->
                         scope.launch { try { withContext(Dispatchers.IO) { downloads?.remove(item) }; toast("Download deleted", ok = true) }
                         catch (e: Exception) { toast(e.message ?: "Could not delete download") } }
                    }, tasks = downloadTasks, onPause = { key -> scope.launch(Dispatchers.IO) { downloads?.pause(key) } }, onResume = { key -> scope.launch(Dispatchers.IO) { downloads?.resume(key) } }, onCancel = { key -> scope.launch(Dispatchers.IO) { downloads?.cancel(key) } },
                        titles = library?.titles.orEmpty(), imageLoader = images, onBack = { downloadsOpen = false; profileOpen = true })
                    mode == "Hub" && admin() -> when (hubTab) {
                        "Dashboard" -> DashboardScreen(name, metrics, metricsTarget, { metricsTarget = it }, { loadMetrics() }, queueJobs, queueDone,
                            onQueue = { hubTab = "Queue" }, refreshing = metricsRefreshing)
                        "Queue" -> QueueScreen(queueJobs, queueDone, queueStage, { queueStage = it }, { loadQueue() }, { id ->
                            scope.launch { try { api.json("/queue/${java.net.URLEncoder.encode(id, "UTF-8")}", "DELETE"); loadQueue() } catch (e: Exception) { toast(e.message ?: "Delete failed") } }
                        }, onCancelStage = { stage, ids -> cancelStage(stage, ids) }, onRetry = { id -> changeQueue(id) },
                            onEdit = { job, link -> changeQueue(job.optString("job_id"), link) }, busy = queueBusy, refreshing = queueRefreshing)
                        "Download" -> NativeIngest(api, searchQuery, { search(it) }, searchResults, searchLoading, { loadQueue() })
                        else -> NativeAdminSettings(user, api, { openUpdates() }, { signOut() }, playbackPrefs, { key, value -> playerPreference(key, value) })
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
                        LaunchedEffect(filesDevice, filesPath) { loadFiles(force = false) }
                        LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { loadOps(); delay(4000) } } }
                        FilesScreen(filesDevice, filesPath, if (filesEntriesKey == "$filesDevice/$filesPath") filesEntries else emptyList(), filesLoading,
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
                                onDownload = { ep -> startDownload(t, ep) }, onMarkPlayed = { markPlayed(t) }, related = relatedTitles(t, lib.titles), onRelated = { selected = it }, completed = completed, onEpisodeComplete = { ep, done -> markEpisode(t, ep, done) })
                        } else when (streamTab) {
                            "Home" -> StreamHomeScreen(lib, { selected = it }, { title ->
                                val resume = lib.progress[title.id]
                                val next = if (title.kind == "series") title.episodes.firstOrNull { it.id == resume?.episodeId }
                                    ?: title.episodes.minWithOrNull(compareBy({ it.season }, { it.episode })) else null
                                play(title, next)
                            }, { toggleWatch(it) }, onSeeAll = { streamTab = it }, onMarkPlayed = { markPlayed(it) }, initialSort = device.sort("Home"), onSortChange = { device.sort("Home", it) }, completed = completed, saved = savedVideos.map { it.key }.toSet())
                            "Movies" -> BrowseScreen("Movies", movieTitles, lib.progress, lib.watch,
                                    browseQueries["Movies"].orEmpty(), { browseQueries["Movies"] = it }, { selected = it }, { toggleWatch(it) }, device.view("Movies"), { device.view("Movies", it) }, completed, savedKeys, device.sort("Movies"), { device.sort("Movies", it) })
                            "Series" -> BrowseScreen("Series", seriesTitles, lib.progress, lib.watch,
                                    browseQueries["Series"].orEmpty(), { browseQueries["Series"] = it }, { selected = it }, { toggleWatch(it) }, device.view("Series"), { device.view("Series", it) }, completed, savedKeys, device.sort("Series"), { device.sort("Series", it) })
                            else -> BrowseScreen("Watchlist", lib.titles, lib.progress, lib.watch,
                                    browseQueries["Watchlist"].orEmpty(), { browseQueries["Watchlist"] = it }, { selected = it }, { toggleWatch(it) }, device.view("Watchlist"), { device.view("Watchlist", it) }, completed, savedKeys, device.sort("Watchlist"), { device.sort("Watchlist", it) })
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
            if (playing && miniPlayer && !inPip) Spacer(Modifier.height(84.dp))
            if (!profileOpen && !updatesOpen && !downloadsOpen && nativeSettings.isBlank() && WindowInsets.ime.getBottom(LocalDensity.current) == 0) {
                val pages = when (if (admin()) mode else "Stream") {
                    "Hub" -> listOf(DockItem("Dashboard", PtsIcons.Home), DockItem("Ingest", PtsIcons.Download, "Download"), DockItem("Queue", PtsIcons.Queue), DockItem("Settings", PtsIcons.Settings))
                    "Files" -> listOf(DockItem("Browse", PtsIcons.Browse), DockItem("Transfers", PtsIcons.Transfers))
                    else -> listOf(
                        DockItem("Home", PtsIcons.Home),
                        DockItem("Movies", PtsIcons.Movies),
                        DockItem("TV Shows", PtsIcons.Tv, "Series"),
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
        val compact = miniPlayer && !inPip
        val playbackOwner = remember { account?.optLong("id") }
        val saved = remember(title.id, ep?.id) { initialSaved }
        val context = LocalContext.current
        var fallback by remember(title.id, ep?.id) { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var showTracks by remember { mutableStateOf(false) }
        var showEpisodes by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var optionsPage by remember { mutableStateOf("") }
        var appearance by remember { mutableStateOf(loadPlayerAppearance(context)) }
        val timing = remember { PlaybackTiming().apply { subtitlePosition = appearance.subtitlePosition } }
        var timingRevision by remember { mutableIntStateOf(0) }
        val telemetry = remember { MediaTelemetry().apply { if (initialSaved != null) accepted(MediaRoute.OFFLINE) } }
        var traffic by remember { mutableStateOf(telemetry.snapshot()) }
        var buffered by remember { mutableLongStateOf(0) }
        var speed by remember { mutableFloatStateOf(playbackPrefs.optDouble("speed", 1.0).toFloat().coerceIn(.5f, 2f)) }
        var fill by remember { mutableStateOf(getSharedPreferences("playback_local", MODE_PRIVATE).getString("fit_mode", "fit") == "fill") }
        var zoom by remember { mutableFloatStateOf(if (playbackPrefs.optString("zoom_mode") == "remember") playbackPrefs.optDouble("zoom", 1.0).toFloat().coerceIn(.5f, 3f) else 1f) }
        var locked by remember { mutableStateOf(false) }
        val completedOffline = remember(title.id, ep?.id) { booleanArrayOf(false) }
        var showSleep by remember { mutableStateOf(false) }
        var controls by remember { mutableStateOf(true) }
        var position by remember { mutableLongStateOf(0L) }
        var duration by remember { mutableLongStateOf(0L) }
        var playingNow by remember { mutableStateOf(true) }
        var seekPreview by remember { mutableStateOf<Long?>(null) }
        var interaction by remember { mutableIntStateOf(0) }
        var touching by remember { mutableStateOf(false) }
        var muted by remember { mutableStateOf(false) }
        var buffering by remember { mutableStateOf(true) }
        var firstFrame by remember { mutableStateOf(false) }
        var gestureFeedback by remember { mutableStateOf("") }
        LaunchedEffect(gestureFeedback) { if (gestureFeedback.isNotBlank()) { delay(1500); gestureFeedback = "" } }
        var ended by remember { mutableStateOf(false) }
        var cancelNext by remember { mutableStateOf(false) }
        var nextCountdown by remember { mutableIntStateOf(5) }
        var autoplay by remember { mutableStateOf(playbackPrefs.optBoolean("autoplay", true)) }
        val downloaded = savedVideos.any { it.key == "${title.id}_${ep?.id ?: 0L}" }
        val nextEpisode = remember(title, ep, appearance.nextDownloadedOnly, savedVideos) {
            nextEpisode(title, ep, savedVideos.map { it.key }.toSet(), appearance.nextDownloadedOnly)
        }
        val seasonSelection = rememberSeasonSelection(title, ep, library?.progress?.get(title.id))
        val playerCompleted = remember(episodeProgressRevision, playbackOwner) { episodeProgress()?.completed().orEmpty() }
        val playerSavedKeys = remember(savedVideos) { savedVideos.map { it.key }.toSet() }
        fun touchControls() { controls = true; interaction++ }
        LaunchedEffect(inPip, compact) {
            if (inPip || compact) {
                controls = false; showSettings = false; showTracks = false; showEpisodes = false; showSleep = false
                seekPreview = null; touching = false; gestureFeedback = ""
            } else touchControls()
        }
        fun leavePlayer() { if (playerFullscreen) fullscreen(false) else onBack() }
        fun selectEpisode(next: Episode) { clearedProgress.remove(title.id); durableEdit({ it.journal.begin(title.id) }); val completed = episodeProgress()?.completed().orEmpty(); scope.launch(Dispatchers.IO) { downloads?.smartNext(title, next, completed) }; episode = next; showEpisodes = false }
        var resumeAt by remember(title.id, ep?.id) {
            mutableLongStateOf(if (restoredPosition >= 0) restoredPosition.also { restoredPosition = -1L }
                else resumePosition(episodeProgress()?.resume(title.id, ep?.id ?: 0) ?: library?.progress?.get(title.id), ep?.id ?: 0L, ep?.duration?.toLong() ?: 0L) * 1000)
        }
        val startPlaying = remember { restoredAutoplay.also { restoredAutoplay = true } }
        var desiredPlay by remember { mutableStateOf(startPlaying) }
        val hevcAvailable = remember { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) }
        } }
        val source = saved?.playbackUri() ?: api.streamUrl(title, ep, fallback)
        val externalSubtitle = remember(title.id, ep?.id, subtitleRevision) { LocalSubtitles(context, playbackOwner ?: 0).get(title.id, ep?.id ?: 0) }
        val mediaItem = remember(source, externalSubtitle?.path, subtitleRevision) { playbackItem(source, externalSubtitle) }
        var appliedSubtitleRevision by remember { mutableIntStateOf(subtitleRevision) }
        val exo = remember {
            val dataSource = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(TrackedCallFactory(api.http, telemetry)).setUserAgent("PTS-Library-Android"))
            if (saved != null) dataSource.setTransferListener(object : androidx.media3.datasource.TransferListener {
                override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
                override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
                override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) { if (!isNetwork) telemetry.read(MediaRoute.OFFLINE, bytesTransferred.toLong()) }
                override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
            })
            ExoPlayer.Builder(context, ptsRenderers(context, timing)).setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    dataSource
                )).build().apply {
                setMediaItem(mediaItem)
                setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setHandleAudioBecomingNoisy(true)
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(playbackPrefs.optString("audio_lang").ifBlank { null })
                    .setPreferredTextLanguage(if (externalSubtitle != null) "und" else playbackPrefs.optString("subtitle_lang").ifBlank { null })
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, externalSubtitle == null && playbackPrefs.optString("subtitle_lang").isBlank()).build()
                if (resumeAt > 0) seekTo(resumeAt)
                setPlaybackSpeed(speed)
            }
        }
        val nativeView = remember(exo) { arrayOfNulls<PlayerView>(1) }
        val captionStyle = remember(exo) { arrayOfNulls<Pair<PlayerView, Any>>(1) }
        BackHandler(enabled = !compact) { if (locked) locked = false else leavePlayer() }
        DisposableEffect(exo) {
            player = exo
            val originalBrightness = window.attributes.screenBrightness
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame = true; updatePipParameters() }
                override fun onPlayerError(exception: PlaybackException) {
                    updatePipParameters()
                    if (saved == null && !fallback && exception.errorCode in 2000..2999) {
                        resumeAt = if (firstFrame) exo.currentPosition.coerceAtLeast(0) else maxOf(resumeAt, exo.currentPosition.coerceAtLeast(0))
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
                    if (state == Player.STATE_ENDED) {
                        controls = true
                        if (playbackOwner == account?.optLong("id")) {
                            val total = exo.duration.coerceAtLeast(0) / 1000
                            durableEdit({ it.journal.markCompleted("${title.id}_${ep?.id ?: 0}"); it.episodes.record(title.id, ep?.id ?: 0, 0, total, true) }, { scope.launch { episodeProgress()?.sync(api) } })
                        }
                        completedOffline[0] = true
                        if (sleepAtEnd) { exo.pause(); cancelNext = true; sleepAtEnd = false }
                    }
                    updatePipParameters()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) { playingNow = isPlaying; updatePipParameters() }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    desiredPlay = playWhenReady
                    if (!playWhenReady) { controls = true; interaction++ }
                    updatePipParameters()
                }
            }
            exo.addListener(listener)
            firstFrame = false
            onDispose {
                val p = exo.currentPosition / 1000
                val d = exo.duration.coerceAtLeast(0) / 1000
                if (playbackOwner == account?.optLong("id") && title.id !in clearedProgress && d > 0) {
                    library = library?.let { it.copy(progress = it.progress + (title.id to Resume(ep?.id ?: 0L, p, d))) }
                    recordProgress(title.id, ep?.id, p, d)
                }
                nativeView[0]?.player = null
                nativeView[0] = null
                exo.removeListener(listener); exo.release()
                if (playbackOwner == account?.optLong("id") && completedOffline[0] && saved != null && NativePreferences(context).downloads().removeWatched)
                    runCatching { downloads?.remove(saved) }
                window.attributes = window.attributes.apply { screenBrightness = originalBrightness }
                if (player === exo) player = null
                pipVideoView = null; updatePipParameters()
            }
        }
        LaunchedEffect(mediaItem, subtitleRevision) {
            if (exo.currentMediaItem != mediaItem || appliedSubtitleRevision != subtitleRevision) {
                val keepPlaying = desiredPlay
                val keepPosition = if (firstFrame) exo.currentPosition.coerceAtLeast(0) else exo.currentPosition.coerceAtLeast(resumeAt.coerceAtLeast(0))
                exo.setMediaItem(mediaItem, keepPosition); firstFrame = false; error = ""
                appliedSubtitleRevision = subtitleRevision
                if (externalSubtitle != null) exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setPreferredTextLanguage("und").setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).build()
                exo.prepare(); exo.playWhenReady = keepPlaying
            }
        }
        DisposableEffect(exo) {
            val manager = getSystemService(android.net.ConnectivityManager::class.java)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    handler.post {
                        if (player === exo && error.isNotBlank() && playbackOwner == account?.optLong("id") && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                            val keepPlaying = desiredPlay; val keepPosition = exo.currentPosition
                            error = ""; exo.seekTo(keepPosition); exo.prepare(); exo.playWhenReady = keepPlaying; touchControls()
                        }
                    }
                }
            }
            manager.registerDefaultNetworkCallback(callback)
            onDispose { runCatching { manager.unregisterNetworkCallback(callback) }; handler.removeCallbacksAndMessages(null) }
        }
        LaunchedEffect(exo, sleepDeadline) {
            if (sleepDeadline > 0) {
                delay((sleepDeadline - System.currentTimeMillis()).coerceAtLeast(0))
                exo.pause(); sleepDeadline = 0; resumeAfterBackground = false; controls = true; persistNavigation()
            }
        }
        LaunchedEffect(exo, inPip) {
            lifecycle.repeatOnLifecycle(if (inPip) Lifecycle.State.STARTED else Lifecycle.State.RESUMED) { while (true) {
                delay(if (controls || compact) 500 else 1500)
                duration = exo.duration.coerceAtLeast(0)
                buffered = exo.bufferedPosition.coerceAtLeast(0)
                traffic = telemetry.snapshot()
                buffering = exo.playbackState == Player.STATE_BUFFERING || exo.playbackState == Player.STATE_IDLE
                if (seekPreview == null) position = exo.currentPosition.coerceAtLeast(0)
                playingNow = exo.isPlaying
            } }
        }
        LaunchedEffect(exo) {
            while (true) {
                delay(15000)
                if (exo.isPlaying && title.id !in clearedProgress) recordProgress(title.id, ep?.id, exo.currentPosition / 1000, exo.duration.coerceAtLeast(0) / 1000)
            }
        }
        LaunchedEffect(controls, desiredPlay, interaction, touching, seekPreview != null, showTracks, showEpisodes, showSettings, showSleep, appearance.controlsSeconds, ended, error) {
            if (controls && !touching && !ended && error.isBlank() && controlsShouldHide(desiredPlay, buffering, seekPreview != null, showTracks || showEpisodes || showSettings || showSleep, appearance.controlsSeconds)) {
                delay(controlDelayMillis(appearance.controlsSeconds)); controls = false
            }
        }
        LaunchedEffect(ended, autoplay, cancelNext) {
            if (ended && autoplay && !cancelNext && nextEpisode != null) {
                for (seconds in 5 downTo 1) { nextCountdown = seconds; delay(1000) }
                selectEpisode(nextEpisode)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(if (compact) Pts.card else Color.Black)) {
          val landscape = compact || maxWidth > maxHeight || inPip || playerFullscreen
          val miniPreviewWidth = if (maxWidth < 340.dp) 88.dp else 108.dp
          val portraitHeight = (maxWidth * .58f).coerceIn(300.dp, 560.dp).coerceAtMost(maxHeight * .65f)
          DisposableEffect(landscape, compact) {
              val bars = WindowCompat.getInsetsController(window, window.decorView)
              if (landscape && !inPip && !compact) {
                  bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                  bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
              }
              onDispose { bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
          }
          Column(Modifier.fillMaxSize()) {
              val frame = if (compact) Modifier.width(miniPreviewWidth).fillMaxHeight() else if (landscape) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(portraitHeight)
              PtsTheme("watch") { Box(frame.testTag("player-surface").clipToBounds().background(Color.Black)) {
                  Box(Modifier.fillMaxSize().then(if (compact) Modifier else Modifier.windowInsetsPadding(WindowInsets.displayCutout)).clipToBounds()) {
                  key(exo) { AndroidView(factory = { ctx -> (android.view.LayoutInflater.from(ctx).inflate(R.layout.native_player, FrameLayout(ctx), false) as PlayerView).apply {
                     useController = false
                     keepScreenOn = true
                     setEnableComposeSurfaceSyncWorkaround(true)
                     setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                      layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                      addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePipParameters() }
                 } }, onRelease = { view -> view.player = null }, update = { view ->
                      nativeView[0] = view
                      pipVideoView = view.videoSurfaceView ?: view
                     if (view.player !== exo) view.player = exo
                     view.keepScreenOn = playingNow || buffering
                      view.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                        if (captionStyle[0]?.first !== view || captionStyle[0]?.second != appearance) view.subtitleView?.apply {
                           setFractionalTextSize(.0533f * appearance.subtitleSize)
                           setApplyEmbeddedStyles(false)
                           setStyle(androidx.media3.ui.CaptionStyleCompat(
                               when (appearance.subtitleColor) { "yellow" -> android.graphics.Color.YELLOW; "cyan" -> android.graphics.Color.CYAN; else -> android.graphics.Color.WHITE },
                               android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                                if (appearance.outline) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE,
                                android.graphics.Color.BLACK, null))
                            captionStyle[0] = view to appearance
                        }
                     if (view.tag !== exo) {
                         view.tag = exo
                         // Decode only after the native surface has dimensions. A new source
                         // gets its own view; the old surface is explicitly detached on release.
                          view.doOnLayout { if (view.player === exo) { exo.prepare(); exo.playWhenReady = startPlaying; updatePipParameters() } }
                     }
                    }, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = if (compact) 1f else zoom; scaleY = if (compact) 1f else zoom }) }
                   }
                   PlayerGestures(this@MainActivity, exo.currentPosition, duration,
                        onTap = { controls = !controls; interaction++ },
                     onSeekPreview = { seekPreview = it; touchControls() },
                     onSeek = { exo.seekTo(it); touchControls() },
                      onFeedback = { gestureFeedback = it; touchControls() }, zoom = zoom,
                      onZoom = { value -> zoom = value; gestureFeedback = "Zoom ${"%.2f".format(Locale.US, value)}×"; touchControls() },
                        onZoomEnd = { if (abs(zoom - 1f) <= .04f) zoom = 1f; if (playbackPrefs.optString("zoom_mode") == "remember") playerPreference("zoom", zoom) }, enabled = !inPip && !locked && !compact,
                        onTouch = { touching = it; if (!it) interaction++ }) {
                  if (!inPip && !compact) androidx.compose.animation.AnimatedVisibility(visible = controls && !locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                       Box(Modifier.fillMaxSize().windowInsetsPadding(if (landscape) WindowInsets.safeDrawing else WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
                          CinemaControls(ep?.let { "${title.title} · S${it.season}E${it.episode}" } ?: title.title,
                               desiredPlay && !ended, seekPreview ?: position, duration, buffered, playerFullscreen,
                               onBack = { leavePlayer() }, onToggle = { if (exo.playWhenReady && !ended) exo.pause() else { if (ended) exo.seekTo(0); exo.play() }; touchControls() },
                              onSeek = { exo.seekTo(it); touchControls() }, onPreview = { seekPreview = it; touchControls() },
                              onOptions = { optionsPage = ""; showSettings = true; touchControls() }, onTracks = { showTracks = true; touchControls() },
                               onPip = { requestPip(true) }, onFullscreen = { fullscreen(!playerFullscreen); touchControls() }, buffering = buffering || !firstFrame, onMinimize = { minimizePlayback() })
                      }
                 }
                  if (locked && !inPip && !compact) Box(Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.Black.copy(alpha = .7f), androidx.compose.foundation.shape.CircleShape)) {
                       CinemaIcon(PtsIcons.Lock, "Unlock player", onClick = { locked = false; touchControls() })
                  }
                  if (gestureFeedback.isNotBlank() && !inPip && !compact) Text(gestureFeedback,
                     Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .9f), RowShape).padding(horizontal = 16.dp, vertical = 10.dp).testTag("gesture-feedback"),
                     style = MaterialTheme.typography.labelLarge, color = Color.White)
                if ((buffering || !firstFrame) && error.isBlank()) CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Pts.accent)
                if (error.isNotBlank() && !inPip && !compact) Column(Modifier.align(Alignment.Center).fillMaxWidth().background(Color.Black.copy(alpha = .9f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Playback interrupted", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(error, style = MaterialTheme.typography.bodySmall, color = Pts.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PtsPrimaryButton("Retry", Modifier.weight(1f)) { val keepPlaying = desiredPlay; val keepPosition = exo.currentPosition; error = ""; exo.seekTo(keepPosition); exo.prepare(); exo.playWhenReady = keepPlaying; touchControls() }
                        PtsSecondaryButton("Audio / CC", Modifier.weight(1f)) { showTracks = true }
                    }
                }
                 if (landscape && !inPip && !compact && ended && nextEpisode != null && !cancelNext) Box(Modifier.align(Alignment.TopEnd).padding(12.dp).widthIn(max = 300.dp)) {
                    UpNextCard(nextEpisode, if (autoplay) nextCountdown else null, { selectEpisode(nextEpisode) }, { cancelNext = true })
                 }
                 }
              } }
             if (!landscape) {
             if (!hevcAvailable) PtsErrorBanner("This device may not support HEVC playback", modifier = Modifier.padding(12.dp))
               androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).windowInsetsPadding(WindowInsets.navigationBars).background(Pts.bg), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                  item { Text(title.title, style = MaterialTheme.typography.titleLarge, color = Pts.text) }
                   ep?.let { item { Text("S${it.season}E${it.episode} · ${it.title.ifBlank { "Episode ${it.episode}" }}", style = MaterialTheme.typography.bodySmall, color = Pts.muted) } }
                   item { Text("Description", style = MaterialTheme.typography.titleMedium, color = Pts.text) }
                   item { ShortSynopsis(ep?.description?.takeIf { it.isNotBlank() } ?: title.synopsis.ifBlank { "No description is available for this title." }) }
                  if (ended && nextEpisode != null && !cancelNext) {
                       item { UpNextCard(nextEpisode, if (autoplay) nextCountdown else null, { selectEpisode(nextEpisode) }, { cancelNext = true }) }
                  }
                   item { WatchActionBar(downloaded, "${title.id}_${ep?.id ?: 0}" in playerCompleted, title.kind == "series",
                      onComplete = { markPlayed(title) }, onSave = { startDownload(title, ep) }, onSeasonDownload = { episodeDownloadTitle = title }) }
                  if (title.kind == "series") episodeSection(title, seasonSelection, ep, library?.progress?.get(title.id), playerSavedKeys, downloading,
                      onPlay = { selectEpisode(it) }, onDownload = { startDownload(title, it) }, completed = playerCompleted, onCompleted = { e, done -> markEpisode(title, e, done) })
                 val related = relatedTitles(title, library?.titles.orEmpty())
                  if (related.isNotEmpty()) item { PtsPosterShelf("Related", related, library?.progress.orEmpty(), library?.watch.orEmpty(),
                      { play(it, if (it.kind == "series") it.episodes.firstOrNull() else null) }, { toggleWatch(it) }) }
             }
            }
          }
          if (compact) MiniPlayerControls(title.title, if (error.isNotBlank()) "Playback interrupted · expand" else ep?.let { "S${it.season} E${it.episode}" } ?: if (ended) "Finished" else "Now playing",
              desiredPlay && !ended, if (duration > 0) position.toFloat() / duration else 0f,
              onExpand = { expandPlayback() }, onToggle = { if (exo.playWhenReady && !ended) exo.pause() else { if (ended) exo.seekTo(0); exo.play() } }, onClose = { stopPlayback() })
          if (message.isNotBlank() && !inPip && !compact) Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
              if (messageOk) PtsOkToast(message) { message = "" }
              else PtsErrorToast(message) { message = "" }
          }
        }
        if (showSleep) androidx.compose.ui.window.Dialog(onDismissRequest = { showSleep = false }) {
            PtsCard(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text("Sleep timer", style = MaterialTheme.typography.titleLarge, color = Pts.text)
                listOf(0, 15, 30, 60).forEach { minutes ->
                    PtsSecondaryButton(if (minutes == 0) "Off" else "$minutes minutes", Modifier.fillMaxWidth()) {
                        sleepDeadline = if (minutes == 0) 0 else System.currentTimeMillis() + minutes * 60000L
                        sleepAtEnd = false; showSleep = false; persistNavigation()
                    }
                }
                PtsSecondaryButton("End of episode", Modifier.fillMaxWidth()) { sleepAtEnd = true; sleepDeadline = 0; showSleep = false; persistNavigation() }
            }
        }
        if (showEpisodes) androidx.compose.ui.window.Dialog(onDismissRequest = { showEpisodes = false }) {
            PtsCard(Modifier.fillMaxWidth().heightIn(max = 620.dp), padding = 16.dp) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    episodeSection(title, seasonSelection, ep, library?.progress?.get(title.id), playerSavedKeys, downloading,
                        onPlay = { selectEpisode(it) }, onDownload = { startDownload(title, it) }, completed = playerCompleted, onCompleted = { e, done -> markEpisode(title, e, done) })
                }
                PtsSecondaryButton("Close", Modifier.fillMaxWidth()) { showEpisodes = false }
            }
        }
        if (showSettings) PtsTheme("watch") {
            PlayerOptions(appearance, { value ->
                val reposition = appearance.subtitlePosition != value.subtitlePosition
                appearance = value; savePlayerAppearance(context, value); timing.subtitlePosition = value.subtitlePosition
                if (reposition) exo.seekTo(exo.currentPosition)
                touchControls()
            }, speed, { rate -> speed = rate; exo.setPlaybackSpeed(rate); playerPreference("speed", rate); touchControls() },
                timing, { audio, subtitle -> timing.audioDelayMs = audio; timing.subtitleDelayMs = subtitle; timingRevision++; exo.seekTo(exo.currentPosition); touchControls() },
                traffic, (buffered - position).coerceAtLeast(0), fill, autoplay,
                onFit = { fill = !fill; getSharedPreferences("playback_local", MODE_PRIVATE).edit().putString("fit_mode", if (fill) "fill" else "fit").apply() },
                onZoomReset = { zoom = 1f; if (playbackPrefs.optString("zoom_mode") == "remember") playerPreference("zoom", 1) },
                onAutoplay = { autoplay = !autoplay; playerPreference("autoplay", autoplay) },
                onTracks = { showSettings = false; showTracks = true }, onSleep = { showSettings = false; showSleep = true },
                onLock = { locked = true; showSettings = false }, onClose = { showSettings = false; touchControls() }, initialPage = optionsPage, timingRevision = timingRevision,
                probe = if (saved != null || ep?.container == "hls" || (ep == null && title.container == "hls")) null else ({
                    val local = probeMediaRoute(api.http, api.streamUrl(title, ep), true)
                    val internet = probeMediaRoute(api.http, api.streamUrl(title, ep), false)
                    "LAN preference: ${local.route.label} · ${formatBytes(local.bytesPerSecond)}/s\nInternet only: ${internet.route.label} · ${formatBytes(internet.bytesPerSecond)}/s"
                }), onEpisodes = if (ep == null) null else ({ showSettings = false; showEpisodes = true; touchControls() }),
                muted = muted, onMute = { muted = !muted; exo.volume = if (muted) 0f else 1f; touchControls() })
        }
        if (showTracks) {
            val audioGroups = exo.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
            val textGroups = exo.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
            androidx.compose.ui.window.Dialog(onDismissRequest = { showTracks = false }) {
                Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).background(Pts.card, androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                    .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Audio and subtitles", style = MaterialTheme.typography.titleLarge, color = Pts.text)
                    PtsSecondaryButton("Import SRT / VTT", Modifier.fillMaxWidth(), icon = PtsIcons.Subtitles) {
                        subtitleTarget = Triple(playbackOwner ?: 0, title.id, ep?.id ?: 0); pickSubtitle.launch(arrayOf("*/*"))
                    }
                    Text("Local files up to 2 MiB. Saved privately for this account and video, including offline playback.", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                    if (externalSubtitle != null) PtsSecondaryButton("Remove imported subtitles", Modifier.fillMaxWidth()) { LocalSubtitles(context, playbackOwner ?: 0).remove(title.id, ep?.id ?: 0); subtitleRevision++ }
                    Text("Audio", style = MaterialTheme.typography.labelMedium, color = Pts.muted)
                    if (audioGroups.isEmpty()) Text("No alternate audio tracks", style = MaterialTheme.typography.bodySmall, color = Pts.muted)
                    audioGroups.forEach { group ->
                        (0 until group.length).forEach { index ->
                            val format = group.getTrackFormat(index)
                            val label = trackLanguageName(format.language)
                            PtsSecondaryButton(label, Modifier.fillMaxWidth(),
                                icon = if (group.isTrackSelected(index)) PtsIcons.Check else null, enabled = group.isTrackSupported(index)) {
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .setPreferredAudioLanguage(format.language)
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
                            val label = trackLanguageName(format.language)
                            PtsSecondaryButton(label.replaceFirstChar { it.uppercase() }, Modifier.fillMaxWidth(), icon = if (group.isTrackSelected(index)) PtsIcons.Check else null, enabled = group.isTrackSupported(index)) {
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .setPreferredTextLanguage(format.language)
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
        updatePipParameters()
        if (Build.VERSION.SDK_INT < 31 && autoPipEligible()) requestPip(false)
        super.onUserLeaveHint()
    }

    private fun autoPipEligible(): Boolean {
        val exo = player ?: return false
        return shouldAutoEnterPip(playing, exo.playWhenReady, exo.playbackState, exo.playerError != null)
    }

    private fun pipAvailable(): Boolean {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        val ops = getSystemService(android.app.AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), packageName)
            else ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), packageName)
        return mode != android.app.AppOpsManager.MODE_IGNORED && mode != android.app.AppOpsManager.MODE_ERRORED
    }

    private fun pipParameters(): PictureInPictureParams {
        val rect = android.graphics.Rect()
        val visible = pipVideoView?.getGlobalVisibleRect(rect) == true && rect.width() > 0 && rect.height() > 0
        val ratio = if (visible && rect.width().toFloat() / rect.height() in (1f / 2.39f)..2.39f) Rational(rect.width(), rect.height()) else Rational(16, 9)
        return PictureInPictureParams.Builder().setAspectRatio(ratio).apply {
            if (visible) setSourceRectHint(rect)
            if (Build.VERSION.SDK_INT >= 31) { setAutoEnterEnabled(autoPipEligible() && pipAvailable()); setSeamlessResizeEnabled(true) }
        }.build()
    }

    private fun updatePipParameters() { runCatching { setPictureInPictureParams(pipParameters()) } }

    private fun requestPip(manual: Boolean) {
        val entered = runCatching { playing && player != null && pipAvailable() && enterPictureInPictureMode(pipParameters()) }.getOrDefault(false)
        if (!entered && manual) toast("Picture-in-picture is unavailable or disabled in Android settings")
    }

    override fun onDestroy() {
        downloadWatch?.cancel(); downloads?.detachUi(); if (imagesDelegate.isInitialized()) images.shutdown()
        DurableWork.scope.launch { DeviceDownloads.trimInactive() }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        persistNavigation()
        super.onSaveInstanceState(outState)
    }

    private fun restoreNavigation() {
        if (navigationRestored) return
        val nav = device.navigation()
        if (nav.optLong("owner") == account?.optLong("id")) {
            mode = streamOnlyMode(if (admin()) "admin" else "user", nav.optString("mode", "Stream"))
            hubTab = nav.optString("hub").takeIf { it in setOf("Dashboard", "Queue", "Download", "Settings") } ?: "Dashboard"
            filesTab = nav.optString("files").takeIf { it in setOf("Browse", "Transfers") } ?: "Browse"
            streamTab = nav.optString("stream").takeIf { it in setOf("Home", "Movies", "Series", "Watchlist") } ?: "Home"
            filesPath = nav.optString("path"); filesDevice = nav.optString("device", "vps")
            selected = library?.titles?.firstOrNull { it.id == nav.optLong("title") }
                ?: savedVideos.firstOrNull { it.titleId == nav.optLong("title") }?.titleMetadata()
                ?: nav.optJSONObject("selected")?.let { runCatching { parseTitle(it) }.getOrNull() }
            playbackTitle = library?.titles?.firstOrNull { it.id == nav.optLong("playback_title_id") }
                ?: savedVideos.firstOrNull { it.titleId == nav.optLong("playback_title_id") }?.titleMetadata()
                ?: nav.optJSONObject("playback_title")?.let { runCatching { parseTitle(it) }.getOrNull() } ?: selected
            miniPlayer = nav.optBoolean("mini_player")
            episode = playbackTitle?.episodes?.firstOrNull { it.id == nav.optLong("episode") }
            downloadsOpen = nav.optBoolean("downloads")
            profileOpen = nav.optBoolean("profile"); updatesOpen = nav.optBoolean("updates")
            playing = nav.optBoolean("playing") && playbackTitle != null && (miniPlayer || (!downloadsOpen && !updatesOpen && !profileOpen))
            restoredPosition = nav.optLong("position", -1L); restoredAutoplay = nav.optBoolean("autoplay", true)
            nativeSettings = nav.optString("native_settings").takeIf { it in setOf("downloads", "playback", "sync") }.orEmpty()
            sleepDeadline = nav.optLong("sleep_deadline"); sleepAtEnd = nav.optBoolean("sleep_at_end")
            val queries = nav.optJSONObject("queries")
            queries?.keys()?.forEach { browseQueries[it] = queries.optString(it) }
        }
        if (intent?.getBooleanExtra("open_downloads", false) == true) { if (playing) miniPlayer = true; downloadsOpen = false; profileOpen = true; updatesOpen = false; nativeSettings = ""; mode = "Stream" }
        if (intent?.getBooleanExtra("open_jobs", false) == true && admin()) {
            playing = false; mode = "Hub"; hubTab = "Queue"; profileOpen = false; downloadsOpen = false; updatesOpen = false; nativeSettings = ""
        }
        navigationRestored = true
    }

    private fun persistNavigation() {
        val user = account ?: return
        val exo = player
        val pos = exo?.currentPosition?.coerceAtLeast(0) ?: restoredPosition
        val queries = JSONObject(); browseQueries.forEach { (key, value) -> queries.put(key, value) }
        device.navigation(JSONObject().put("owner", user.optLong("id")).put("mode", mode).put("hub", hubTab)
            .put("files", filesTab).put("stream", streamTab).put("path", filesPath).put("device", filesDevice)
            .put("title", selected?.id ?: 0).put("episode", episode?.id ?: 0)
            .put("playback_title_id", if (playing) playbackTitle?.id ?: 0 else 0).put("mini_player", miniPlayer && playing)
            .put("downloads", downloadsOpen).put("profile", profileOpen).put("updates", updatesOpen)
            .put("playing", playing).put("position", pos).put("autoplay", resumeAfterBackground || exo?.isPlaying == true)
            .put("native_settings", nativeSettings).put("sleep_deadline", sleepDeadline).put("sleep_at_end", sleepAtEnd)
            .put("queries", queries))
        // The library is cached when it changes, never re-serialized on section taps.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.getBooleanExtra("open_downloads", false)) { if (playing) { miniPlayer = true; fullscreen(false) }; mode = "Stream"; downloadsOpen = false; profileOpen = true; updatesOpen = false; streamTab = "Home" }
        if (intent.getBooleanExtra("open_jobs", false) && admin()) { if (playing) { miniPlayer = true; fullscreen(false) }; mode = "Hub"; hubTab = "Queue"; profileOpen = false; downloadsOpen = false; updatesOpen = false; nativeSettings = "" }
    }

    override fun onStart() {
        super.onStart()
        backgroundPosition?.let { position -> player?.seekTo(position); player?.prepare(); backgroundPosition = null }
        if (resumeAfterBackground) {
            if (sleepDeadline == 0L || sleepDeadline > System.currentTimeMillis()) player?.play()
            resumeAfterBackground = false
        }
    }

    private fun startAccountSync() {
        foregroundSync = scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (account != null && !loading) {
                        val owner = account?.optLong("id"); val generation = sessionGeneration
                        try {
                            val user = api.me()
                            if (generation != sessionGeneration || owner != account?.optLong("id")) continue
                            if (user.optLong("id") != account?.optLong("id")) { downloads?.pauseAll(); device.clearSession(); account = null; library = null; playing = false; continue }
                            val wasAdmin = admin()
                            if (account?.toString() != user.toString()) { account = user; device.account(user) }
                            roleVerified = true
                            if (!admin() && (wasAdmin || mode != "Stream")) restrictToStream()
                            if (android.os.SystemClock.elapsedRealtime() - lastCatalogueAt >= 5 * 60 * 1000 && mode == "Stream" && !profileOpen && nativeSettings.isBlank() && !playing) refreshLibrary()
                        } catch (e: CancellationException) { throw e }
                        catch (e: ApiException) { if (e.status == 401 && generation == sessionGeneration && owner == account?.optLong("id")) { downloads?.pauseAll(); device.clearSession(); account = null; library = null; playing = false; roleVerified = false; restrictToStream() } }
                        catch (_: Exception) { /* A temporary outage must not destroy the saved session/UI. */ }
                    }
                    delay(30000)
                }
            }
        }
    }

    override fun onStop() {
        libraryRequest?.cancel(); libraryRequest = null
        val t = playbackTitle
        if (playing && t != null && t.id !in clearedProgress && player?.duration?.let { it > 0 } == true)
            recordProgress(t.id, episode?.id, player!!.currentPosition / 1000, player!!.duration / 1000)
        if (!isInPictureInPictureMode) {
            resumeAfterBackground = player?.isPlaying == true
            persistNavigation()
            player?.pause()
            player?.let { exo -> backgroundPosition = exo.currentPosition.coerceAtLeast(0); exo.stop() }
            if (imagesDelegate.isInitialized()) images.memoryCache?.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            PosterImages.trim(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            DurableWork.scope.launch { DeviceDownloads.trimInactive() }
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
