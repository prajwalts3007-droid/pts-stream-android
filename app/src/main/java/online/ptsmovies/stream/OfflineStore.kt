package online.ptsmovies.stream

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class SavedVideo(val key: String, val titleId: Long, val title: String, val season: Int,
                      val episode: Int, val label: String, val bytes: Long, val file: File)

class OfflineStore(private val context: Context, private val api: PtsApi, ownerId: Long) {
    private val prefs = context.getSharedPreferences("offline_index_$ownerId", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "media/$ownerId").apply { mkdirs() }
    private val notifier = context.getSystemService(NotificationManager::class.java)
    init { notifier.createNotificationChannel(NotificationChannel("downloads", "Offline downloads", NotificationManager.IMPORTANCE_LOW)) }
    private fun key(title: Title, episode: Episode?) = "${title.id}_${episode?.id ?: 0}"

    fun list(): List<SavedVideo> {
        val entries = JSONArray(prefs.getString("items", "[]"))
        return (0 until entries.length()).mapNotNull { i ->
            val item = entries.optJSONObject(i) ?: return@mapNotNull null
            val k = item.optString("key")
            if (!k.matches(Regex("[0-9]+_[0-9]+"))) return@mapNotNull null
            val file = File(root, "$k.mp4")
            if (!file.isFile || file.length() != item.optLong("bytes") || file.length() < 1) return@mapNotNull null
            SavedVideo(k, item.optLong("title_id"), item.optString("title"), item.optInt("season"),
                item.optInt("episode"), item.optString("label"), file.length(), file)
        }
    }
    fun find(title: Title, episode: Episode?): SavedVideo? = list().firstOrNull { it.key == key(title, episode) }
    fun remove(item: SavedVideo) {
        val next = list().filterNot { it.key == item.key }
        if (!item.file.delete()) throw IOException("Cannot delete local video")
        writeIndex(next)
    }
    private fun writeIndex(items: List<SavedVideo>) {
        val array = JSONArray()
        items.forEach { v -> array.put(JSONObject().put("key", v.key).put("title_id", v.titleId)
            .put("title", v.title).put("season", v.season).put("episode", v.episode)
            .put("label", v.label).put("bytes", v.bytes)) }
        if (!prefs.edit().putString("items", array.toString()).commit()) throw IOException("Could not save offline index")
    }

    // App-private transfer; never puts media on the VPS or public Android Downloads.
    suspend fun save(title: Title, episode: Episode?, onProgress: (Int) -> Unit): SavedVideo = withContext(Dispatchers.IO) {
        find(title, episode)?.let { return@withContext it }
        if (episode?.container == "hls" || (episode == null && title.container == "hls"))
            throw IOException("This title needs MP4 preparation on the Pi before offline saving; user access is not yet verified")
        val id = key(title, episode)
        val partial = File(root, "$id.part")
        val dest = File(root, "$id.mp4")
        fun transfer(allowFallback: Boolean) {
            val req = Request.Builder().url(api.streamUrl(title, episode, allowFallback))
                .build()
            api.http.newCall(req).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response")
                if (response.code != 200) throw IOException("Source did not return a complete file")
                val total = body.contentLength().takeIf { it > 0 }
                if (total == null || total <= 0) throw IOException("Source size is unknown; safe offline download unavailable")
                if (total > root.usableSpace - 64L * 1024 * 1024) throw IOException("Insufficient device storage")
                var written = 0L
                partial.outputStream().buffered().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            onProgress((written * 100 / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
                if (written != total) throw IOException("Incomplete transfer: $written of $total bytes")
                if (!partial.renameTo(dest)) throw IOException("Cannot finalize offline download")
            }
        }
        // The source may expire between sessions. A fresh signed URL is obtained for each attempt.
        var succeeded = false
        var last: Throwable? = null
        for (fallback in listOf(false, true)) {
            try {
                partial.delete() // a full request avoids silently joining unrelated or changed remote files
                transfer(fallback)
                succeeded = true
                break
            } catch (e: Exception) { last = e }
        }
        if (!succeeded) throw IOException("Offline download failed: ${last?.message ?: "source unavailable"}")
        val item = SavedVideo(id, title.id, title.title, episode?.season ?: 0, episode?.episode ?: 0,
            episode?.title ?: title.title, dest.length(), dest)
        writeIndex(list().filterNot { it.key == id } + item)
        try {
            notifier.notify(id.hashCode(), NotificationCompat.Builder(context, "downloads")
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("PTS Stream")
                .setContentText("${item.label} ready offline").setAutoCancel(true).build())
        } catch (_: SecurityException) { /* notification permission is optional */ }
        item
    }
}
