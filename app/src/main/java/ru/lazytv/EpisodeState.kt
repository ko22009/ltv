package ru.lazytv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One episode the user put into the bookmarks: enough to reopen its series and find the episode again. */
data class FavoriteEpisode(
    val sourceId: String, val source: String, val ref: String,
    val seriesTitle: String, val note: String, val alt: String, val description: String,
    val episodeTitle: String, val index: Int,
) {
    fun variant() = Variant(source, sourceId, seriesTitle, note, ref, alt, description)
}

/**
 * Watched marks and favourite episodes kept on the device in their own preferences (not in the settings,
 * so they do not make the home screen redraw and are cleared separately).
 */
object EpisodeState {
    private const val PREFS = "episodes"
    private const val WATCHED = "watched"
    private const val FAVORITES = "favorite_episodes"
    private const val REVISION = "revision"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    /** Changes whenever a mark or a favourite episode changes; the home screen uses it to redraw the rows. */
    fun revision(context: Context): String = prefs(context).getString(REVISION, "0") ?: "0"

    /** The stable key of a watched episode: the series page and the episode title (the order may change). */
    fun key(sourceId: String, ref: String, title: String) = "$sourceId|$ref|$title"

    fun isWatched(context: Context, key: String): Boolean = prefs(context).getStringSet(WATCHED, emptySet())?.contains(key) == true

    fun setWatched(context: Context, key: String, watched: Boolean) {
        val current = (prefs(context).getStringSet(WATCHED, emptySet()) ?: emptySet()).toMutableSet()
        if (watched) current.add(key) else current.remove(key)
        prefs(context).edit()
            .putStringSet(WATCHED, current)
            .putString(REVISION, System.currentTimeMillis().toString())
            .apply()
    }

    fun favorites(context: Context): List<FavoriteEpisode> {
        val array = runCatching { JSONArray(prefs(context).getString(FAVORITES, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                FavoriteEpisode(
                    o.optString("sourceId"), o.optString("source"), o.optString("ref"),
                    o.optString("seriesTitle"), o.optString("note"), o.optString("alt"),
                    o.optString("description"), o.optString("episodeTitle"), o.optInt("index"),
                )
            }.getOrNull()
        }
    }

    fun isFavorite(context: Context, sourceId: String, ref: String, index: Int, episodeTitle: String): Boolean =
        favorites(context).any { it.sourceId == sourceId && it.ref == ref && it.index == index && it.episodeTitle == episodeTitle }

    /** Adds the episode to the bookmarks or removes it; returns true when it was added. */
    fun toggleFavorite(context: Context, episode: FavoriteEpisode): Boolean {
        val list = favorites(context).toMutableList()
        val same = { f: FavoriteEpisode ->
            f.sourceId == episode.sourceId && f.ref == episode.ref && f.index == episode.index && f.episodeTitle == episode.episodeTitle
        }
        val existing = list.firstOrNull(same)
        if (existing != null) {
            list.remove(existing)
            save(context, list)
            return false
        }
        list.add(0, episode)
        save(context, list)
        return true
    }

    private fun save(context: Context, list: List<FavoriteEpisode>) {
        val array = JSONArray()
        list.forEach { f ->
            array.put(JSONObject().put("sourceId", f.sourceId).put("source", f.source).put("ref", f.ref)
                .put("seriesTitle", f.seriesTitle).put("note", f.note).put("alt", f.alt)
                .put("description", f.description).put("episodeTitle", f.episodeTitle).put("index", f.index))
        }
        prefs(context).edit()
            .putString(FAVORITES, array.toString())
            .putString(REVISION, System.currentTimeMillis().toString())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().putString(REVISION, System.currentTimeMillis().toString()).apply()
    }
}
