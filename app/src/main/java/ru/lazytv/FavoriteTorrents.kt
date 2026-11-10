package ru.lazytv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Torrents marked with the star, kept on the device; the magnet (or the topic address) is enough to start them later. */
object FavoriteTorrents {
    private const val KEY = "favorite_torrents"

    fun all(context: Context): List<Torrent> {
        val array = runCatching { JSONArray(Settings.string(context, KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).map { array.getJSONObject(it) }.map {
            // records saved before the separator became a comma still carry " • "
            Torrent(it.optString("title").replace(" • ", ", ").removeSuffix(", null"),it.optString("size"), it.optInt("seeds"), it.optInt("leeches"), it.optString("magnet"), it.optString("source"))
        }
    }

    fun has(context: Context, torrent: Torrent) = all(context).any { it.identity() == torrent.identity() }

    /** Adds the torrent or removes it when it is already there; returns true when it is in the list afterwards. */
    fun toggle(context: Context, torrent: Torrent): Boolean {
        val saved = all(context)
        val now = saved.none { it.identity() == torrent.identity() }
        val list = if (now) listOf(torrent) + saved else saved.filter { it.identity() != torrent.identity() }
        val array = JSONArray()
        list.take(LIMIT).forEach {
            array.put(JSONObject().put("title", it.title).put("size", it.size).put("seeds", it.seeds).put("leeches", it.leeches)
                .put("magnet", it.magnet).put("source", it.source))
        }
        Settings.setString(context, KEY, array.toString())
        return now
    }

    private const val LIMIT = 100
}
