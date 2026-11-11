package online.ptsmovies.stream

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun SyncAlertsScreen(user: JSONObject, api: PtsApi, onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = user.optLong("id")
    val progress = remember(owner) { EpisodeProgress(context, owner) }
    val alerts = remember(owner) { context.getSharedPreferences("job_alerts_$owner",0) }
    var status by remember { mutableStateOf(progress.status()) }
    var alertStatus by remember { mutableStateOf(alerts.getString("status","Stopped") ?: "Stopped") }
    var running by remember { mutableStateOf(alerts.getBoolean("running",false)) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun start() {
        try { ContextCompat.startForegroundService(context,Intent(context,JobAlertsService::class.java).putExtra("owner",owner)) }
        catch (_: Exception) { alertStatus = "Android could not start live alerts. Keep the app in the foreground and try again." }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> if (allowed) start() else alertStatus = "Notification permission is required for live alerts" }
    LaunchedEffect(owner) { while (true) { status = progress.status(); running = alerts.getBoolean("running",false) && System.currentTimeMillis() - alerts.getLong("heartbeat",0) < 90000; alertStatus = alerts.getString("status","Stopped") ?: "Stopped"; delay(3000) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PtsSecondaryButton("Back", onClick = onBack)
        PtsPage("Sync & alerts")
        PtsCard(Modifier.fillMaxWidth()) {
            Text("Episode progress", color = Pts.text, style = MaterialTheme.typography.titleMedium)
            Text(status, color = Pts.muted)
            Text("Individual episode completion and resume points sync between signed-in Android devices. Offline edits stay private to this account and upload on reconnect. The web app still uses its original title-level resume point.", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
            PtsRefreshButton("Sync now",busy,Modifier.fillMaxWidth()) { if (!busy) { busy = true; scope.launch { try { progress.sync(api); status = progress.status() } finally { busy = false } } } }
        }
        if (user.optString("role") == "admin") PtsCard(Modifier.fillMaxWidth()) {
            Text("Live job alerts", color = Pts.text, style = MaterialTheme.typography.titleMedium)
            Text(if (running) alertStatus else "Stopped · tap Start to listen", color = Pts.muted)
            Text("Real server completion/failure events, even with the app screen closed. Requires an ongoing Android notification and a network connection. Android/OEM battery restrictions, force-stop or reboot can stop listening; reopen this page to restart. This is an opt-in live connection, not FCM or browser Web Push. Initial connection skips historical jobs.", color = Pts.muted, style = MaterialTheme.typography.bodySmall)
            PtsSecondaryButton(if (running) "Stop live alerts" else "Start live alerts",Modifier.fillMaxWidth()) {
                if (running) context.stopService(Intent(context,JobAlertsService::class.java))
                else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else start()
            }
        }
    }
}
