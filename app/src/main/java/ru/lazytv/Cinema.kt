package ru.lazytv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap

/**
 * Films that are in the cinemas now: the ids of TMDB's "now playing" list for Russia. Kept in preferences between
 * starts (so the mark is there at once) and renewed in the background when older than six hours; a card bound before
 * the answer arrives simply shows no mark until it is bound again.
 */
object Cinema {
    private const val PREFS = "cinema"
    private const val IDS = "ids"
    // renamed when the number of pages grew: the list saved by the previous version counts as stale and is renewed at once
    private const val AT = "at_v2"
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    // the whole list (about 5 pages for Russia): a film of a later page would lack the mark though the filter lists it
    private const val PAGES = 6

    private val ids: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var loadedAt = 0L

    @Volatile
    private var refreshing = false

    /** Called once from [LazyApp]: reads the saved list, then renews it when it is stale. */
    fun init(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, 0)
        loadedAt = prefs.getLong(AT, 0L)
        prefs.getString(IDS, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.let { ids.addAll(it) }
        refreshIfStale(app)
    }

    /**
     * Films that are not out yet but get the same mark with the caption "СКОРО". упрощено: the list is kept by hand
     * (TMDB ids); a film that appears in the cinemas list gets "В КИНО" instead. When the list grows, take the ids from
     * TMDB's "upcoming" list.
     */
    private val soon = setOf(
        1003596L, // Мстители: Доктор Дум, 2026-12-16
    )

    /** The caption of the mark over the poster of a film: "В КИНО", "СКОРО" or null when there is none. */
    fun caption(id: Long, tv: Boolean): String? = when {
        tv -> null
        ids.contains(id) -> "В КИНО"
        id in soon -> "СКОРО"
        else -> null
    }

    private fun refreshIfStale(context: Context) {
        if (refreshing || System.currentTimeMillis() - loadedAt < TTL_MS) return
        refreshing = true
        Thread {
            try {
                val fresh = HashSet<Long>()
                val collection = Tmdb.Collection("Сейчас в кино", false, "now_playing", "&region=RU")
                for (page in 1..PAGES) {
                    val titles = runCatching { Tmdb.loadCollection(context, collection, page) }.getOrNull() ?: break
                    fresh.addAll(titles.map { it.id })
                    if (titles.size < 20) break
                }
                // a failed download keeps the previous list instead of wiping the marks
                if (fresh.isNotEmpty()) {
                    ids.retainAll(fresh)
                    ids.addAll(fresh)
                    loadedAt = System.currentTimeMillis()
                    context.getSharedPreferences(PREFS, 0).edit()
                        .putString(IDS, fresh.joinToString(",")).putLong(AT, loadedAt).apply()
                }
            } finally {
                refreshing = false
            }
        }.start()
    }

    /** The semi-transparent label over a poster (the caption comes from [caption]); [px] turns design pixels into real ones. */
    fun label(context: Context, px: (Float) -> Int): TextView = TextView(context).apply {
        setTextColor(0xE6FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(12f).toFloat())
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.05f
        includeFontPadding = false
        gravity = Gravity.CENTER
        setPadding(px(8f), px(4f), px(8f), px(4f))
        // a dark glass plate: the poster stays visible through it
        background = GradientDrawable().apply {
            setColor(0x99101216.toInt())
            cornerRadius = px(4f).toFloat()
        }
        isFocusable = false
        isClickable = false
        visibility = android.view.View.GONE
    }
}
