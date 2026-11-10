package online.ptsmovies.stream

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer

/** Offsets are media time, not wall-clock timers. Audio remains the unmodified master clock. */
@UnstableApi
class PlaybackTiming {
    @Volatile var audioDelayMs: Int = 0
    @Volatile var subtitleDelayMs: Int = 0
    @Volatile var subtitlePosition: String = "bottom"
    fun renderPosition(type: Int, positionUs: Long): Long = when (type) {
        C.TRACK_TYPE_VIDEO -> positionUs + audioDelayMs.coerceIn(-2000, 2000) * 1000L
        C.TRACK_TYPE_TEXT -> positionUs + (audioDelayMs.coerceIn(-2000, 2000) - subtitleDelayMs.coerceIn(-10000, 10000)) * 1000L
        else -> positionUs
    }
}

@UnstableApi
class TimedRenderer(private val base: Renderer, private val timing: PlaybackTiming) : Renderer by base {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) = base.render(timing.renderPosition(base.trackType, positionUs), elapsedRealtimeUs)
    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long) = base.getDurationToProgressUs(timing.renderPosition(base.trackType, positionUs), elapsedRealtimeUs)
}

@UnstableApi
fun ptsRenderers(context: Context, timing: PlaybackTiming): androidx.media3.exoplayer.RenderersFactory {
    val defaults = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
    return androidx.media3.exoplayer.RenderersFactory { handler, video, audio, text, metadata ->
        val positioned = object : androidx.media3.exoplayer.text.TextOutput {
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                val line = when (timing.subtitlePosition) { "top" -> .1f; "middle" -> .5f; else -> .9f }
                val cues = cueGroup.cues.map { cue -> if (cue.bitmap != null) cue else cue.buildUpon()
                    .setLine(line, androidx.media3.common.text.Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(if (line < .2f) androidx.media3.common.text.Cue.ANCHOR_TYPE_START else if (line > .8f) androidx.media3.common.text.Cue.ANCHOR_TYPE_END else androidx.media3.common.text.Cue.ANCHOR_TYPE_MIDDLE).build() }
                text.onCues(androidx.media3.common.text.CueGroup(cues, cueGroup.presentationTimeUs))
            }
        }
        defaults.createRenderers(handler, video, audio, positioned, metadata).map { renderer ->
            if (renderer.trackType in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT)) TimedRenderer(renderer, timing) else renderer
        }.toTypedArray()
    }
}

fun nextEpisode(title: Title, active: Episode?, savedKeys: Set<String>, downloadedOnly: Boolean): Episode? =
    title.episodes.sortedWith(compareBy({ it.season }, { it.episode })).firstOrNull {
        active != null && (it.season > active.season || (it.season == active.season && it.episode > active.episode)) &&
            (!downloadedOnly || "${title.id}_${it.id}" in savedKeys)
    }
