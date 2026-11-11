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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class DownloadTask(val title: Title, val episode: Episode?, val status: String = "queued",
    val bytes: Long = 0, val total: Long = 0, val speed: Long = 0, val error: String = "", val route: String = "") {
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
                if (o.optString("status") in listOf("queued", "preparing", "downloading", "waiting_wifi", "cancelling")) "paused" else o.optString("status"),
                o.optLong("bytes"), o.optLong("total"), error = o.optString("error"))
        }.getOrNull() }.filter { it.status != "complete" || saved.any { s -> s.key == it.key } }
        mutable.value = DownloadSnapshot(tasks, saved)
    }
    private var checkpointAt = 0L
    private fun update(checkpoint: Boolean = true, transform: (DownloadSnapshot) -> DownloadSnapshot) = synchronized(lock) {
        val next = transform(mutable.value)
        if (next == mutable.value) return@synchronized
        mutable.value = next
        val now = android.os.SystemClock.elapsedRealtime()
        // Serializing every episode's full title metadata twice per 250ms report stalls fast LAN
        // transfers. State transitions persist immediately; resumable byte counters checkpoint
        // at most every two seconds. Resume validates the actual partial length and validator.
        if (checkpoint || now - checkpointAt >= 2000) {
            prefs.edit().putString("jobs", JSONArray(next.tasks.map { it.json() }).toString()).apply()
            checkpointAt = now
        }
    }
    private fun task(key: String, checkpoint: Boolean = true, transform: (DownloadTask) -> DownloadTask) { update(checkpoint) { s -> s.copy(tasks = s.tasks.map { if (it.key == key) transform(it) else it }) } }
    fun enqueue(title: Title, episode: Episode?, smart: Boolean = false) {
        if (episode?.container == "hls" || (episode == null && title.container == "hls")) {
            val user = DeviceState(context).account()
            require(!smart) { "Smart downloads do not automatically prepare HLS media" }
            require(canRequestOfflinePreparation(owner, user?.optLong("id") ?: 0, user?.optString("role"))) { "Sign in to prepare an offline download" }
        }
        val t = DownloadTask(title, episode)
        if (!smart) prefs.edit().putStringSet("smart_blocked", prefs.getStringSet("smart_blocked", emptySet())!! - t.key).apply()
        if (mutable.value.saved.any { it.key == t.key } || mutable.value.tasks.any { it.key == t.key && it.status in listOf("queued", "preparing", "downloading", "waiting_wifi", "cancelling") }) return
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
        if (status !in listOf("queued", "preparing", "downloading", "waiting_wifi")) return@synchronized
        task(key) { it.copy(status = "paused", speed = 0) }
        jobs[key]?.cancel(); calls[key]?.cancel()
        if (jobs.isEmpty()) reconcile()
    }
    fun cancel(key: String) {
        prefs.edit().putStringSet("smart_blocked", prefs.getStringSet("smart_blocked", emptySet())!! + key).apply()
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
        update { s -> s.copy(tasks = s.tasks.map { if (it.status in listOf("queued", "preparing", "downloading", "waiting_wifi")) it.copy(status = "paused", speed = 0) else it }) }
        jobs.values.forEach { it.cancel() }; calls.values.forEach { it.cancel() }
        reconcile()
    }
    fun detachService(): Unit = synchronized(lock) { statusCallback = null; pauseAll() }
    fun remove(saved: SavedVideo) { store.remove(saved); DownloadNotifications.remove(context, owner, saved.key); update { s -> s.copy(saved = store.list(), tasks = s.tasks.filterNot { it.key == saved.key }) } }
    fun refresh() { update { it.copy(saved = store.list()) } }
    fun enqueueSeason(title: Title, episodes: List<Episode>) { episodes.filter { ep -> store.find(title, ep) == null }.forEach { enqueue(title, it) } }
    fun smartNext(title: Title, current: Episode?, completed: Set<String>) {
        if (current == null || DeviceState(context).account()?.optLong("id") != owner) return
        val count = NativePreferences(context).downloads().smartEpisodes
        val skip = mutable.value.tasks.map { it.key }.toSet() + mutable.value.saved.map { it.key } + prefs.getStringSet("smart_blocked", emptySet())!!
        smartEpisodes(title, current, count, completed, skip).forEach { enqueue(title, it, smart = true) }
    }
    fun runNext(onStatus: (DownloadTask?) -> Unit) { synchronized(lock) { statusCallback = onStatus }; reconcile() }
    fun reconcile(): Unit = synchronized(lock) {
        val callback = statusCallback ?: return@synchronized
        val options = NativePreferences(context).downloads()
        if (options.wifiOnly && !wifi()) {
            update { s -> s.copy(tasks = s.tasks.map { if (it.status in listOf("queued", "preparing", "downloading")) it.copy(status = "waiting_wifi", speed = 0) else it }) }
            jobs.values.forEach { it.cancel() }; calls.values.forEach { it.cancel() }
        } else {
            update { s -> s.copy(tasks = s.tasks.map { if (it.status == "waiting_wifi") it.copy(status = "queued") else it }) }
            val available = (options.concurrent - jobs.size).coerceAtLeast(0)
            mutable.value.tasks.filter { it.status == "queued" && it.key !in jobs }.take(available).forEach { next ->
                task(next.key) { it.copy(status = "downloading", error = "", route = "") }
                val worker = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val prepared = if (next.episode?.container == "hls" || (next.episode == null && next.title.container == "hls")) {
                            val user = api.me()
                            require(canRequestOfflinePreparation(owner, user.optLong("id"), user.optString("role"))) { "The signed-in account changed or cannot prepare this download" }
                            task(next.key) { it.copy(status = "preparing") }
                            statusCallback?.invoke(mutable.value.tasks.firstOrNull { it.key == next.key })
                            prepareOfflineMp4(api, next.title, next.episode)
                        } else null
                        currentCoroutineContext().ensureActive()
                        task(next.key) { if (it.status == "preparing") it.copy(status = "downloading") else it }
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
                            task(next.key, checkpoint = false) { it.copy(bytes = p.bytes, total = p.total, speed = if (it.status == "downloading") p.speed else 0) }
                            statusCallback?.invoke(mutable.value.tasks.firstOrNull { it.key == next.key })
                        }, onRoute = { route -> task(next.key, checkpoint = false) { it.copy(route = route.label) } }, preparedUrl = prepared) { }
                        val finished = store.find(next.title, next.episode) ?: error("Downloaded file is missing")
                        task(next.key) { it.copy(status = "complete", bytes = finished.bytes, total = finished.bytes, speed = 0) }; refresh()
                    } catch (e: CancellationException) {
                        task(next.key) { if (it.status in listOf("downloading", "preparing")) it.copy(status = "paused", speed = 0) else it }
                    } catch (e: Exception) {
                        task(next.key) { if (it.status in listOf("downloading", "preparing")) it.copy(status = "failed", speed = 0, error = e.message ?: "Download failed") else it }
                    } finally {
                        synchronized(lock) { calls.remove(next.key); jobs.remove(next.key); reservations.remove(next.key) }
                        reconcile()
                    }
                }
                jobs[next.key] = worker; worker.start()
            }
        }
        callback(mutable.value.tasks.firstOrNull { it.status in listOf("downloading", "preparing") } ?: mutable.value.tasks.firstOrNull { it.status == "waiting_wifi" })
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
    private var notifications: DownloadNotifications? = null
    private var observer: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastStartId = 0
    private val manager get() = getSystemService(NotificationManager::class.java)
    private val network = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { repository?.reconcile() }
        override fun onLost(network: android.net.Network) { repository?.reconcile() }
        override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) { repository?.reconcile() }
    }
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel("native-transfers", "Device downloads", NotificationManager.IMPORTANCE_LOW))
        manager.activeNotifications.filter { it.notification.channelId == "downloads" }.forEach { manager.cancel(it.tag, it.id) }
        getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val owner = intent?.getLongExtra("owner", 0) ?: 0
        val display = notifications ?: DownloadNotifications(this, owner)
        startForeground(DownloadNotifications.SUMMARY_ID, display.summary(repository?.state?.value?.tasks.orEmpty()))
        // Do not continue private transfers for an account that has signed out.
        if (owner <= 0 || DeviceState(this).account()?.optLong("id") != owner) { if (owner > 0) DownloadNotifications.clear(this, owner); stopSelf(startId); return START_NOT_STICKY }
        lastStartId = startId
        if (repository?.owner != null && repository?.owner != owner) { observer?.cancel(); repository?.detachService(); DownloadNotifications.clear(this, repository!!.owner); notifications = null }
        val repo = DeviceDownloads.forOwner(this, ApiProvider.create(), owner)
        if (repository !== repo || observer?.isActive != true) {
            repository = repo
            DownloadNotifications.clear(this, owner)
            notifications = DownloadNotifications(this, owner).also { it.seed(repo.state.value.tasks) }
            startForeground(DownloadNotifications.SUMMARY_ID, notifications!!.summary(repo.state.value.tasks))
            repo.runNext { /* Full snapshots, not whichever episode reported last, drive notifications. */ }
            observer = scope.launch {
                repo.state.collect { snapshot ->
                    if (DeviceState(this@DownloadService).account()?.optLong("id") != owner) {
                        DownloadNotifications.clear(this@DownloadService, owner); stopSelf(); return@collect
                    }
                    try { notifications?.publish(snapshot) } catch (_: SecurityException) { /* Android may hide notifications when permission is revoked; transfer remains owned. */ }
                    if (snapshot.tasks.none { transferIsActive(it.status) } && repo.state.value.tasks.none { transferIsActive(it.status) }) {
                        stopForeground(if (notifications?.hasChildren == true) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE); stopSelf(lastStartId)
                    }
                }
            }
        }
        val key = intent?.getStringExtra("key").orEmpty()
        when (intent?.action) { "PAUSE" -> repo.pause(key); "RESUME" -> repo.resume(key); "CANCEL" -> repo.cancel(key) }
        repo.reconcile()
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { repository?.pauseAll(); stopSelf() }
    override fun onDestroy() {
        observer?.cancel(); scope.cancel()
        runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        repository?.detachService()
        repository?.owner?.let { if (DeviceState(this).account()?.optLong("id") != it) DownloadNotifications.clear(this, it) }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
