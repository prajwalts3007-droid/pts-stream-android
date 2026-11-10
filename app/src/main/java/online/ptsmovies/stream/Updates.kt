package online.ptsmovies.stream

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class ReleaseUpdate(val code: Int, val url: String, val digest: String)

internal fun parseReleaseUpdate(update: JSONObject, installedVersion: Int): ReleaseUpdate? {
    if (update.optBoolean("draft") || update.optBoolean("prerelease")) return null
    val tag = update.optString("tag_name")
    require(tag.matches(Regex("v[1-9][0-9]*"))) { "Invalid stable release version" }
    val code = tag.drop(1).toInt()
    if (code <= installedVersion) return null
    val assets = update.optJSONArray("assets") ?: error("Release has no APK")
    val name = "PTS-Stream-v$code.apk"
    val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
        .firstOrNull { it.optString("name") == name } ?: error("Signed APK not found in release")
    val expected = "https://github.com/${BuildConfig.APP_REPOSITORY}/releases/download/$tag/$name"
    require(asset.optString("browser_download_url") == expected) { "Untrusted download address" }
    return ReleaseUpdate(code, expected, asset.optString("digest"))
}

private var updateInProgress = false

@Suppress("DEPRECATION")
private fun verifyUpdate(context: Context, file: File, expected: ReleaseUpdate) {
    if (expected.digest.startsWith("sha256:")) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected.digest.removePrefix("sha256:")) { "APK checksum mismatch" }
    }
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid APK")
    val installed = pm.getPackageInfo(context.packageName, flags)
    check(archive.packageName == context.packageName) { "APK package mismatch" }
    val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
    check(code == expected.code.toLong()) { "APK version mismatch" }
    val incoming = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
    val current = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
    check(!incoming.isNullOrEmpty() && !current.isNullOrEmpty() && incoming.toSet() == current.toSet()) {
        "Signing key differs. Debug installs cannot receive release updates in place."
    }
}

fun checkForUpdate(context: Context, scope: CoroutineScope, notify: (String) -> Unit) {
    if (updateInProgress) { notify("An update check is already running"); return }
    updateInProgress = true
    scope.launch {
        try {
            val update = withContext(Dispatchers.IO) {
                OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(BuildConfig.RELEASES_API).build()).execute().use { response ->
                    if (response.code == 404) error("No stable release has been published yet")
                    if (!response.isSuccessful) error("Release check HTTP ${response.code}")
                    JSONObject(response.body?.string().orEmpty())
                }
            }
            val candidate = parseReleaseUpdate(update, BuildConfig.VERSION_CODE)
            if (candidate == null) { notify("PTS Library is up to date"); return@launch }
            val code = candidate.code
            val url = candidate.url
            if (!context.packageManager.canRequestPackageInstalls()) {
                notify("Allow installs from PTS Library, then check for updates again")
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@launch
            }
            val folder = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
            val file = File(folder, "PTS-Stream-v$code.apk")
            if (file.exists()) file.delete()
            val manager = context.getSystemService(DownloadManager::class.java)
            val id = manager.enqueue(DownloadManager.Request(Uri.parse(url))
                .setTitle("PTS Library v$code")
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
                            withContext(Dispatchers.IO) { verifyUpdate(context, file, candidate) }
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
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { notify("Update unavailable: ${e.message}") }
        finally { updateInProgress = false }
    }.invokeOnCompletion { updateInProgress = false }
}
