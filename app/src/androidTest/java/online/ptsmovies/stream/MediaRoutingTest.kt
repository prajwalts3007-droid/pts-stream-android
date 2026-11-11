package online.ptsmovies.stream

import okhttp3.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** TLS fixtures only; never uses the production Pi key or credentials. */
class MediaRoutingTest {
    private lateinit var local: MockWebServer
    private lateinit var tunnel: MockWebServer
    private lateinit var trust: HandshakeCertificates
    private lateinit var baseClient: OkHttpClient
    private val signed = "/media?p=generated-fixture&e=123&a=&s=&sig=fixture"

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("synthetic-pts")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("media.ptsmovies.online").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        local = MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); start() }
        tunnel = MockWebServer().apply { useHttps(tls.sslSocketFactory(), false); start() }
        baseClient = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) })
            .followRedirects(false).readTimeout(3, TimeUnit.SECONDS).build()
    }
    @After fun close() { local.shutdown(); tunnel.shutdown() }
    private fun request(path: String = signed) = Request.Builder()
        .url(tunnel.url(path).newBuilder().host("media.ptsmovies.online").build())
        .header("Cookie", "session=must-not-reach-lan").header("Authorization", "Bearer private")
        .header("X-CSRF-Token", "private-csrf").header("Range", "bytes=1-4").build()
    private fun client(localClient: OkHttpClient = baseClient) = baseClient.newBuilder()
        .addInterceptor(MediaRouting { LanEndpoint(localClient, local.url("/")) }).build()

    @Test fun boundedComparisonMeasuresActualLanAndCanBypassItWithoutChangingPreferences() = kotlinx.coroutines.runBlocking {
        local.enqueue(MockResponse().setBody("pts-pi-lan-v1"))
        local.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-3/4").setBody("test"))
        tunnel.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-3/4").setBody("test"))
        val http = client(); val url = request().url.toString()
        val lan = probeMediaRoute(http, url, true); val internet = probeMediaRoute(http, url, false)
        assertEquals(MediaRoute.LAN, lan.route); assertEquals(MediaRoute.TUNNEL, internet.route)
        assertEquals(4L, lan.bytes); assertEquals(4L, internet.bytes)
        assertEquals(2, local.requestCount); assertEquals(1, tunnel.requestCount)
        local.takeRequest(); val media = local.takeRequest()
        assertEquals("bytes=0-1048575", media.getHeader("Range")); assertEquals(signed, media.path)
        assertEquals(signed, tunnel.takeRequest().path)
    }

    @Test fun boundedComparisonRefusesAFullLargeFileWhenRangeIsIgnored() = kotlinx.coroutines.runBlocking {
        tunnel.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024)))
        try { probeMediaRoute(client(), request().url.toString(), false); fail("Unbounded sample accepted") }
        catch (_: java.io.IOException) { }
        assertEquals(0, local.requestCount)
    }

    @Test fun signedRedirectUsesVerifiedLanWithExactQueryAndRangeButNoAccountCredentials() {
        tunnel.enqueue(MockResponse().setResponseCode(302).setHeader("Location", tunnel.url(signed).newBuilder().host("media.ptsmovies.online").build()))
        local.enqueue(MockResponse().setBody("pts-pi-lan-v1"))
        local.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 1-4/6").setBody("bcde"))
        client().newCall(request("/start")).execute().use { response ->
            assertEquals(206, response.code); assertEquals("bcde", response.body!!.string())
            assertEquals("bytes 1-4/6", response.header("Content-Range"))
        }
        assertEquals("/lan-health", local.takeRequest(2, TimeUnit.SECONDS)!!.path)
        val media = local.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(signed, media.path); assertEquals("bytes=1-4", media.getHeader("Range"))
        for (header in listOf("Cookie", "Authorization", "X-CSRF-Token")) assertNull(media.getHeader(header))
        assertEquals(1, tunnel.requestCount)
    }

    @Test fun unavailableLanFallsBackToTunnelWithoutChangingSignedUrl() {
        local.enqueue(MockResponse().setResponseCode(503))
        tunnel.enqueue(MockResponse().setBody("tunnel-fixture"))
        client().newCall(request()).execute().use { assertEquals("tunnel-fixture", it.body!!.string()) }
        assertEquals(signed, tunnel.takeRequest(2, TimeUnit.SECONDS)!!.path)
    }

    @Test fun wrongLanCertificateIsNotTrustedAndNeverReceivesSignedMedia() {
        val other = HeldCertificate.Builder().commonName("wrong-device").addSubjectAlternativeName("localhost").build()
        val wrongTrust = HandshakeCertificates.Builder().addTrustedCertificate(other.certificate).build()
        val untrusted = baseClient.newBuilder().sslSocketFactory(wrongTrust.sslSocketFactory(), wrongTrust.trustManager).build()
        tunnel.enqueue(MockResponse().setBody("safe-tunnel"))
        client(untrusted).newCall(request()).execute().use { assertEquals("safe-tunnel", it.body!!.string()) }
        assertEquals(0, local.requestCount)
    }

    @Test fun unsignedMediaAndAccountApiNeverProbeLan() {
        repeat(2) { tunnel.enqueue(MockResponse().setBody("not-routed")) }
        val client = client()
        for (path in listOf("/media?p=unsigned", "/api/v1/auth/me")) client.newCall(request(path)).execute().close()
        assertEquals(0, local.requestCount); assertEquals(2, tunnel.requestCount)
    }

    @Test fun failedLocalMediaResponseFallsBackToTheSameTunnelRequest() {
        local.enqueue(MockResponse().setBody("pts-pi-lan-v1"))
        local.enqueue(MockResponse().setResponseCode(503))
        tunnel.enqueue(MockResponse().setBody("fallback"))
        client().newCall(request()).execute().use { assertEquals("fallback", it.body!!.string()) }
        assertEquals(signed, tunnel.takeRequest(2, TimeUnit.SECONDS)!!.path)
    }

    @Test fun telemetryCountsActualLocalPayloadWithoutCountingHealthOrInferringWifi() {
        val telemetry = MediaTelemetry()
        local.enqueue(MockResponse().setBody("pts-pi-lan-v1"))
        local.enqueue(MockResponse().setResponseCode(206).setBody("local-payload"))
        client().newCall(request().newBuilder().tag(MediaTelemetry::class.java, telemetry).build()).execute().use { assertEquals("local-payload", it.body!!.string()) }
        val snapshot = telemetry.snapshot()
        assertEquals(MediaRoute.LAN, snapshot.route)
        assertEquals(13L, snapshot.bytes[MediaRoute.LAN]); assertEquals(13L, snapshot.total)
        assertEquals(0, tunnel.requestCount)
    }

    @Test fun telemetryRecordsTunnelAfterLocalRejectionNotLocalBandwidth() {
        val telemetry = MediaTelemetry()
        local.enqueue(MockResponse().setBody("pts-pi-lan-v1"))
        local.enqueue(MockResponse().setResponseCode(403).setBody("not-media"))
        tunnel.enqueue(MockResponse().setBody("internet-payload"))
        client().newCall(request().newBuilder().tag(MediaTelemetry::class.java, telemetry).build()).execute().use { it.body!!.string() }
        val snapshot = telemetry.snapshot()
        assertEquals(MediaRoute.TUNNEL, snapshot.route)
        assertEquals(0L, snapshot.bytes[MediaRoute.LAN]); assertEquals(16L, snapshot.bytes[MediaRoute.TUNNEL])
    }
}
