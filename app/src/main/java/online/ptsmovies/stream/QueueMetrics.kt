package online.ptsmovies.stream

import org.json.JSONObject

internal data class QueueMetrics(val progress: Float, val speed: Long?, val eta: Long?, val active: Boolean)

internal fun queueMetrics(job: JSONObject): QueueMetrics {
    val stage = job.optString("stage", "queued")
    val progress = (job.optDouble("progress", 0.0) / 100).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val active = stage in setOf("downloading", "compressing", "uploading", "to_pi")
    val transferring = stage in setOf("downloading", "uploading", "to_pi")
    val speed = job.optLong("speed_bps").takeIf { transferring && it > 0 }
    val reportedEta = job.optLong("eta_seconds").takeIf { active && it > 0 }
    // Progress is stage-specific: source bytes apply only to downloading, output bytes to transfers.
    val total = when (stage) {
        "downloading" -> job.optLong("file_size")
        "uploading", "to_pi" -> job.optLong("out_size")
        else -> 0L
    }
    val estimate = if (speed != null && total > 0 && progress > 0f && progress < 1f) {
        (total.toDouble() * (1.0 - progress) / speed).toLong().coerceAtLeast(1)
    } else null
    return QueueMetrics(progress, speed, reportedEta ?: estimate, active)
}
