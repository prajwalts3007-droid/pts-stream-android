package online.ptsmovies.stream

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

fun validPasswordChange(current: String, replacement: String, confirmation: String) =
    current.isNotEmpty() && replacement.length in 8..1024 && replacement != current && replacement == confirmation

@Composable
internal fun PasswordDialog(api: PtsApi, onSaved: () -> Unit, onClose: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { current = ""; replacement = ""; confirmation = "" } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); current = ""; replacement = ""; confirmation = "" }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() }) {
        PtsCard(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Text("Change password", style = MaterialTheme.typography.titleLarge, color = Pts.text)
            PtsField(current, { current = it.take(1024) }, "Current password", Modifier.fillMaxWidth(), password = true, enabled = !busy)
            PtsField(replacement, { replacement = it.take(1024) }, "New password", Modifier.fillMaxWidth(), password = true, enabled = !busy)
            PtsField(confirmation, { confirmation = it.take(1024) }, "Confirm new password", Modifier.fillMaxWidth(), password = true, enabled = !busy)
            Text("Use at least 8 characters. Passwords are never saved on this device.", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) PtsErrorBanner(error)
            PtsButtonPair(first = { PtsSecondaryButton("Cancel", it, enabled = !busy, onClick = onClose) }, second = {
                PtsPrimaryButton(if (busy) "Saving…" else "Save password", it, enabled = !busy && validPasswordChange(current, replacement, confirmation)) {
                    if (!busy) {
                        busy = true; error = ""
                        val body = JSONObject().put("current_password", current).put("new_password", replacement)
                        current = ""; replacement = ""; confirmation = ""
                        scope.launch {
                            try { api.json("/auth/password", "POST", body); onSaved() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = if (e is ApiException && e.status == 401) "Your session expired. Sign in again." else "Password was not changed. Check your current password and try again." }
                            finally { body.remove("current_password"); body.remove("new_password"); busy = false }
                        }
                    }
                }
            })
        }
    }
}
