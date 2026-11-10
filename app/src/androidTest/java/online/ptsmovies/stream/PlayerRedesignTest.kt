@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package online.ptsmovies.stream

import androidx.media3.common.C
import androidx.media3.exoplayer.Renderer
import org.junit.Assert.*
import org.junit.Test

class PlayerRedesignTest {
    @Test fun timingMovesVideoAndSubtitlesAgainstUnmodifiedAudioClock() {
        val timing = PlaybackTiming().apply { audioDelayMs = 500; subtitleDelayMs = 200 }
        assertEquals(1_500_000L, timing.renderPosition(C.TRACK_TYPE_VIDEO, 1_000_000))
        assertEquals(1_300_000L, timing.renderPosition(C.TRACK_TYPE_TEXT, 1_000_000))
        assertEquals(1_000_000L, timing.renderPosition(C.TRACK_TYPE_AUDIO, 1_000_000))
        timing.audioDelayMs = -500; timing.subtitleDelayMs = -200
        assertEquals(500_000L, timing.renderPosition(C.TRACK_TYPE_VIDEO, 1_000_000))
        assertEquals(700_000L, timing.renderPosition(C.TRACK_TYPE_TEXT, 1_000_000))
    }

    @Test fun timedRendererForwardsTheAdjustedRenderPositionAndScheduling() {
        val received = mutableListOf<Long>()
        val base = java.lang.reflect.Proxy.newProxyInstance(Renderer::class.java.classLoader, arrayOf(Renderer::class.java)) { _, method, args ->
            when (method.name) {
                "getTrackType" -> C.TRACK_TYPE_VIDEO
                "render" -> { received += args!![0] as Long; null }
                "getDurationToProgressUs" -> { received += args!![0] as Long; 10000L }
                else -> null
            }
        } as Renderer
        val renderer = TimedRenderer(base, PlaybackTiming().apply { audioDelayMs = 100 })
        renderer.render(1000000, 0); assertEquals(10000L, renderer.getDurationToProgressUs(1000000, 0))
        assertEquals(listOf(1100000L, 1100000L), received)
    }

    @Test fun controlsNeverExpireWhenPausedSeekingBufferingOrInMenus() {
        assertFalse(controlsShouldHide(false, false, false, false, 12))
        assertFalse(controlsShouldHide(true, true, false, false, 12))
        assertFalse(controlsShouldHide(true, false, true, false, 12))
        assertFalse(controlsShouldHide(true, false, false, true, 12))
        assertFalse(controlsShouldHide(true, false, false, false, 0))
        assertTrue(controlsShouldHide(true, false, false, false, 12))
        assertEquals(12, PlayerAppearance().controlsSeconds)
    }

    @Test fun downloadedOnlyEpisodeNavigationSkipsUnsavedEpisodesAcrossSeasons() {
        val first = Episode(1, 1, 1, "First", 60.0, "mp4")
        val second = first.copy(id = 2, episode = 2)
        val third = first.copy(id = 3, season = 2)
        val title = Title(90001, "series", "Generated", 2026, "", "", emptyList(), listOf(first, second, third), "hevc", "mp4")
        assertEquals(second, nextEpisode(title, first, emptySet(), false))
        assertEquals(third, nextEpisode(title, first, setOf("90001_3"), true))
        assertNull(nextEpisode(title, first, emptySet(), true))
    }
}
