package online.ptsmovies.stream

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Native counterpart of web renderMediaCard / ZIP preflight / duplicate replacement. */
@Composable
fun NativeIngest(api: PtsApi, query: String, onQuery: (String) -> Unit, results: List<JSONObject>, searching: Boolean, onQueued: () -> Unit, searchError: String = "") {
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var rows by remember { mutableStateOf(emptyList<IngestEpisode>()) }
    var season by remember { mutableStateOf("1") }; var count by remember { mutableStateOf("1") }
    var movieLink by remember { mutableStateOf("") }; var zipLink by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }; var notice by remember { mutableStateOf("") }
    var paste by remember { mutableStateOf<String?>(null) }
    var replacement by remember { mutableStateOf<List<IngestEpisode>?>(null) }
    var duplicate by remember { mutableStateOf<Pair<JSONObject, String>?>(null) }
    var submission by remember { mutableStateOf<JSONObject?>(null) }
    fun fill(items: List<IngestEpisode>) {
        if (rows.any { it.link.isNotBlank() }) replacement = items else rows = items
    }
    fun import(text: String) {
        try {
            val plain = text.trim().split(Regex("\\s+")).filter { it.startsWith("http://") || it.startsWith("https://") }
            val hasNumbers = text.contains('\t') || hasNumberedLinkHints(plain)
            if (hasNumbers) fill(parseEpisodeLinks(text))
            else {
                require(text.toByteArray().size <= 2 * 1024 * 1024 && rows.isNotEmpty() && plain.isNotEmpty() && plain.all(::validSourceLink)) { "Generate rows first, or import links with S01E01 filenames" }
                fill(rows.mapIndexed { i, row -> if (i < plain.size) row.copy(link = plain[i], member = "") else row })
                if (plain.size > rows.size) notice = "${plain.size - rows.size} extra links ignored"
            }
        } catch (e: Exception) { error = e.message.orEmpty() }
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytesBounded(2 * 1024 * 1024) } }
                import(bytes.toString(Charsets.UTF_8))
            } catch (e: Exception) { error = e.message.orEmpty() }
        }
    }
    fun submit(payload: JSONObject) {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try {
                val data = api.json("/queue", "POST", payload)
                notice = "${data.optInt("count", payload.getJSONArray("items").length())} added to queue"
                selected = null; rows = emptyList(); movieLink = ""; zipLink = ""; onQuery(""); onQueued()
            } catch (e: ApiException) {
                val duplicates = e.data?.optJSONArray("duplicates")
                if (e.status == 409 && !payload.optBoolean("force") && duplicates != null && duplicates.length() > 0) {
                    duplicate = payload to (0 until duplicates.length()).joinToString("\n") { val d = duplicates.getJSONObject(it); "${d.optString("label")} — ${d.optString("where")}" }
                } else error = e.message.orEmpty()
            } catch (e: Exception) { error = e.message.orEmpty() }
            finally { busy = false }
        }
    }
    replacement?.let { items -> PtsConfirmDialog("Replace episode rows?", "Replace the links currently in this form?", "Replace",
        onConfirm = { rows = items; replacement = null }, onDismiss = { replacement = null }) }
    paste?.let { text -> PtsConfirmDialog("Paste links", "Numbered links map by season/episode; unnumbered links fill generated rows in order.", "Apply",
        onConfirm = { paste = null; import(text) }, onDismiss = { paste = null }) { PtsField(text, { paste = it }, "Links", Modifier.fillMaxWidth(), singleLine = false) } }
    duplicate?.let { (payload, labels) -> PtsConfirmDialog("Already added", "$labels\n\nDownload again with the new link? The file on the Pi will be overwritten.", "Replace", destructive = true,
        onConfirm = { duplicate = null; submit(JSONObject(payload.toString()).put("force", true)) }, onDismiss = { duplicate = null }) }
    submission?.let { payload -> PtsConfirmDialog("Add to queue?", "Submit ${payload.getJSONArray("items").length()} selected source(s) for ${payload.optString("title")}? Server source and ZIP checks still apply.", "Add",
        onConfirm = { submission = null; submit(payload) }, onDismiss = { submission = null }) }
    LazyColumn(Modifier.testTag("native-ingest"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PtsPage("Ingest") }
        item { PtsSearchField(query, onQuery, "Title or IMDb ID") }
        item { PtsSecondaryButton("Look up IMDb ID / URL", enabled = query.isNotBlank() && !busy) {
            busy = true; error = ""; scope.launch { try {
                selected = api.json("/imdb/lookup?q=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}").getJSONObject("title"); rows = emptyList(); movieLink = ""; zipLink = ""
            } catch (e: Exception) { error = e.message.orEmpty() } finally { busy = false } }
        } }
        if (busy || searching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error.isNotBlank()) item { PtsErrorBanner(error) }
        if (notice.isNotBlank()) item { Text(notice, color = Pts.ok) }
        if (selected == null) {
            if (searchError.isNotBlank()) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PtsErrorBanner(searchError)
                    PtsSecondaryButton("Retry search", enabled = query.trim().length >= 2 && !searching) { onQuery(query) }
                }
            }
            if (results.isEmpty() && !searching && query.trim().length >= 2 && searchError.isBlank()) item {
                Text("No matching titles. Try the full title or an IMDb ID.", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
            }
            itemsIndexed(results) { _, result ->
                PtsCard(Modifier.fillMaxWidth(), onClick = { selected = result; rows = emptyList(); movieLink = ""; zipLink = ""; error = "" }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(52.dp).aspectRatio(2f / 3f).clip(PosterShape).background(Pts.soft)) {
                            PtsPosterImage(api.imageUrl(result.optString("poster")), result.optString("title"), Modifier.fillMaxSize())
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(result.optString("title"), color = Pts.text, style = MaterialTheme.typography.titleMedium)
                            Text("${result.optInt("year")} · ${result.optString("type")}", color = Pts.muted)
                            val stars = result.optString("stars")
                            if (stars.isNotBlank()) Text(stars, color = Pts.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }
        }
        selected?.let { title ->
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(52.dp).aspectRatio(2f / 3f).clip(PosterShape).background(Pts.soft)) {
                    PtsPosterImage(api.imageUrl(title.optString("poster")), title.optString("title"), Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(title.optString("title"), color = Pts.text, style = MaterialTheme.typography.titleLarge); Text("${title.optInt("year")} · ${title.optString("type")}", color = Pts.muted) }
                PtsIconAction(PtsIcons.Close, "Clear selected title", { selected = null; rows = emptyList() })
            } }
            if (title.optString("type") == "series") {
                item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PtsField(season, { season = it }, "Season", Modifier.weight(1f))
                    PtsField(count, { count = it }, "Episode count", Modifier.weight(1f))
                } }
                item { PtsSecondaryButton("Generate", enabled = !busy) {
                    try { val s = season.toInt(); val n = count.toInt(); require(s in 1..100 && n in 1..200) { "Use seasons 1–100 and 1–200 episodes" }; fill((1..n).map { IngestEpisode(s, it) }) }
                    catch (e: Exception) { error = e.message ?: "Invalid season/count" }
                } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PtsSecondaryButton("Upload links.txt", Modifier.weight(1f), enabled = !busy) { upload.launch(arrayOf("text/plain", "application/octet-stream")) }
                    PtsSecondaryButton("Paste links", Modifier.weight(1f), enabled = !busy) { paste = "" }
                } }
                item { PtsField(zipLink, { zipLink = it }, "Public HTTPS ZIP link", Modifier.fillMaxWidth().testTag("ingest-zip-source")) }
                item { PtsSecondaryButton("Inspect ZIP", enabled = !busy && zipLink.startsWith("https://")) {
                    val link = zipLink.trim(); busy = true; error = ""
                    scope.launch { try { fill(zipEpisodeLinks(link, api.json("/queue/zip/preflight", "POST", JSONObject().put("url", link)))) }
                    catch (e: Exception) { error = e.message.orEmpty() } finally { busy = false } }
                } }
                item { Text("ZIP inspection checks ranges from the VPS only; the host must also allow GitHub runners. Review selected members before adding.", color = Pts.muted, style = MaterialTheme.typography.bodySmall) }
                item { Text("${rows.count { it.selected && it.link.isNotBlank() }} sources ready", Modifier.testTag("ingest-source-count"), color = Pts.muted, style = MaterialTheme.typography.labelMedium) }
                itemsIndexed(rows, key = { _, r -> "${r.season}_${r.episode}" }) { index, row ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(row.selected, { value -> rows = rows.toMutableList().also { it[index] = row.copy(selected = value) } }, Modifier.semantics { contentDescription = "Select S%02dE%02d".format(row.season, row.episode) })
                            Text("S%02dE%02d".format(row.season, row.episode), color = Pts.text)
                        }
                        PtsField(row.link, { link -> rows = rows.toMutableList().also { it[index] = row.copy(link = link, member = "") } }, "Source link", Modifier.fillMaxWidth())
                        if (row.member.isNotBlank()) Text(row.member, color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { PtsPrimaryButton("Add selected to queue", Modifier.fillMaxWidth(), enabled = !busy && rows.any { it.selected && it.link.isNotBlank() }) {
                    try { submission = ingestPayload(title, rows.filter { it.selected && it.link.isNotBlank() }.map { it.json() }) }
                    catch (e: Exception) { error = e.message.orEmpty() }
                } }
            } else {
                item { PtsField(movieLink, { movieLink = it }, "Direct download link", Modifier.fillMaxWidth()) }
                item { PtsPrimaryButton("Add to queue", Modifier.fillMaxWidth(), enabled = !busy && validSourceLink(movieLink)) {
                    try { submission = ingestPayload(title, listOf(JSONObject().put("download_url", movieLink.trim()))) }
                    catch (e: Exception) { error = e.message.orEmpty() }
                } }
            }
        }
    }
}

fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16384)
    while (true) { val n = read(buffer); if (n < 0) return out.toByteArray(); require(out.size() + n <= limit) { "File exceeds size limit" }; out.write(buffer, 0, n) }
}
