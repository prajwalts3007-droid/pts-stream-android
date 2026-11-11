package online.ptsmovies.stream

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class ReleaseUpdate(val code: Int, val url: String, val digest: String)

internal fun parseReleaseUpdate(update: JSONObject, installedVersion: Int, allowTest: Boolean = false): ReleaseUpdate? {
    if (update.optBoolean("draft") || (update.optBoolean("prerelease") && !allowTest)) return null
    val tag = update.optString("tag_name")
    require(tag.matches(Regex(if (allowTest) "v[1-9][0-9]*(-rc[1-9][0-9]*)?" else "v[1-9][0-9]*"))) { "Invalid release version" }
    val code = tag.drop(1).substringBefore('-').toInt()
    if (code <= installedVersion) return null
    val assets = update.optJSONArray("assets") ?: error("Release has no APK")
    val name = "PTS-Stream-v$code.apk"
    val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
        .firstOrNull { it.optString("name") == name } ?: error("Signed APK not found in release")
    val expected = "https://github.com/${BuildConfig.APP_REPOSITORY}/releases/download/$tag/$name"
    require(asset.optString("browser_download_url") == expected) { "Untrusted download address" }
    return ReleaseUpdate(code, expected, asset.optString("digest"))
}

@Suppress("DEPRECATION")
internal fun verifyUpdate(context: Context, file: File, expected: ReleaseUpdate) {
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
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNATURES
    val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid APK")
    val installed = pm.getPackageInfo(context.packageName, flags)
    check(archive.packageName == context.packageName) { "APK package mismatch" }
    val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
    check(code == expected.code.toLong()) { "APK version mismatch" }
    // Older PackageManager archive parsers can leave signingInfo empty even though
    // verified legacy certificate bytes are available. Never fall back to trusting
    // release metadata or an empty signer: both nonempty certificate sets must match.
    val incoming = (if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else null)?.takeIf { it.isNotEmpty() } ?: archive.signatures
    val current = (if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else null)?.takeIf { it.isNotEmpty() } ?: installed.signatures
    check(sameSigningCertificates(incoming, current)) {
        "Signing key differs or is unavailable (archive=${incoming?.size ?: 0}, installed=${current?.size ?: 0}). Debug installs cannot receive release updates in place."
    }
}

internal fun sameSigningCertificates(incoming: Array<android.content.pm.Signature>?, installed: Array<android.content.pm.Signature>?): Boolean {
    if (incoming.isNullOrEmpty() || installed.isNullOrEmpty()) return false
    fun digests(signers: Array<android.content.pm.Signature>) = signers.map { signer ->
        MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
    }.toSet()
    return digests(incoming) == digests(installed)
}
