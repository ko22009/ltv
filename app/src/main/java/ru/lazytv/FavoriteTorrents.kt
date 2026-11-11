package ru.lazytv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Torrents marked with the star, kept on the device; the magnet (or the topic address) is enough to start them later.
 * A torrent starred from a film card also keeps that film (poster, year, rating from TMDB): the bookmarks draw it as a film card.
 */
object FavoriteTorrents {
    private const val KEY = "favorite_torrents"

    private fun entries(context: Context): List<Pair<Torrent, Title?>> {
        val array = runCatching { JSONArray(Settings.string(context, KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).map { array.getJSONObject(it) }.map {
            // records saved before the separator became a comma still carry " • "
            val torrent = Torrent(it.optString("title").replace(" • ", ", ").removeSuffix(", null"),it.optString("size"), it.optInt("seeds"), it.optInt("leeches"), it.optString("magnet"), it.optString("source"))
            torrent to it.optJSONObject("tmdb")?.let { film -> runCatching { film.toTitle() }.getOrNull() }
        }
    }

    fun all(context: Context): List<Torrent> = entries(context).map { it.first }

    /** The torrents saved without a film (old records): they stay lines of the list. */
    fun unlinked(context: Context): List<Torrent> = entries(context).filter { it.second == null }.map { it.first }

    /** The torrents saved with their film, as cards of that film wearing the mark "ТОРРЕНТ". */
    fun cards(context: Context): List<Pair<Torrent, Title>> =
        entries(context).mapNotNull { (torrent, film) -> film?.let { torrent to it.copy(torrentKey = torrent.identity()) } }

    fun has(context: Context, torrent: Torrent) = all(context).any { it.identity() == torrent.identity() }

    /** Adds the torrent (with its [film] when known) or removes it when it is already there; returns true when it is in the list afterwards. */
    fun toggle(context: Context, torrent: Torrent, film: Title? = null): Boolean {
        val saved = entries(context)
        val now = saved.none { it.first.identity() == torrent.identity() }
        val list = if (now) listOf(torrent to film) + saved else saved.filter { it.first.identity() != torrent.identity() }
        val array = JSONArray()
        list.take(LIMIT).forEach { (it, tmdb) ->
            val o = JSONObject().put("title", it.title).put("size", it.size).put("seeds", it.seeds).put("leeches", it.leeches)
                .put("magnet", it.magnet).put("source", it.source)
            if (tmdb != null) o.put("tmdb", tmdb.toJson())
            array.put(o)
        }
        Settings.setString(context, KEY, array.toString())
        return now
    }

    private const val LIMIT = 100
}
