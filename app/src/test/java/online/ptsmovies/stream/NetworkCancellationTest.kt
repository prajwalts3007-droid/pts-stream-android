package online.ptsmovies.stream

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class NetworkCancellationTest {
    @Test fun cancellationClosesSocketWhileReadingSlowJsonBody() = runBlocking {
        val server = MockWebServer(); server.start()
        val client = OkHttpClient()
        try {
            server.enqueue(MockResponse().setBody("{\"value\":\"${"x".repeat(1000)}\"}").throttleBody(1, 1, TimeUnit.SECONDS))
            val call = client.newCall(Request.Builder().url(server.url("/fixture")).build())
            val job = launch(start = CoroutineStart.UNDISPATCHED) { call.awaitJson() }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
            withTimeout(3000) { while (client.dispatcher.runningCallsCount() > 0) delay(10) }
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown() }
    }
    @Test fun authorizationFailuresRemainExplicit() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403).setBody("{\"error\":\"fixture\"}"))
            try { OkHttpClient().newCall(Request.Builder().url(server.url("/fixture")).build()).awaitJson(); fail("Expected rejection") }
            catch (e: ApiException) { assertEquals(403, e.status) }
        } finally { server.shutdown() }
    }
    @Test fun successfulJsonContractIsUnchanged() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("{\"owner\":17}"))
            assertEquals(17, OkHttpClient().newCall(Request.Builder().url(server.url("/fixture")).build()).awaitJson().getInt("owner"))
        } finally { server.shutdown() }
    }
}
