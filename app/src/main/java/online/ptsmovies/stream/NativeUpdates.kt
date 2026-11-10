package online.ptsmovies.stream

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class NativeUpdateState(val status: String = "idle", val message: String = "Choose a channel and check for updates.",
    val bytes: Long = 0, val total: Long = 0, val speed: Long = 0, val candidate: ReleaseUpdate? = null)

/** Android DownloadManager owns the APK transfer, not the Activity or a browser page. */
class NativeUpdates private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("native_updates", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val mutable = MutableStateFlow(NativeUpdateState())
    val state = mutable.asStateFlow()
    var channel by mutableStateOf(prefs.getString("channel", if (BuildConfig.IS_PRERELEASE) "test" else "stable") ?: "stable")
        private set
    private var monitor: Job? = null
    init {
        runCatching {
            val raw = JSONObject(prefs.getString("candidate", "")!!)
            val candidate = ReleaseUpdate(raw.getInt("code"), raw.getString("url"), raw.optString("digest"))
            if (candidate.code > BuildConfig.VERSION_CODE && prefs.getLong("download_id", 0) > 0) observe(candidate, prefs.getLong("download_id", 0))
        }
    }
    fun channel(value: String) { if (mutable.value.status in listOf("downloading", "verifying")) return; channel = value; prefs.edit().putString("channel", value).apply(); mutable.value = NativeUpdateState() }
    fun check() {
        if (mutable.value.status in listOf("checking", "downloading", "verifying")) return
        val tests = channel == "test"
        mutable.value = NativeUpdateState("checking", "Checking signed ${if (tests) "test and stable" else "stable"} releases…")
        scope.launch {
            try {
                val candidate = withContext(Dispatchers.IO) {
                    val url = if (tests) BuildConfig.RELEASES_API.removeSuffix("/latest") + "?per_page=20" else BuildConfig.RELEASES_API
                    OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (response.code == 404) return@use null
                        check(response.isSuccessful) { "Release server HTTP ${response.code}" }
                        val text = response.body?.string().orEmpty()
                        val releases = if (tests) JSONArray(text) else JSONArray().put(JSONObject(text))
                        (0 until releases.length()).mapNotNull { i ->
                            runCatching { parseReleaseUpdate(releases.getJSONObject(i), BuildConfig.VERSION_CODE, tests) }.getOrNull()
                        }.maxByOrNull { it.code }
                    }
                }
                mutable.value = if (candidate == null) NativeUpdateState("current", if (tests) "No newer signed test or stable APK is published." else "No newer stable APK is published. Test builds are available only on the Test channel.")
                else NativeUpdateState("available", "Signed version ${candidate.code} is available.", candidate = candidate)
            } catch (e: Exception) { mutable.value = NativeUpdateState("error", e.message ?: "Could not check for updates") }
        }
    }
    private fun file(candidate: ReleaseUpdate) = File(context.getExternalFilesDir(null), "updates/PTS-Stream-v${candidate.code}.apk")
    fun download() {
        val candidate = mutable.value.candidate ?: return
        if (monitor?.isActive == true) return
        val file = file(candidate); file.parentFile?.mkdirs(); file.delete()
        try {
            val id = manager.enqueue(DownloadManager.Request(Uri.parse(candidate.url)).setTitle("PTS Library v${candidate.code}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, null, "updates/${file.name}"))
            prefs.edit().putLong("download_id", id).putString("candidate", JSONObject().put("code", candidate.code).put("url", candidate.url).put("digest", candidate.digest).toString()).commit()
            observe(candidate, id)
        } catch (e: Exception) { mutable.value = NativeUpdateState("error", e.message ?: "Cannot start update", candidate = candidate) }
    }
    private fun observe(candidate: ReleaseUpdate, id: Long) {
        monitor?.cancel()
        monitor = scope.launch {
            var previous = 0L
            var previousTime = android.os.SystemClock.elapsedRealtime()
            try {
                while (true) {
                    val result = withContext(Dispatchers.IO) {
                        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                            check(cursor.moveToFirst()) { "Update download no longer exists. Check and download again." }
                            Triple(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                        } ?: error("Cannot query update download")
                    }
                    if (result.first == DownloadManager.STATUS_FAILED) error("APK download failed. Check your connection and try again.")
                    if (result.first == DownloadManager.STATUS_SUCCESSFUL) {
                        mutable.value = NativeUpdateState("verifying", "Verifying package, version, signing key and checksum…", candidate = candidate)
                        withContext(Dispatchers.IO) { verifyUpdate(context, file(candidate), candidate) }
                        mutable.value = NativeUpdateState("ready", "Verified APK ready. Tap Install; Android will ask for your consent.", candidate = candidate)
                        break
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    val speed = (result.second - previous).coerceAtLeast(0) * 1000 / (now - previousTime).coerceAtLeast(1)
                    mutable.value = NativeUpdateState("downloading", if (result.first == DownloadManager.STATUS_PAUSED) "Waiting for network…" else "Downloading signed APK", result.second, result.third, speed, candidate)
                    previous = result.second; previousTime = now; delay(1000)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = NativeUpdateState("error", e.message ?: "Update failed", candidate = candidate) }
        }
    }
    fun install(activity: android.app.Activity) {
        val candidate = mutable.value.candidate ?: return
        if (mutable.value.status != "ready") return
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                mutable.value = mutable.value.copy(message = "Allow installs from PTS Library, return here, then tap Install again.")
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                return
            }
            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file(candidate))
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { mutable.value = mutable.value.copy(message = "Installer unavailable: ${e.message}") }
    }
    companion object {
        @Volatile private var instance: NativeUpdates? = null
        fun get(context: Context): NativeUpdates = instance ?: synchronized(this) { instance ?: NativeUpdates(context.applicationContext).also { instance = it } }
    }
}

@Composable
fun NativeUpdateScreen(controller: NativeUpdates, onInstall: () -> Unit, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PtsSecondaryButton("Back", onClick = onBack)
        PtsPage("App updates", "Installed ${BuildConfig.VERSION_NAME} · signed APKs only")
        PtsSegmented(listOf("stable" to "Stable", "test" to "Test releases"), controller.channel, controller::channel)
        Text(state.message, color = Pts.text, style = MaterialTheme.typography.bodyMedium)
        if (state.status == "downloading") {
            val p = TransferProgress(state.bytes, state.total, state.speed)
            PtsProgressBar(p.percent / 100f)
            Text("${p.percent}% · ${formatBytes(p.bytes)} / ${formatBytes(p.total.coerceAtLeast(0))}", color = Pts.muted)
            Text("${formatBytes(p.speed)}/s · ETA ${p.eta?.let { formatDuration(it) } ?: "calculating…"}", color = Pts.accent)
        }
        when (state.status) {
            "available", "error" -> {
                if (state.candidate != null) PtsPrimaryButton("Download update", Modifier.fillMaxWidth(), onClick = controller::download)
                PtsSecondaryButton("Check again", Modifier.fillMaxWidth(), onClick = controller::check)
            }
            "ready" -> PtsPrimaryButton("Install", Modifier.fillMaxWidth(), onClick = onInstall)
            "checking", "verifying", "downloading" -> Unit
            else -> PtsPrimaryButton("Check for updates", Modifier.fillMaxWidth(), onClick = controller::check)
        }
        Text("Test releases are prereleases, not a production-readiness guarantee. Installation always requires your approval.", style = MaterialTheme.typography.bodySmall, color = Pts.muted)
    }
}
