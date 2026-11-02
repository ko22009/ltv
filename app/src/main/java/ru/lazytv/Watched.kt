package ru.lazytv

import android.content.Context

/**
 * Films and series the user marked as watched. Kept as one string of ",kind:id," pieces in the settings, so a
 * card in a list can ask "watched?" with a plain substring check, without parsing anything.
 */
object Watched {
    private const val KEY = "watched_titles"

    private fun token(title: Title) = ",${title.tv}:${title.id},"

    fun has(context: Context, title: Title): Boolean = Settings.string(context, KEY, "").contains(token(title))

    fun set(context: Context, title: Title, watched: Boolean) {
        val all = Settings.string(context, KEY, "")
        val without = all.replace(token(title), ",")
        Settings.setString(context, KEY, if (watched) (if (without.isEmpty()) "," else without) + token(title).drop(1) else without)
    }

    /** Changes whenever a mark is set or removed: the lists redraw their cards when it differs. */
    fun revision(context: Context): String = Settings.string(context, KEY, "")
}
