package online.ptsmovies.stream

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

@Composable
fun EpisodeNamesControl(title: Title, metadata: EpisodeMetadata, onResolved: (Title) -> Unit) {
    var open by remember { mutableStateOf(false) }; var source by remember(title) { mutableStateOf(metadata.cached(title)?.source) }
    val uri = LocalUriHandler.current
    LaunchedEffect(title.id, title.title, title.year) { onResolved(metadata.resolve(title)); source = metadata.cached(title)?.source }
    Column {
        if (source != null) TextButton({ uri.openUri(source!!.url) }) { Text("Episode metadata: ${source!!.provider} · CC BY-SA", style = MaterialTheme.typography.labelSmall) }
        TextButton({ open = true }) { Text(if (source == null) "Find original episode names" else "Correct episode-name match", style = MaterialTheme.typography.labelMedium) }
    }
    if (open) {
        val scope = rememberCoroutineScope()
        var query by remember { mutableStateOf(title.title) }; var results by remember { mutableStateOf(emptyList<MetadataMatch>()) }
        var selected by remember { mutableStateOf<MetadataMatch?>(null) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
        Dialog({ open = false }) {
            PtsCard(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text("Original episode names", color = Pts.text, style = MaterialTheme.typography.titleLarge)
                Text("Choose the correct show, year and language. Public episode tables are scraped when TVmaze coverage is missing. Only generated labels are replaced, never existing original names.", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                PtsField(query, { query = it }, "Show name", Modifier.fillMaxWidth())
                PtsSecondaryButton("Search sources", Modifier.fillMaxWidth(), enabled = !busy && query.isNotBlank()) {
                    busy = true; error = ""; selected = null
                    scope.launch { try { results = metadata.candidates(query.trim()); if (results.isEmpty()) error = "No sources available. Keep current names and try again later." }
                    catch (e: Exception) { error = e.message.orEmpty() } finally { busy = false } }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = Pts.danger, style = MaterialTheme.typography.bodySmall)
                results.forEach { result -> PtsSecondaryButton("${result.name} · ${result.year.takeIf { it > 0 } ?: ""} ${result.language} · ${result.provider}", Modifier.fillMaxWidth(), icon = if (selected == result) PtsIcons.Check else null, enabled = !busy) { selected = result } }
                PtsPrimaryButton("Use selected match", Modifier.fillMaxWidth(), enabled = selected != null && !busy) {
                    busy = true; error = ""
                    scope.launch { try {
                        val previous = metadata.cached(title)
                        val names = metadata.choose(title, selected!!); source = names.source; onResolved(applyEpisodeNames(title, names, previous)); open = false
                    } catch (e: Exception) { error = e.message.orEmpty() } finally { busy = false } }
                }
                Text("TVmaze and Wikipedia data: CC BY-SA. Source links and attribution stay with cached names. Unavailable names are not invented.", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
                PtsSecondaryButton("Close", Modifier.fillMaxWidth()) { open = false }
            }
        }
    }
}
