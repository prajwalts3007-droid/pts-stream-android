package online.ptsmovies.stream

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

fun checkForUpdate(context: Context, scope: CoroutineScope, notify: (String) -> Unit) {
    scope.launch {
        try {
            val update = withContext(Dispatchers.IO) {
                OkHttpClient().newCall(Request.Builder().url(BuildConfig.RELEASES_API).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("No signed release available yet")
                    JSONObject(response.body?.string().orEmpty())
                }
            }
            val code = update.optString("tag_name").removePrefix("v").toIntOrNull()
                ?: throw IllegalStateException("Invalid release version")
            if (code <= BuildConfig.VERSION_CODE) { notify("PTS Stream is up to date"); return@launch }
            val assets = update.optJSONArray("assets") ?: throw IllegalStateException("Release has no APK")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == "PTS-Stream-v$code.apk" }
                ?: throw IllegalStateException("Signed APK not found in release")
            val url = asset.getString("browser_download_url")
            if (Uri.parse(url).scheme != "https" || Uri.parse(url).host != "github.com")
                throw IllegalStateException("Untrusted download address")
            val folder = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
            val file = File(folder, "PTS-Stream-v$code.apk")
            if (file.exists()) file.delete()
            val manager = context.getSystemService(DownloadManager::class.java)
            val id = manager.enqueue(DownloadManager.Request(Uri.parse(url))
                .setTitle("PTS Stream v$code")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, null, "updates/${file.name}"))
            notify("Downloading update v$code")
            repeat(300) {
                delay(2000)
                manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_FAILED) throw IllegalStateException("Update download failed")
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            if (!file.isFile || file.length() < 100_000) throw IllegalStateException("Incomplete APK")
                            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent) // Android requires user consent and matching signing certificate.
                            return@launch
                        }
                    }
                }
            }
            throw IllegalStateException("Update download timed out")
        } catch (e: Exception) { notify("Update unavailable: ${e.message}") }
    }
}
