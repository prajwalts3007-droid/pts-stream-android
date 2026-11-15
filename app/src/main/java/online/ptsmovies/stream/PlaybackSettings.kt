package online.ptsmovies.stream

import android.content.Context
import org.json.JSONObject
import java.util.UUID

internal data class SettingsSnapshot(val value: String, val revision: String, val pending: Boolean)

/** All instances for an owner share a lock. Network I/O never holds it. */
internal class PlaybackSettings(context: Context, private val owner: Long) {
    private val prefs = context.getSharedPreferences("playback_$owner", Context.MODE_PRIVATE)
    private val lock get() = locks.getOrPut(owner) { Any() }
    fun snapshot() = synchronized(lock) { SettingsSnapshot(prefs.getString("value", "{}") ?: "{}", prefs.getString("revision", "legacy") ?: "legacy", prefs.getBoolean("pending", false)) }
    fun edit(changes: JSONObject): JSONObject = synchronized(lock) {
        val next = JSONObject(snapshot().value)
        changes.keys().forEach { next.put(it, changes.get(it)) }
        prefs.edit().putString("value", next.toString()).putString("revision", UUID.randomUUID().toString()).putBoolean("pending", true).apply()
        next
    }
    fun acknowledge(sent: SettingsSnapshot): Boolean = synchronized(lock) {
        if (snapshot().revision != sent.revision) false else { prefs.edit().putBoolean("pending", false).apply(); true }
    }
    fun acceptRemote(expected: SettingsSnapshot, value: JSONObject): Boolean = synchronized(lock) {
        val current = snapshot()
        if (current.pending || current.revision != expected.revision) false else {
            prefs.edit().putString("value", value.toString()).putBoolean("synced", true).apply(); true
        }
    }
    fun status(): String = synchronized(lock) {
        if (snapshot().pending) "Sync pending · saved on device" else if (prefs.getBoolean("synced", false)) "Synced" else "Saved on device"
    }
    companion object { private val locks = java.util.concurrent.ConcurrentHashMap<Long, Any>() }
}
