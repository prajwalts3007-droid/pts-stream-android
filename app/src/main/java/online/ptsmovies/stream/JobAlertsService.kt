package online.ptsmovies.stream

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/** Explicitly enabled live server events, not a fake browser Web Push subscription. */
class JobAlertsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listener: Job? = null
    private var owner = 0L
    private val manager get() = getSystemService(NotificationManager::class.java)
    private fun prefs() = getSharedPreferences("job_alerts_$owner", MODE_PRIVATE)
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel("job-listener", "Live job connection", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("job-events", "Job completion and failures", NotificationManager.IMPORTANCE_DEFAULT))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        val user = DeviceState(this).account()
        val requested = intent?.getLongExtra("owner", 0) ?: 0
        if (requested <= 0 || requested != user?.optLong("id") || user.optString("role") != "admin") { stopSelf(); return START_NOT_STICKY }
        if (listener?.isActive == true && owner == requested) return START_NOT_STICKY
        listener?.cancel(); owner = requested
        val stop = PendingIntent.getService(this, 3202, Intent(this, JobAlertsService::class.java).setAction("STOP"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this,3201,Intent(this,MainActivity::class.java).putExtra("open_jobs",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note = NotificationCompat.Builder(this,"job-listener").setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("PTS live job alerts").setContentText("Listening for server completion and failure events")
            .setOngoing(true).setContentIntent(open).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Stop",stop).build()
        startForeground(3200,note)
        prefs().edit().putBoolean("running",true).putString("status","Connecting to job events…").apply()
        val scopedOwner = owner
        listener = scope.launch {
            val api = ApiProvider.create()
            var backoff = 2L
            while (isActive && DeviceState(this@JobAlertsService).account()?.let { it.optLong("id") == scopedOwner && it.optString("role") == "admin" } == true) {
                prefs().edit().putLong("heartbeat",System.currentTimeMillis()).apply()
                if (!androidx.core.app.NotificationManagerCompat.from(this@JobAlertsService).areNotificationsEnabled()) break
                try {
                    val cursor = prefs().getLong("cursor", -1)
                    val response = api.json("/native/events?cursor=$cursor")
                    ensureActive()
                    if (DeviceState(this@JobAlertsService).account()?.optLong("id") != scopedOwner) break
                    val events = response.getJSONArray("events")
                    repeat(events.length()) { index ->
                        val event = events.getJSONObject(index)
                        if (event.optString("stage") in listOf("done","failed") && event.optLong("id") > prefs().getLong("cursor", -1)) {
                            val notification = NotificationCompat.Builder(this@JobAlertsService,"job-events")
                                .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(if (event.optString("stage") == "done") "Library job completed" else "Library job failed")
                                .setContentText(event.optString("label").take(80)).setContentIntent(open).setAutoCancel(true)
                                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
                            manager.notify("job-${event.optString("job_id")}",3203,notification)
                            prefs().edit().putLong("cursor",event.optLong("id")).commit()
                        }
                    }
                    prefs().edit().putLong("cursor",response.getLong("cursor")).putString("status","Connected · live job alerts enabled").apply()
                    backoff = 2
                } catch (e: CancellationException) { throw e }
                catch (e: ApiException) {
                    if (e.status in listOf(401,403)) { prefs().edit().putString("status","Access expired · sign in and start again").apply(); break }
                    prefs().edit().putString("status","Reconnecting when the server is available").apply(); delay(backoff * 1000); backoff = (backoff * 2).coerceAtMost(60)
                } catch (_: Exception) {
                    prefs().edit().putString("status","Offline · reconnecting").apply(); delay(backoff * 1000); backoff = (backoff * 2).coerceAtMost(60)
                }
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        scope.cancel()
        if (owner > 0) prefs().edit().putBoolean("running",false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
