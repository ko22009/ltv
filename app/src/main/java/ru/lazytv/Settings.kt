package ru.lazytv

import android.content.Context

/** Settings take effect immediately: screens read them when shown, nothing needs a restart. */
object Settings {
    private const val PREFS = "lazytv"

    const val SHOW_ADULT = "show_adult"
    const val SHOW_HORROR = "show_horror"
    const val SHOW_OVERVIEW = "show_overview"
    const val LANGUAGE = "language"
    const val SOURCE_MODE = "source_mode"
    const val PLAYER = "player"
    const val TORRSERVE = "torrserve"
    const val QUALITY = "quality"
    const val VOICE = "voice"
    const val REMEMBER_POSITION = "remember_position"
    const val AUTO_BYPASS = "auto_bypass"
    const val APP_PROXY = "app_proxy"
    const val CARD_SIZE = "card_size"
    const val APP_PROXY_TORRENTS = "app_proxy_torrents"
    const val TORRENT_SORT = "torrent_sort"
    const val SEARCH_HISTORY = "search_history"
    const val TORRENT_EXACT = "torrent_exact"
    const val TORRENT_MIN_SEEDS = "torrent_min_seeds"
    /** "internal" streams through TorrServe at [TORRSERVE] as LazyTV always did; "external" hands the magnet to
     *  whatever torrent app is installed (TorrServe, TorrServe MatriX, …), the way LazyMedia Deluxe does it. */
    const val TORRENT_CLIENT = "torrent_client"
    /** Comma-separated JacRed servers; empty means the built-in list. */
    const val JACRED_SERVERS = "jacred_servers"
    /** Own Jackett: base address (for example http://192.168.1.10:9117) and its API key. */
    const val JACKETT_URL = "jackett_url"
    const val JACKETT_KEY = "jackett_key"
    const val PARENTAL_PIN = "parental_pin"
    const val AUTO_NEXT = "auto_next"
    const val CACHE_SOURCES = "cache_sources"
    const val RECOMMENDATIONS = "recommendations"
    /** Address of the JSON that carries the current addresses of sites and trackers (`docs/remote-config.md`). */
    const val REMOTE_URL = "remote_url"

    /** Choices of the "minimum seeders" filter, in the order the chip on the torrent screen cycles through. */
    val MIN_SEEDS = listOf(0, 1, 3, 5, 10)

    fun service(id: String) = "service_$id"

    /** The address the user typed for a source or tracker instead of the one from the config. */
    fun baseKey(id: String) = "base_$id"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    fun bool(context: Context, key: String, default: Boolean = true): Boolean =
        prefs(context).getBoolean(key, default)

    fun setBool(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    fun string(context: Context, key: String, default: String): String =
        prefs(context).getString(key, default) ?: default

    fun setString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun remove(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    /** Keys in the shared "lazytv" file that hold user data rather than preferences. */
    private val USER_DATA_KEYS = setOf(
        "favorites", "history", "watched_titles", "favorite_torrents",
        "last_backup_at", "recommendation_ids", "recommendation_channel",
    )

    /** Resets settings, manual addresses and catalog filters while keeping bookmarks, history and watched marks. */
    fun resetSettingsOnly(context: Context) {
        val p = prefs(context)
        val edit = p.edit()
        for (key in p.all.keys) {
            if (key !in USER_DATA_KEYS) edit.remove(key)
        }
        edit.apply()
        context.getSharedPreferences("remote", 0).edit().clear().apply()
        context.getSharedPreferences("catalog_filters", 0).edit().clear().apply()
    }

    /** Changes whenever any setting changes; screens compare it to know when to redraw. */
    fun snapshot(context: Context): String = prefs(context).all.toSortedMap().toString()
}
