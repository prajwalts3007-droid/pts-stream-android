package online.ptsmovies.stream

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Fixed action width prevents a fillMaxWidth button from consuming the device name. */
@Composable
fun PowerDeviceRow(target: String, label: String, enabled: Boolean, onReboot: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("power-row-$target"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f).testTag("power-name-$target"), color = Pts.text, style = MaterialTheme.typography.titleSmall, maxLines = 2)
        PtsSecondaryButton("Reboot", Modifier.width(104.dp).testTag("power-reboot-$target"), danger = true, enabled = enabled, onClick = onReboot)
    }
}
