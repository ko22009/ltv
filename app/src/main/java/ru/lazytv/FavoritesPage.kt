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
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var signature = ""
    private var watchedMarks = ""
    private var firstFocus: View? = null
    private val titleHolders = mutableListOf<Presenter.ViewHolder>()
    private var titles = listOf<Title>()
    private val cardPresenter = CardPresenter()

    private fun state(): String {
        val ctx = requireContext()
        return Settings.string(ctx, "favorites", "[]") + "|" + EpisodeState.revision(ctx) + "|" + Settings.string(ctx, "favorite_torrents", "[]")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        scroll = ScrollView(ctx).apply {
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
    fun focusFirst(): Boolean = firstFocus?.requestFocus() ?: false

    /** A card or tile grows under the focus, as the cards of the catalog do. */
    private fun zoomOnFocus(view: View) {
        view.setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.1f else 1f).scaleY(if (focused) 1.1f else 1f)
                .translationZ(if (focused) 8f else 0f).setDuration(150).start()
        }
    }

    private fun fill() {
        val ctx = requireContext()
        signature = state()
        watchedMarks = Watched.revision(ctx)
        content.removeAllViews()
        titleHolders.clear()
        firstFocus = null
        val scale = resources.displayMetrics.widthPixels / 1280f
        val pad = resources.getDimensionPixelSize(R.dimen.page_padding_start)

        content.addView(TextView(ctx).apply {
            text = "Закладки"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f * scale)
            // the same distance from the title to the first card as on the pages of the catalog ("Фильмы", "Сериалы")
            setPadding(pad, (24 * scale).toInt(), 0, (31 * scale).toInt())
        })

        titles = Favorites.all(ctx)
        val episodes = EpisodeState.favorites(ctx)
        // torrents saved from a film card stand among the films, as cards with the mark "ТОРРЕНТ"
        val torrentCards = FavoriteTorrents.cards(ctx)
        if (titles.isEmpty() && torrentCards.isEmpty() && episodes.isEmpty()) {
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

    private fun addHeader(text: String, pad: Int, scale: Float) {
        content.addView(TextView(requireContext()).apply {
            this.text = text
            setTextColor(TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 26f * scale)
            setPadding(pad, (18 * scale).toInt(), 0, (6 * scale).toInt())
        })
    }

    /** Lays the views out in rows of [perRow] (five by default), each in a cell of the grid pitch. */
    private fun addGrid(views: List<View>, pad: Int, pitch: Int, perRow: Int = 5) {
        views.chunked(perRow).forEach { chunk ->
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
                zoomOnFocus(view)
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
