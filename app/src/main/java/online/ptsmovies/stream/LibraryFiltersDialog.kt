package online.ptsmovies.stream

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun LibraryFiltersDialog(titles: List<Title>, value: LibraryFilter, onChange: (LibraryFilter) -> Unit, onClose: () -> Unit) {
    Dialog(onClose) {
        PtsCard(Modifier.fillMaxWidth().heightIn(max = 580.dp).verticalScroll(rememberScrollState())) {
            Text("Library filters", style = MaterialTheme.typography.titleLarge, color = Pts.text)
            WebSelect("Year", listOf("0" to "All years") + titles.map { it.year }.filter { it > 0 }.distinct().sortedDescending().map { it.toString() to it.toString() }, value.year.toString()) { onChange(value.copy(year = it.toInt())) }
            WebSelect("Audio language", listOf("" to "All languages") + titles.flatMap { it.languages }.distinct().sorted().map { code -> code to java.util.Locale.forLanguageTag(code).displayLanguage.ifBlank { code } }, value.language) { onChange(value.copy(language = it)) }
            Text("Languages use available audio-track metadata; missing languages are not guessed.", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
            PreferenceSwitch("Unwatched only", value.unwatched) { onChange(value.copy(unwatched = it)) }
            PreferenceSwitch("Downloaded on this device", value.downloaded) { onChange(value.copy(downloaded = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PtsSecondaryButton("Reset", Modifier.weight(1f)) { onChange(LibraryFilter()) }
                PtsPrimaryButton("Done", Modifier.weight(1f), onClick = onClose)
            }
        }
    }
}
