package ru.lazytv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a film was left, kept per title (not per stream): the stream address belongs to one device, the title does not.
 * This list travels with the sync, so a film started on the phone continues from the same minute on the set-top box,
 * whatever source the box finds for it. Films only: an episode has no key that means the same in every source.
 */
object TitleProgress {
    private const val KEY = "title_progress"
    private const val LIMIT = 50

    private fun read(context: Context): List<JSONObject> = runCatching {
        val array = JSONArray(Settings.string(context, KEY, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun write(context: Context, list: List<JSONObject>) {
        val array = JSONArray()
        list.take(LIMIT).forEach { array.put(it) }
        Settings.setString(context, KEY, array.toString())
    }

    /** Raw records for the sync. */
    fun all(context: Context): List<JSONObject> = read(context)

    /** Replaces the list with the merged one from the sync. */
    fun replace(context: Context, list: List<JSONObject>) = write(context, list)

    @Synchronized
    fun put(context: Context, id: Long, name: String, poster: String?, year: String, positionMs: Long, durationMs: Long) {
        val record = JSONObject().put("id", id).put("tv", false).put("name", name).put("poster", poster.orEmpty())
            .put("year", year).put("pos", positionMs).put("dur", durationMs).put("at", System.currentTimeMillis())
        write(context, listOf(record) + read(context).filter { it.optLong("id") != id })
    }

    @Synchronized
    fun remove(context: Context, id: Long) {
        val rest = read(context).filter { it.optLong("id") != id }
        write(context, rest)
    }

    /** The place in milliseconds, or 0 when nothing is known. */
    fun position(context: Context, id: Long): Long =
        read(context).firstOrNull { it.optLong("id") == id }?.optLong("pos") ?: 0L
}
