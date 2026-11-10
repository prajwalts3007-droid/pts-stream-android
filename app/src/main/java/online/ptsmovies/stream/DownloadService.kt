package online.ptsmovies.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class DownloadTask(val title: Title, val episode: Episode?, val status: String = "queued",
    val bytes: Long = 0, val total: Long = 0, val speed: Long = 0, val error: String = "") {
    val key get() = "${title.id}_${episode?.id ?: 0}"
    val label get() = episode?.let { "${title.title} · S${it.season}E${it.episode}" } ?: title.title
    val progress get() = TransferProgress(bytes, total, speed)
    fun json() = JSONObject().put("title", title.toJson()).put("episode", episode?.toJson()).put("status", status)
        .put("bytes", bytes).put("total", total).put("error", error)
}
data class DownloadSnapshot(val tasks: List<DownloadTask> = emptyList(), val saved: List<SavedVideo> = emptyList())

/** Process-wide, owner-scoped transfer queue. Activity recreation never owns or cancels it. */
class DownloadRepository internal constructor(private val context: Context, private val api: PtsApi, val owner: Long,
    private val wifi: () -> Boolean = { onWifi(context) }, private val foreground: (() -> Unit)? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("download_jobs_$owner", Context.MODE_PRIVATE)
    val store = OfflineStore(context, api, owner)
    private val lock = Any()
    private val mutable = MutableStateFlow(DownloadSnapshot())
    val state: StateFlow<DownloadSnapshot> = mutable.asStateFlow()
    private val jobs = linkedMapOf<String, Job>()
    private val calls = linkedMapOf<String, Call>()
    private val reservations = linkedMapOf<String, Long>()
    private var statusCallback: ((DownloadTask?) -> Unit)? = null
    init {
        val array = runCatching { JSONArray(prefs.getString("jobs", "[]")) }.getOrDefault(JSONArray())
        val saved = store.list()
        val tasks = (0 until array.length()).mapNotNull { i -> runCatching {
            val o = array.getJSONObject(i)
            DownloadTask(parseTitle(o.getJSONObject("title")), o.optJSONObject("episode")?.let { parseEpisode(it) },
                if (o.optString("status") in listOf("queued", "downloading", "waiting_wifi", "cancelling")) "paused" else o.optString("status"),
                o.optLong("bytes"), o.optLong("total"), error = o.optString("error"))
        }.getOrNull() }.filter { it.status != "complete" || saved.any { s -> s.key == it.key } }
        mutable.value = DownloadSnapshot(tasks, saved)
    }
    private fun update(transform: (DownloadSnapshot) -> DownloadSnapshot) = synchronized(lock) {
        mutable.value = transform(mutable.value)
        prefs.edit().putString("jobs", JSONArray(mutable.value.tasks.map { it.json() }).toString()).apply()
    }
    private fun task(key: String, transform: (DownloadTask) -> DownloadTask) { update { s -> s.copy(tasks = s.tasks.map { if (it.key == key) transform(it) else it }) } }
    fun enqueue(title: Title, episode: Episode?) {
        require(episode?.container != "hls" && (episode != null || title.container != "hls")) { "This HLS title is not available as an authorized offline MP4" }
        val t = DownloadTask(title, episode)
        if (mutable.value.saved.any { it.key == t.key } || mutable.value.tasks.any { it.key == t.key && it.status in listOf("queued", "downloading", "waiting_wifi", "cancelling") }) return
        update { s -> s.copy(tasks = s.tasks.filterNot { it.key == t.key } + t) }
        startService()
    }
    private fun startService() {
        foreground?.let { it(); return }
        try { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java).putExtra("owner", owner)) }
        catch (e: Exception) { update { s -> s.copy(tasks = s.tasks.map { if (it.status == "queued") it.copy(status = "paused", error = "Open Downloads and tap Resume: ${e.message}") else it }) } }
    }
    fun resume(key: String) {
        if (mutable.value.tasks.none { it.key == key && it.status in listOf("paused", "failed") }) return
        task(key) { it.copy(status = "queued", speed = 0, error = "") }; startService()
    }
    fun pause(key: String): Unit = synchronized(lock) {
        val status = mutable.value.tasks.firstOrNull { it.key == key }?.status
        if (status !in listOf("queued", "downloading", "waiting_wifi")) return@synchronized
        task(key) { it.copy(status = "paused", speed = 0) }
        jobs[key]?.cancel(); calls[key]?.cancel()
        if (jobs.isEmpty()) reconcile()
    }
    fun cancel(key: String) {
        val metadata = mutable.value.tasks.firstOrNull { it.key == key } ?: return
        val running = synchronized(lock) { jobs[key] }
        pause(key)
        task(key) { it.copy(status = "cancelling", speed = 0) }
        scope.launch {
            running?.join(); store.discardPartial(key)
            store.find(metadata.title, metadata.episode)?.let { store.remove(it) }
            update { s -> s.copy(tasks = s.tasks.filterNot { it.key == key }, saved = store.list()) }
        }
    }
    fun pauseAll(): Unit = synchronized(lock) {
        update { s -> s.copy(tasks = s.tasks.map { if (it.status in listOf("queued", "downloading", "waiting_wifi")) it.copy(status = "paused", speed = 0) else it }) }
        jobs.values.forEach { it.cancel() }; calls.values.forEach { it.cancel() }
        reconcile()
    }
    fun detachService(): Unit = synchronized(lock) { statusCallback = null; pauseAll() }
    fun remove(saved: SavedVideo) { store.remove(saved); update { s -> s.copy(saved = store.list(), tasks = s.tasks.filterNot { it.key == saved.key }) } }
    fun refresh() { update { it.copy(saved = store.list()) } }
    fun enqueueSeason(title: Title, episodes: List<Episode>) { episodes.filter { ep -> store.find(title, ep) == null }.forEach { enqueue(title, it) } }
    fun runNext(onStatus: (DownloadTask?) -> Unit) { synchronized(lock) { statusCallback = onStatus }; reconcile() }
    fun reconcile(): Unit = synchronized(lock) {
        val callback = statusCallback ?: return@synchronized
        val options = NativePreferences(context).downloads()
        if (options.wifiOnly && !wifi()) {
            update { s -> s.copy(tasks = s.tasks.map { if (it.status in listOf("queued", "downloading")) it.copy(status = "waiting_wifi", speed = 0) else it }) }
            jobs.values.forEach { it.cancel() }; calls.values.forEach { it.cancel() }
        } else {
            update { s -> s.copy(tasks = s.tasks.map { if (it.status == "waiting_wifi") it.copy(status = "queued") else it }) }
            val available = (options.concurrent - jobs.size).coerceAtLeast(0)
            mutable.value.tasks.filter { it.status == "queued" && it.key !in jobs }.take(available).forEach { next ->
                task(next.key) { it.copy(status = "downloading", error = "") }
                val worker = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        store.save(next.title, next.episode, resume = true, onCall = { call -> synchronized(lock) {
                            if (call != null) calls[next.key] = call else calls.remove(next.key)
                        } }, admit = { total, offset -> synchronized(lock) {
                            val limit = NativePreferences(context).downloads().limitGiB.toLong() * 1024 * 1024 * 1024
                            val needed = total - offset
                            if (needed + reservations.filterKeys { it != next.key }.values.sum() > store.freeBytes())
                                throw DeviceStorageException("Insufficient device storage for concurrent downloads")
                            if (limit > 0 && store.usedBytes() + reservations.filterKeys { it != next.key }.values.sum() + needed > limit)
                                throw DeviceStorageException("Storage limit reached. Remove a download or increase the limit.")
                            reservations[next.key] = needed
                        } }, onTransfer = { p ->
                            synchronized(lock) { reservations[next.key] = (p.total - p.bytes).coerceAtLeast(0) }
                            task(next.key) { it.copy(bytes = p.bytes, total = p.total, speed = if (it.status == "downloading") p.speed else 0) }
                            statusCallback?.invoke(mutable.value.tasks.firstOrNull { it.key == next.key })
                        }) { }
                        val finished = store.find(next.title, next.episode) ?: error("Downloaded file is missing")
                        task(next.key) { it.copy(status = "complete", bytes = finished.bytes, total = finished.bytes, speed = 0) }; refresh()
                    } catch (e: CancellationException) {
                        task(next.key) { if (it.status == "downloading") it.copy(status = "paused", speed = 0) else it }
                    } catch (e: Exception) {
                        task(next.key) { if (it.status == "downloading") it.copy(status = "failed", speed = 0, error = e.message ?: "Download failed") else it }
                    } finally {
                        synchronized(lock) { calls.remove(next.key); jobs.remove(next.key); reservations.remove(next.key) }
                        reconcile()
                    }
                }
                jobs[next.key] = worker; worker.start()
            }
        }
        callback(mutable.value.tasks.firstOrNull { it.status == "downloading" } ?: mutable.value.tasks.firstOrNull { it.status == "waiting_wifi" })
    }
}

object DeviceDownloads {
    private val repositories = ConcurrentHashMap<String, DownloadRepository>()
    fun forOwner(context: Context, api: PtsApi, owner: Long) = repositories.getOrPut("${api.resolveUrl("")}|$owner") {
        DownloadRepository(context.applicationContext, api, owner)
    }
}

class DownloadService : Service() {
    private var repository: DownloadRepository? = null
    private val manager get() = getSystemService(NotificationManager::class.java)
    private val network = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { repository?.reconcile() }
        override fun onLost(network: android.net.Network) { repository?.reconcile() }
        override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) { repository?.reconcile() }
    }
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel("native-transfers", "Device downloads", NotificationManager.IMPORTANCE_LOW))
        getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(3001, notification(null))
        val owner = intent?.getLongExtra("owner", 0) ?: 0
        // Do not continue private transfers for an account that has signed out.
        if (owner <= 0 || DeviceState(this).account()?.optLong("id") != owner) { stopSelf(startId); return START_NOT_STICKY }
        if (repository?.owner != null && repository?.owner != owner) repository?.pauseAll()
        repository = DeviceDownloads.forOwner(this, ApiProvider.create(), owner)
        repository!!.runNext { task ->
            if (task == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
            else try { manager.notify(3001, notification(task)) } catch (_: SecurityException) { /* The foreground service still owns the transfer when notification visibility is denied. */ }
        }
        return START_NOT_STICKY
    }
    private fun notification(task: DownloadTask?): Notification {
        val intent = Intent(this, MainActivity::class.java).putExtra("open_downloads", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(this, 3100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "native-transfers").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task?.label ?: "PTS Library downloads")
            .setContentText(task?.let { if (it.status == "waiting_wifi") "Waiting for Wi-Fi" else "${it.progress.percent}% · ${formatBytes(it.speed)}/s" } ?: "Preparing device transfer")
            .setProgress(100, task?.progress?.percent ?: 0, task == null).setOngoing(true).setContentIntent(open).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { repository?.pauseAll(); stopSelf() }
    override fun onDestroy() {
        runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        repository?.detachService(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
