package online.ptsmovies.stream

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import android.os.SystemClock
import okhttp3.Call

internal class DeviceStorageException(message: String) : IOException(message)

data class SavedVideo(val key: String, val titleId: Long, val title: String, val season: Int,
                      val episode: Int, val label: String, val bytes: Long, val file: File,
                      val poster: File? = null, val metadata: Title? = null, val documentUri: String? = null) {
    fun playbackUri(): String = documentUri ?: file.toURI().toString()
    fun titleMetadata(): Title {
        val local = if (season > 0) Episode(key.substringAfter('_').toLong(), season, episode, label, 0.0, "mp4", size = bytes) else null
        val base = metadata ?: Title(titleId, if (season > 0) "series" else "movie", title, 0, "", "", emptyList(), emptyList(), "hevc", "mp4")
        return if (local != null && base.episodes.none { it.id == local.id }) base.copy(episodes = base.episodes + local) else base
    }
}
data class TransferProgress(val bytes: Long, val total: Long, val speed: Long) {
    val percent: Int get() = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
    val eta: Long? get() = if (speed > 0 && total >= bytes) (total - bytes) / speed else null
}

class OfflineStore(private val context: Context, private val api: PtsApi, private val ownerId: Long) {
    private val prefs = context.getSharedPreferences("offline_index_$ownerId", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "media/$ownerId").apply { mkdirs() }
    private val indexLock = Any()
    private val database = StateDatabase.get(context)
    private var cachedIndex: String? = null
    private var cachedVideos = emptyList<SavedVideo>()
    init {
        database.migrate(ownerId, "offline_index", { prefs.getString("items", null) }, { if (prefs.contains("items")) prefs.edit().remove("items").commit() })
        if (database.read(ownerId, "offline_index") == null) database.write(ownerId, "offline_index", "[]")
    }
    private fun key(title: Title, episode: Episode?) = "${title.id}_${episode?.id ?: 0}"

    fun list(): List<SavedVideo> {
        val source = database.read(ownerId, "offline_index") ?: "[]"
        val entries = JSONArray(source)
        val titles = mutableMapOf<Long, Title?>()
        val result = (0 until entries.length()).mapNotNull { i ->
            val item = entries.optJSONObject(i) ?: return@mapNotNull null
            val k = item.optString("key")
            if (!k.matches(Regex("[0-9]+_[0-9]+"))) return@mapNotNull null
            val file = File(root, "$k.mp4")
            val uri = item.optString("document_uri").takeIf { it.isNotBlank() }
            if (uri == null && (!file.isFile || file.length() != item.optLong("bytes") || file.length() < 1)) return@mapNotNull null
            if (uri != null) {
                val parsed = android.net.Uri.parse(uri)
                if (parsed.scheme != "content") return@mapNotNull null
                // Missing/revoked documents stay indexed so restoring access can recover them.
                val valid = runCatching { context.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { it.length == item.optLong("bytes") } == true }.getOrDefault(false)
                if (!valid) return@mapNotNull null
            }
            val id = item.optLong("title_id")
            val metadata = titles.getOrPut(id) { database.title(ownerId, id) ?: runCatching { parseTitle(JSONObject(prefs.getString("title_$id", "")!!)) }.getOrNull()?.also { database.titles(ownerId, listOf(it)) } }
            SavedVideo(k, id, item.optString("title"), item.optInt("season"),
                item.optInt("episode"), item.optString("label"), item.optLong("bytes"), file,
                File(root, "poster_$id.jpg").takeIf { it.isFile }, metadata, uri)
        }
        cachedIndex = source; cachedVideos = result
        return result
    }
    fun find(title: Title, episode: Episode?): SavedVideo? = list().firstOrNull { it.key == key(title, episode) }
    fun remove(item: SavedVideo): Unit = synchronized(indexLock) {
        val next = list().filterNot { it.key == item.key }
        if (item.documentUri != null) {
            if (!android.provider.DocumentsContract.deleteDocument(context.contentResolver, android.net.Uri.parse(item.documentUri))) throw IOException("Cannot delete selected-folder video")
        } else if (!item.file.delete()) throw IOException("Cannot delete local video")
        writeIndex(next)
        if (next.none { it.titleId == item.titleId }) File(root, "poster_${item.titleId}.jpg").delete()
    }
    private fun writeIndex(items: List<SavedVideo>) {
        val array = JSONArray()
        items.forEach { v -> array.put(JSONObject().put("key", v.key).put("title_id", v.titleId)
            .put("title", v.title).put("season", v.season).put("episode", v.episode)
            .put("label", v.label).put("bytes", v.bytes).put("document_uri", v.documentUri)) }
        // Preserve inaccessible document entries rather than losing them on another download.
        val existing = JSONArray(database.read(ownerId, "offline_index") ?: "[]")
        val present = items.mapTo(hashSetOf()) { it.key }
        val knownValid = cachedVideos.mapTo(hashSetOf()) { it.key }
        repeat(existing.length()) { i -> val row = existing.getJSONObject(i); if (row.optString("document_uri").isNotBlank() && row.optString("key") !in present && row.optString("key") !in knownValid) array.put(row) }
        database.write(ownerId, "offline_index", array.toString())
    }

    // App-private transfer; never puts media on the VPS or public Android Downloads.
    fun discardPartial(key: String) {
        require(key.matches(Regex("[0-9]+_[0-9]+")))
        File(root, "$key.part").delete(); File(root, "$key.source").delete()
        val marker = File(root, "$key.complete")
        if (marker.isFile) { File(root, "$key.mp4").delete(); marker.delete() }
    }
    suspend fun save(title: Title, episode: Episode?, resume: Boolean = false,
        onTransfer: (TransferProgress) -> Unit = {}, onCall: (Call?) -> Unit = {}, maxBytes: Long = Long.MAX_VALUE,
        admit: (Long, Long) -> Unit = { _, _ -> },
        onRoute: (MediaRoute) -> Unit = {},
        preparedUrl: String? = null, destinationUri: String? = null,
        onProgress: (Int) -> Unit): SavedVideo = withContext(Dispatchers.IO) {
        find(title, episode)?.let { database.remove(ownerId, "export_${it.key}"); return@withContext it }
        if (preparedUrl == null && (episode?.container == "hls" || (episode == null && title.container == "hls")))
            throw IOException("This title needs MP4 preparation on the Pi before offline saving; user access is not yet verified")
        val id = key(title, episode)
        val telemetry = MediaTelemetry()
        val partial = File(root, "$id.part")
        val dest = File(root, "$id.mp4")
        val sourceInfo = File(root, "$id.source")
        val complete = File(root, "$id.complete")
        suspend fun transfer(allowFallback: Boolean) {
            currentCoroutineContext().ensureActive()
            val req = Request.Builder().url(preparedUrl ?: api.streamUrl(title, episode, allowFallback)).tag(MediaTelemetry::class.java, telemetry)
            val info = if (resume) runCatching { JSONObject(sourceInfo.readText()) }.getOrNull() else null
            val validator = info?.optString("validator").orEmpty()
            val offset = if (validator.isNotBlank() && partial.isFile) partial.length() else 0L
            if (offset > 0) req.header("Range", "bytes=$offset-").header("If-Range", validator)
            val call = api.http.newCall(req.build())
            onCall(call)
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response")
                val range = response.header("Content-Range").orEmpty().let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it) }
                val append = response.code == 206 && offset > 0
                val responseValidator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                    ?: response.header("Last-Modified").orEmpty()
                if (append && (range == null || range.groupValues[1].toLong() != offset ||
                        range.groupValues[2].toLong() != range.groupValues[3].toLong() - 1 ||
                        range.groupValues[3].toLong() != info?.optLong("total") || responseValidator != validator))
                    throw IOException("Source changed; restart the download")
                if (response.code != 200 && !append) throw IOException("Source did not return a safe complete file or range")
                val total = if (append) range!!.groupValues[3].toLong() else body.contentLength()
                if (total <= 0) throw IOException("Source size is unknown; safe offline download unavailable")
                val start = if (append) offset else 0L
                if (total - start > root.usableSpace - 64L * 1024 * 1024) throw DeviceStorageException("Insufficient device storage")
                if (total > maxBytes) throw DeviceStorageException("Download would exceed your storage limit")
                admit(total, start)
                sourceInfo.writeText(JSONObject().put("validator", responseValidator).put("total", total).toString())
                var written = start
                val started = SystemClock.elapsedRealtime()
                var lastReport = 0L
                java.io.FileOutputStream(partial, append).buffered(256 * 1024).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            if (written > total) throw IOException("Source exceeded its declared size")
                            val elapsed = SystemClock.elapsedRealtime() - started
                            if (elapsed - lastReport >= 1000 || written == total) {
                                onProgress((written * 100 / total).toInt().coerceIn(0, 100))
                                onTransfer(TransferProgress(written, total, (written - start) * 1000 / elapsed.coerceAtLeast(1)))
                                onRoute(telemetry.snapshot().route)
                                lastReport = elapsed
                            }
                        }
                    }
                }
                if (written != total) throw IOException("Incomplete transfer: $written of $total bytes")
                currentCoroutineContext().ensureActive()
                if (!partial.renameTo(dest)) throw IOException("Cannot finalize offline download")
                complete.writeText(written.toString())
                sourceInfo.delete()
            }
            onCall(null)
        }
        // The source may expire between sessions. A fresh signed URL is obtained for each attempt.
        var succeeded = dest.isFile && runCatching { complete.readText().toLong() == dest.length() && dest.length() > 0 }.getOrDefault(false)
        var last: Throwable? = null
        for (fallback in listOf(false, true)) {
            if (succeeded) break
            try {
                if (!resume) { partial.delete(); sourceInfo.delete() }
                transfer(fallback)
                succeeded = true
                break
            } catch (e: kotlinx.coroutines.CancellationException) { if (!resume) discardPartial(id); throw e }
            catch (e: DeviceStorageException) { if (!resume) discardPartial(id); throw e }
            catch (e: Exception) { currentCoroutineContext().ensureActive(); partial.delete(); sourceInfo.delete(); last = e }
        }
        if (!succeeded) throw IOException("Offline download failed: ${last?.message ?: "source unavailable"}")
        var item = SavedVideo(id, title.id, title.title, episode?.season ?: 0, episode?.episode ?: 0,
            episode?.title ?: title.title, dest.length(), dest)
        val tree = destinationUri
        if (tree != null) {
            val uri = android.net.Uri.parse(tree)
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, android.provider.DocumentsContract.getTreeDocumentId(uri))
            val previous = database.read(ownerId, "export_$id")?.let { runCatching { JSONObject(it) }.getOrNull() }
            val document = previous?.takeIf { it.optString("tree") == tree }?.optString("uri")?.takeIf { it.startsWith("content://") }?.let(android.net.Uri::parse)
                ?: android.provider.DocumentsContract.createDocument(context.contentResolver, parent, "video/mp4", "PTS-$ownerId-$id.mp4") ?: throw IOException("Selected folder is unavailable; private staged file kept")
            try {
                database.write(ownerId, "export_$id", JSONObject().put("tree", tree).put("uri", document.toString()).toString())
                context.contentResolver.openOutputStream(document, "w")!!.use { output -> dest.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
                } }
                val length = context.contentResolver.openAssetFileDescriptor(document, "r")?.use { it.length }
                require(length == dest.length()) { "Folder provider could not verify the complete file" }
                item = item.copy(documentUri = document.toString())
            } catch (e: Exception) {
                val deleted = runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, document) }.getOrDefault(false)
                if (deleted) database.remove(ownerId, "export_$id")
                if (e is kotlinx.coroutines.CancellationException) throw e
                throw IOException("Cannot save to the selected folder. Private staged media kept. Restore access, or choose private storage and queue this title again.", e)
            }
        }
        synchronized(indexLock) {
            database.titles(ownerId, listOf(title))
            writeIndex(list().filterNot { it.key == id } + item)
        }
        if (item.documentUri != null) dest.delete() // Only after document + index are durable.
        complete.delete()
        database.remove(ownerId, "export_$id")
        cachePoster(title)
        // The owner-scoped DownloadService publishes completion in the same
        // stable per-episode group as progress, never a second ungrouped alert.
        item
    }

    private fun cachePoster(title: Title) {
        val target = File(root, "poster_${title.id}.jpg")
        if (target.isFile) return
        val url = api.imageUrl(title.poster) ?: return
        try {
            val encoded = api.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful || response.body?.contentType()?.type != "image") return
                response.body!!.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(32768)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        if (output.size() + count > 4 * 1024 * 1024) return
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 640) }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options) ?: return
            synchronized(indexLock) { target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } }
            bitmap.recycle()
        } catch (_: Exception) { /* Poster failure never destroys a completed media transfer. */ }
    }

    fun usedBytes(): Long {
        val rows = JSONArray(database.read(ownerId, "offline_index") ?: "[]")
        val shared = (0 until rows.length()).sumOf { i -> rows.getJSONObject(i).let { if (it.optString("document_uri").isNotBlank()) it.optLong("bytes") else 0L } }
        return (root.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L) + shared
    }
    fun freeBytes(): Long = (root.usableSpace - 64L * 1024 * 1024).coerceAtLeast(0)
    suspend fun hydrate(titles: List<Title>) = withContext(Dispatchers.IO) {
        val savedIds = list().map { it.titleId }.toSet()
        titles.filter { it.id in savedIds }.forEach { title ->
            database.titles(ownerId, listOf(title))
            cachePoster(title)
        }
    }
}
