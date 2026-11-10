package online.ptsmovies.stream

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import androidx.compose.ui.unit.sp

private val panel = Color(0xFF202343)
private val subtle = Color(0xFFC4C9E4)
private val border = Color(0xFF414671)

@Composable fun PageLabel(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
        Text("—  ${text.uppercase()}  —", style = MaterialTheme.typography.labelMedium, color = subtle, letterSpacing = 2.sp)
    }
}

@Composable fun PtsCard(modifier: Modifier = Modifier, body: @Composable ColumnScope.() -> Unit) {
    Column(modifier.background(panel, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = body)
}

private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.list(key: String) = (optJSONArray(key) ?: JSONArray()).objects()
private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
private fun size(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes.toDouble() / 1_073_741_824)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes.toDouble() / 1_048_576)
    else -> "$bytes B"
}

private val stages = listOf("queued", "dispatched", "downloading", "compressing", "uploading", "to_pi", "failed", "done")
private fun stageLabel(stage: String) = when (stage) { "to_pi" -> "To Pi"; "done" -> "Completed"; "dispatched" -> "Starting"; else -> stage.replaceFirstChar { it.uppercase() } }

@Composable fun HubPanel(tab: String, api: PtsApi, notice: (String) -> Unit) {
    when (tab) {
        "Dashboard" -> DashboardPanel(api, notice)
        "Queue" -> QueuePanel(api, notice)
        "Download" -> LookupPanel(api, notice)
        "Settings" -> SettingsPanel(api, notice)
    }
}

@Composable private fun DashboardPanel(api: PtsApi, notice: (String) -> Unit) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var target by remember { mutableStateOf("vps") }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        try { data = api.json("/system/metrics") } catch (e: Exception) { notice("Metrics: ${e.message}") }
    }
    val host = data?.optJSONObject(target)
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageLabel("Dashboard") }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            listOf("vps", "pi").forEach { value -> FilterChip(selected = target == value, onClick = { target = value }, label = { Text(if (value == "pi") "Raspberry Pi" else "VPS") }, modifier = Modifier.padding(horizontal = 4.dp)) }
        } }
        item { PtsCard(Modifier.fillMaxWidth()) {
            Text(host?.optString("hostname")?.takeIf { it.isNotBlank() } ?: if (host == null) "Not connected" else "Connected", fontWeight = FontWeight.Bold)
            Text("Live system metrics", color = subtle)
            TextButton(onClick = { refresh++ }) { Text("Refresh") }
        } }
        if (host != null) {
            val keys = host.keys().asSequence().toList().filter { it != "hostname" && it != "updated_at" && it != "error" }
            items(keys) { key -> PtsCard(Modifier.fillMaxWidth()) {
                Text(key.replace('_', ' ').uppercase(), style = MaterialTheme.typography.labelMedium, color = subtle)
                Text(host.opt(key)?.toString().orEmpty(), style = MaterialTheme.typography.titleMedium)
            } }
        }
    }
}

@Composable private fun LookupPanel(api: PtsApi, notice: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<JSONObject>()) }
    var waiting by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        waiting = true
        try { results = api.json("/imdb/search?q=${encode(query.trim())}").list("results") }
        catch (e: Exception) { notice("Search: ${e.message}") }
        finally { waiting = false }
    }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageLabel("Download") }
        item { OutlinedTextField(query, { query = it }, label = { Text("Search title or IMDb ID") }, modifier = Modifier.fillMaxWidth()) }
        if (waiting) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        items(results) { result -> PtsCard(Modifier.fillMaxWidth()) {
            Text(result.optString("title"), fontWeight = FontWeight.Bold)
            Text("${result.optString("year")} · ${result.optString("type")}", color = subtle)
        } }
        item { Text("Before adding media, select an unambiguous, accessible source. ZIP preflight and per-episode selection must be preserved; this screen cannot yet submit jobs.", color = subtle) }
    }
}

@Composable private fun QueuePanel(api: PtsApi, notice: (String) -> Unit) {
    var jobs by remember { mutableStateOf(emptyList<JSONObject>()) }
    var done by remember { mutableStateOf(emptyList<JSONObject>()) }
    var stage by remember { mutableStateOf("queued") }
    var confirm by remember { mutableStateOf(false) }
    var phrase by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        while (true) {
            try {
                val d = api.json("/queue")
                jobs = d.list("jobs"); done = d.list("completed")
            } catch (e: Exception) { notice("Queue: ${e.message}") }
            delay(6000)
        }
    }
    val visible = if (stage == "done") done else jobs.filter { it.optString("stage", "queued") == stage }
    // Capture immutable IDs before any deletion; never call the legacy bulk-delete endpoint.
    val snapshot = remember(confirm) { if (confirm) visible.mapNotNull { it.optString("job_id").takeIf(String::isNotEmpty) } else emptyList() }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false; phrase = "" },
        title = { Text("Cancel ${stageLabel(stage)} jobs") },
        text = { Column {
            Text("Delete the ${snapshot.size} jobs shown when you opened this confirmation? This cannot be undone. Type ${stageLabel(stage).uppercase()}.")
            OutlinedTextField(phrase, { phrase = it }, label = { Text("${stageLabel(stage).uppercase()} to confirm") })
        } },
        confirmButton = { TextButton(enabled = !deleting && phrase == stageLabel(stage).uppercase() && snapshot.isNotEmpty(), onClick = {
            deleting = true
            // This is a stage snapshot, not DELETE /queue.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                var removed = 0
                for (id in snapshot) {
                    try { api.json("/queue/${encode(id)}", "DELETE"); removed++ }
                    catch (e: Exception) { notice("Stopped after $removed/${snapshot.size}: ${e.message}"); break }
                }
                deleting = false; confirm = false; phrase = ""; refresh++
            }
        }) { Text("Delete ${snapshot.size}") } },
        dismissButton = { TextButton(onClick = { confirm = false; phrase = "" }) { Text("Keep jobs") } })
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageLabel("Queue") }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            stages.forEach { s -> FilterChip(selected = stage == s, onClick = { stage = s }, label = {
                Text("${stageLabel(s)} ${if (s == "done") done.size else jobs.count { it.optString("stage", "queued") == s }}")
            }) }
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${stageLabel(stage)} · ${visible.size}", fontWeight = FontWeight.Bold)
            if (stage != "done" && visible.isNotEmpty()) TextButton(onClick = { confirm = true }) { Text("Cancel all") }
        } }
        if (visible.isEmpty()) item { PtsCard(Modifier.fillMaxWidth()) { Text("No ${stageLabel(stage).lowercase()} jobs") } }
        items(visible, key = { it.optString("job_id") }) { job ->
            PtsCard(Modifier.fillMaxWidth()) {
                Text(job.optString("title").ifBlank { job.optString("job_id") }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text("${job.optString("message").ifBlank { job.optString("error") }} · ${job.optInt("progress")}%", color = subtle)
                LinearProgressIndicator(progress = { (job.optDouble("progress") / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable fun FilesPanel(tab: String, api: PtsApi, context: Context, notice: (String) -> Unit) {
    if (tab == "Transfers") { TransfersPanel(api, notice); return }
    var device by remember { mutableStateOf("vps") }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<JSONObject>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var folderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(device, path, refresh) {
        loading = true
        try {
            val data = api.json("/admin/files?path=${encode(path)}&device=$device")
            entries = data.list("entries")
        } catch (e: Exception) { notice("Files: ${e.message}") }
        finally { loading = false }
    }
    fun joined(name: String) = if (path.isBlank()) name else "$path/$name"
    if (folderDialog) AlertDialog(onDismissRequest = { folderDialog = false }, title = { Text("New folder") },
        text = { OutlinedTextField(folderName, { folderName = it }, label = { Text("Folder name") }) },
        confirmButton = { TextButton(enabled = folderName.isNotBlank() && '/' !in folderName && '\\' !in folderName, onClick = {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                try { api.json("/admin/files/mkdir", "POST", JSONObject().put("device", device).put("path", path).put("name", folderName.trim())); refresh++ }
                catch (e: Exception) { notice(e.message.orEmpty()) }
                folderDialog = false; folderName = ""
            }
        }) { Text("Create") } }, dismissButton = { TextButton(onClick = { folderDialog = false }) { Text("Cancel") } })
    val selected = delete
    if (selected != null) AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete file?") },
        text = { Text("Remove ${selected.optString("name")} from $device? This cannot be undone.") },
        confirmButton = { TextButton(onClick = {
            val target = joined(selected.optString("name"))
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                try { api.json("/admin/files/delete", "POST", JSONObject().put("device", device).put("paths", JSONArray().put(target))); refresh++ }
                catch (e: Exception) { notice(e.message.orEmpty()) }
                delete = null
            }
        }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Keep") } })
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageLabel("Browse") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("vps", "pi").forEach { name -> FilterChip(selected = device == name, onClick = { device = name; path = "" }, label = { Text(if (name == "pi") "Raspberry Pi" else "VPS") }) }
        } }
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = path.isNotEmpty(), onClick = { path = path.substringBeforeLast('/', "") }) { Text("↑ Up") }
            Text("/${path}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { refresh++ }) { Text("⟳") }
        } }
        item { Button(onClick = { folderDialog = true }) { Text("+ New folder") } }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!loading && entries.isEmpty()) item { PtsCard(Modifier.fillMaxWidth()) { Text("This folder is empty") } }
        items(entries, key = { it.optString("name") }) { entry ->
            val name = entry.optString("name")
            Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (entry.optBoolean("is_dir")) "▣" else "▤", modifier = Modifier.padding(end = 10.dp))
                Column(Modifier.weight(1f).clickable(enabled = entry.optBoolean("is_dir")) { path = joined(name) }) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (entry.optBoolean("is_dir")) "Folder" else size(entry.optLong("size")), style = MaterialTheme.typography.labelSmall, color = subtle)
                }
                if (!entry.optBoolean("is_dir")) TextButton(onClick = {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val url = "$BASE/api/v1/admin/files/download?path=${encode(joined(name))}&device=$device"
                                val folder = File(context.getExternalFilesDir(null), "admin-files").apply { mkdirs() }
                                val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100)
                                val temp = File(folder, "$safeName.part")
                                api.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                    if (!response.isSuccessful) throw IOException("Download HTTP ${response.code}")
                                    val bytes = response.body?.byteStream()?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: 0L
                                    if (bytes == 0L || (response.body?.contentLength() ?: -1L).let { it >= 0 && it != bytes }) { temp.delete(); throw IOException("Incomplete file") }
                                    if (!temp.renameTo(File(folder, safeName))) throw IOException("Cannot save file")
                                }
                            }
                            notice("$name saved to app-private files")
                        } catch (e: Exception) { notice(e.message.orEmpty()) }
                    }
                }) { Text("↓") }
                TextButton(onClick = { delete = entry }) { Text("⋯") }
            }
        }
    }
}

@Composable private fun TransfersPanel(api: PtsApi, notice: (String) -> Unit) {
    var ops by remember { mutableStateOf(emptyList<JSONObject>()) }
    LaunchedEffect(Unit) { while (true) {
        try { ops = api.json("/admin/files/ops").list("ops") } catch (e: Exception) { notice("Transfers: ${e.message}") }
        delay(3000)
    } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageLabel("Transfers") }
        if (ops.isEmpty()) item { PtsCard(Modifier.fillMaxWidth()) { Text("No transfers") } }
        items(ops) { op -> PtsCard(Modifier.fillMaxWidth()) {
            Text("${op.optString("kind").replaceFirstChar { it.uppercase() }} · ${op.optString("detail")}", fontWeight = FontWeight.Bold)
            Text(op.optString("state") + " · " + op.optString("err"), color = subtle)
            if (op.optString("state") == "running") LinearProgressIndicator(progress = { (op.optDouble("pct") / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } }
    }
}

@Composable private fun SettingsPanel(api: PtsApi, notice: (String) -> Unit) {
    var users by remember { mutableStateOf(emptyList<JSONObject>()) }
    var github by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) {
        try { users = api.json("/admin/users").list("users") } catch (e: Exception) { notice(e.message.orEmpty()) }
        try { github = api.json("/admin/github") } catch (e: Exception) { notice(e.message.orEmpty()) }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageLabel("Settings") }
        item { PtsCard(Modifier.fillMaxWidth()) {
            Text("Accounts", fontWeight = FontWeight.Bold)
            Text("${users.size} configured users", color = subtle)
            users.take(8).forEach { user -> Text("${user.optString("username")} · ${user.optString("role")}") }
        } }
        item { PtsCard(Modifier.fillMaxWidth()) {
            Text("GitHub pipeline", fontWeight = FontWeight.Bold)
            Text(if (github == null) "Status unavailable" else "Connected; account configuration is administrator-only", color = subtle)
        } }
        item { Text("Some settings require additional native forms. This build does not expose dangerous backend controls through an unverified client.", color = subtle) }
    }
}
