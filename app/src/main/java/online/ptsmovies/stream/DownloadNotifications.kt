package online.ptsmovies.stream

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat

fun transferIsActive(status: String) = status in setOf("queued", "preparing", "downloading", "waiting_wifi", "cancelling")
fun downloadNoticeTag(owner: Long, key: String) = "pts-download:$owner:$key"
fun downloadNoticePercent(task: DownloadTask) = if (task.status == "complete") 100 else task.progress.percent.coerceIn(0, 100)
fun downloadNoticeSortKey(order: Int) = order.coerceAtLeast(0).toString().padStart(10, '0')
fun appendDownloadOrder(existing: Map<String, Int>, keys: List<String>): Map<String, Int> {
    val result = existing.toMutableMap()
    var next = (existing.values.maxOrNull() ?: -1) + 1
    keys.forEach { if (it !in result) result[it] = next++ }
    return result
}
fun visibleDownloadTasks(tasks: List<DownloadTask>, tracked: Set<String>): List<DownloadTask> {
    val candidates = tasks.filter { transferIsActive(it.status) || it.key in tracked }
    if (candidates.size <= 40) return candidates
    // Android limits notifications per package. Reserve room for the group and
    // other app notifications; always prioritize running/queued transfers.
    val included = candidates.sortedBy { when (it.status) { "downloading", "preparing" -> 0; "queued", "waiting_wifi", "cancelling" -> 1; else -> 2 } }.take(40).map { it.key }.toSet()
    return candidates.filter { it.key in included }
}

/** Stable owner+episode tags, stable queue order, and a non-shuffling group summary. */
class DownloadNotifications(private val context: Context, private val owner: Long) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val group = "pts-device-downloads-$owner"
    private val tracked = mutableSetOf<String>()
    private val known = mutableSetOf<String>()
    private var initialized = false
    private var order = emptyMap<String, Int>()
    private val shown = mutableSetOf<String>()
    private val last = mutableMapOf<String, Pair<Long, String>>()
    private var summarySignature = ""
    val hasChildren get() = shown.isNotEmpty()
    fun seed(tasks: List<DownloadTask>) {
        val keys = tasks.map { it.key }
        if (initialized) tracked.addAll(keys.filter { it !in known })
        known.addAll(keys); initialized = true
        tracked.addAll(tasks.filter { transferIsActive(it.status) }.map { it.key })
        order = appendDownloadOrder(order, keys.filter { it in tracked })
    }
    private fun open(): PendingIntent = PendingIntent.getActivity(context, 3100,
        Intent(context, MainActivity::class.java).putExtra("open_downloads", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun summary(sourceTasks: List<DownloadTask>): android.app.Notification {
        val tasks = sourceTasks.filter { transferIsActive(it.status) || it.key in tracked }
        val active = tasks.count { transferIsActive(it.status) }
        val complete = tasks.count { it.status == "complete" }
        val paused = tasks.count { it.status == "paused" }
        val failed = tasks.count { it.status == "failed" }
        val detail = if (tasks.isEmpty()) "Preparing download queue" else "$active active · $complete completed · $paused paused · $failed failed"
        return NotificationCompat.Builder(context, "native-transfers").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PTS Library downloads").setContentText(detail).setGroup(group).setGroupSummary(true)
            .setSubText(if (tasks.size > 40) "40 individual items shown · Android notification limit" else null)
            .setOnlyAlertOnce(true).setSilent(true).setShowWhen(false).setWhen(0)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY).setOngoing(active > 0 || tasks.isEmpty())
            .setAutoCancel(active == 0 && tasks.isNotEmpty()).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(open()).build()
    }

    private fun action(key: String, action: String): PendingIntent {
        val intent = Intent(context, DownloadService::class.java).setAction(action).putExtra("owner", owner).putExtra("key", key)
            .setData(Uri.parse("pts-download://control/$owner/${Uri.encode(key)}/$action"))
        return PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun publish(snapshot: DownloadSnapshot) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        seed(snapshot.tasks)
        val visible = visibleDownloadTasks(snapshot.tasks, tracked)
        val present = visible.map { it.key }.toSet()
        (shown - present).forEach { key -> manager.cancel(downloadNoticeTag(owner, key), CHILD_ID); last.remove(key) }
        shown.retainAll(present)
        val now = android.os.SystemClock.elapsedRealtime()
        visible.forEach { task ->
            val percent = downloadNoticePercent(task)
            val phase = when (task.status) {
                "queued" -> "Queued"; "preparing" -> "Preparing MP4 on Pi"; "waiting_wifi" -> "Waiting for Wi-Fi"
                "paused" -> "Paused"; "failed" -> "Failed · open Downloads for details"; "complete" -> "Downloaded"
                "cancelling" -> "Cancelling"; else -> "$percent% · ${formatBytes(task.bytes)} / ${formatBytes(task.total)} · ${formatBytes(task.speed)}/s"
            }
            val signature = "${task.status}:$phase:$percent"
            val previous = last[task.key]
            if (previous?.second == signature) return@forEach
            val statusChanged = previous == null || previous.second.substringBefore(':') != task.status
            if (!statusChanged && now - previous!!.first < 750) return@forEach
            val notification = NotificationCompat.Builder(context, "native-transfers").setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(task.label).setContentText(phase).setGroup(group).setSortKey(downloadNoticeSortKey(order[task.key] ?: 0))
                .setWhen(0).setShowWhen(false).setOnlyAlertOnce(true).setSilent(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY).setContentIntent(open()).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOngoing(transferIsActive(task.status)).setAutoCancel(!transferIsActive(task.status))
                .setProgress(100, percent, task.status == "preparing" || (task.status == "downloading" && task.total <= 0))
                .apply {
                    if (task.status in listOf("queued", "preparing", "downloading", "waiting_wifi")) addAction(android.R.drawable.ic_media_pause, "Pause", action(task.key, "PAUSE"))
                    if (task.status in listOf("paused", "failed")) addAction(android.R.drawable.ic_media_play, "Resume", action(task.key, "RESUME"))
                    if (task.status != "complete" && task.status != "cancelling") addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", action(task.key, "CANCEL"))
                }.build()
            manager.notify(downloadNoticeTag(owner, task.key), CHILD_ID, notification)
            shown.add(task.key); last[task.key] = now to signature
        }
        val all = snapshot.tasks.filter { transferIsActive(it.status) || it.key in tracked }
        val signature = all.joinToString { "${it.key}:${it.status}" }
        if (summarySignature != signature) { manager.notify(SUMMARY_ID, summary(all)); summarySignature = signature }
    }

    companion object {
        const val SUMMARY_ID = 3001
        const val CHILD_ID = 3002
        fun clear(context: Context, owner: Long) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.filter { it.notification.group == "pts-device-downloads-$owner" }.forEach { manager.cancel(it.tag, it.id) }
        }
        fun remove(context: Context, owner: Long, key: String) { context.getSystemService(NotificationManager::class.java).cancel(downloadNoticeTag(owner, key), CHILD_ID) }
    }
}
