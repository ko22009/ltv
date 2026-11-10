package ru.lazytv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The "show all" tile at the end of a catalog row. It knows which list the row came from: a category with a sort order.
 */
data class MoreTile(val title: String, val category: String = "", val sort: String = "", val filtered: Boolean = false) {
    /** One TMDB page: what is left of it after "hide watched", and whether TMDB had nothing on it at all. */
    class Loaded(val titles: List<Title>, val ended: Boolean)

    fun loadPage(context: Context, page: Int): Loaded {
        val list = Tmdb.categories.first { it.id == category }
        if (!filtered) return Tmdb.load(context, list, sort, page).let { Loaded(it, it.isEmpty()) }
        // the filters chosen on the list page are read at the moment of loading, so every page of the grid agrees
        val filter = CatalogFilter.load(context, category)
        val found = Tmdb.load(context, list, sort, page, filter.query(list.tv))
        val shown = if (filter.hideWatched) found.filter { !Watched.has(context, it) } else found
        return Loaded(shown, found.isEmpty())
    }

    /** The first titles of the list for a row: pages made up only of watched titles are skipped. */
    fun load(context: Context, page: Int = 1): List<Title> {
        var at = page
        while (at < page + MAX_SKIPPED_PAGES) {
            val loaded = loadPage(context, at++)
            if (loaded.titles.isNotEmpty() || loaded.ended) return loaded.titles
        }
        return emptyList()
    }

    fun toIntent(context: Context): Intent = Intent(context, TitlesGridActivity::class.java)
        .putExtra("title", title).putExtra("category", category).putExtra("sort", sort).putExtra("filtered", filtered)

    companion object {
        /** How many pages in a row may hold only watched titles before the row gives up. */
        private const val MAX_SKIPPED_PAGES = 5

        fun from(intent: Intent) = MoreTile(
            intent.getStringExtra("title").orEmpty(),
            intent.getStringExtra("category").orEmpty(), intent.getStringExtra("sort").orEmpty(),
            intent.getBooleanExtra("filtered", false),
        )
    }
}

/** The tile has the size of a title card, so the row keeps its height; focused, it is painted like the active left-menu item. */
class MoreTilePresenter(private val cardScale: Float = CARD_SCALE) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val scale = parent.context.uiScale() * cardScale
        fun px(v: Float) = (v * scale).toInt()
        val context = parent.context
        val tile = LinearLayout(context)
        tile.orientation = LinearLayout.VERTICAL
        tile.gravity = Gravity.CENTER
        tile.isFocusable = true
        tile.isFocusableInTouchMode = true
        tile.layoutParams = ViewGroup.MarginLayoutParams(px(240f), px(TITLE_POSTER_HEIGHT) + px(TITLE_INFO_HEIGHT)).apply {
            rightMargin = px(17f)
            topMargin = px(12f)
            bottomMargin = px(12f)
        }
        // the focused tile has the colour of the focused item of the left menu (#0F766E) and a white icon
        fun plate(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = px(TITLE_CARD_RADIUS).toFloat()
        }
        tile.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), plate(FOCUS_FILL))
            addState(intArrayOf(), plate(PLATE_FILL))
        }
        tile.elevation = 0f
        tile.addView(ImageView(context).apply {
            isDuplicateParentStateEnabled = true
            setImageDrawable(PathDrawable(MenuIcons.LINK))
            imageTintList = cursorColors(context, Color.WHITE)
        }, LinearLayout.LayoutParams(px(64f), px(64f)))
        // only the name of the row, as written over it
        tile.addView(TextView(context).apply {
            tag = "caption"
            isDuplicateParentStateEnabled = true
            setTextColor(cursorColors(context, Color.WHITE))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * scale)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(px(12f), px(14f), px(12f), 0)
        })
        return ViewHolder(tile)
    }

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {
        holder.view.findViewWithTag<TextView>("caption").text = (item as MoreTile).title.substringAfter(": ")
    }

    override fun onUnbindViewHolder(holder: ViewHolder) {}
}
