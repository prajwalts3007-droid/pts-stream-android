package online.ptsmovies.stream

import android.app.Application
import android.content.Context
import okhttp3.*
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class PtsApplication : Application() {
    override fun onCreate() { super.onCreate(); environment = applicationContext }
    companion object { @Volatile var environment: Context? = null; private set }
}

/** Route signed media only. Account cookies/passwords never go to LAN. */
class MediaRouting : Interceptor {
    @Volatile private var cachedAddress = ""
    @Volatile private var local: OkHttpClient? = null
    @Volatile private var checkedAt = 0L
    @Volatile private var healthy = false
    private fun lanClient(context: Context, address: String): OkHttpClient {
        if (address == cachedAddress) local?.let { return it }
        synchronized(this) {
            if (address == cachedAddress) local?.let { return it }
            val certificate = context.resources.openRawResource(R.raw.pts_lan_certificate).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("owned-pi", certificate) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
                .dns { host -> if (host == "media.ptsmovies.online") listOf(InetAddress.getByName(address)) else Dns.SYSTEM.lookup(host) }
                .cookieJar(CookieJar.NO_COOKIES).followRedirects(false)
                .connectTimeout(750, TimeUnit.MILLISECONDS).readTimeout(45, TimeUnit.SECONDS).build().also { local = it; cachedAddress = address; checkedAt = 0; healthy = false }
        }
    }
    private fun tryLocal(request: Request): Response? {
        val context = PtsApplication.environment ?: return null
        val prefs = NativePreferences(context)
        if (!prefs.lanEnabled() || !onWifi(context) || !isPrivateV4(prefs.lanAddress())) return null
        if (request.method !in listOf("GET", "HEAD") || request.url.scheme != "https" || request.url.host != "media.ptsmovies.online" || request.url.encodedPath != "/media" || request.url.queryParameter("sig").isNullOrBlank()) return null
        return try {
            val client = lanClient(context, prefs.lanAddress())
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - checkedAt > 15000 || checkedAt == 0L) {
                healthy = client.newBuilder().readTimeout(750, TimeUnit.MILLISECONDS).build().newCall(Request.Builder().url("https://media.ptsmovies.online:4443/lan-health").build()).execute().use { it.isSuccessful && it.body?.string() == "pts-pi-lan-v1" }
                checkedAt = now
            }
            if (!healthy) return null
            val headers = Headers.Builder()
            listOf("Range", "If-Range", "Accept", "User-Agent").forEach { name -> request.header(name)?.let { headers.add(name, it) } }
            val response = client.newCall(Request.Builder().url(request.url.newBuilder().port(4443).build()).headers(headers.build()).method(request.method, null).build()).execute()
            if (!response.isSuccessful && response.code !in listOf(304, 416)) { response.close(); healthy = false; checkedAt = now; null }
            else response.newBuilder().request(request).build()
        } catch (_: Exception) { healthy = false; checkedAt = android.os.SystemClock.elapsedRealtime(); null }
    }
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        repeat(10) {
            val response = tryLocal(request) ?: chain.proceed(request)
            if (response.code !in listOf(301, 302, 303, 307, 308)) return response
            val target = response.header("Location")?.let { request.url.resolve(it) } ?: return response
            if (request.url.isHttps && !target.isHttps) { response.close(); throw java.io.IOException("Insecure media redirect refused") }
            if (target.host != request.url.host && request.method !in listOf("GET", "HEAD")) { response.close(); throw java.io.IOException("Cross-host credential redirect refused") }
            val next = request.newBuilder().url(target)
            if (target.host != request.url.host) next.removeHeader("Authorization").removeHeader("Cookie")
            if (response.code == 303 || (response.code in listOf(301, 302) && request.method == "POST")) next.get()
            response.close(); request = next.build()
        }
        throw java.io.IOException("Too many redirects")
    }
}
