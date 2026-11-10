package online.ptsmovies.stream

import org.json.JSONArray
import org.json.JSONObject

data class FileClipboard(val mode: String, val device: String, val paths: List<String>)
data class FileCommand(val action: String, val device: String, val directory: String,
    val names: List<String> = emptyList(), val value: String = "")
data class FileEditor(val device: String, val path: String, val content: String)

fun filePath(directory: String, name: String): String = if (directory.isBlank()) name else "${directory.trimEnd('/')}/$name"
fun validEntryName(name: String): Boolean = name.isNotBlank() && name.trim() !in setOf(".", "..") &&
    '/' !in name && '\\' !in name && '\u0000' !in name && name.toByteArray(Charsets.UTF_8).size <= 255
fun editableFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf(
    "txt", "json", "yaml", "yml", "toml", "ini", "cfg", "conf", "log", "md", "csv", "xml", "html", "htm", "css", "js", "go", "py", "sh", "srt", "nfo", "env", "vtt", "ass")

/** Exact bodies used by the live web Files UI. The device/path are captured before any dialog/request. */
suspend fun PtsApi.executeFile(command: FileCommand, clipboard: FileClipboard? = null): JSONObject {
    val c = command
    val body = JSONObject().put("device", c.device)
    return when (c.action) {
        "touch", "mkdir" -> {
            require(validEntryName(c.value)) { "Invalid file or folder name" }
            json("/admin/files/${c.action}", "POST", body.put("path", c.directory).put("name", c.value.trim()))
        }
        "rename" -> {
            require(c.names.size == 1 && validEntryName(c.value)) { "Invalid rename" }
            json("/admin/files/rename", "POST", body.put("path", filePath(c.directory, c.names.single())).put("new_name", c.value.trim()))
        }
        "zip" -> {
            require(c.names.isNotEmpty()) { "Select files first" }
            json("/admin/files/zip", "POST", body.put("dir", c.directory).put("names", JSONArray(c.names)))
        }
        "unzip" -> {
            require(c.names.size == 1 && c.names.single().endsWith(".zip", true)) { "Choose a ZIP archive" }
            json("/admin/files/unzip", "POST", body.put("path", filePath(c.directory, c.names.single())))
        }
        "delete" -> {
            require(c.names.isNotEmpty()) { "Select files first" }
            json("/admin/files/delete", "POST", body.put("paths", JSONArray(c.names.map { filePath(c.directory, it) })))
        }
        "paste" -> {
            val clip = requireNotNull(clipboard) { "Clipboard is empty" }
            require(clip.paths.isNotEmpty()) { "Clipboard is empty" }
            json("/admin/files/paste", "POST", JSONObject().put("mode", if (clip.mode == "cut") "move" else "copy")
                .put("from_device", clip.device).put("to_device", c.device).put("paths", JSONArray(clip.paths)).put("target", c.directory))
        }
        else -> error("Unknown file action: ${c.action}")
    }
}
