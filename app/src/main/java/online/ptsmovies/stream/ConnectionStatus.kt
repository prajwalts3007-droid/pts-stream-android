package online.ptsmovies.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal enum class ConnectionRoute(val label: String) { CHECKING("Checking connection"), LOCAL("Local Pi"), INTERNET("Internet"), OFFLINE("Offline") }
internal data class ConnectionState(val route: ConnectionRoute = ConnectionRoute.CHECKING, val checkedAt: Long = 0, val address: String = "")
internal fun connectionRoute(local: Boolean, internet: Boolean) = when { local -> ConnectionRoute.LOCAL; internet -> ConnectionRoute.INTERNET; else -> ConnectionRoute.OFFLINE }

/** Foreground-only checks, no account cookies, and no media/ingestion side effects. */
internal object ConnectionMonitor {
    private val mutable = MutableStateFlow(ConnectionState())
    val state = mutable.asStateFlow()
    private val requests = MutableStateFlow(0L)
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val internet = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
    fun recheck() { mutable.value = ConnectionState(); requests.value = requests.value + 1 }
    suspend fun run(context: Context) = coroutineScope {
        val token = generation.incrementAndGet()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = recheck()
            override fun onLost(network: Network) = recheck()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = recheck()
        }
        manager.registerDefaultNetworkCallback(callback)
        try {
            requests.collectLatest {
                while (isActive && generation.get() == token) {
                    val network = manager.activeNetwork
                    val prefs = NativePreferences(context)
                    val local = prefs.lanEnabled() && try { withContext(Dispatchers.IO) { LanDiscovery.verifyRemembered(context) } }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                    if (!local) LanDiscovery.maybeDiscover(context)
                    val remote = if (local || network == null) false else try {
                        val client = internet.newBuilder().socketFactory(network.socketFactory).dns(object : okhttp3.Dns {
                            override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
                        }).build()
                        val config = client.newCall(Request.Builder().url("$BASE/api/v1/auth/config").build()).awaitJson(64 * 1024)
                        config.has("google_enabled") || config.has("google_client_id")
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                    if (manager.activeNetwork == network && generation.get() == token) mutable.value = ConnectionState(connectionRoute(local, remote), SystemClock.elapsedRealtime(), if (local) prefs.lanAddress() else "")
                    delay(30_000)
                }
            }
        } finally {
            manager.unregisterNetworkCallback(callback)
            if (generation.get() == token) mutable.value = ConnectionState()
        }
    }
}

internal val LocalConnectionDetails = staticCompositionLocalOf<() -> Unit> { {} }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ProfileAvatar(initial: String, avatar: String?, imageLoader: coil.ImageLoader?, size: Dp = 48.dp, description: String = "Open profile", snapshot: ConnectionState? = null, onClick: () -> Unit) {
    val observed by ConnectionMonitor.state.collectAsState()
    val connection = snapshot ?: observed
    val details = LocalConnectionDetails.current
    val tint = when (connection.route) {
        ConnectionRoute.LOCAL -> Color(0xFFFF5252)
        ConnectionRoute.INTERNET -> Color(0xFF35D07F)
        else -> Pts.muted
    }
    val glowing = connection.route in listOf(ConnectionRoute.LOCAL, ConnectionRoute.INTERNET)
    Box(Modifier.size(size).testTag("profile-avatar").drawBehind {
        val radius = this.size.minDimension / 2
        if (glowing) {
            drawCircle(tint.copy(alpha = .08f), radius - .5.dp.toPx(), style = Stroke(1.dp.toPx()))
            drawCircle(tint.copy(alpha = .18f), radius - 1.5.dp.toPx(), style = Stroke(2.dp.toPx()))
        }
        drawCircle(tint, radius - 3.5.dp.toPx(), style = Stroke(2.dp.toPx()))
    }.semantics { contentDescription = description; stateDescription = connection.route.label }
        .combinedClickable(onClick = onClick, onLongClickLabel = "Connection details", onLongClick = details).padding(6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(Pts.soft), contentAlignment = Alignment.Center) {
            Text(initial.ifBlank { "?" }, color = Pts.accent, style = MaterialTheme.typography.titleMedium)
            if (avatar != null && imageLoader != null) AsyncImage(avatar, "Profile photo", imageLoader = imageLoader, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
internal fun ConnectionDetailsDialog(onDismiss: () -> Unit) {
    val connection by ConnectionMonitor.state.collectAsState()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Connection") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(connection.route.label, style = MaterialTheme.typography.titleMedium)
            Text(when (connection.route) {
                ConnectionRoute.LOCAL -> "Red glow · trusted Pi verified directly on Wi-Fi${if (connection.address.isNotBlank()) " · ${connection.address}" else ""}."
                ConnectionRoute.INTERNET -> "Green glow · server verified through the internet. Direct Pi connection is not currently verified."
                ConnectionRoute.CHECKING -> "Checking the trusted Pi and internet connection…"
                ConnectionRoute.OFFLINE -> "Neither route could be verified. Saved videos remain available in Profile → My Downloads."
            })
            if (connection.checkedAt > 0) Text("Last checked ${((SystemClock.elapsedRealtime() - connection.checkedAt) / 1000).coerceAtLeast(0)}s ago", style = MaterialTheme.typography.bodySmall)
            Text("Same Wi-Fi alone is not proof of Pi access. Guest-network isolation, a VPN or a different subnet can prevent direct access. Account requests still use the internet; the ring shows verified preferred media connectivity, not every request's route.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton({ ConnectionMonitor.recheck() }, enabled = connection.route != ConnectionRoute.CHECKING) { Text("Recheck") } },
        dismissButton = { TextButton(onDismiss) { Text("Close") } })
}
