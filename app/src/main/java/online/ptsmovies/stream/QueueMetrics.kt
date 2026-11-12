package online.ptsmovies.stream

import org.json.JSONObject

internal data class QueueMetrics(val progress: Float, val speed: Long?, val eta: Long?, val active: Boolean)

internal fun queueMetrics(job: JSONObject): QueueMetrics {
    val stage = job.optString("stage", "queued")
    val progress = (job.optDouble("progress", 0.0) / 100).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val active = stage in setOf("downloading", "compressing", "uploading", "to_pi")
    val transferring = stage in setOf("downloading", "uploading", "to_pi")
    val speed = job.optLong("speed_bps").takeIf { transferring && it > 0 }
    // Sizes/progress can describe different pipeline stages; never invent an ETA from them.
    val eta = job.optLong("eta_seconds").takeIf { active && it > 0 }
    return QueueMetrics(progress, speed, eta, active)
}
