package online.ptsmovies.stream

import androidx.media3.common.Player

fun shouldAutoEnterPip(screenPlaying: Boolean, playWhenReady: Boolean, state: Int, failed: Boolean) =
    screenPlaying && playWhenReady && !failed && state in listOf(Player.STATE_READY, Player.STATE_BUFFERING)
