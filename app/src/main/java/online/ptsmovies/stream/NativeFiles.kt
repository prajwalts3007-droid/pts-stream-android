package online.ptsmovies.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.json.JSONObject

@Composable
fun FilesScreen(device: String, path: String, entries: List<JSONObject>, loading: Boolean,
    onDevice: (String) -> Unit, onPath: (String) -> Unit, onRefresh: () -> Unit,
    onDownload: (String) -> Unit, onDelete: (JSONObject) -> Unit, onMkdir: (String) -> Unit,
    onCommand: (FileCommand) -> Unit = {}, clipboard: FileClipboard? = null,
    onClearClipboard: () -> Unit = {}, onUpload: () -> Unit = {}, busy: Boolean = false, status: String = "") {
    NativeFilesScreen(device, path, entries, loading, onDevice, onPath, onRefresh, onDownload,
        onDelete, onMkdir, onCommand, clipboard, onClearClipboard, onUpload, busy, status)
}

@Composable
fun NativeFilesScreen(
    device: String, path: String, entries: List<JSONObject>, loading: Boolean,
    onDevice: (String) -> Unit, onPath: (String) -> Unit, onRefresh: () -> Unit,
    onDownload: (String) -> Unit, onDelete: (JSONObject) -> Unit, onMkdir: (String) -> Unit,
    onCommand: (FileCommand) -> Unit, clipboard: FileClipboard?, onClearClipboard: () -> Unit,
    onUpload: () -> Unit, busy: Boolean, status: String
) {
    var selecting by remember(device, path) { mutableStateOf(false) }
    var selected by remember(device, path) { mutableStateOf(emptySet<String>()) }
    var menu by remember(device, path) { mutableStateOf<JSONObject?>(null) }
    var pending by remember { mutableStateOf<FileCommand?>(null) }
    var name by remember { mutableStateOf("") }
    fun command(action: String, names: List<String> = emptyList()) = FileCommand(action, device, path, names)
    fun request(c: FileCommand) {
        if (!busy) {
            if (c.action in setOf("cut", "copy", "edit")) {
                onCommand(c); selecting = false; selected = emptySet()
            } else { pending = c; name = if (c.action == "rename") c.names.single() else "" }
        }
    }
    menu?.let { entry ->
        val filename = entry.optString("name")
        val directory = entry.optBoolean("is_dir")
        Dialog(onDismissRequest = { menu = null }) {
            PtsCard(Modifier.fillMaxWidth().heightIn(max = 580.dp).verticalScroll(rememberScrollState()), padding = 16.dp) {
                Text(filename, style = MaterialTheme.typography.titleLarge, color = Pts.text)
                val actions = buildList {
                    add(if (directory) "Open" to "open" else "Download" to "download")
                    if (!directory && editableFile(filename)) add("Edit" to "edit")
                    addAll(listOf("Cut" to "cut", "Copy" to "copy", "Rename" to "rename", "Zip" to "zip"))
                    if (!directory && filename.endsWith(".zip", true)) add("Unzip" to "unzip")
                    add("Delete" to "delete")
                }
                actions.forEach { (label, action) ->
                    PtsSecondaryButton(label, Modifier.fillMaxWidth(), danger = action == "delete", enabled = !busy) {
                        menu = null
                        when (action) {
                            "open" -> onPath(filePath(path, filename))
                            "download" -> onDownload(filename)
                            else -> request(command(action, listOf(filename)))
                        }
                    }
                }
            }
        }
    }
    pending?.let { c ->
        val input = c.action in setOf("mkdir", "touch", "rename")
        val title = when (c.action) { "mkdir" -> "New folder"; "touch" -> "New file"; "rename" -> "Rename"; "zip" -> "Zip"; "unzip" -> "Unzip"; "paste" -> "Paste"; else -> "Delete" }
        PtsConfirmDialog(title,
            when (c.action) {
                "delete" -> "Delete ${c.names.joinToString()} from ${c.device.uppercase()}? This cannot be undone."
                "zip" -> "Create a ZIP from ${c.names.size} selected item(s) on ${c.device.uppercase()}? Follow progress in Transfers."
                "unzip" -> "Extract ${c.names.single()} on ${c.device.uppercase()}? Follow progress in Transfers."
                "paste" -> "${if (clipboard?.mode == "cut") "Move" else "Copy"} ${clipboard?.paths?.size ?: 0} item(s) to ${c.device.uppercase()}:/${c.directory}?"
                else -> "${c.device.uppercase()}:/${c.directory}"
            }, if (input && c.action != "rename") "Create" else title,
            destructive = c.action == "delete", confirmEnabled = !busy && (!input || validEntryName(name)),
            onConfirm = {
                pending = null
                when {
                    c.action == "mkdir" -> onMkdir(name.trim())
                    c.action == "delete" && c.names.size == 1 -> onDelete(entries.firstOrNull { it.optString("name") == c.names.single() } ?: JSONObject().put("name", c.names.single()))
                    else -> onCommand(c.copy(value = name))
                }
                selecting = false; selected = emptySet()
            }, onDismiss = { pending = null }
        ) { if (input) PtsField(name, { name = it }, "Name", Modifier.fillMaxWidth()) }
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PtsPage("Files", "Browse storage on the VPS and Raspberry Pi") }
        item { PtsSegmented(listOf("vps" to "VPS", "pi" to "Raspberry Pi"), device, onDevice) }
        item {
            PtsCard(Modifier.fillMaxWidth(), padding = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PtsIconAction(PtsIcons.Up, "Up one folder", { onPath(path.substringBeforeLast('/', "")) })
                    Text("/$path", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Pts.text, style = MaterialTheme.typography.bodySmall)
                    PtsIconAction(PtsIcons.Refresh, "Refresh", onRefresh)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PtsSecondaryButton("New folder", enabled = !busy) { request(command("mkdir")) }
                    PtsSecondaryButton("New file", enabled = !busy) { request(command("touch")) }
                    PtsSecondaryButton("Upload", enabled = !busy, onClick = onUpload)
                    PtsSecondaryButton(if (selecting) "Done" else "Select") { selecting = !selecting; selected = emptySet() }
                }
            }
        }
        if (clipboard != null) item {
            PtsCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                Text("${clipboard.paths.size} item(s) ${if (clipboard.mode == "cut") "cut" else "copied"} · ${clipboard.device.uppercase()}", style = MaterialTheme.typography.bodySmall, color = Pts.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PtsPrimaryButton("Paste", Modifier.weight(1f), enabled = !busy) { request(command("paste")) }
                    PtsSecondaryButton("Clear", Modifier.weight(1f), onClick = onClearClipboard)
                }
            }
        }
        if (selecting) item {
            PtsCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${selected.size} selected", Modifier.weight(1f), color = Pts.accent)
                    Text("Select all", Modifier.clickable { selected = entries.map { it.optString("name") }.toSet() }.padding(8.dp), color = Pts.accent)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Cut" to "cut", "Copy" to "copy", "Zip" to "zip", "Delete" to "delete").forEach { (label, action) ->
                        PtsSecondaryButton(label, enabled = selected.isNotEmpty() && !busy, danger = action == "delete") { request(command(action, selected.toList())) }
                    }
                }
            }
        }
        if (status.isNotBlank()) item { Text(status, style = MaterialTheme.typography.bodySmall, color = Pts.accent) }
        if (loading || busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pts.accent, trackColor = Pts.soft) }
        if (!loading && entries.isEmpty()) item { PtsEmpty("This folder is empty", "Use New folder, New file or Upload to add an item.", icon = PtsIcons.Browse) }
        items(entries, key = { it.optString("name") }) { entry ->
            val filename = entry.optString("name")
            val isDir = entry.optBoolean("is_dir")
            PtsCard(Modifier.fillMaxWidth(), padding = 10.dp, borderColor = if (filename in selected) Pts.accent else Pts.line, onClick = {
                if (selecting) selected = if (filename in selected) selected - filename else selected + filename
                else if (isDir) onPath(filePath(path, filename)) else menu = entry
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Pts.soft), contentAlignment = Alignment.Center) {
                        Icon(if (selecting && filename in selected) PtsIcons.Check else if (isDir) PtsIcons.Browse else PtsIcons.Film, null, tint = Pts.accent)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(filename, style = MaterialTheme.typography.titleSmall, color = Pts.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (isDir) "Folder" else formatBytes(entry.optLong("size")), style = MaterialTheme.typography.bodySmall, color = Pts.muted)
                    }
                    if (!selecting) PtsIconAction(PtsIcons.More, "More", { menu = entry })
                }
            }
        }
    }
}

@Composable
fun FileEditorDialog(editor: FileEditor, busy: Boolean, onSave: (String) -> Unit, onClose: () -> Unit) {
    var content by remember(editor) { mutableStateOf(editor.content) }
    var discard by remember { mutableStateOf(false) }
    fun close() { if (content != editor.content) discard = true else onClose() }
    if (discard) PtsConfirmDialog("Discard edits?", "Your unsaved changes will be lost.", "Discard", destructive = true,
        onConfirm = onClose, onDismiss = { discard = false })
    Dialog(onDismissRequest = { if (!busy) close() }) {
        PtsCard(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), padding = 16.dp) {
            Text(editor.path.substringAfterLast('/'), style = MaterialTheme.typography.titleLarge, color = Pts.text)
            Text("${editor.device.uppercase()} · UTF-8 text · 1 MiB maximum", style = MaterialTheme.typography.bodySmall, color = Pts.muted)
            OutlinedTextField(content, { content = it }, modifier = Modifier.fillMaxWidth().height(300.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PtsSecondaryButton("Cancel", Modifier.weight(1f), enabled = !busy) { close() }
                PtsPrimaryButton("Save", Modifier.weight(1f), enabled = !busy && content.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { onSave(content) }
            }
        }
    }
}
