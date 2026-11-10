package online.ptsmovies.stream

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToLong

private val ink = Color(0xFF0C111D)
private val accent = Color(0xFF7BB8FF)
private val white = Color(0xFFF5F7FF)

class MainActivity : ComponentActivity() {
    private val api = PtsApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var offline: OfflineStore
    private var account by mutableStateOf<JSONObject?>(null)
    private var library by mutableStateOf<Library?>(null)
    private var loading by mutableStateOf(true)
    private var message by mutableStateOf("")
    private var tab by mutableStateOf("Home")
    private var selected by mutableStateOf<Title?>(null)
    private var episode by mutableStateOf<Episode?>(null)
    private var playing by mutableStateOf(false)
    private var player: ExoPlayer? = null
    private var downloadJob: Job? = null
    private var downloading by mutableStateOf("")
    private var downloadPercent by mutableIntStateOf(0)
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        scope.launch {
            try { account = api.me(); offline = OfflineStore(this@MainActivity, api, account!!.optLong("id")); refresh() }
            catch (_: Exception) { loading = false }
        }
        window.statusBarColor = android.graphics.Color.rgb(12, 17, 29)
        window.navigationBarColor = android.graphics.Color.rgb(12, 17, 29)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = ink, surface = Color(0xFF172235))) {
                Surface(Modifier.fillMaxSize(), color = ink) {
                    when {
                        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        account == null -> LoginScreen(message) { user, pass ->
                            scope.launch {
                                loading = true
                                try {
                                    account = api.login(user, pass)
                                    offline = OfflineStore(this@MainActivity, api, account!!.optLong("id"))
                                    message = ""; refresh()
                                }
                                catch (e: Exception) { message = e.message.orEmpty(); loading = false }
                            }
                        }
                        playing && selected != null -> PlayerScreen(selected!!, episode, offline.find(selected!!, episode))
                        else -> AppScreen()
                    }
                }
            }
        }
    }

    private suspend fun refresh() {
        try { library = api.library(); message = "" }
        catch (e: Exception) { message = "Library unavailable: ${e.message}" }
        finally { loading = false }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && playing && player?.isPlaying == true) {
            try { enterPictureInPictureMode(PictureInPictureParams.Builder().build()) } catch (_: Exception) { }
        }
    }

    @Composable private fun AppScreen() {
        var query by remember { mutableStateOf("") }
        var revision by remember { mutableIntStateOf(0) }
        BackHandler(selected != null || tab == "Admin") {
            if (selected != null) selected = null else tab = "Home"
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("PTS Stream", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { loading = true; refresh() } }) { Text("Refresh") }
            }
            if (message.isNotBlank()) Text(message, color = Color(0xFFFFA7A7), modifier = Modifier.padding(horizontal = 16.dp))
            Box(Modifier.weight(1f)) {
                if (tab == "Admin" && account?.optString("role") == "admin") {
                    // All existing administrative forms retain the server's role/CSRF enforcement.
                    AndroidView(factory = { ctx -> WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                                val uri = request.url
                                return uri.scheme != "https" || uri.host != "ptsmovies.online"
                            }
                        }
                        loadUrl(BASE)
                    } }, modifier = Modifier.fillMaxSize())
                } else if (tab == "Downloads") {
                    key(revision) { DownloadsScreen(offline.list(), { item ->
                        val title = library?.titles?.firstOrNull { it.id == item.titleId }
                        if (title != null) {
                            selected = title
                            episode = title.episodes.firstOrNull { item.key.endsWith("_${it.id}") }
                            playing = true
                        } else message = "This title is no longer in the library"
                    }, { item ->
                        try { offline.remove(item); revision++ } catch (e: Exception) { message = e.message.orEmpty() }
                    }) }
                } else if (tab == "Profile") {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Profile", style = MaterialTheme.typography.headlineMedium)
                        Text(account?.optString("username").orEmpty())
                        Text("Offline videos: ${offline.list().size} · stored only on this device")
                        Button(onClick = { tab = "Downloads" }) { Text("My Downloads") }
                        Button(onClick = { checkForUpdate(this@MainActivity, scope) { message = it } }) { Text("Check for updates") }
                        OutlinedButton(onClick = {
                            downloadJob?.cancel()
                            scope.launch { try { api.logout() } finally { account = null; library = null; selected = null } }
                        }) { Text("Sign out") }
                    }
                } else {
                    val lib = library
                    if (lib == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Unable to load library") }
                    else {
                        val titles = remember(lib, query, tab) {
                            lib.titles.filter { t -> (tab != "Watchlist" || t.id in lib.watch) &&
                                (tab != "Movies" || t.kind == "movie") && (tab != "Series" || t.kind == "series") &&
                                (query.isBlank() || t.title.contains(query, ignoreCase = true) || t.genres.any { it.contains(query, true) }) }
                        }
                        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item { OutlinedTextField(query, { query = it }, label = { Text("Search titles and genres") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                            item { Text(if (selected == null) "$tab · ${titles.size} titles" else selected!!.title,
                                style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp)) }
                            if (selected == null) {
                                items(titles, key = { it.id }) { t -> TitleCard(t, lib.progress[t.id], { selected = t }) }
                            } else {
                                val t = selected!!
                                item {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(t.synopsis.ifBlank { t.genres.joinToString() })
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { episode = if (t.kind == "movie") null else t.episodes.firstOrNull(); playing = true },
                                                enabled = t.kind == "movie" || t.episodes.isNotEmpty()) { Text(if (t.kind == "movie") "Play" else "Play series") }
                                            OutlinedButton(onClick = { scope.launch {
                                                try { api.watch(t.id, t.id !in lib.watch); refresh() }
                                                catch (e: Exception) { message = e.message.orEmpty() }
                                            } }) { Text(if (t.id in lib.watch) "✓ My List" else "+ My List") }
                                        }
                                        if (t.kind == "movie") DownloadButton(t, null) { revision++ }
                                    }
                                }
                                if (t.kind == "series") {
                                    t.episodes.groupBy { it.season }.forEach { (season, eps) ->
                                        item { Text("Season $season", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 18.dp)) }
                                        items(eps, key = { it.id }) { ep ->
                                            Column(Modifier.fillMaxWidth().background(Color(0xFF172235), RoundedCornerShape(12.dp)).padding(12.dp)) {
                                                Text("E${ep.episode} · ${ep.title.ifBlank { t.title }}", fontWeight = FontWeight.Bold)
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    TextButton(onClick = { episode = ep; playing = true }) { Text("Play") }
                                                    DownloadButton(t, ep) { revision++ }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (downloading.isNotBlank()) Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Text("Downloading $downloading · $downloadPercent%", maxLines = 1)
                LinearProgressIndicator(progress = { downloadPercent / 100f }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { downloadJob?.cancel(); downloading = "" }) { Text("Cancel") }
            }
            NavigationBar(containerColor = Color(0xFF172235)) {
                (listOf("Home", "Movies", "Series", "Watchlist", "Downloads", "Profile") +
                    if (account?.optString("role") == "admin") listOf("Admin") else emptyList()).forEach { name ->
                    NavigationBarItem(selected = tab == name, onClick = { selected = null; tab = name; revision++ },
                        icon = { Text(name.take(1)) }, label = { Text(name, maxLines = 1) })
                }
            }
        }
    }

    @Composable private fun DownloadButton(title: Title, ep: Episode?, onComplete: () -> Unit) {
        val saved = offline.find(title, ep)
        TextButton(enabled = downloading.isBlank() && saved == null, onClick = {
            downloadJob = scope.launch {
                downloading = ep?.title?.ifBlank { "S${ep.season}E${ep.episode}" } ?: title.title
                downloadPercent = 0
                try { offline.save(title, ep) { value -> runOnUiThread { downloadPercent = value } }; onComplete() }
                catch (e: Exception) { message = e.message.orEmpty() }
                finally { downloading = "" }
            }
        }) { Text(if (saved == null) "Save offline" else "✓ Downloaded") }
    }

    @Composable private fun PlayerScreen(title: Title, ep: Episode?, saved: SavedVideo?) {
        val context = LocalContext.current
        val fallback = remember(title.id, ep?.id) { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var showTracks by remember { mutableStateOf(false) }
        val hevcAvailable = remember { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) }
        } }
        BackHandler { player?.release(); player = null; playing = false; selected = null }
        val source = saved?.file?.toURI()?.toString() ?: api.streamUrl(title, ep, fallback.value)
        val exo = remember(source) {
            ExoPlayer.Builder(context).setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    OkHttpDataSource.Factory(api.http).setUserAgent("PTS-Stream-Android")
                )).build().apply {
                setMediaItem(MediaItem.fromUri(source))
                prepare(); playWhenReady = true
            }
        }
        DisposableEffect(exo) {
            player = exo
            val listener = object : Player.Listener {
                override fun onPlayerError(exception: PlaybackException) {
                    if (saved == null && !fallback.value) fallback.value = true
                    else error = "Playback failed: ${exception.errorCodeName}"
                }
            }
            exo.addListener(listener)
            onDispose {
                val position = exo.currentPosition / 1000
                val duration = exo.duration.coerceAtLeast(0) / 1000
                scope.launch { try { api.progress(title.id, ep?.id, position, duration) } catch (_: Exception) { } }
                exo.removeListener(listener); exo.release()
                if (player === exo) player = null
            }
        }
        LaunchedEffect(exo) {
            while (true) {
                delay(15000)
                if (exo.isPlaying) try { api.progress(title.id, ep?.id, exo.currentPosition / 1000, exo.duration.coerceAtLeast(0) / 1000) } catch (_: Exception) { }
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { playing = false; selected = null }) { Text("← Back") }
                Text((ep?.title?.ifBlank { title.title } ?: title.title), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { showTracks = true }) { Text("Audio / CC") }
            }
            if (!hevcAvailable) Text("This device may not support HEVC playback", color = Color.Yellow, modifier = Modifier.padding(12.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                AndroidView(factory = { ctx -> PlayerView(ctx).apply {
                    player = exo
                    useController = true
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                } }, update = { it.player = exo }, modifier = Modifier.fillMaxSize())
                var dragX by remember { mutableFloatStateOf(0f) }
                var dragY by remember { mutableFloatStateOf(0f) }
                Box(Modifier.fillMaxSize()
                    .pointerInput(exo) {
                        detectTapGestures(onDoubleTap = { pos ->
                            val step = if (pos.x < size.width / 2) -10000L else 10000L
                            exo.seekTo((exo.currentPosition + step).coerceIn(0, exo.duration.coerceAtLeast(0)))
                        }, onTap = { exo.playWhenReady = !exo.playWhenReady })
                    }
                    .pointerInput(exo) {
                        detectDragGestures(onDragEnd = {
                            if (kotlin.math.abs(dragX) > kotlin.math.abs(dragY)) exo.seekTo(
                                (exo.currentPosition + (dragX * 50).roundToLong()).coerceIn(0, exo.duration.coerceAtLeast(0)))
                            dragX = 0f; dragY = 0f
                        }) { change, amount ->
                            change.consume(); dragX += amount.x; dragY += amount.y
                            if (kotlin.math.abs(dragY) > kotlin.math.abs(dragX) && change.position.x > size.width / 2) {
                                val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (amount.y < 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                            }
                        }
                    })
            }
            if (error.isNotBlank()) Text(error, color = Color.Red, modifier = Modifier.padding(12.dp))
            Text(if (saved != null) "Offline · ${saved.bytes / 1048576} MiB" else if (fallback.value) "VPS fallback" else "Direct Pi stream",
                modifier = Modifier.padding(16.dp))
            if (showTracks) AlertDialog(onDismissRequest = { showTracks = false }, title = { Text("Player tracks") }, text = {
                Column {
                    Text("Audio")
                    exo.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }.forEach { group ->
                        (0 until group.length).forEach { index ->
                            TextButton(onClick = {
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                                showTracks = false
                            }) { Text(group.getTrackFormat(index).language ?: "Audio ${index + 1}") }
                        }
                    }
                    Text("Subtitles")
                    TextButton(onClick = {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build(); showTracks = false
                    }) { Text("Off") }
                    exo.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }.forEach { group ->
                        (0 until group.length).forEach { index ->
                            TextButton(onClick = {
                                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                                showTracks = false
                            }) { Text(group.getTrackFormat(index).language ?: "Subtitle ${index + 1}") }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showTracks = false }) { Text("Close") } })
        }
    }
}

@Composable private fun LoginScreen(message: String, submit: (String, String) -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("PTS Stream", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, color = Color(0xFFFFA7A7))
        Button(onClick = { submit(user.trim(), pass) }, enabled = user.isNotBlank() && pass.isNotBlank(), modifier = Modifier.padding(top = 18.dp)) { Text("Sign in") }
    }
}

@Composable private fun TitleCard(title: Title, position: Long?, open: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF172235), RoundedCornerShape(14.dp))
        .clickable(onClick = open).padding(18.dp)) {
        Text(title.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("${title.kind.replaceFirstChar { it.uppercase() }} · ${title.year} · ${title.genres.take(2).joinToString()}")
        if (position != null && position > 0) Text("Continue · ${position / 60} min", color = accent)
        if (title.synopsis.isNotBlank()) Text(title.synopsis, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun DownloadsScreen(saved: List<SavedVideo>, play: (SavedVideo) -> Unit, delete: (SavedVideo) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("My Downloads", style = MaterialTheme.typography.headlineMedium) }
        if (saved.isEmpty()) item { Text("Offline titles will appear here, grouped by series and season.") }
        saved.groupBy { it.title }.forEach { (title, videos) ->
            item { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
            videos.groupBy { it.season }.forEach { (season, episodes) ->
                if (season > 0) item { Text("Season $season", color = accent) }
                items(episodes.sortedBy { it.episode }, key = { it.key }) { item ->
                    Row(Modifier.fillMaxWidth().background(Color(0xFF172235), RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(item.label, fontWeight = FontWeight.Bold); Text("${item.bytes / 1048576} MiB · on this device") }
                        TextButton(onClick = { play(item) }) { Text("Play") }
                        TextButton(onClick = { delete(item) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
