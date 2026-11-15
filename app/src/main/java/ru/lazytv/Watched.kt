package ru.lazytv

import android.content.Context
import org.json.JSONArray

/**
 * Films and series the user marked as watched. Kept as one string of ",kind:id," pieces in the settings, so a
 * card in a list can ask "watched?" with a plain substring check, without parsing anything. The cards for the
 * "watched" list of the bookmarks are kept next to it ([CARDS]); marks without a card get one from TMDB in [complete].
 */
object Watched {
    private const val KEY = "watched_titles"
    private const val CARDS = "watched_cards"

    private fun token(title: Title) = ",${title.tv}:${title.id},"

    fun has(context: Context, title: Title): Boolean = Settings.string(context, KEY, "").contains(token(title))

    fun set(context: Context, title: Title, watched: Boolean) {
        val all = Settings.string(context, KEY, "")
        val raw = all.replace(token(title), ",").replace(Regex(",+"), ",")
        val without = if (raw == ",") "" else raw
        Settings.setString(context, KEY, if (watched) (if (without.isEmpty()) "," else without) + token(title).drop(1) else without)
        val others = cards(context).filter { !(it.id == title.id && it.tv == title.tv) }
        // a mark from a place that knows no name yet gets its card later, in [complete]
        saveCards(context, if (watched && title.name.isNotEmpty()) listOf(title) + others else others)
    }

    /** Changes whenever a mark is set or removed: the lists redraw their cards when it differs. */
    fun revision(context: Context): String = Settings.string(context, KEY, "")

    private fun cards(context: Context): List<Title> {
        val array = runCatching { JSONArray(Settings.string(context, CARDS, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { runCatching { array.getJSONObject(it).toTitle() }.getOrNull() }
    }

    private fun saveCards(context: Context, list: List<Title>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        Settings.setString(context, CARDS, array.toString())
    }

    /** Ids of the marks that have no card yet. */
    private fun missing(context: Context): List<Pair<Boolean, Long>> {
        val have = cards(context).map { it.tv to it.id }.toSet()
        return Settings.string(context, KEY, "").split(',').mapNotNull { piece ->
            val (kind, id) = piece.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val key = kind.toBoolean() to (id.toLongOrNull() ?: return@mapNotNull null)
            key.takeIf { it !in have }
        }
    }

    /** The titles marked as watched, the latest first; bookmarks whose card is still to come stand at the end. */
    fun all(context: Context): List<Title> {
        val marked = Settings.string(context, KEY, "")
        val known = cards(context).filter { marked.contains(token(it)) }
        return known + Favorites.all(context).filter { b -> marked.contains(token(b)) && known.none { it.id == b.id && it.tv == b.tv } }
    }

    fun hasMissing(context: Context): Boolean = missing(context).isNotEmpty()

    /** Asks TMDB for the cards of the marks that have none (marks made before the cards were kept); call it off the main thread. True when something was added. */
    fun complete(context: Context): Boolean {
        val found = missing(context).mapNotNull { (tv, id) ->
            runCatching {
                val d = Tmdb.detailsCached(context, id, tv)
                if (d.title.isEmpty()) null
                else Title(id, d.title, d.overview, d.poster.ifEmpty { null }, tv, d.date.take(4), genre = d.genres.take(2).joinToString(", "))
            }.getOrNull()
        }
        if (found.isEmpty()) return false
        // the marks set meanwhile stay in front
        saveCards(context, cards(context) + found)
        return true
    }
}
