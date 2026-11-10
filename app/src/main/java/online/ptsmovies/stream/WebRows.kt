package online.ptsmovies.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

data class WebTab(val key: String, val label: String, val icon: ImageVector, val count: Int? = null)

/** One web-style row. It scrolls instead of wrapping or shrinking touch targets. */
@Composable
fun WebIconTabs(tabs: List<WebTab>, selected: String, onSelect: (String) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().testTag(tag).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEach { tab ->
            val active = tab.key == selected
            Row(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
                .background(if (active) Pts.accentSoft else Pts.card, RoundedCornerShape(12.dp))
                .border(1.dp, if (active) Pts.accent else Pts.line, RoundedCornerShape(12.dp))
                .semantics { contentDescription = tab.label; this.selected = active; role = Role.Tab }
                .clickable { onSelect(tab.key) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(tab.icon, null, Modifier.size(22.dp), tint = if (active) Pts.accent else Pts.muted)
                tab.count?.let { Text(it.toString(), color = if (active) Pts.accent else Pts.muted, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
fun WebSelect(label: String, options: List<Pair<String, String>>, value: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.labelMedium, color = Pts.muted)
    Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) { Text(options.firstOrNull { it.first == value }?.second ?: value, Modifier.weight(1f)); Icon(PtsIcons.ChevronDown, null) }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onSelect(key) }) } }
    }
}
