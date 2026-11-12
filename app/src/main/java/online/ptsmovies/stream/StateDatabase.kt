package online.ptsmovies.stream

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.*
import org.json.JSONObject

/** Large state never shares the small UI-preferences XML or its lifecycle flush. */
internal class StateDatabase private constructor(context: Context) : SQLiteOpenHelper(context, "native_state.db", null, 1) {
    private val documents = java.util.concurrent.ConcurrentHashMap<String, String>()
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE documents (owner INTEGER NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(owner,name))")
        db.execSQL("CREATE TABLE titles (owner INTEGER NOT NULL, id INTEGER NOT NULL, value TEXT NOT NULL, PRIMARY KEY(owner,id))")
        db.execSQL("CREATE TABLE resume (owner INTEGER NOT NULL, id INTEGER NOT NULL, episode INTEGER NOT NULL, position INTEGER NOT NULL, duration INTEGER NOT NULL, cleared INTEGER NOT NULL, changed INTEGER NOT NULL, PRIMARY KEY(owner,id))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Explicit migration required") }
    fun read(owner: Long, name: String): String? = documents["$owner/$name"] ?: synchronized(this) {
        documents["$owner/$name"] ?: readableDatabase.rawQuery("SELECT value FROM documents WHERE owner=? AND name=?", arrayOf(owner.toString(), name)).use { if (it.moveToFirst()) it.getString(0).also { value -> documents["$owner/$name"] = value } else null }
    }
    @Synchronized fun write(owner: Long, name: String, value: String) {
        writableDatabase.insertWithOnConflict("documents", null, ContentValues().apply { put("owner", owner); put("name", name); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Could not persist device state" } }
        documents["$owner/$name"] = value
    }
    @Synchronized fun remove(owner: Long, name: String) { writableDatabase.delete("documents", "owner=? AND name=?", arrayOf(owner.toString(), name)); documents.remove("$owner/$name") }
    fun trim(activeOwner: Long?) { documents.keys.removeIf { key -> !key.startsWith("$activeOwner/") || key.endsWith("/download_queue") || key.endsWith("/offline_index") || key.endsWith("/catalogue") } }
    fun title(owner: Long, id: Long): Title? = readableDatabase.rawQuery("SELECT value FROM titles WHERE owner=? AND id=?", arrayOf(owner.toString(), id.toString())).use { if (it.moveToFirst()) parseTitle(JSONObject(it.getString(0))) else null }
    fun resume(owner: Long, id: Long, episode: Long, position: Long, duration: Long, clear: Boolean) {
        writableDatabase.insertWithOnConflict("resume", null, ContentValues().apply { put("owner", owner); put("id", id); put("episode", episode); put("position", position); put("duration", duration); put("cleared", if (clear) 1 else 0); put("changed", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    fun overlay(owner: Long, library: Library): Library {
        val progress = library.progress.toMutableMap()
        readableDatabase.rawQuery("SELECT id,episode,position,duration,cleared FROM resume WHERE owner=?", arrayOf(owner.toString())).use { rows -> while (rows.moveToNext()) { if (rows.getInt(4) == 1) progress.remove(rows.getLong(0)) else progress[rows.getLong(0)] = Resume(rows.getLong(1), rows.getLong(2), rows.getLong(3)) } }
        return library.copy(progress = progress)
    }
    fun titles(owner: Long, titles: List<Title>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            titles.distinctBy { it.id }.forEach { title ->
                db.insertWithOnConflict("titles", null, ContentValues().apply { put("owner", owner); put("id", title.id); put("value", title.toJson().toString()) }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun catalogue(owner: Long, library: Library, observedAt: Long = 0) {
        val rows = library.titles.distinctBy { it.id }.map { it.id to it.toJson().toString() }
        val value = library.copy(titles = emptyList()).toJson().put("title_ids", org.json.JSONArray(library.titles.map { it.id })).toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { (id, json) -> check(db.insertWithOnConflict("titles", null, ContentValues().apply { put("owner", owner); put("id", id); put("value", json) }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) }
            check(db.insertWithOnConflict("documents", null, ContentValues().apply { put("owner", owner); put("name", "catalogue"); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            if (observedAt > 0) db.delete("resume", "owner=? AND changed<=?", arrayOf(owner.toString(), observedAt.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        documents["$owner/catalogue"] = value
    }
    /** Idempotent migration: legacy source is removed only AFTER a committed destination. */
    fun migrate(owner: Long, name: String, legacy: () -> String?, clear: () -> Unit): String? {
        read(owner, name)?.let { clear(); return it }
        val value = legacy() ?: return null
        write(owner, name, value)
        clear()
        return value
    }
    companion object {
        @Volatile private var instance: StateDatabase? = null
        fun get(context: Context): StateDatabase = instance ?: synchronized(this) { instance ?: StateDatabase(context.applicationContext).also { instance = it } }
    }
}

/** Writes survive Activity destruction; callers never block Android stop/start on preference flushes. */
internal object DurableWork {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
}

internal fun compactQueue(tasks: List<DownloadTask>): String {
    val titles = JSONObject()
    tasks.distinctBy { it.title.id }.forEach { titles.put(it.title.id.toString(), it.title.toJson()) }
    val rows = org.json.JSONArray(tasks.map { task -> JSONObject().put("title_id", task.title.id).put("episode_id", task.episode?.id ?: 0)
        .put("status", task.status).put("bytes", task.bytes).put("total", task.total).put("error", task.error).put("destination", task.destination) })
    return JSONObject().put("version", 1).put("titles", titles).put("tasks", rows).toString()
}

internal fun parseQueue(source: String): List<DownloadTask> {
    val legacy = source.trimStart().startsWith("[")
    val root = if (legacy) null else JSONObject(source)
    require(legacy || root!!.getInt("version") == 1) { "Unsupported download queue version" }
    val titles = mutableMapOf<Long, Title>()
    root?.getJSONObject("titles")?.let { objectTitles -> objectTitles.keys().forEach { key -> titles[key.toLong()] = parseTitle(objectTitles.getJSONObject(key)) } }
    val rows = if (legacy) org.json.JSONArray(source) else root!!.getJSONArray("tasks")
    return (0 until rows.length()).map { i ->
        val row = rows.getJSONObject(i)
        val title = if (legacy) parseTitle(row.getJSONObject("title")).let { titles.getOrPut(it.id) { it } } else titles.getValue(row.getLong("title_id"))
        val episode = if (legacy) row.optJSONObject("episode")?.let(::parseEpisode) else row.optLong("episode_id").takeIf { it > 0 }?.let { id -> title.episodes.firstOrNull { it.id == id } ?: error("Episode missing from queue metadata") }
        DownloadTask(title, episode, row.optString("status"), row.optLong("bytes"), row.optLong("total"), error = row.optString("error"), destination = row.optString("destination").takeIf { it.isNotBlank() && it != "null" })
    }
}
