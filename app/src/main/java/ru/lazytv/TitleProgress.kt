package ru.lazytv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a title was left, kept per title (not per stream): the stream address belongs to one device, the title does not.
 * This list travels with the sync, so a film started on the phone continues from the same minute on the set-top box,
 * whatever source the box finds for it. A series keeps the season and the episode number (read from the episode name,
 * see [episodeOf]), so the box opens the same episode in whatever source it finds.
 */
object TitleProgress {
    private const val KEY = "title_progress"
    private const val LIMIT = 50

    /** Season and episode numbers out of an episode name ("Сезон 2 • Серия 5", "5 серия", "S02E05"); a missing season is the first one. */
    fun episodeOf(name: String): Pair<Int, Int>? {
        val season = Regex("(\\d+)\\s*сезон|сезон\\s*(\\d+)|[sс](\\d+)\\s*[eе]\\d+", RegexOption.IGNORE_CASE).find(name)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 1
        val episode = Regex("(\\d+)\\s*сери|сери[яию]\\s*(\\d+)|эпизод\\s*(\\d+)|[sс]\\d+\\s*[eе](\\d+)", RegexOption.IGNORE_CASE).find(name)
            ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: return null
        return season to episode
    }

    private fun read(context: Context): List<JSONObject> = runCatching {
        val array = JSONArray(Settings.string(context, KEY, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun write(context: Context, list: List<JSONObject>) {
        val array = JSONArray()
        list.take(LIMIT).forEach { array.put(it) }
        Settings.setString(context, KEY, array.toString())
    }

    private fun same(o: JSONObject, tv: Boolean, id: Long) = o.optLong("id") == id && o.optBoolean("tv") == tv

    /** Raw records for the sync. */
    fun all(context: Context): List<JSONObject> = read(context)

    /** Replaces the list with the merged one from the sync. */
    fun replace(context: Context, list: List<JSONObject>) = write(context, list)

    /** A film (or an episode with its [season] and [episode]) left at [positionMs] of [durationMs]; the newest record first. */
    @Synchronized
    fun put(
        context: Context, tv: Boolean, id: Long, name: String, poster: String?, year: String, positionMs: Long, durationMs: Long,
        season: Int = 0, episode: Int = 0,
    ) {
        val record = JSONObject().put("id", id).put("tv", tv).put("name", name).put("poster", poster.orEmpty())
            .put("year", year).put("pos", positionMs).put("dur", durationMs).put("at", System.currentTimeMillis())
        if (tv) record.put("season", season).put("episode", episode)
        write(context, listOf(record) + read(context).filterNot { same(it, tv, id) })
    }

    @Synchronized
    fun remove(context: Context, tv: Boolean, id: Long) {
        write(context, read(context).filterNot { same(it, tv, id) })
    }

    fun get(context: Context, tv: Boolean, id: Long): JSONObject? = read(context).firstOrNull { same(it, tv, id) }

    /** The place of a film in milliseconds, or 0 when nothing is known. */
    fun position(context: Context, id: Long): Long = get(context, false, id)?.optLong("pos") ?: 0L

    /** The place in the episode [season]x[episode] of a series; 0 when the record is about another episode. */
    fun position(context: Context, id: Long, season: Int, episode: Int): Long {
        val r = get(context, true, id) ?: return 0L
        return if (r.optInt("season") == season && r.optInt("episode") == episode) r.optLong("pos") else 0L
    }
}
