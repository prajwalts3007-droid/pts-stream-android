package online.ptsmovies.stream

import org.junit.Assert.*
import org.junit.Test

class IngestSearchTest {
    @Test fun retriesTransientReadFailuresOnly() {
        assertTrue(retryableIngestSearch(java.io.IOException("connection interrupted")))
        listOf(408, 500, 502, 503, 504).forEach { assertTrue(retryableIngestSearch(ApiException(it, "fixture"))) }
        listOf(400, 401, 403, 404, 409, 429).forEach { assertFalse(retryableIngestSearch(ApiException(it, "fixture"))) }
        assertFalse(retryableIngestSearch(org.json.JSONException("fixture")))
        assertFalse(retryableIngestSearch(kotlinx.coroutines.CancellationException("cancelled")))
    }
}
