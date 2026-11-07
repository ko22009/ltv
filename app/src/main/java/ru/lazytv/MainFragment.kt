package ru.lazytv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.util.TypedValue
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DividerPresenter
import androidx.leanback.widget.DividerRow
import androidx.leanback.widget.PageRow
import androidx.leanback.widget.RowHeaderPresenter
import androidx.leanback.widget.SectionRow
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val OVERVIEW = "ОБЗОР"
private const val FAVORITES = "ЗАКЛАДКИ"
private const val SETTINGS = "НАСТРОЙКИ"

/** How long the menu slides (Leanback's lb_browse_headers_transition_duration): the rail and the page follow the same time, or the move looks cut short. */
private const val MENU_SLIDE_MS = 250L

/** One section of the settings, shown as its own card on the settings page. */
data class SettingsSection(val title: String, val description: String)

private val SETTINGS_SECTIONS = listOf(
    SettingsSection("Источники видео", "Сайты и торренты, кэш источников"),
    SettingsSection("Адреса сайтов", "Конфиг с адресами, свои адреса сайтов"),
    SettingsSection("Воспроизведение", "Плеер, качество, озвучка, позиция"),
    SettingsSection("Торренты и адреса", "TorrServe, JacRed, Jackett, минимум сидов"),
    SettingsSection("Каталог", "Язык описаний, рекомендации на ТВ"),
    SettingsSection("Сеть", "Обход блокировок и прокси"),
    SettingsSection("Данные", "Копия в файл, кэш, история, сброс"),
    SettingsSection("Помощь", "Частые вопросы: сайты, видео, торренты, время"),
    SettingsSection("О приложении", "Версия и условия использования"),
)

/** Opens the settings screen from the tile on the settings page. */
object OpenSettings

class MainFragment : BrowseSupportFragment() {
    private val headers = ArrayObjectAdapter(ListRowPresenter())
    private val pages = HashMap<String, ArrayObjectAdapter>()
    private var shown = ""
    /** Adapter of the page that changes when a title or episode is bookmarked. */
    private var favoritesRows: ArrayObjectAdapter? = null
    private var favoritesSignature = ""
    private var torrentsSignature = ""
    private var watchedMarks = ""
    /** The strip of icons that stands in for the menu while it is collapsed, and the page it lights. */
    private var rail: LinearLayout? = null
    private var currentPage = OVERVIEW
    /** The page the menu must open after an update; empty when the menu starts as usual (docs/workflow.md). */
    private var pageToRestore = ""
    /** Whether the wide menu was open before the update; the rail stands in its place otherwise. */
    private var menuToRestore = true
    private var searchPage: SearchPageFragment? = null

    fun voiceResult(requestCode: Int, resultCode: Int, data: Intent?) {
        searchPage?.voiceResult(requestCode, resultCode, data)
    }

    private val onClick = OnItemViewClickedListener { _, item, _, _ ->
        when (item) {
            is Title -> startActivity(Intent(requireContext(), DetailsActivity::class.java).putExtras(item.toExtras()))
            is MoreTile -> startActivity(item.toIntent(requireContext()))
            is Torrent -> startActivity(
                Intent(requireContext(), TorrentsActivity::class.java).putExtra("name", item.title)
                    .putExtra("magnet", item.magnet).putExtra("size", item.size).putExtra("seeds", item.seeds)
                    .putExtra("leeches", item.leeches).putExtra("source", item.source)
            )
            is FavoriteEpisode -> startActivity(
                Intent(requireContext(), EpisodesActivity::class.java)
                    .putExtra("source", item.source).putExtra("sourceId", item.sourceId)
                    .putExtra("title", item.seriesTitle).putExtra("note", item.note).putExtra("ref", item.ref)
                    .putExtra("alt", item.alt).putExtra("description", item.description)
                    .putExtra("episodeIndex", item.index)
            )
            is SettingsSection -> startActivity(
                Intent(requireContext(), SettingsActivity::class.java).putExtra("section", item.title)
            )
            OpenSettings -> startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // the saved page is read before the overview opens and is saved over it (docs/workflow.md)
        if (arguments?.getBoolean(RESTORE, false) == true) {
            pageToRestore = LastScreen.page(requireContext())
            menuToRestore = LastScreen.menuOpen(requireContext())
        }
        // no page title in the corner: the rows start at the top edge and would run under it
        title = ""
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = 0xFF1B2E23.toInt()
        mainFragmentRegistry.registerFragment(PageRow::class.java, PageFactory())
        adapter = headers
        showHeaders()
    }

    private val ROW_AHEAD = 6 // titles left in a row when the next page is asked for

    private var spacingTries = 0

    /** The next pages of an overview row: asked for while the cursor nears the end of the row. */
    private class RowPager(val more: MoreTile, val cards: ArrayObjectAdapter) {
        var next = 2
        var busy = false
        var ended = false
    }

    private val pagers = HashMap<ListRow, RowPager>()

    private fun loadMoreOf(row: ListRow, item: Any?) {
        val pager = pagers[row] ?: return
        val index = pager.cards.indexOf(item)
        if (pager.busy || pager.ended || index < pager.cards.size() - ROW_AHEAD) return
        pager.busy = true
        val ctx = requireContext().applicationContext
        val page = pager.next
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { pager.more.loadPage(ctx, page) }.getOrNull() }
            pager.busy = false
            if (loaded == null) return@launch
            // titles already in the row are not added twice when TMDB repeats one on the next page
            val fresh = loaded.titles.filter { t -> (0 until pager.cards.size()).none { (pager.cards.get(it) as? Title)?.id == t.id } }
            if (loaded.ended) pager.ended = true else pager.next = page + 1
            if (fresh.isNotEmpty()) pager.cards.addAll(pager.cards.size(), fresh)
            // a page that held only watched titles adds nothing: the next one is asked for at once
            else if (!loaded.ended) loadMoreOf(row, pager.cards.get(pager.cards.size() - 1))
        }
    }

    /** Rows of the overview that wait to be loaded until they come near the screen. */
    private val pendingLoads = HashMap<ListRow, () -> Unit>()

    /** The menu fragment exists only after the view is created, so its look is set here. */
    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        headersSupportFragment?.presenterSelector = ClassPresenterSelector()
            .addClassPresenter(DividerRow::class.java, DividerPresenter())
            .addClassPresenter(SectionRow::class.java, SectionHeaderPresenter())
            .addClassPresenter(Row::class.java, IconHeaderPresenter())
        buildRail(view)
        // the menu list starts at the top and the cursor walks down it; it scrolls only when the cursor reaches the middle
        // (Leanback pins the chosen item to one place by default). The pages share the sizes of Leanback, so the menu
        // takes its own edges here. Leanback sets the alignment again after the view is made, so it is held on every layout pass.
        val scale = resources.displayMetrics.widthPixels / 1280f
        view.viewTreeObserver.addOnGlobalLayoutListener {
            val menu = headersSupportFragment?.verticalGridView ?: return@addOnGlobalLayoutListener
            if (menu.windowAlignment != androidx.leanback.widget.BaseGridView.WINDOW_ALIGN_BOTH_EDGE) {
                menu.windowAlignment = androidx.leanback.widget.BaseGridView.WINDOW_ALIGN_BOTH_EDGE
                menu.windowAlignmentOffsetPercent = 50f
                menu.windowAlignmentOffset = 0
            }
            // the first item sits level with the search row of the page and the edges are not tight
            val side = (28 * scale).toInt()
            val top = (27 * scale).toInt()
            val gap = (8 * scale).toInt()
            if (menu.paddingLeft != side || menu.paddingTop != top) {
                menu.clipToPadding = false
                menu.setPadding(side, top, side, (16 * scale).toInt())
            }
            // Leanback leaves 28dp between the items of the menu; they stand closer. Just after the start Leanback keeps its own gap
            // even when the value is already set, so the gap really laid out is compared and the value is forced through a change
            val first = menu.getChildAt(0)
            val second = menu.getChildAt(1)
            if (first != null && second != null) {
                if (Math.abs(second.top - first.bottom - gap) > 2 && spacingTries < 30) {
                    spacingTries++
                    menu.verticalSpacing = gap + 1
                    menu.verticalSpacing = gap
                    // the positions already laid out are not moved by the new value alone: the items are laid out again
                    menu.adapter?.notifyDataSetChanged()
                } else if (Math.abs(second.top - first.bottom - gap) <= 2) spacingTries = 0
            }
        }
        // Leanback may lay the menu out with its own gap after the first passes: the gap is checked again a little later
        for (delay in listOf(600L, 1500L)) view.postDelayed({
            spacingTries = 0
            headersSupportFragment?.verticalGridView?.requestLayout()
        }, delay)
        setSelectedPosition(0, false)
        setBrowseTransitionListener(object : BrowseTransitionListener() {
            override fun onHeadersTransitionStart(withHeaders: Boolean) {
                // the state is remembered for the install script: after an update the menu opens as it was (docs/workflow.md)
                LastScreen.rememberMenu(requireContext(), withHeaders)
                // Leanback lays the menu out with its own gap again when it opens: the check of the gap starts over
                spacingTries = 0
                headersSupportFragment?.verticalGridView?.requestLayout()
                if (withHeaders) rail?.animate()?.alpha(0f)?.setDuration(MENU_SLIDE_MS)?.withEndAction { rail?.visibility = View.GONE }?.start()
                else { refreshRail(); rail?.visibility = View.VISIBLE; rail?.animate()?.alpha(1f)?.setDuration(MENU_SLIDE_MS)?.start() }
                shiftPage(withHeaders)
            }

            override fun onHeadersTransitionStop(withHeaders: Boolean) {
                spacingTries = 0
                headersSupportFragment?.verticalGridView?.requestLayout()
            }
        })
        // the page stands next to the open menu from the start, not only after the menu was closed and opened once
        view.post { shiftPage(isShowingHeaders()) }
        // the lists are warmed in the background: the pages and the rows open with the cards, not with skeletons
        val warmContext = requireContext().applicationContext
        Thread {
            runCatching {
                Tmdb.visibleCategories(warmContext).forEach { category ->
                    if (!CatalogFilter.supports(category.id)) return@forEach
                    val filter = CatalogFilter.load(warmContext, category.id)
                    Tmdb.load(warmContext, category, CatalogFilter.sort(warmContext, category.id).sort, 1, filter.query(category.tv))
                }
                Tmdb.overview.take(4).forEach { runCatching { Tmdb.loadCollection(warmContext, it) } }
            }
        }.start()
        // after an update the menu comes back to the page the user was on instead of the overview (docs/workflow.md)
        if (arguments?.getBoolean(RESTORE, false) == true) view.post { restorePage() }
    }

    /** Selects the page saved before the update; unknown pages stay on the overview. */
    private fun restorePage() {
        val name = pageToRestore
        // from now on the page is saved as it is chosen again
        pageToRestore = ""
        val index = if (name.isEmpty() || name == OVERVIEW) 0 else
            (0 until headers.size()).indexOfFirst { (headers.get(it) as? PageRow)?.headerItem?.name == name }
        if (index > 0) setSelectedPosition(index, false)
        else LastScreen.rememberPage(requireContext(), currentPage)
        // the menu was closed before the update: the rail stands in its place, as it was
        if (!menuToRestore) startHeadersTransition(false)
    }

    /**
     * The page keeps its wide left margin for the rail, but next to the open menu that margin is too much: the page moves
     * left by the extra. It glides only when the menu opens or closes; moving the cursor from one page to another (the pages
     * swap under it) must not slide the page.
     */
    private fun shiftPage(menuOpen: Boolean) {
        val scale = resources.displayMetrics.widthPixels / 1280f
        // the menu is 30dp narrower than the stock one, so the page comes closer by the same room
        val shift = if (!menuOpen) 0f else -55 * scale
        val frame = this.view?.findViewById<View>(androidx.leanback.R.id.scale_frame) ?: return
        frame.animate().cancel()
        if (menuOpen == menuWasOpen) frame.translationX = shift
        else frame.animate().translationX(shift).setDuration(MENU_SLIDE_MS).start()
        menuWasOpen = menuOpen
    }

    private var menuWasOpen = true

    /**
     * The menu shrinks to a narrow rail of icons while a page has the focus: the icons stay where the names were,
     * the page under the cursor is lit. Leanback slides the wide menu out, and this strip takes its place.
     */
    private fun buildRail(root: View) {
        val parent = root as? ViewGroup ?: return
        val strip = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            alpha = 0f
            // a solid panel in the colour of the menu: it also covers the bare edge Leanback leaves of the hidden one
            setBackgroundColor(0xFF1B2E23.toInt())
            setPadding(0, (28 * resources.displayMetrics.widthPixels / 1280f).toInt(), 0, 0)
        }
        // narrower than the free left edge of the page (cards start at about 98 px of 1920), so nothing is covered
        val width = (64 * resources.displayMetrics.widthPixels / 1280f).toInt()
        parent.addView(strip, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START))
        rail = strip
    }

    private fun refreshRail() {
        val strip = rail ?: return
        val scale = resources.displayMetrics.widthPixels / 1280f
        val names = (0 until headers.size()).mapNotNull { (headers.get(it) as? PageRow)?.headerItem?.name }
        val room = resources.displayMetrics.heightPixels - (28 + 24) * scale
        val cell = minOf(44 * scale, room / names.size.coerceAtLeast(1)).toInt()
        val icon = (cell * 0.62f).toInt()
        strip.removeAllViews()
        names.forEach { name ->
            val active = name == currentPage
            strip.addView(ImageView(requireContext()).apply {
                setImageDrawable(MenuIcons.forName(name).apply {
                    setColorFilter(if (active) Color.WHITE else 0xFF94AFA1.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                })
                setPadding((cell - icon) / 2, (cell - icon) / 2, (cell - icon) / 2, (cell - icon) / 2)
                if (active) background = plate(0xFF2A5540.toInt(), cell)
                contentDescription = name
            }, LinearLayout.LayoutParams(cell, cell).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
    }

    private fun plate(color: Int, size: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = size / 4f
    }

    private fun menuSignature(ctx: android.content.Context): String =
        Tmdb.visibleCategories(ctx).joinToString(",") { it.id } + "|" +
            Settings.string(ctx, Settings.LANGUAGE, "ru-RU") + "|" + ClockCheck.skewed

    private fun favoritesState(ctx: android.content.Context): String =
        Settings.string(ctx, "favorites", "[]") + "|" + EpisodeState.revision(ctx)

    /** Settings are applied when the screen comes back: lists appear and disappear without a restart. */
    override fun onResume() {
        super.onResume()
        val ctx = requireContext()
        if (pageToRestore.isEmpty()) LastScreen.rememberPage(ctx, currentPage)
        if (shown != menuSignature(ctx)) {
            showHeaders()
            return
        }
        val favNow = favoritesState(ctx)
        if (favNow != favoritesSignature) {
            favoritesSignature = favNow
            favoritesRows?.let { rows ->
                fillFavorites(rows, ctx)
                // when the last bookmark is removed while the menu is hidden, reopen it so focus is not stranded
                if (currentPage == FAVORITES && !isShowingHeaders() && Favorites.all(ctx).isEmpty() && EpisodeState.favorites(ctx).isEmpty()) {
                    startHeadersTransition(true)
                }
            }
        }
        val torNow = Settings.string(ctx, "favorite_torrents", "[]")
        if (torNow != torrentsSignature) {
            torrentsSignature = torNow
            pages[OVERVIEW]?.let { syncFavoriteTorrents(it, ctx) }
        }
        // a title marked watched on its card: the cards of every opened page are drawn again, dimmed or not
        val marks = Watched.revision(ctx)
        if (marks != watchedMarks) {
            watchedMarks = marks
            pages.values.forEach { rows ->
                for (i in 0 until rows.size()) {
                    val cards = (rows.get(i) as? ListRow)?.adapter as? ArrayObjectAdapter ?: continue
                    cards.notifyArrayItemRangeChanged(0, cards.size())
                }
            }
        }
    }

    private fun showHeaders() {
        val ctx = requireContext()
        shown = menuSignature(ctx)
        watchedMarks = Watched.revision(ctx)
        headers.clear()
        pages.clear()
        pendingLoads.clear()
        pagers.clear()
        favoritesRows = null
        // the overview is the first entry and the page that opens, so the app does not start on the search and jump away
        headers.add(PageRow(HeaderItem(OVERVIEW)))
        headers.add(PageRow(HeaderItem(SEARCH_HEADER)))
        headers.add(PageRow(HeaderItem(FAVORITES)))
        Tmdb.visibleCategories(ctx).forEach { headers.add(PageRow(HeaderItem(it.title.uppercase()))) }
        headers.add(PageRow(HeaderItem(SETTINGS)))
        // (the view does not exist yet when this runs from onCreate: onViewCreated sets it then)
        if (view != null) {
            val keep = (0 until headers.size()).indexOfFirst { (headers.get(it) as? PageRow)?.headerItem?.name == currentPage }
            setSelectedPosition(if (keep >= 0) keep else 0, false)
            if (rail?.visibility == View.VISIBLE) refreshRail()
        }
    }

    /** Rows of the pages: the focused card grows a little, so it is clear at once which one is chosen. */
    private fun rowPresenter(dimOthers: Boolean) = object : ListRowPresenter(FocusHighlight.ZOOM_FACTOR_MEDIUM) {
        // Leanback hides the captions of the rows while the menu has the focus, and they appear only after the page opens;
        // here they are there at once, so the page does not change when it is opened
        override fun onRowViewExpanded(holder: RowPresenter.ViewHolder, expanded: Boolean) {
            super.onRowViewExpanded(holder, expanded)
            showCaption(holder)
        }

        override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any) {
            super.onBindRowViewHolder(holder, item)
            showCaption(holder)
            pendingLoads.remove(item)?.invoke()
        }

        override fun onRowViewSelected(holder: RowPresenter.ViewHolder, selected: Boolean) {
            super.onRowViewSelected(holder, selected)
            showCaption(holder)
        }

        // the caption sits in a dock of the row container, which Leanback closes while the row is not expanded
        private fun showCaption(holder: RowPresenter.ViewHolder) {
            (holder.headerViewHolder?.view?.parent as? View)?.visibility = View.VISIBLE
        }
    }.apply {
        selectEffectEnabled = dimOthers
    }

    /** Rows of a page plus its title row: the title is a part of the page, so it moves, shrinks and dims together with it. */
    private fun pageRows(dimOthers: Boolean) = ClassPresenterSelector()
        .addClassPresenter(PageNoteRow::class.java, PageNotePresenter())
        .addClassPresenter(ListRow::class.java, rowPresenter(dimOthers))

    private fun pageTitle(name: String) = name.lowercase().replaceFirstChar { it.uppercase() }

    private fun pageFor(name: String): ArrayObjectAdapter = pages.getOrPut(name) {
        // settings tiles keep one background in every row: no dimming of the rows under the focused one
        val rows = ArrayObjectAdapter(pageRows(dimOthers = name != SETTINGS))
        val ctx = requireContext()
        when (name) {
            OVERVIEW -> {
                if (ClockCheck.skewed) rows.add(PageNoteRow(ClockCheck.MESSAGE))
                torrentsSignature = Settings.string(ctx, "favorite_torrents", "[]")
                val favTorrents = FavoriteTorrents.all(ctx)
                if (favTorrents.isNotEmpty()) {
                    rows.add(ListRow(HeaderItem("Избранные раздачи"), ArrayObjectAdapter(TorrentCardPresenter()).apply { addAll(0, favTorrents) }))
                }
                Tmdb.overview.forEachIndexed { index, collection ->
                    addRow(rows, collection.title, MoreTile(collection.title, collection = index)) { context -> Tmdb.loadCollection(context, collection) }
                }
            }
            FAVORITES -> {
                favoritesRows = rows
                favoritesSignature = favoritesState(ctx)
                fillFavorites(rows, ctx)
            }
            else -> Unit
        }
        rows
    }

    private fun syncFavoriteTorrents(rows: ArrayObjectAdapter, ctx: android.content.Context) {
        val existingIndex = (0 until rows.size()).indexOfFirst { (rows.get(it) as? ListRow)?.headerItem?.name == "Избранные раздачи" }
        val favTorrents = FavoriteTorrents.all(ctx)
        if (favTorrents.isEmpty()) {
            if (existingIndex >= 0) rows.removeItems(existingIndex, 1)
        } else if (existingIndex >= 0) {
            val adapter = (rows.get(existingIndex) as ListRow).adapter as ArrayObjectAdapter
            adapter.clear()
            adapter.addAll(0, favTorrents)
        } else {
            val insertAt = if (rows.size() > 0 && rows.get(0) is PageNoteRow) 1 else 0
            rows.add(insertAt, ListRow(HeaderItem("Избранные раздачи"), ArrayObjectAdapter(TorrentCardPresenter()).apply { addAll(0, favTorrents) }))
        }
    }

    private fun fillFavorites(rows: ArrayObjectAdapter, ctx: android.content.Context) {
        rows.clear()
        // the bookmarks are one list: the folders are not chosen any more, the old ones are bookmarks too
        val saved = Favorites.all(ctx)
        if (saved.isNotEmpty()) rows.add(ListRow(ArrayObjectAdapter(CardPresenter()).apply { addAll(0, saved) }))
        val episodes = EpisodeState.favorites(ctx)
        if (episodes.isNotEmpty()) {
            rows.add(ListRow(HeaderItem("Избранные серии"), ArrayObjectAdapter(FavoriteEpisodePresenter()).apply {
                addAll(0, episodes)
            }))
        }
        if (rows.size() == 0) rows.add(PageNoteRow("Пока пусто. Добавляйте кнопкой «Закладки» в карточке или «Добавить в избранное» у серии"))
    }

    /** A row of title cards; the "show all" tile closes it, and it opens the whole list as a page. */
    private fun addRow(rows: ArrayObjectAdapter, title: String, more: MoreTile, load: (android.content.Context) -> List<Title>) {
        val presenters = ClassPresenterSelector()
            .addClassPresenter(Title::class.java, CardPresenter())
            .addClassPresenter(MoreTile::class.java, MoreTilePresenter())
            .addClassPresenter(SkeletonCard::class.java, SkeletonPresenter())
        val cards = ArrayObjectAdapter(presenters)
        // grey cards hold the place until the titles come
        repeat(SKELETONS) { cards.add(SkeletonCard) }
        val row = ListRow(HeaderItem(title), cards)
        rows.add(row)
        val ctx = requireContext().applicationContext
        // lazy: the row is asked for only when the list binds it, that is when it comes to the screen or near it
        pendingLoads[row] = {
            lifecycleScope.launch {
                val loaded = withContext(Dispatchers.IO) { runCatching { load(ctx) }.getOrDefault(emptyList()) }
                cards.clear()
                // the tile goes first and takes the cursor when a row is entered
                cards.addAll(0,if (loaded.isEmpty()) loaded else listOf<Any>(more) + loaded)
                if (loaded.isNotEmpty()) pagers[row] = RowPager(more, cards)
            }
        }
    }

    private inner class PageFactory : FragmentFactory<Fragment>() {
        override fun createFragment(row: Any?): Fragment {
            val name = (row as Row).headerItem.name
            currentPage = name
            // the page can be opened after the menu has closed (the restore after an update): the icon rail lights it then
            if (rail?.visibility == View.VISIBLE) refreshRail()
            // the page is remembered for the install script: after an update the menu opens on it (docs/workflow.md)
            if (pageToRestore.isEmpty()) LastScreen.rememberPage(requireContext(), name)
            if (name == SEARCH_HEADER) return SearchPageFragment().also { searchPage = it }
            if (name == SETTINGS) return SettingsPageFragment()
            // films and series are one long grid with lazy loading, not rows of collections with a "Результаты" tile
            val category = Tmdb.visibleCategories(requireContext()).firstOrNull { it.title.uppercase() == name }
            if (category != null) return CatalogPageFragment().apply {
                arguments = android.os.Bundle().apply { putString("category", category.id) }
            }
            val page = TitledRowsFragment().apply {
                arguments = android.os.Bundle().apply {
                    putString("title", pageTitle(name))
                    // the settings tiles stand as close under the title as the cards of "Фильмы"
                    putBoolean("compact", name == SETTINGS)
                }
            }
            val rows = pageFor(name)
            page.adapter = rows
            page.onItemViewClickedListener = onClick
            page.onItemViewSelectedListener = androidx.leanback.widget.OnItemViewSelectedListener { _, item, _, row -> if (row is ListRow) loadMoreOf(row, item) }
            return page
        }
    }

    companion object {
        /** Fragment argument: open the menu page the user was on before the update (docs/workflow.md). */
        const val RESTORE = "restore"
    }
}

fun titleFrom(extras: android.os.Bundle) = Title(
    extras.getLong("id"), extras.getString("name").orEmpty(), extras.getString("overview").orEmpty(),
    extras.getString("poster"), extras.getBoolean("tv"), extras.getString("year").orEmpty(),
    extras.getString("rating").orEmpty(),
)

fun Title.toExtras() = android.os.Bundle().apply {
    putLong("id", id)
    putString("name", name)
    putString("overview", overview)
    putString("poster", poster)
    putBoolean("tv", tv)
    putString("year", year)
    putString("rating", rating)
}

/** Rating over the poster: just large text with a shadow, no filled plate behind it. */
class BadgeDrawable(private val text: String, color: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.CENTER
        setShadowLayer(5f, 0f, 1f, 0xCC000000.toInt())
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        canvas.drawText(text, w / 2, h / 2 - (paint.descent() + paint.ascent()) / 2, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
    override fun getIntrinsicWidth() = 132
    override fun getIntrinsicHeight() = 52
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

private const val SKELETONS = 7

/** [cardScale] is the one of the matching [CardPresenter]. */
class SkeletonPresenter(private val cardScale: Float = 1f) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder =
        ViewHolder(SkeletonCardView(parent.context, parent.resources.displayMetrics.widthPixels / 1280f * cardScale))

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {}

    override fun onUnbindViewHolder(holder: ViewHolder) {}
}

class CardPresenter(private val cardScale: Float = 1f) : Presenter() {
    private companion object {
        /** Runtimes are not in the lists: a few threads fetch them for the cards that are on the screen. */
        val runtimePool: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(3)
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder =
        ViewHolder(TitleCard(parent.context, parent.resources.displayMetrics.widthPixels / 1280f * cardScale))

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {
        val card = holder.view as TitleCard
        val title = item as Title
        card.bind(title)
        // long press on OK marks the film watched (or takes the mark back)
        card.setOnLongClickListener {
            when (card.toggleWatched()) {
                true -> android.widget.Toast.makeText(card.context, "Отмечено просмотренным", android.widget.Toast.LENGTH_SHORT).show()
                false -> android.widget.Toast.makeText(card.context, "Отметка снята", android.widget.Toast.LENGTH_SHORT).show()
                null -> Unit
            }
            true
        }
        val appContext = card.context.applicationContext
        card.runtimeTask?.cancel(false)
        card.runtimeTask = runtimePool.submit {
            if (card.shownId != title.id) return@submit
            val details = runCatching { Tmdb.detailsCached(appContext, title.id, title.tv) }.getOrNull()
            if (card.shownId != title.id) return@submit
            val minutes = details?.runtime ?: 0
            if (minutes > 0) card.post { card.setRuntime(title.id, minutes) }
            // the saved records keep no genre: the card details fill the plate so it has no empty bottom
            val genres = details?.genres?.joinToString(", ").orEmpty()
            if (title.genre.isEmpty() && genres.isNotEmpty()) card.post { card.setGenre(title.id, genres) }
        }
        // the chosen title is about to be opened: its wide backdrop is loaded into the cache now, so the card
        // screen does not wait for it (the details are cached by the call above or this one)
        // it waits a moment and only runs if the cursor stays on the card: scrolling through a row loads nothing
        val warmUp = Runnable {
            if (card.isFocused && card.shownId == title.id) runtimePool.execute {
                val backdrop = runCatching { Tmdb.detailsCached(appContext, title.id, title.tv).backdrop }.getOrDefault("")
                if (backdrop.isNotEmpty()) card.post {
                    Glide.with(appContext).load("https://image.tmdb.org/t/p/original$backdrop").preload()
                }
            }
        }
        card.onFocused = {
            card.removeCallbacks(warmUp)
            card.postDelayed(warmUp, 300)
        }
        val poster = title.poster
        // w500 stays sharp at the card's size on a 1080p screen; w342 was upscaled and looked soft
        // no poster (or TMDB has none at all): the card says so instead of showing an empty plate
        card.showPoster(poster)
    }

    override fun onUnbindViewHolder(holder: ViewHolder) {
        val card = holder.view as TitleCard
        card.runtimeTask?.cancel(false)
        card.runtimeTask = null
        card.poster.setImageDrawable(null)
    }
}

/** A calm line of plain text instead of an empty-state card. */
class PlainTextPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val scale = parent.resources.displayMetrics.widthPixels / 1280f
        val view = TextView(parent.context).apply {
            setTextColor(0xFFDCE8E0.toInt())
            textSize = 18f
            gravity = Gravity.START
            setPadding(0, (18 * scale).toInt(), (24 * scale).toInt(), (18 * scale).toInt())
            isFocusable = false
            isClickable = false
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {
        ((holder.view as TextView).text) = item as String
    }

    override fun onUnbindViewHolder(holder: ViewHolder) {}
}

/**
 * Settings page as one three-column grid instead of separate horizontal rows, so Up/Down stays in the same column
 * and Right at the end of a row continues onto the first tile of the next row.
 */
class SettingsPageFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {
    private val pageAdapter = object : BrowseSupportFragment.MainFragmentAdapter<SettingsPageFragment>(this) {
        override fun onTransitionStart() {
            super.onTransitionStart()
            view?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        override fun onTransitionEnd() {
            super.onTransitionEnd()
            view?.setLayerType(View.LAYER_TYPE_NONE, null)
        }

        override fun setExpand(expand: Boolean) {
            super.setExpand(expand)
            view?.animate()?.alpha(if (expand) 1f else 0.55f)?.setDuration(200)?.start()
        }
    }

    override fun getMainFragmentAdapter() = pageAdapter.also { it.isScalingEnabled = true }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: android.os.Bundle?): View {
        val scale = resources.displayMetrics.widthPixels / 1280f
        fun px(v: Int) = (v * scale).toInt()
        val page = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(92), px(21), px(48), px(16))
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(resources.displayMetrics.widthPixels, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        page.addView(TextView(requireContext()).apply {
            text = "Настройки"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f * scale)
        })
        val tiles = ArrayObjectAdapter(SettingsSectionPresenter()).apply { addAll(0, SETTINGS_SECTIONS) }
        val bridge = androidx.leanback.widget.ItemBridgeAdapter(tiles)
        bridge.setAdapterListener(object : androidx.leanback.widget.ItemBridgeAdapter.AdapterListener() {
            override fun onCreate(viewHolder: androidx.leanback.widget.ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    (viewHolder.item as? SettingsSection)?.let { section ->
                        startActivity(Intent(requireContext(), SettingsActivity::class.java).putExtra("section", section.title))
                    }
                }
            }
        })
        val gap = px(12)
        val side = px(20)
        val grid = titleCardGrid(requireContext(), scale, bridge, COLUMNS).apply {
            setItemSpacing(gap)
        }
        fun moveTo(target: Int) {
            grid.selectedPosition = target
            grid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
        }
        grid.setOnKeyInterceptListener { event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyInterceptListener false
            val pos = grid.selectedPosition
            val count = SETTINGS_SECTIONS.size
            if (pos !in 0 until count) return@setOnKeyInterceptListener false
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (pos + 1 < count) moveTo(pos + 1)
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // on the first column Left passes through so BrowseSupportFragment opens the side menu
                    if (pos % COLUMNS == 0) false
                    else {
                        moveTo(pos - 1)
                        true
                    }
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val nextRowStart = (pos / COLUMNS + 1) * COLUMNS
                    if (nextRowStart < count) moveTo(minOf(pos + COLUMNS, count - 1))
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    if (pos >= COLUMNS) moveTo(pos - COLUMNS)
                    true
                }
                else -> false
            }
        }
        val gridWidth = COLUMNS * px(290) + (COLUMNS - 1) * gap + 2 * side
        page.addView(grid, LinearLayout.LayoutParams(gridWidth, 0, 1f).apply {
            setMargins(-side, px(14), 0, 0)
        })
        return page
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pageAdapter.fragmentHost?.notifyViewCreated(pageAdapter)
        pageAdapter.fragmentHost?.notifyDataReady(pageAdapter)
    }

    private companion object {
        const val COLUMNS = 3
    }
}

/** One settings section as a compact card with a caption and a short explanation. */
class SettingsSectionPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val scale = parent.resources.displayMetrics.widthPixels / 1280f
        fun dp(v: Int) = (v * scale).toInt()
        val tile = FrameLayout(parent.context)
        tile.layoutParams = ViewGroup.LayoutParams(dp(290), dp(124))
        tile.isFocusable = true
        tile.isFocusableInTouchMode = true
        val texts = android.widget.LinearLayout(parent.context)
        texts.orientation = android.widget.LinearLayout.VERTICAL
        texts.setPadding(dp(16), dp(14), dp(16), dp(14))
        val title = TextView(parent.context).apply {
            setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; maxLines = 2
        }
        val description = TextView(parent.context).apply {
            setTextColor(0xFFAFC4B6.toInt()); textSize = 13f; maxLines = 3; setPadding(0, dp(8), 0, 0)
        }
        texts.addView(title)
        texts.addView(description)
        tile.addView(texts, FrameLayout.LayoutParams(-1, -1))
        tile.tag = Pair(title, description)
        return ViewHolder(tile)
    }

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {
        val section = item as SettingsSection
        val tile = holder.view as FrameLayout
        tile.setBackgroundColor(if (tile.isFocused) 0xFF2F7D57.toInt() else 0x33000000)
        tile.setOnFocusChangeListener { _, focused -> tile.setBackgroundColor(if (focused) 0xFF2F7D57.toInt() else 0x33000000) }
        @Suppress("UNCHECKED_CAST")
        val texts = tile.tag as Pair<TextView, TextView>
        texts.first.text = section.title
        texts.second.text = section.description
    }

    override fun onUnbindViewHolder(holder: ViewHolder) {}
}

/** A starred episode: the series title and the episode on a dark tile; picking it opens the episode list. */
class FavoriteEpisodePresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val scale = parent.resources.displayMetrics.widthPixels / 1280f
        fun dp(v: Int) = (v * scale).toInt()
        val tile = FrameLayout(parent.context)
        tile.layoutParams = ViewGroup.LayoutParams(dp(280), dp(190))
        tile.isFocusable = true
        tile.isFocusableInTouchMode = true
        val texts = android.widget.LinearLayout(parent.context)
        texts.orientation = android.widget.LinearLayout.VERTICAL
        texts.setPadding(dp(16), dp(14), dp(16), dp(14))
        val series = TextView(parent.context).apply {
            setTextColor(Color.WHITE); textSize = 16f; maxLines = 3; typeface = Typeface.DEFAULT_BOLD
        }
        val episode = TextView(parent.context).apply {
            setTextColor(0xFFDCE8E0.toInt()); textSize = 13f; maxLines = 2; setPadding(0, dp(8), 0, 0)
        }
        val mark = TextView(parent.context).apply { setTextColor(0xFFE0B94F.toInt()); textSize = 13f }
        texts.addView(series)
        texts.addView(episode)
        texts.addView(mark)
        tile.addView(texts, FrameLayout.LayoutParams(-1, -1))
        tile.tag = Triple(series, episode, mark)
        return ViewHolder(tile)
    }

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {
        val favorite = item as FavoriteEpisode
        val tile = holder.view as FrameLayout
        tile.setBackgroundColor(if (tile.isFocused) 0xFF2F7D57.toInt() else 0x33000000)
        tile.setOnFocusChangeListener { _, focused -> tile.setBackgroundColor(if (focused) 0xFF2F7D57.toInt() else 0x33000000) }
        @Suppress("UNCHECKED_CAST")
        val texts = tile.tag as Triple<TextView, TextView, TextView>
        texts.first.text = favorite.seriesTitle
        texts.second.text = favorite.episodeTitle
        texts.third.text = "★"
    }

    override fun onUnbindViewHolder(holder: ViewHolder) {}
}

/** Tile of a starred torrent: title, size and source on a flat dark card (no poster — torrents have none). */
class TorrentCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val scale = parent.resources.displayMetrics.widthPixels / 1280f
        fun dp(v: Int) = (v * scale).toInt()
        val tile = FrameLayout(parent.context)
        tile.layoutParams = ViewGroup.LayoutParams(dp(240), dp(180))
        tile.isFocusable = true
        tile.isFocusableInTouchMode = true
        val texts = android.widget.LinearLayout(parent.context)
        texts.orientation = android.widget.LinearLayout.VERTICAL
        texts.setPadding(dp(14), dp(14), dp(14), dp(14))
        val title = TextView(parent.context).apply { setTextColor(Color.WHITE); textSize = 15f; maxLines = 3; typeface = Typeface.DEFAULT_BOLD }
        val info = TextView(parent.context).apply { setTextColor(0xFF8FB39D.toInt()); textSize = 12f; setPadding(0, dp(8), 0, 0) }
        texts.addView(title)
        texts.addView(info)
        tile.addView(texts, FrameLayout.LayoutParams(-1, -1))
        tile.tag = Pair(title, info)
        return ViewHolder(tile)
    }

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {
        val torrent = item as Torrent
        val tile = holder.view as FrameLayout
        tile.setBackgroundColor(if (tile.isFocused) 0xFF2F7D57.toInt() else 0x33000000)
        tile.setOnFocusChangeListener { _, focused -> tile.setBackgroundColor(if (focused) 0xFF2F7D57.toInt() else 0x33000000) }
        @Suppress("UNCHECKED_CAST")
        val pair = tile.tag as Pair<TextView, TextView>
        pair.first.text = torrent.title
        pair.second.text = "${torrent.source} · ${torrent.size}"
    }

    override fun onUnbindViewHolder(holder: ViewHolder) {}
}

/** Section caption ("СПИСКИ") in a muted green instead of the bright default. */
class SectionHeaderPresenter : RowHeaderPresenter(androidx.leanback.R.layout.lb_section_header, false) {
    override fun onBindViewHolder(viewHolder: androidx.leanback.widget.Presenter.ViewHolder, item: Any?) {
        super.onBindViewHolder(viewHolder, item)
        (viewHolder.view as? android.widget.TextView)?.setTextColor(0xFF6FA487.toInt())
    }
}


/**
 * A line of text for a page with nothing in it (the empty bookmarks). It is only a marker in the rows: the page
 * itself shows the text right under its title ([TitledRowsFragment]), so it does not depend on how the list aligns its rows.
 */
class PageNoteRow(val text: String) : Row()

class PageNotePresenter : RowPresenter() {
    init {
        headerPresenter = null
        selectEffectEnabled = false
    }

    override fun createRowViewHolder(parent: ViewGroup): RowPresenter.ViewHolder =
        RowPresenter.ViewHolder(View(parent.context).apply { isFocusable = false; minimumHeight = 1 })

    override fun isUsingDefaultSelectEffect() = false
}

/**
 * The rows of a page under its title. The title is a part of the page, so it moves and scales with it, but it is not a row:
 * it does not scroll away with the rows and stands at the same place with the menu open and closed, like on the catalogue pages.
 */
class TitledRowsFragment : RowsSupportFragment() {
    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: android.os.Bundle?): View? {
        val rows = super.onCreateView(inflater, container, savedInstanceState) ?: return null
        val scale = resources.displayMetrics.widthPixels / 1280f
        // measured on the emulator: with these numbers the title and the first tiles of the settings sit where the title and cards of "Фильмы" do
        val compact = requireArguments().getBoolean("compact")
        val title = TextView(requireContext()).apply {
            text = requireArguments().getString("title")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f * scale)
            setPadding(resources.getDimensionPixelSize(androidx.leanback.R.dimen.lb_browse_padding_start), (30 * scale).toInt(), 0, (6 * scale).toInt())
        }
        note = TextView(requireContext()).apply {
            setTextColor(0xFF8FA597.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
            setPadding(resources.getDimensionPixelSize(androidx.leanback.R.dimen.lb_browse_padding_start), (6 * scale).toInt(), 0, 0)
            visibility = View.GONE
        }
        return FrameLayout(requireContext()).apply {
            addView(rows, FrameLayout.LayoutParams(-1, -1).apply { topMargin = ((if (compact) 39 else 72) * scale).toInt() })
            addView(android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(title, android.widget.LinearLayout.LayoutParams(-1, -2))
                addView(note, android.widget.LinearLayout.LayoutParams(-1, -2))
            }, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private lateinit var note: TextView

    /** The text of the note row, when the page has one, stands under the title. */
    private val noteWatcher = object : androidx.leanback.widget.ObjectAdapter.DataObserver() {
        override fun onChanged() = showNote()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = showNote()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = showNote()
    }

    private fun showNote() {
        val rows = adapter ?: return
        val text = (0 until rows.size()).firstNotNullOfOrNull { (rows.get(it) as? PageNoteRow)?.text }
        note.text = text.orEmpty()
        note.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter?.registerObserver(noteWatcher)
        showNote()
    }

    override fun onDestroyView() {
        adapter?.unregisterObserver(noteWatcher)
        super.onDestroyView()
    }
}
