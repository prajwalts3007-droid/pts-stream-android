package online.ptsmovies.stream

import android.content.Context

/** Synthetic loopback test owners only. Never invoked by production app code. */
internal fun clearFixtureDatabase(context: Context, owner: Long) {
    require(owner in 90000L..90100L)
    val db = StateDatabase.get(context)
    listOf("catalogue", "download_queue", "offline_index", "episode_records", "progress_pending").forEach { db.remove(owner, it) }
    db.writableDatabase.delete("titles", "owner=?", arrayOf(owner.toString()))
    db.writableDatabase.delete("resume", "owner=?", arrayOf(owner.toString()))
    EpisodeProgress.trimInactive(null)
}
