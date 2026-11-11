package online.ptsmovies.stream

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import java.io.File

const val MAX_SUBTITLE_BYTES = 2 * 1024 * 1024
fun validateSubtitle(bytes: ByteArray): Pair<String, String> {
    require(bytes.isNotEmpty() && bytes.size <= MAX_SUBTITLE_BYTES) { "Subtitle files must be 1 byte–2 MiB" }
    val text = when {
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> bytes.toString(Charsets.UTF_16LE).removePrefix("\uFEFF")
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> bytes.toString(Charsets.UTF_16BE).removePrefix("\uFEFF")
        else -> Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    }.replace("\r\n", "\n").trim()
    require(!text.contains('\u0000')) { "Not a text subtitle file" }
    val vtt = text.startsWith("WEBVTT")
    val cue = if (vtt) Regex("(?m)^(?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3}\\s+-->\\s+(?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3}")
        else Regex("(?m)^\\d{2}:\\d{2}:\\d{2},\\d{3}\\s+-->\\s+\\d{2}:\\d{2}:\\d{2},\\d{3}")
    require(cue.containsMatchIn(text)) { "Choose a valid SRT or WebVTT subtitle file" }
    return (if (vtt) "vtt" else "srt") to text
}

class LocalSubtitles(private val context: Context, private val owner: Long) {
    private val folder get() = File(context.filesDir, "subtitles/$owner").apply { mkdirs() }
    private fun key(title: Long, episode: Long) = "${title}_$episode"
    fun get(title: Long, episode: Long): File? = listOf("vtt", "srt").map { File(folder, "${key(title, episode)}.$it") }.firstOrNull { it.isFile && it.length() in 1..MAX_SUBTITLE_BYTES.toLong() }
    fun save(title: Long, episode: Long, bytes: ByteArray): File {
        require(owner > 0 && title > 0 && episode >= 0)
        val (ext, text) = validateSubtitle(bytes)
        val file = File(folder, "${key(title, episode)}.$ext")
        val temporary = File(folder, "${key(title, episode)}.tmp")
        temporary.writeText(text, Charsets.UTF_8)
        require(temporary.renameTo(file)) { "Could not save subtitles" }
        File(folder, "${key(title, episode)}.${if (ext == "vtt") "srt" else "vtt"}").delete()
        return file
    }
    fun remove(title: Long, episode: Long) { listOf("vtt", "srt").forEach { File(folder, "${key(title, episode)}.$it").delete() } }
}

fun playbackItem(source: String, subtitle: File?): MediaItem = MediaItem.Builder().setUri(source).apply {
    if (subtitle != null) setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(subtitle))
        .setMimeType(if (subtitle.extension == "vtt") MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
        .setLanguage("und").setLabel("Imported subtitles").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
}.build()
