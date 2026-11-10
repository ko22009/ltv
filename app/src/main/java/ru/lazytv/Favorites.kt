package ru.lazytv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bookmark folders. A title lives in one folder at a time. */
enum class Folder(val key: String, val label: String) {
    FAVORITES("favorites", "Избранное"),
    LATER("later", "На потом"),
    IN_PROGRESS("progress", "В процессе"),
    DONE("done", "Просмотрено");

    companion object {
        fun of(key: String) = values().firstOrNull { it.key == key } ?: FAVORITES
    }
}

private fun Title.toJson() = JSONObject().put("id", id).put("name", name).put("overview", overview)
    .put("poster", poster ?: "").put("tv", tv).put("year", year).put("rating", rating)
    .put("backdrop", backdrop ?: "").put("genre", genre)

private fun JSONObject.toTitle() = Title(getLong("id"), getString("name"), optString("overview"),
    optString("poster").ifEmpty { null }, optBoolean("tv"), optString("year"), optString("rating"),
    genre = optString("genre"), backdrop = optString("backdrop").ifEmpty { null })

/**
 * Bookmarks kept on the device in the same preferences as the settings. Records saved before the folders
 * existed have no folder and count as favorites.
 */
object Favorites {
    private const val KEY = "favorites"

    private fun records(context: Context): List<Pair<Folder, Title>> {
        val array = runCatching { JSONArray(Settings.string(context, KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                Folder.of(o.optString("folder", Folder.FAVORITES.key)) to o.toTitle()
            }.getOrNull()
        }
    }

    private fun save(context: Context, list: List<Pair<Folder, Title>>) {
        val array = JSONArray()
        list.forEach { (folder, title) -> array.put(title.toJson().put("folder", folder.key)) }
        Settings.setString(context, KEY, array.toString())
    }

    private fun same(a: Title, b: Title) = a.id == b.id && a.tv == b.tv

    fun inFolder(context: Context, folder: Folder): List<Title> = records(context).filter { it.first == folder }.map { it.second }

    /** Everything in the bookmarks, whatever folder a record was saved in before the folders were dropped. */
    fun all(context: Context): List<Title> = records(context).map { it.second }

    fun folderOf(context: Context, title: Title): Folder? = records(context).firstOrNull { same(it.second, title) }?.first

    /** Puts the title into the folder (it leaves its previous one); null removes it from the bookmarks. */
    fun set(context: Context, title: Title, folder: Folder?) {
        val list = records(context).filter { !same(it.second, title) }.toMutableList()
        if (folder != null) list.add(0, folder to title)
        save(context, list)
        // the home-screen row follows the bookmarks; provider calls must stay off the main thread
        val app = context.applicationContext
        Thread { runCatching { Recommendations.refreshBookmarks(app) } }.start()
    }

    /** Fills in the poster of a record saved before the card knew it (the folder of the record stays). */
    private fun setPoster(context: Context, id: Long, tv: Boolean, poster: String) {
        val list = records(context)
        if (list.none { it.second.id == id && it.second.tv == tv && it.second.poster == null }) return
        save(context, list.map { (folder, t) -> if (t.id == id && t.tv == tv && t.poster == null) folder to t.copy(poster = poster) else folder to t })
    }

    /**
     * A bookmark saved from a place that had no poster yet (a search answer, an old record) showed the stub "Нет картинки".
     * The posters are asked for once, saved into the record and returned; call it off the main thread.
     */
    fun withPosters(context: Context, titles: List<Title>): List<Title> {
        if (titles.none { it.poster == null }) return titles
        return titles.map { title ->
            if (title.poster != null) return@map title
            val poster = runCatching { Tmdb.detailsCached(context, title.id, title.tv) }.getOrNull()?.poster.orEmpty()
            if (poster.isNotEmpty()) {
                setPoster(context, title.id, title.tv, poster)
                title.copy(poster = poster)
            } else title
        }
    }
}

/** Titles the user started watching, the latest first. Kept on the device, at most [LIMIT]. */
object History {
    private const val KEY = "history"
    private const val LIMIT = 100

    fun all(context: Context): List<Title> {
        val array = runCatching { JSONArray(Settings.string(context, KEY, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { runCatching { array.getJSONObject(it).toTitle() }.getOrNull() }
    }

    fun add(context: Context, title: Title) {
        save(context, (listOf(title) + all(context).filter { !(it.id == title.id && it.tv == title.tv) }).take(LIMIT))
    }

    /** Fills in the poster of a record that was saved before the card knew it: the row is drawn again after that. */
    fun setPoster(context: Context, id: Long, tv: Boolean, poster: String) {
        val list = all(context)
        if (list.none { it.id == id && it.tv == tv && it.poster == null }) return
        save(context, list.map { if (it.id == id && it.tv == tv && it.poster == null) it.copy(poster = poster) else it })
    }

    fun clear(context: Context) = Settings.setString(context, KEY, "[]")

    /** Takes the given titles out of the history (the "edit" mode of the search page). */
    fun remove(context: Context, titles: Collection<Title>) {
        val gone = titles.map { it.tv to it.id }.toSet()
        save(context, all(context).filter { (it.tv to it.id) !in gone })
    }

    /**
     * Records saved before the card asked TMDB for a poster have none, and their card stayed empty. The posters are
     * asked for once, saved into the record and returned; call it off the main thread.
     */
    fun withPosters(context: Context, titles: List<Title>): List<Title> {
        if (titles.none { it.poster == null }) return titles
        return titles.map { title ->
            val poster = title.poster ?: runCatching { Tmdb.detailsCached(context, title.id, title.tv) }.getOrNull()?.poster.orEmpty()
            if (title.poster == null && poster.isNotEmpty()) {
                setPoster(context, title.id, title.tv, poster)
                title.copy(poster = poster)
            } else title
        }
    }

    private fun save(context: Context, list: List<Title>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        Settings.setString(context, KEY, array.toString())
    }
}
