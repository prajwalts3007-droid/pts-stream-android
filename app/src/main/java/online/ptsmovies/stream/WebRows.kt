package online.ptsmovies.stream

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow

data class WebTab(val key: String, val label: String, val icon: ImageVector, val count: Int? = null, val enabled: Boolean = true)

/** One web-style row. It scrolls instead of wrapping or shrinking touch targets. */
@Composable
fun WebIconTabs(tabs: List<WebTab>, selected: String, onSelect: (String) -> Unit, tag: String, fit: Boolean = false) {
    Row(Modifier.fillMaxWidth().testTag(tag).then(if (fit) Modifier else Modifier.horizontalScroll(rememberScrollState())), horizontalArrangement = Arrangement.spacedBy(if (fit) 2.dp else 6.dp)) {
        tabs.forEach { tab ->
            val active = tab.key == selected
            val background by animateColorAsState(if (active) Pts.accentSoft else Pts.card, tween(motionMillis(ptsMotionEnabled(), 150)), label = "web-tab-background")
            val border by animateColorAsState(if (active) Pts.accent else Pts.line, tween(motionMillis(ptsMotionEnabled(), 150)), label = "web-tab-border")
            Column((if (fit) Modifier.weight(1f) else Modifier.widthIn(min = 48.dp)).heightIn(min = 48.dp)
                .background(background, RoundedCornerShape(12.dp))
                .border(1.dp, border, RoundedCornerShape(12.dp))
                .semantics { contentDescription = tab.label; this.selected = active; role = Role.Tab; tab.count?.let { stateDescription = "$it jobs" }; if (!tab.enabled) disabled() }
                .clickable(enabled = tab.enabled) { onSelect(tab.key) }.padding(horizontal = if (fit) 0.dp else 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(tab.icon, null, Modifier.size(if (fit) 17.dp else 22.dp), tint = if (active) Pts.accent else Pts.muted)
                tab.count?.let { Text(if (it > 99) "99+" else it.toString(), maxLines = 1, fontSize = if (fit) 10.sp else 12.sp, color = if (active) Pts.accent else Pts.muted, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
fun WebSelect(label: String, options: List<Pair<String, String>>, value: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(motionMillis(ptsMotionEnabled(), 150)), label = "select-chevron")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Pts.muted)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { contentDescription = label }, shape = FieldShape) {
                Text(options.firstOrNull { it.first == value }?.second ?: value, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(PtsIcons.ChevronDown, null, Modifier.padding(start = 8.dp).size(20.dp).graphicsLayer { rotationZ = angle })
            }
            DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = 320.dp)) { options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, leadingIcon = { if (key == value) Icon(PtsIcons.Check, "Selected") }, onClick = { expanded = false; onSelect(key) })
            } }
        }
    }
}
