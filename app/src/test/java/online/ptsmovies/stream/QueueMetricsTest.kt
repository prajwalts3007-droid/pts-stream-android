package online.ptsmovies.stream

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QueueMetricsTest {
    @Test fun reportedEtaWinsOverEstimate() {
        val m = queueMetrics(JSONObject("""{"stage":"downloading","progress":50,"speed_bps":100,"file_size":10000,"eta_seconds":12}"""))
        assertEquals(12L, m.eta); assertEquals(100L, m.speed); assertTrue(m.active)
    }
    @Test fun estimatesUseTheCorrectStageSize() {
        val job = JSONObject("""{"stage":"to_pi","progress":50,"speed_bps":100,"file_size":10000,"out_size":2000}""")
        assertEquals(10L, queueMetrics(job).eta)
        job.put("stage", "downloading")
        assertEquals(50L, queueMetrics(job).eta)
        job.put("stage", "compressing")
        assertNull(queueMetrics(job).eta)
    }
    @Test fun staleEtaAndSpeedDoNotLeakIntoInactiveStages() {
        listOf("queued", "dispatched", "done", "failed").forEach { stage ->
            val m = queueMetrics(JSONObject().put("stage", stage).put("eta_seconds", 123).put("speed_bps", 456))
            assertNull(m.eta); assertNull(m.speed); assertFalse(m.active)
        }
    }
    @Test fun noEstimateWithoutEnoughEvidenceAndProgressIsBounded() {
        val job = JSONObject("""{"stage":"to_pi","progress":-20,"speed_bps":100,"file_size":10000}""")
        assertNull(queueMetrics(job).eta); assertEquals(0f, queueMetrics(job).progress)
        job.put("progress", 200)
        assertEquals(1f, queueMetrics(job).progress); assertNull(queueMetrics(job).eta)
    }
}
