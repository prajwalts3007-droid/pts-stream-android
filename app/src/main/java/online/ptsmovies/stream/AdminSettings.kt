package online.ptsmovies.stream

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToLong

data class AdminCommand(val path: String, val method: String, val body: JSONObject? = null)
fun pipelineBody(values: Map<String, String>, original: JSONObject): JSONObject {
    val result = JSONObject()
    val speed = requireNotNull(values.getValue("pi_bwlimit_kbps").toDoubleOrNull()) { "Invalid transfer speed" }
    require(speed.isFinite() && speed in 0.0..8589.93) { "Speed must be 0–8589.93 Mbps" }
    val prior = original.getLong("pi_bwlimit_kbps")
    val display = kotlin.math.round(prior * 8192.0 / 1000000 * 100) / 100
    result.put("pi_bwlimit_kbps", if (speed == display) prior else if (speed == 0.0) 0L else (speed * 1000000 / 8192).roundToLong().coerceAtLeast(1))
    val ranges = mapOf("max_runners" to 1L..60L, "max_pi_transfers" to 1L..8L, "min_free_gib" to 1L..1000000L,
        "max_staging_gib" to 0L..1000000L, "reserve_per_job_gib" to 1L..100L)
    ranges.forEach { (key, range) ->
        val value = requireNotNull(values.getValue(key).toLongOrNull()) { "Enter a whole number for $key" }
        require(value in range) { "$key must be ${range.first}–${range.last}" }; result.put(key, value)
    }
    return result
}

@Composable
fun NativeAdminSettings(user: JSONObject, api: PtsApi, onUpdate: () -> Unit, onSignOut: () -> Unit,
    playbackPrefs: JSONObject = JSONObject(), onPlayback: (String, Any) -> Unit = { _, _ -> }) {
    if (user.optString("role") != "admin") return
    val scope = rememberCoroutineScope()
    var category by rememberSaveable { mutableStateOf("pipeline") }
    var provider by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pipeline by remember { mutableStateOf<JSONObject?>(null) }
    var github by remember { mutableStateOf(JSONObject()) }
    var google by remember { mutableStateOf(JSONObject()) }
    var users by remember { mutableStateOf(emptyList<JSONObject>()) }
    val fields = remember { mutableStateMapOf<String, String>() }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var cleanupToken by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("primary") }
    var unlocked by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<AdminCommand?>(null) }
    var confirmation by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }
    var powerPassword by remember { mutableStateOf("") }
    var newUser by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("user") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun run(command: AdminCommand, after: (JSONObject) -> Unit = {}) {
        if (busy) return
        busy = true; error = ""; notice = ""
        scope.launch {
            try { val result = api.json(command.path, command.method, command.body); after(result); notice = "Saved" }
            catch (e: Exception) { error = e.message ?: "Request failed" }
            finally { busy = false; token = ""; cleanupToken = ""; powerPassword = ""; newPassword = "" }
        }
    }
    fun refreshUsers() { scope.launch { try { val a = api.json("/admin/users").optJSONArray("users"); users = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) } } catch (e: Exception) { error = e.message.orEmpty() } } }
    fun refreshPipeline() {
        scope.launch { try {
            val value = api.json("/admin/pipeline-settings")
            val keys = listOf("pi_bwlimit_kbps", "max_runners", "max_pi_transfers", "min_free_gib", "max_staging_gib", "reserve_per_job_gib")
            require(keys.all { value.has(it) }) { "Pipeline settings response is incomplete" }
            pipeline = value
            keys.forEach { fields[it] = if (it == "pi_bwlimit_kbps") "%.2f".format(java.util.Locale.US, value.getLong(it) * 8192.0 / 1000000) else value.getLong(it).toString() }
        } catch (e: Exception) { error = e.message.orEmpty() } }
    }
    LaunchedEffect(category) {
        error = ""; notice = ""
        if (category == "pipeline") refreshPipeline()
        if (category == "users") refreshUsers()
        if (category != "pipeline") { password = ""; token = ""; unlocked = false; provider = "" }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            password = ""; token = ""; cleanupToken = ""; powerPassword = ""; newPassword = ""; unlocked = false; pending = null; newUser = false
        } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    pending?.let { command ->
        val needsPassword = command.path == "/admin/power" || command.path.startsWith("/admin/relay/bucket/")
        PtsConfirmDialog("Confirm change", confirmation, "Confirm", destructive = command.path.startsWith("/admin/power") || command.method == "DELETE",
            confirmEnabled = !busy && phrase == "CONFIRM" && (!needsPassword || powerPassword.isNotBlank()),
            onConfirm = {
                pending = null
                val captured = if (needsPassword) command.copy(body = JSONObject(command.body.toString()).put("password", powerPassword)) else command
                run(captured) { result ->
                    if (command.path.startsWith("/admin/users")) refreshUsers()
                    if (command.path == "/admin/pipeline-settings") refreshPipeline()
                    if (command.path == "/admin/github" && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) github = result
                }
            }, onDismiss = { pending = null; powerPassword = "" }) {
            PtsField(phrase, { phrase = it }, "Type CONFIRM", Modifier.fillMaxWidth())
            if (needsPassword) PtsField(powerPassword, { powerPassword = it }, "Your password", Modifier.fillMaxWidth(), password = true)
        }
    }
    if (newUser) PtsConfirmDialog("Create user", "", "Create", confirmEnabled = !busy && username.isNotBlank() && newPassword.length >= 8,
        onConfirm = { newUser = false; run(AdminCommand("/admin/users", "POST", JSONObject().put("username", username.trim()).put("name", name.trim()).put("email", email.trim()).put("password", newPassword).put("role", role))) { newPassword = ""; refreshUsers() } },
        onDismiss = { newUser = false; newPassword = "" }) {
        PtsField(username, { username = it }, "Username", Modifier.fillMaxWidth())
        PtsField(name, { name = it }, "Name", Modifier.fillMaxWidth())
        PtsField(email, { email = it }, "Email", Modifier.fillMaxWidth())
        PtsField(newPassword, { newPassword = it }, "Password", Modifier.fillMaxWidth(), password = true)
        PtsSegmented(listOf("user" to "User", "admin" to "Admin"), role, { role = it })
    }
    fun confirm(command: AdminCommand, message: String) { pending = command; confirmation = message; phrase = ""; powerPassword = "" }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PtsPage("Settings") }
        item { WebIconTabs(listOf(WebTab("pipeline", "Pipeline", PtsIcons.Pipeline), WebTab("playback", "Playback", PtsIcons.Play),
            WebTab("devices", "Devices", PtsIcons.Tv), WebTab("users", "Users", PtsIcons.User)), category, { category = it }, "settings-tab-row") }
        if (error.isNotBlank()) item { PtsErrorBanner(error) }
        if (notice.isNotBlank()) item { Text(notice, color = Pts.ok) }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        val sections = when (category) {
            "pipeline" -> listOf("API management", "Pipeline settings")
            "playback" -> listOf("Playback preferences")
            "devices" -> listOf("Power options", "Legacy Hugging Face transfer bucket")
            else -> listOf("User management")
        }
        sections.forEach { section ->
            item {
                PtsCard(Modifier.fillMaxWidth(), padding = 14.dp) {
                    Text(when (section) { "Pipeline settings" -> "Pipeline limits"; "Power options" -> "Power"; "API management" -> "API Management"; else -> section }, style = MaterialTheme.typography.titleMedium, color = Pts.text)
                    when (section) {
                        "Playback preferences" -> PlaybackPreferencesScreen(playbackPrefs, onPlayback, {}, embedded = true)
                        "Pipeline settings" -> {
                            val labels = linkedMapOf("pi_bwlimit_kbps" to "Pi transfer speed (Mbps, 0 = unlimited)", "max_runners" to "GitHub jobs at once", "max_pi_transfers" to "Pi transfers at once", "min_free_gib" to "Keep VPS space free (GiB)", "max_staging_gib" to "VPS space for waiting files (GiB)", "reserve_per_job_gib" to "Estimated file size (GiB)")
                            if (pipeline != null) labels.forEach { (key, label) -> PtsField(fields[key].orEmpty(), { fields[key] = it }, label, Modifier.fillMaxWidth().testTag("pipeline-$key")) }
                            pipeline?.optJSONObject("status")?.let { status ->
                                Text((if (status.optBoolean("dispatch_paused")) "Dispatch paused: ${status.optString("reason")} · " else "") +
                                    "GitHub: ${status.optInt("active_runners")}/${pipeline!!.optInt("max_runners")} · Pi: ${status.optInt("active_transfers")}/${pipeline!!.optInt("max_pi_transfers")} · Waiting: ${status.optInt("waiting_transfers")}",
                                    color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PtsPrimaryButton("Save limits", Modifier.weight(1f), enabled = pipeline != null && !busy) {
                                try { confirm(AdminCommand("/admin/pipeline-settings", "PUT", pipelineBody(fields.toMap(), pipeline!!)), "Apply pipeline settings?") } catch (e: Exception) { error = e.message.orEmpty() }
                            }
                            PtsSecondaryButton("Refresh status", Modifier.weight(1f)) { refreshPipeline() }
                            }
                        }
                        "API management" -> {
                            if (!unlocked) {
                                PtsField(password, { password = it }, "Your password", Modifier.fillMaxWidth(), password = true)
                                PtsPrimaryButton("Unlock", Modifier.fillMaxWidth(), enabled = password.isNotBlank() && !busy) {
                                    run(AdminCommand("/admin/github", "POST", JSONObject().put("password", password).put("token", ""))) { cfg ->
                                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && category == "pipeline" && password.isNotBlank()) {
                                            github = cfg; unlocked = true
                                            scope.launch { try { val result = api.json("/admin/google"); if (unlocked) { google = result; clientId = result.optString("client_id") } } catch (e: Exception) { error = e.message.orEmpty() } }
                                        }
                                    }
                                }
                            } else {
                                val accounts = github.optJSONArray("accounts")
                                val roles = (0 until (accounts?.length() ?: 0)).map { accounts!!.getJSONObject(it).optString("role") }.filter { it.isNotBlank() }.ifEmpty { listOf("primary") }
                                PtsSecondaryButton("GitHub", Modifier.fillMaxWidth(), icon = PtsIcons.ChevronDown) { provider = if (provider == "github") "" else "github" }
                                if (provider == "github") {
                                WebSelect("Runner account", roles.map { it to it.replaceFirstChar(Char::uppercase) }, account) { account = it; token = "" }
                                val cfg = (0 until (accounts?.length() ?: 0)).map { accounts!!.getJSONObject(it) }.firstOrNull { it.optString("role") == account } ?: github
                                Text(cfg.optString("repo"), color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                                 Text(cfg.optString("token_masked", "Not configured"), color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                                 Text("${(0 until (accounts?.length() ?: 0)).sumOf { accounts!!.getJSONObject(it).optInt("active") }}/${github.optInt("max_runners", 60)} active · up to ${github.optInt("per_account", 20)} per account · round robin with failover" +
                                     if (cfg.optLong("cooldown_until") > System.currentTimeMillis() / 1000) " · Selected account temporarily unavailable" else "", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                                PtsField(token, { token = it }, "Replacement GitHub token", Modifier.fillMaxWidth(), password = true)
                                PtsPrimaryButton("Save & provision", Modifier.fillMaxWidth(), enabled = token.isNotBlank() && !busy) {
                                    confirm(AdminCommand("/admin/github", "POST", JSONObject().put("password", password).put("token", token.trim()).put("account", account)), "Replace the selected account token and provision its runner repository?")
                                }
                                }
                                PtsSecondaryButton("Google sign-in", Modifier.fillMaxWidth(), icon = PtsIcons.ChevronDown) { provider = if (provider == "google") "" else "google" }
                                if (provider == "google") {
                                Text("${if (google.optBoolean("enabled")) "Enabled" else "Not configured"} · ${google.optString("origin")}", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                                PtsField(clientId, { clientId = it }, "Google client ID", Modifier.fillMaxWidth())
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PtsSecondaryButton("Save", enabled = !busy) {
                                    confirm(AdminCommand("/admin/google", "PUT", JSONObject().put("client_id", clientId.trim()).put("password", password)), "Update Google sign-in?")
                                }
                                PtsSecondaryButton("Disable", enabled = !busy) { confirm(AdminCommand("/admin/google", "PUT", JSONObject().put("client_id", "").put("password", password)), "Disable Google sign-in? Password sign-in is kept.") }
                                }
                                }
                                PtsSecondaryButton("Lock panel", Modifier.fillMaxWidth()) { password = ""; token = ""; unlocked = false; github = JSONObject(); google = JSONObject() }
                            }
                        }
                        "Legacy Hugging Face transfer bucket" -> {
                            Text("Read-only historical cleanup. Hugging Face currently rejects its token; cleanup requires restored account access. Purge All does not remove Pixeldrain files.", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                            PtsField(cleanupToken, { cleanupToken = it }, "Replacement token for historical cleanup", Modifier.fillMaxWidth(), password = true)
                            PtsSecondaryButton("Update cleanup token", enabled = !busy && cleanupToken.isNotBlank()) {
                                confirm(AdminCommand("/admin/relay/bucket/token", "PUT", JSONObject().put("token", cleanupToken.trim())), "Update the historical cleanup credential only? This does not restore a suspended account.")
                            }
                            PtsSecondaryButton("Purge All", danger = true, enabled = !busy) {
                                confirm(AdminCommand("/admin/relay/bucket/purge", "POST", JSONObject()), "Permanently remove every historical public ZIP-streamer upload and invalidate its text links? Pixeldrain files are not affected. Locked provider access can still prevent cleanup.")
                            }
                        }
                        "Power options" -> listOf("vps" to "VPS", "pi" to "Raspberry Pi").forEach { (target, label) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1f), color = Pts.text, style = MaterialTheme.typography.titleSmall)
                                PtsSecondaryButton("Reboot", danger = true, enabled = !busy) { confirm(AdminCommand("/admin/power", "POST", JSONObject().put("target", target)), "Reboot $label now? Active connections will be interrupted.") }
                            }
                        }
                        "User management" -> {
                            PtsPrimaryButton("+ Add user", enabled = !busy) { username = ""; name = ""; email = ""; newPassword = ""; role = "user"; newUser = true }
                            users.forEach { entry ->
                                val id = entry.optLong("id"); val self = id == user.optLong("id")
                                Text(entry.optString("name").ifBlank { entry.optString("username") }, color = Pts.text, style = MaterialTheme.typography.titleSmall)
                                Text("@${entry.optString("username")} · ${entry.optString("role")} · ${if (entry.optBoolean("is_active", true)) "Active" else "Disabled"}", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                                if (!self) {
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PtsSecondaryButton(if (entry.optString("role") == "admin") "Make user" else "Make admin", enabled = !busy) {
                                        confirm(AdminCommand("/admin/users/$id", "PATCH", JSONObject().put("role", if (entry.optString("role") == "admin") "user" else "admin")), "Change ${entry.optString("username")}'s role?")
                                    }
                                    PtsSecondaryButton(if (entry.optBoolean("is_active", true)) "Disable" else "Enable", enabled = !busy) {
                                        confirm(AdminCommand("/admin/users/$id", "PATCH", JSONObject().put("is_active", !entry.optBoolean("is_active", true))), "Change ${entry.optString("username")}'s access?")
                                    }
                                    PtsSecondaryButton("Delete", danger = true, enabled = !busy) { confirm(AdminCommand("/admin/users/$id", "DELETE"), "Delete ${entry.optString("username")}? This cannot be undone.") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
