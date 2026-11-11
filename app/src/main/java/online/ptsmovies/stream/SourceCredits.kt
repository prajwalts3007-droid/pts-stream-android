package online.ptsmovies.stream

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Attribution stays accessible without metadata management controls on the watch page. */
@Composable
fun SourceCreditsDialog(sources: List<MetadataMatch>, onClose: () -> Unit) {
    val uri = LocalUriHandler.current
    Dialog(onClose) {
        PtsCard(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Text("About PTS Library", style = MaterialTheme.typography.titleLarge, color = Pts.text)
            Text("Version ${BuildConfig.VERSION_NAME} · Native Media3 playback", color = Pts.muted)
            Text("Cached episode details may include TVmaze and Wikipedia data. Original sources and CC BY-SA attribution are retained here; no metadata lookups are started by this page.", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
            TextButton({ uri.openUri("https://creativecommons.org/licenses/by-sa/4.0/") }) { Text("CC BY-SA 4.0 licence") }
            sources.forEach { source -> TextButton({ uri.openUri(source.url) }) { Text("${source.name} · ${source.provider}") } }
            PtsSecondaryButton("Close", Modifier.fillMaxWidth(), onClick = onClose)
        }
    }
}
