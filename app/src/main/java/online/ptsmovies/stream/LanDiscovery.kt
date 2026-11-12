package online.ptsmovies.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Finds the Pi relay when DHCP hands it a new LAN address after a reboot.
 *
 * Only runs while "Prefer local Pi" is enabled on Wi-Fi. The probe uses the
 * same pinned certificate and hostname as normal LAN playback, so a random
 * host answering on port 4443 can never be adopted. No account data is sent.
 */
internal object LanDiscovery {
    private const val HOST = "media.ptsmovies.online"
    private const val PORT = 4443
    private const val HEALTH = "pts-pi-lan-v1"
    private const val SCAN_COOLDOWN_MS = 120_000L
    private const val SCAN_TIMEOUT_MS = 9_000L
    private const val CONCURRENCY = 24

    // Discovery must not occupy the single-thread durable database/persistence scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanning = false
    private var lastScanAt = -SCAN_COOLDOWN_MS
    private var lastNetwork: Network? = null
    private val pool = ConnectionPool(6, 15, TimeUnit.SECONDS)

    /** Respect smaller subnets; cap wider ones to this /24 (at most 254 probes). */
    internal fun candidatesFor(host: String, prefixLength: Int = 24): List<String> {
        if (!isPrivateV4(host) || prefixLength !in 1..32) return emptyList()
        val parts = host.split('.').map { it.toIntOrNull() ?: return emptyList() }
        val self = parts[3]
        val width = 1 shl (32 - prefixLength.coerceAtLeast(24))
        val start = self / width * width
        val usable = if (width <= 2) start until start + width else start + 1 until start + width - 1
        return usable.filter { it != self }.map { "${parts[0]}.${parts[1]}.${parts[2]}.$it" }
    }

    private data class WifiSubnet(val network: Network, val address: String, val prefix: Int)

    private fun wifiSubnet(context: Context): WifiSubnet? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val caps = manager.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val link = manager.getLinkProperties(network)?.linkAddresses?.firstOrNull {
            it.address is Inet4Address && isPrivateV4(it.address.hostAddress.orEmpty())
        } ?: return null
        return WifiSubnet(network, link.address.hostAddress ?: return null, link.prefixLength)
    }

    /** Runs the probe list with a bounded budget and returns the first verified relay address. */
    internal suspend fun discoverAmong(candidates: List<String>, probe: suspend (String) -> Boolean): String? =
        coroutineScope {
            if (candidates.isEmpty()) return@coroutineScope null
            val found = CompletableDeferred<String?>()
            val next = AtomicInteger()
            val jobs = List(minOf(CONCURRENCY, candidates.size)) {
                launch(Dispatchers.IO) {
                    while (!found.isCompleted) {
                        val index = next.getAndIncrement()
                        if (index >= candidates.size) break
                        val address = candidates[index]
                        try {
                            if (probe(address)) found.complete(address)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // A refused connection is the normal answer for most of the subnet.
                        }
                    }
                }
            }
            val finished = launch {
                jobs.joinAll()
                found.complete(null)
            }
            try { withTimeoutOrNull(SCAN_TIMEOUT_MS) { found.await() } }
            finally { finished.cancel(); jobs.forEach { it.cancel() } }
        }

    private fun probeClient(context: Context, network: Network): OkHttpClient {
        val certificate = context.resources.openRawResource(R.raw.pts_lan_certificate).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("owned-pi", certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }.trustManagers.filterIsInstance<X509TrustManager>().single()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .sslSocketFactory(ssl.socketFactory, trust)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .connectionPool(pool)
            .build()
    }

    private suspend fun probeLan(address: String, base: OkHttpClient): Boolean {
        val client = base.newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == HOST) listOf(InetAddress.getByName(address)) else Dns.SYSTEM.lookup(hostname)
            })
            .connectTimeout(350, TimeUnit.MILLISECONDS)
            .readTimeout(900, TimeUnit.MILLISECONDS)
            .callTimeout(1200, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url("https://$HOST:$PORT/lan-health").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resume(false) }
                override fun onResponse(call: Call, response: Response) {
                    val valid = runCatching { response.use {
                        it.isSuccessful && it.body?.byteStream()?.use { input -> input.readBytesBounded(32).toString(Charsets.UTF_8) } == HEALTH
                    } }.getOrDefault(false)
                    if (continuation.isActive) continuation.resume(valid)
                }
            })
        }
    }

    /** Probe the remembered address first; sweep only if it no longer verifies. */
    fun refresh(context: Context) = maybeDiscover(context)

    /** Fire-and-forget from a failed media request; never scans on cellular, never scans twice at once. */
    @Synchronized fun maybeDiscover(context: Context) {
        val prefs = NativePreferences(context)
        if (!prefs.lanEnabled()) return
        val wifi = wifiSubnet(context) ?: return
        val now = SystemClock.elapsedRealtime()
        if (scanning || (wifi.network == lastNetwork && now - lastScanAt in 0 until SCAN_COOLDOWN_MS)) return
        scanning = true; lastScanAt = now; lastNetwork = wifi.network
        val originalAddress = prefs.lanAddress()
        val application = context.applicationContext
        scope.launch {
            try {
                val base = probeClient(application, wifi.network)
                if (probeLan(originalAddress, base)) return@launch
                val found = discoverAmong(candidatesFor(wifi.address, wifi.prefix)) { probeLan(it, base) } ?: return@launch
                // Do not overwrite a manual edit, a disabled preference, or another network's hint.
                if (prefs.lanEnabled() && prefs.lanAddress() == originalAddress && wifiSubnet(application)?.network == wifi.network) prefs.lanAddress(found)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                synchronized(this@LanDiscovery) { scanning = false }
            }
        }
    }
}
