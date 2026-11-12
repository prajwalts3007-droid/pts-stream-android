package online.ptsmovies.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    private val scanning = AtomicBoolean(false)
    private val pool = ConnectionPool(6, 15, TimeUnit.SECONDS)

    /** Home routers are effectively always /24; a full sweep of a wider subnet is not practical on a phone. */
    internal fun candidatesFor(host: String): List<String> {
        val parts = host.split('.').map { it.toIntOrNull() ?: return emptyList() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return emptyList()
        val self = parts[3]
        return (1..254).filter { it != self }.map { "${parts[0]}.${parts[1]}.${parts[2]}.$it" }
    }

    internal fun wifiIpv4(context: Context): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        manager.allNetworks.forEach { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@forEach
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@forEach
            val properties = manager.getLinkProperties(network) ?: return@forEach
            val link = properties.linkAddresses.firstOrNull {
                it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress && it.address.hostAddress != null
            } ?: return@forEach
            return link.address.hostAddress
        }
        return null
    }

    /** Runs the probe list with a bounded budget and returns the first verified relay address. */
    internal suspend fun discoverAmong(candidates: List<String>, probe: suspend (String) -> Boolean): String? =
        coroutineScope {
            if (candidates.isEmpty()) return@coroutineScope null
            val found = CompletableDeferred<String>()
            val permits = Semaphore(CONCURRENCY)
            val jobs = candidates.map { address ->
                launch(Dispatchers.IO) {
                    try {
                        if (permits.withPermit { probe(address) }) found.complete(address)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A refused connection is the normal answer for most of the subnet.
                    }
                }
            }
            val finished = launch {
                jobs.joinAll()
                found.complete("")
            }
            val winner = withTimeoutOrNull(SCAN_TIMEOUT_MS) { found.await() }
            finished.cancel()
            jobs.forEach { it.cancel() }
            winner?.takeIf { it.isNotBlank() }
        }

    private fun probeClient(context: Context): OkHttpClient {
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
            .sslSocketFactory(ssl.socketFactory, trust)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .connectionPool(pool)
            .build()
    }

    private suspend fun probeLan(address: String, base: OkHttpClient): Boolean = withContext(Dispatchers.IO) {
        val client = base.newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == HOST) listOf(InetAddress.getByName(address)) else Dns.SYSTEM.lookup(hostname)
            })
            .connectTimeout(350, TimeUnit.MILLISECONDS)
            .readTimeout(900, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        runCatching {
            client.newCall(Request.Builder().url("https://$HOST:$PORT/lan-health").build()).execute().use { response ->
                response.isSuccessful && response.body?.string() == HEALTH
            }
        }.getOrDefault(false)
    }

    internal suspend fun discover(context: Context): String? {
        val host = wifiIpv4(context) ?: return null
        val base = probeClient(context)
        return discoverAmong(candidatesFor(host)) { probeLan(it, base) }
    }

    /** Cheap launch-time check: probe the remembered address first, sweep only when it moved. */
    fun refresh(context: Context) {
        val prefs = NativePreferences(context)
        if (!prefs.lanEnabled() || !onWifi(context)) return
        DurableWork.scope.launch {
            try {
                val base = probeClient(context)
                if (probeLan(prefs.lanAddress(), base)) return@launch
                maybeDiscover(context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Fire-and-forget from a failed media request; never scans on cellular, never scans twice at once. */
    fun maybeDiscover(context: Context) {
        val prefs = NativePreferences(context)
        if (!prefs.lanEnabled() || !onWifi(context)) return
        val now = SystemClock.elapsedRealtime()
        val since = now - prefs.lanScanAt()
        if (since in 0 until SCAN_COOLDOWN_MS) return
        if (!scanning.compareAndSet(false, true)) return
        prefs.lanScanAt(now)
        DurableWork.scope.launch {
            try {
                val found = discover(context) ?: return@launch
                if (found != prefs.lanAddress()) prefs.lanAddress(found)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                scanning.set(false)
            }
        }
    }
}
