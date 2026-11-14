package ru.lazytv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * The bookmarks page of the TV: plain views in a scroll view, no Leanback rows. A Leanback list scrolls its rows
 * to its own alignment every time the focus goes to another row, so the captions and cards jumped; a scroll view moves
 * only as much as is needed to show the focused card. The cards and tiles are the same ones the rows used.
 */
class FavoritesPageFragment : Fragment() {
    private lateinit var scroll: JumpScrollView
    private lateinit var content: LinearLayout
    private var signature = ""
    private var watchedMarks = ""
    private var firstFocus: View? = null
    private val titleHolders = mutableListOf<Presenter.ViewHolder>()
    private var titles = listOf<Title>()
    private val cardPresenter = CardPresenter()

    private fun state(): String {
        val ctx = requireContext()
        return Settings.string(ctx, "favorites", "[]") + "|" + EpisodeState.revision(ctx) + "|" + Settings.string(ctx, "favorite_torrents", "[]") + "|" + Settings.string(ctx, "title_progress", "[]")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        scroll = JumpScrollView(ctx).apply {
            ownFocusScroll = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            addView(content, ViewGroup.LayoutParams(-1, -2))
        }
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        whenSized { fill() }
    }

    private fun whenSized(block: () -> Unit) {
        if (scroll.width > 0) { block(); return }
        scroll.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (v.width > 0) {
                    v.removeOnLayoutChangeListener(this)
                    block()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refreshIfChanged()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshIfChanged()
    }

    private fun refreshIfChanged() {
        if (!::scroll.isInitialized || scroll.width == 0) return
        if (state() != signature) {
            fill()
            return
        }
        // marks "watched" are redrawn on the cards that stay, so the focus is not lost
        val marks = Watched.revision(requireContext())
        if (marks != watchedMarks) {
            watchedMarks = marks
            titleHolders.forEachIndexed { i, holder -> cardPresenter.onBindViewHolder(holder, titles[i]) }
        }
    }

    /** Puts the focus on the first card (or the first tile when there are no titles); false when the page is empty. */
    fun focusFirst(): Boolean = (lastFocus?.takeIf { it.isAttachedToWindow } ?: firstFocus)?.requestFocus() ?: false

    /** The card or tile that held the focus last: "right" from the menu returns to it, the scroll stays as it was (as in the catalog). */
    private var lastFocus: View? = null

    /** A card or tile grows under the focus, as the cards of the catalog do. */
    private fun zoomOnFocus(view: View, onFocused: (() -> Unit)? = null) {
        view.setOnFocusChangeListener { v, focused ->
            if (focused) {
                lastFocus = v
                onFocused?.invoke()
            }
            v.animate().scaleX(if (focused) 1.1f else 1f).scaleY(if (focused) 1.1f else 1f)
                .translationZ(if (focused) 8f else 0f).setDuration(150).start()
        }
    }

    private fun fill() {
        val ctx = requireContext()
        signature = state()
        watchedMarks = Watched.revision(ctx)
        content.removeAllViews()
        lastHeader = null
        titleHolders.clear()
        firstFocus = null
        // a rebuilt page starts from the top: the old cards are gone
        lastFocus = null
        scroll.scrollTo(0, 0)
        val scale = resources.displayMetrics.widthPixels / 1280f
        val pad = resources.getDimensionPixelSize(R.dimen.page_padding_start)

        content.addView(TextView(ctx).apply {
            text = "Закладки"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f * scale)
            // the same distance from the title to the first card as on the pages of the catalog ("Фильмы", "Сериалы")
            setPadding(pad, (24 * scale).toInt(), 0, (31 * scale).toInt())
        })

        val unfinished = TitleProgress.titles(ctx)
        // a title left half-way stands in its own block only, not among the bookmarks again
        titles = Favorites.all(ctx).filter { b -> unfinished.none { it.id == b.id && it.tv == b.tv } }
        val episodes = EpisodeState.favorites(ctx)
        // torrents saved from a film card stand among the films, as cards with the mark "ТОРРЕНТ"
        val torrentCards = FavoriteTorrents.cards(ctx)
        if (titles.isEmpty() && torrentCards.isEmpty() && episodes.isEmpty() && unfinished.isEmpty()) {
            content.addView(TextView(ctx).apply {
                text = "Пока пусто. Добавляйте кнопкой «Закладки» в карточке или «Добавить в избранное» у серии"
                setTextColor(TEXT_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
                setPadding(pad, (6 * scale).toInt(), 0, 0)
            })
            return
        }

        // the same pitch as the grid of the catalog: five columns over the width of the page
        val pitch = tvGridPitch(scale)

        if (unfinished.isNotEmpty()) {
            addHeader("Продолжить просмотр", pad, scale, first = true)
            if (unfinished.any { it.rating.isEmpty() && it.genre.isEmpty() }) {
                val app = ctx.applicationContext
                Thread { if (TitleProgress.complete(app)) activity?.runOnUiThread { if (isAdded) fill() } }.start()
            }
            addGrid(unfinished.map { title ->
                val holder = cardPresenter.onCreateViewHolder(content)
                cardPresenter.onBindViewHolder(holder, title)
                holder.view.setOnClickListener {
                    startActivity(Intent(ctx, DetailsActivity::class.java).putExtras(title.toExtras()))
                }
                holder.view
            }, pad, pitch)
            if (titles.isNotEmpty() || torrentCards.isNotEmpty()) addHeader("Закладки", pad, scale)
        }
        if (titles.isNotEmpty() || torrentCards.isNotEmpty()) {
            val views = titles.map { title ->
                val holder = cardPresenter.onCreateViewHolder(content)
                cardPresenter.onBindViewHolder(holder, title)
                titleHolders.add(holder)
                holder.view.setOnClickListener {
                    startActivity(Intent(ctx, DetailsActivity::class.java).putExtras(title.toExtras()))
                }
                holder.view
            } + torrentCards.map { (torrent, film) ->
                val holder = cardPresenter.onCreateViewHolder(content)
                cardPresenter.onBindViewHolder(holder, film)
                holder.view.setOnClickListener {
                    // a saved torrent goes straight to the preload, no new search
                    startActivity(
                        Intent(ctx, TorrentsPopupActivity::class.java).putExtra("name", torrent.title)
                            .putExtra("magnet", torrent.magnet).putExtra("size", torrent.size).putExtra("seeds", torrent.seeds)
                            .putExtra("leeches", torrent.leeches).putExtra("source", torrent.source)
                    )
                }
                holder.view
            }
            addGrid(views, pad, pitch)
            fixMissingPosters()
        }
        if (episodes.isNotEmpty()) {
            addHeader("Избранные серии", pad, scale)
            val presenter = FavoriteEpisodePresenter()
            addGrid(episodes.map { item ->
                val holder = presenter.onCreateViewHolder(content)
                presenter.onBindViewHolder(holder, item)
                holder.view.setOnClickListener {
                    startActivity(
                        Intent(ctx, EpisodesActivity::class.java)
                            .putExtra("source", item.source).putExtra("sourceId", item.sourceId)
                            .putExtra("title", item.seriesTitle).putExtra("note", item.note).putExtra("ref", item.ref)
                            .putExtra("alt", item.alt).putExtra("description", item.description)
                            .putExtra("episodeIndex", item.index)
                    )
                }
                holder.view
            }, pad, pitch)
        }
        // room under the last row for the focused card that grows
        content.addView(View(ctx), LinearLayout.LayoutParams(1, (24 * scale).toInt()))
    }

    /** [first]: right under the page title, which already keeps the distance to the first block (as the cards of a plain list). */
    private var lastHeader: View? = null

    private fun addHeader(text: String, pad: Int, scale: Float, first: Boolean = false) {
        val header = TextView(requireContext()).apply {
            this.text = text
            setTextColor(TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 26f * scale)
            setPadding(pad, if (first) 0 else (18 * scale).toInt(), 0, (6 * scale).toInt())
        }
        // the heading of the first block is not an anchor: the page scrolls to its very top, to the page title
        lastHeader = if (first) null else header
        content.addView(header)
    }

    /** Lays the views out in rows of [perRow] (five by default), each in a cell of the grid pitch. */
    private fun addGrid(views: List<View>, pad: Int, pitch: Int, perRow: Int = 5) {
        // the first row of a block brings its heading into view only when the heading is above the shown part: a page that fits is not moved
        val anchor = lastHeader
        views.chunked(perRow).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                setPadding(pad, 0, 0, 0)
            }
            chunk.forEach { view ->
                val own = view.layoutParams
                val cell = FrameLayout(requireContext()).apply {
                    clipChildren = false
                    clipToPadding = false
                }
                val lp = FrameLayout.LayoutParams(own?.width ?: -2, own?.height ?: -2)
                (own as? ViewGroup.MarginLayoutParams)?.let { lp.topMargin = it.topMargin; lp.bottomMargin = it.bottomMargin }
                zoomOnFocus(view) {
                    // one smooth move: the card with room for its zoom, and for the first row of a block its heading too (the first
                    // block: the page title); a page that already shows all this is not moved
                    val box = android.graphics.Rect()
                    view.getDrawingRect(box)
                    scroll.offsetDescendantRectToMyCoords(view, box)
                    val room = (14 * resources.displayMetrics.density).toInt()
                    var y = scroll.scrollY
                    val topWanted = if (rowIndex == 0) (if (anchor == null || anchor.top < 0) 0 else anchor.top) else box.top - room
                    if (topWanted < y) y = topWanted
                    else if (box.bottom + room > y + scroll.height) y = box.bottom + room - scroll.height
                    if (y != scroll.scrollY) scroll.smoothScrollTo(0, y.coerceAtLeast(0))
                }
                cell.addView(view, lp)
                row.addView(cell, LinearLayout.LayoutParams(pitch, -2))
                if (firstFocus == null) firstFocus = view
            }
            content.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** A bookmark saved without a poster asks TMDB for it once; the cards are rebound in place, so the focus stays. */
    private fun fixMissingPosters() {
        if (titles.none { it.poster == null || it.genre.isEmpty() }) return
        val app = requireContext().applicationContext
        val asked = titles
        Thread {
            val fixed = Favorites.withPosters(app, asked)
            activity?.runOnUiThread {
                if (!isAdded || titles !== asked || fixed == asked) return@runOnUiThread
                fixed.forEachIndexed { i, t ->
                    if (i < titleHolders.size && asked[i] != t) cardPresenter.onBindViewHolder(titleHolders[i], t)
                }
                titles = fixed
                // the new records are in the preferences: the page must not take them for a change and redraw
                signature = state()
            }
        }.start()
    }
}
