package online.ptsmovies.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.json.JSONObject

@Composable
fun PtsTitleListRow(title: Title, marked: Boolean, onOpen: () -> Unit, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    PtsCard(modifier.fillMaxWidth().testTag("poster-list-${title.id}"), padding = 12.dp, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(64.dp).height(92.dp).testTag("poster-list-art-${title.id}").clip(RoundedCornerShape(8.dp)).background(Pts.soft)) {
                if (title.poster.startsWith("https://")) PtsPosterImage(title.poster, null, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title.title, style = MaterialTheme.typography.titleMedium, color = Pts.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            PtsIconAction(if (marked) PtsIcons.Check else PtsIcons.Plus, "Toggle My List", onToggle)
        }
    }
}

@Composable
fun EditProfileDialog(user: JSONObject, onSave: (String, String) -> Unit, onClose: () -> Unit) {
    var name by remember(user) { mutableStateOf(user.optString("name")) }
    var email by remember(user) { mutableStateOf(user.optString("email")) }
    PtsConfirmDialog("Edit profile", "Changes sync to your PTS web account.", "Save", confirmEnabled = name.isNotBlank(),
        onConfirm = { onSave(name.trim(), email.trim()) }, onDismiss = onClose) {
        PtsField(name, { name = it }, "Name", Modifier.fillMaxWidth())
        PtsField(email, { email = it }, "Email", Modifier.fillMaxWidth())
    }
}
