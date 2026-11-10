package online.ptsmovies.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class DownloadOptions(val wifiOnly: Boolean = true, val concurrent: Int = 1, val limitGiB: Int = 0, val removeWatched: Boolean = false)
class NativePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("native_preferences", Context.MODE_PRIVATE)
    fun downloads() = DownloadOptions(prefs.getBoolean("wifi_only", true), prefs.getInt("concurrent", 1).coerceIn(1, 3), prefs.getInt("storage_gib", 0).coerceAtLeast(0), prefs.getBoolean("remove_watched", false))
    fun downloads(value: DownloadOptions) { require(value.concurrent in 1..3 && value.limitGiB in 0..10000); prefs.edit().putBoolean("wifi_only", value.wifiOnly).putInt("concurrent", value.concurrent).putInt("storage_gib", value.limitGiB).putBoolean("remove_watched", value.removeWatched).apply() }
    fun lanAddress() = prefs.getString("lan_address", "192.168.29.75") ?: "192.168.29.75"
    fun lanAddress(value: String) { require(isPrivateV4(value)); prefs.edit().putString("lan_address", value).apply() }
    fun lanEnabled() = prefs.getBoolean("lan_enabled", true)
    fun lanEnabled(value: Boolean) { prefs.edit().putBoolean("lan_enabled", value).apply() }
}

fun isPrivateV4(address: String): Boolean {
    val parts = address.split('.').map { it.toIntOrNull() ?: return false }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    if (parts.joinToString(".") != address) return false
    return parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31)
}
fun onWifi(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    return manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
}

@Composable
fun DownloadSettingsScreen(preferences: NativePreferences, onBack: () -> Unit, onSaved: () -> Unit) {
    var options by remember { mutableStateOf(preferences.downloads()) }
    var limit by remember { mutableStateOf(options.limitGiB.toString()) }
    var address by remember { mutableStateOf(preferences.lanAddress()) }
    var lan by remember { mutableStateOf(preferences.lanEnabled()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PtsSecondaryButton("Back", onClick = onBack)
        PtsPage("Downloads & network")
        PreferenceSwitch("Wi-Fi downloads only", options.wifiOnly) { options = options.copy(wifiOnly = it) }
        PreferenceSwitch("Remove completed downloads after playback closes", options.removeWatched) { options = options.copy(removeWatched = it) }
        Text("Concurrent downloads", style = MaterialTheme.typography.titleSmall, color = Pts.text)
        PtsSegmented((1..3).map { it.toString() to it.toString() }, options.concurrent.toString(), { options = options.copy(concurrent = it.toInt()) })
        PtsField(limit, { limit = it.filter(Char::isDigit) }, "Storage limit (GiB, 0 = available space)", Modifier.fillMaxWidth())
        PreferenceSwitch("Prefer local Pi", lan) { lan = it }
        PtsField(address, { address = it.trim() }, "Pi LAN address", Modifier.fillMaxWidth())
        PtsPrimaryButton("Save", Modifier.fillMaxWidth(), enabled = (limit.toIntOrNull() ?: -1) in 0..10000 && isPrivateV4(address)) {
            preferences.downloads(options.copy(limitGiB = limit.toInt())); preferences.lanAddress(address); preferences.lanEnabled(lan); onSaved()
        }
    }
}

@Composable
fun PreferenceSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Pts.text, style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

@Composable
fun PlaybackPreferencesScreen(prefs: org.json.JSONObject, onChange: (String, Any) -> Unit, onBack: () -> Unit) {
    var audio by remember { mutableStateOf(prefs.optString("audio_lang")) }
    var subtitles by remember { mutableStateOf(prefs.optString("subtitle_lang")) }
    var autoplay by remember { mutableStateOf(prefs.optBoolean("autoplay", true)) }
    var rememberZoom by remember { mutableStateOf(prefs.optString("zoom_mode") == "remember") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val local = remember { context.getSharedPreferences("playback_local", Context.MODE_PRIVATE) }
    var size by remember { mutableStateOf(local.getFloat("subtitle_size", 1f)) }
    var fit by remember { mutableStateOf(local.getString("fit_mode", "fit") ?: "fit") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PtsSecondaryButton("Back", onClick = onBack)
        PtsPage("Playback")
        PtsField(audio, { audio = it }, "Audio language (en, hi, ta…)", Modifier.fillMaxWidth())
        PtsField(subtitles, { subtitles = it }, "Subtitle language (blank = off)", Modifier.fillMaxWidth())
        PreferenceSwitch("Autoplay next episode", autoplay) { autoplay = it }
        PreferenceSwitch("Remember zoom", rememberZoom) { rememberZoom = it }
        PtsSegmented(listOf("fit" to "Fit", "fill" to "Fill"), fit, { fit = it; local.edit().putString("fit_mode", fit).apply() })
        Text("Subtitle size", color = Pts.text)
        PtsSegmented(listOf("0.8" to "Small", "1.0" to "Medium", "1.3" to "Large"), size.toString(), {
            size = it.toFloat(); local.edit().putFloat("subtitle_size", size).apply()
        })
        PtsPrimaryButton("Save", Modifier.fillMaxWidth()) {
            onChange("audio_lang", audio.trim()); onChange("subtitle_lang", subtitles.trim())
            onChange("autoplay", autoplay); onChange("zoom_mode", if (rememberZoom) "remember" else "reset"); onBack()
        }
    }
}
