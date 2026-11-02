package ru.lazytv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
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
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val OVERVIEW = "ОБЗОР"
private const val FAVORITES = "ЗАКЛАДКИ"
private const val HISTORY = "ИСТОРИЯ"
private const val SETTINGS = "НАСТРОЙКИ"
private const val ADULT = "18+"

/** A tile that asks for the parental code before the adult list opens. */
private data class LockTile(val label: String, val ok: () -> Unit)

/** A category tile of a service page, with the service it belongs to. */
data class SourceCategory(val source: VideoSource, val category: ServiceCategory)

/** Opens the settings screen from the tile on the settings page. */
object OpenSettings

class MainFragment : BrowseSupportFragment() {
    private val headers = ArrayObjectAdapter(ListRowPresenter())
    private val pages = HashMap<String, ArrayObjectAdapter>()
    private var shown = ""
    /** The adult list was opened with the parental code during this screen's life. */
    private var adultUnlocked = false
    /** Adapters of the two pages that change when an episode is marked watched or starred. */
    private var favoritesRows: ArrayObjectAdapter? = null
    private var historyRows: ArrayObjectAdapter? = null
    private var episodeRevision = ""

    private val onClick = OnItemViewClickedListener { _, item, _, _ ->
        when (item) {
            is Title -> startActivity(Intent(requireContext(), DetailsActivity::class.java).putExtras(item.toExtras()))
            is Torrent -> startActivity(
                Intent(requireContext(), TorrentsActivity::class.java).putExtra("name", item.title)
                    .putExtra("magnet", item.magnet).putExtra("size", item.size).putExtra("seeds", item.seeds)
                    .putExtra("leeches", item.leeches).putExtra("source", item.source)
            )
            is SourceCategory -> startActivity(
                Intent(requireContext(), ServiceActivity::class.java)
                    .putExtra("source", item.source.id).putExtra("category", item.category.id)
            )
            is ServiceItem -> startActivity(
                Intent(requireContext(), EpisodesActivity::class.java)
                    .putExtra("source", item.variant.source).putExtra("sourceId", item.variant.sourceId)
                    .putExtra("title", item.variant.title).putExtra("note", item.variant.note)
                    .putExtra("ref", item.variant.ref).putExtra("alt", item.variant.alt)
                    .putExtra("description", item.variant.description)
            )
            is FavoriteEpisode -> startActivity(
                Intent(requireContext(), EpisodesActivity::class.java)
                    .putExtra("source", item.source).putExtra("sourceId", item.sourceId)
                    .putExtra("title", item.seriesTitle).putExtra("note", item.note).putExtra("ref", item.ref)
                    .putExtra("alt", item.alt).putExtra("description", item.description)
                    .putExtra("episodeIndex", item.index)
            )
            OpenSettings -> startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Lazy TV"
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = 0xFF1B2E23.toInt()
        searchAffordanceColor = 0xFF2F7D57.toInt()
        setOnSearchClickedListener { startActivity(Intent(requireContext(), SearchActivity::class.java)) }
        mainFragmentRegistry.registerFragment(PageRow::class.java, PageFactory())
        adapter = headers
        showHeaders()
    }

    /** The menu fragment exists only after the view is created, so its look is set here. */
    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        headersSupportFragment?.presenterSelector = ClassPresenterSelector()
            .addClassPresenter(DividerRow::class.java, DividerPresenter())
            .addClassPresenter(SectionRow::class.java, SectionHeaderPresenter())
            .addClassPresenter(Row::class.java, IconHeaderPresenter())
    }

    /** Settings are applied when the screen comes back: lists appear and disappear without a restart. */
    override fun onResume() {
        super.onResume()
        val ctx = requireContext()
        val revision = EpisodeState.revision(ctx)
        if (revision != episodeRevision) {
            episodeRevision = revision
            // the episode marks change the bookmarks and the history while the player screen was open
            favoritesRows?.let { fillFavorites(it, ctx) }
            historyRows?.let { fillHistory(it, ctx) }
        }
        if (shown != Settings.snapshot(ctx)) showHeaders()
    }

    private fun showHeaders() {
        val ctx = requireContext()
        shown = Settings.snapshot(ctx)
        if (Settings.string(ctx, Settings.PARENTAL_PIN, "").isNotEmpty()) adultUnlocked = false
        headers.clear()
        pages.clear()
        favoritesRows = null
        historyRows = null
        headers.add(PageRow(HeaderItem(OVERVIEW)))
        headers.add(PageRow(HeaderItem(FAVORITES)))
        headers.add(PageRow(HeaderItem(HISTORY)))
        headers.add(SectionRow(HeaderItem("СПИСКИ")))
        Tmdb.visibleCategories(ctx).forEach { headers.add(PageRow(HeaderItem(it.title.uppercase()))) }
        val catalog = Sources.enabled(ctx)
        if (catalog.isNotEmpty()) {
            headers.add(SectionRow(HeaderItem("ВИДЕОСЕРВИСЫ")))
            catalog.forEach { headers.add(PageRow(HeaderItem(it.name))) }
        }
        headers.add(DividerRow())
        headers.add(PageRow(HeaderItem(SETTINGS)))
    }

    private fun pageFor(name: String): ArrayObjectAdapter = pages.getOrPut(name) {
        val rows = ArrayObjectAdapter(ListRowPresenter())
        val ctx = requireContext()
        when (name) {
            OVERVIEW -> {
                val favTorrents = FavoriteTorrents.all(ctx)
                if (favTorrents.isNotEmpty()) {
                    rows.add(ListRow(HeaderItem("Избранные раздачи"), ArrayObjectAdapter(TorrentCardPresenter()).apply { addAll(0, favTorrents) }))
                }
                Tmdb.overview.forEach { collection ->
                    addRow(rows, collection.title) { context -> Tmdb.loadCollection(context, collection) }
                }
            }
            FAVORITES -> {
                favoritesRows = rows
                fillFavorites(rows, ctx)
            }
            HISTORY -> {
                historyRows = rows
                fillHistory(rows, ctx)
            }
            SETTINGS -> rows.add(ListRow(HeaderItem("Настройки приложения"), ArrayObjectAdapter(SettingsTilePresenter()).apply {
                add(OpenSettings)
            }))
            else -> servicePage(rows, name, ctx) ?: Tmdb.visibleCategories(ctx).firstOrNull { it.title.uppercase() == name }?.let { category ->
                Tmdb.rows.forEach { (title, sort) -> addRow(rows, title) { context -> Tmdb.load(context, category, sort) } }
            } ?: Unit
        }
        rows
    }

    private fun fillFavorites(rows: ArrayObjectAdapter, ctx: android.content.Context) {
        rows.clear()
        Folder.values().forEach { folder ->
            val saved = Favorites.inFolder(ctx, folder)
            if (saved.isNotEmpty()) rows.add(ListRow(HeaderItem(folder.label), ArrayObjectAdapter(CardPresenter()).apply { addAll(0, saved) }))
        }
        val episodes = EpisodeState.favorites(ctx)
        if (episodes.isNotEmpty()) {
            rows.add(ListRow(HeaderItem("Избранные серии"), ArrayObjectAdapter(FavoriteEpisodePresenter()).apply {
                addAll(0, episodes)
            }))
        }
        if (rows.size() == 0) rows.add(ListRow(HeaderItem("Закладки"), ArrayObjectAdapter(PlainTextPresenter()).apply {
            add("Пока пусто. Добавляйте кнопкой «Закладки» в карточке или через ⋮ у серии")
        }))
    }

    private fun fillHistory(rows: ArrayObjectAdapter, ctx: android.content.Context) {
        rows.clear()
        val seen = History.all(ctx)
        if (seen.isEmpty()) {
            rows.add(ListRow(HeaderItem("Недавно смотрели"), ArrayObjectAdapter(PlainTextPresenter()).apply { add("Пока пусто") }))
            return
        }
        val cards = ArrayObjectAdapter(CardPresenter())
        cards.addAll(0, seen)
        rows.add(ListRow(HeaderItem("Недавно смотрели"), cards))
        fillPosters(cards, seen, ctx)
    }

    /**
     * Records saved before the card asked TMDB for a poster have none, and their card stayed empty. The posters are
     * filled in once, saved into the record and the row is drawn again.
     */
    private fun fillPosters(cards: ArrayObjectAdapter, titles: List<Title>, ctx: android.content.Context) {
        if (titles.none { it.poster == null }) return
        lifecycleScope.launch {
            val fixed = withContext(Dispatchers.IO) {
                titles.map { title ->
                    val poster = title.poster ?: runCatching { Tmdb.details(ctx, title.id, title.tv) }.getOrNull()?.poster.orEmpty()
                    if (title.poster == null && poster.isNotEmpty()) {
                        History.setPoster(ctx, title.id, title.tv, poster)
                        title.copy(poster = poster)
                    } else title
                }
            }
            if (fixed != titles) {
                cards.clear()
                cards.addAll(0, fixed)
            }
        }
    }

    private fun addRow(rows: ArrayObjectAdapter, title: String, load: (android.content.Context) -> List<Title>) =
        addCards(rows, title, CardPresenter()) { load(it) }

    private fun addCards(rows: ArrayObjectAdapter, title: String, presenter: Presenter, load: (android.content.Context) -> List<Any>) {
        val cards = ArrayObjectAdapter(presenter)
        rows.add(ListRow(HeaderItem(title), cards))
        val ctx = requireContext().applicationContext
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { load(ctx) }.getOrDefault(emptyList()) }
            cards.addAll(0, loaded)
        }
    }

    /**
     * Page of one video service: its categories go one under another, and each row is filled with real poster
     * cards as soon as the site answers, so the page opens already showing what the service has — no taps on tiles.
     * Returns null when the name belongs to no service, and the caller looks among the catalogue lists.
     */
    private fun servicePage(rows: ArrayObjectAdapter, name: String, ctx: android.content.Context): Unit? {
        val source = Sources.enabled(ctx).firstOrNull { it.name == name } ?: return null
        if (source.categories.isEmpty()) {
            rows.add(ListRow(HeaderItem(source.name), ArrayObjectAdapter(PlainTextPresenter()).apply {
                add("У этого сервиса нет каталога: название ищется в общем поиске и в карточке фильма.")
            }))
            return Unit
        }
        lifecycleScope.launch {
            source.categories.forEach { category ->
                val items = withContext(Dispatchers.IO) { runCatching { source.browse(category) }.getOrDefault(emptyList()) }
                if (items.isEmpty()) return@forEach
                val cards = ArrayObjectAdapter(ServiceCardPresenter())
                cards.addAll(0, items)
                rows.add(ListRow(HeaderItem(category.title), cards))
            }
        }
        return Unit
    }

    private inner class PageFactory : FragmentFactory<Fragment>() {
        override fun createFragment(row: Any?): Fragment {
            val page = RowsSupportFragment()
            val name = (row as Row).headerItem.name
            title = name
            val pin = Settings.string(requireContext(), Settings.PARENTAL_PIN, "")
            if (name == ADULT && pin.isNotEmpty() && !adultUnlocked) {
                // the list stays closed until the code is typed; the page draws a single lock tile
                val tile = ArrayObjectAdapter(SettingsTilePresenter()).apply { add(LockTile("18+ • Введите код") { unlockAdult(page, name) }) }
                page.adapter = ArrayObjectAdapter(ListRowPresenter()).apply { add(ListRow(HeaderItem("Родительский контроль"), tile)) }
                page.onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
                    if (item is LockTile) item.ok()
                }
                return page
            }
            page.adapter = pageFor(name)
            page.onItemViewClickedListener = onClick
            return page
        }
    }

    private fun unlockAdult(page: RowsSupportFragment, name: String) {
        askPin { ok ->
            if (ok) {
                adultUnlocked = true
                page.adapter = pageFor(name)
                page.onItemViewClickedListener = onClick
            }
        }
    }

    /** Numeric code dialog of the parental control; the callback gets true only for the saved code. */
    private fun askPin(done: (Boolean) -> Unit) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Код"
            setSingleLine()
        }
        android.app.AlertDialog.Builder(requireContext()).setTitle("Родительский контроль")
            .setMessage("Введите код из настроек")
            .setView(input)
            .setPositiveButton("Открыть") { _, _ ->
                val ok = input.text.toString() == Settings.string(requireContext(), Settings.PARENTAL_PIN, "")
                if (!ok) android.widget.Toast.makeText(requireContext(), "Неверный код", android.widget.Toast.LENGTH_SHORT).show()
                done(ok)
            }
            .setNegativeButton("Отмена") { _, _ -> done(false) }
            .show()
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

/** A small rounded badge with a number, drawn over the poster of a card. */
class BadgeDrawable(private val text: String, private val background: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 30f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.CENTER
    }
    private val plate = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = background }

    override fun draw(canvas: android.graphics.Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, plate)
        canvas.drawText(text, w / 2, h / 2 - (paint.descent() + paint.ascent()) / 2, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; plate.alpha = alpha }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter; plate.colorFilter = filter }
    override fun getIntrinsicWidth() = 64
    override fun getIntrinsicHeight() = 36
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

class CardPresenter : Presenter() {    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context)
        card.isFocusable = true
        card.setMainImageDimensions(240, 360)
        return ViewHolder(card)
    }

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {
        val title = item as Title
        val card = holder.view as ImageCardView
        card.titleText = title.name
        card.contentText = listOf(title.year, title.rating).filter { it.isNotEmpty() }.joinToString("  ★ ")
        // the rating on the poster itself, colored by value, as the LazyMedia cards show it
        val rating = title.rating.toDoubleOrNull()
        card.badgeImage = if (rating != null && rating > 0) {
            BadgeDrawable("%.1f".format(rating), when {
                rating >= 7.5 -> 0xFF1B5E20.toInt()
                rating >= 6.0 -> 0xFF2F7D57.toInt()
                else -> 0xFF6D4C41.toInt()
            })
        } else null
        val poster = title.poster
        if (poster != null) Glide.with(card.context).load("https://image.tmdb.org/t/p/w342$poster").into(card.mainImageView)
        // no poster yet: an empty card, not the picture of a recycled one
        else Glide.with(card.context).clear(card.mainImageView)
    }

    override fun onUnbindViewHolder(holder: ViewHolder) {
        (holder.view as ImageCardView).mainImage = null
        (holder.view as ImageCardView).badgeImage = null
    }
}

/** Poster card of one release of a service catalogue: the same look as the TMDB cards of the app. */
class ServiceCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context)
        card.isFocusable = true
        card.setMainImageDimensions(240, 360)
        return ViewHolder(card)
    }

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {
        val release = item as ServiceItem
        val card = holder.view as ImageCardView
        card.titleText = release.title
        card.contentText = release.note
        card.badgeImage = null
        val poster = release.poster
        if (poster != null) Glide.with(card.context).load(poster).into(card.mainImageView)
        else Glide.with(card.context).clear(card.mainImageView)
    }

    override fun onUnbindViewHolder(holder: ViewHolder) {
        (holder.view as ImageCardView).mainImage = null
    }
}

/** A calm line of plain text instead of an empty-state card. */
class PlainTextPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val scale = parent.resources.displayMetrics.widthPixels / 1280f
        val view = TextView(parent.context).apply {
            setTextColor(0xFFDCE8E0.toInt())
            textSize = 18f
            setPadding((24 * scale).toInt(), (18 * scale).toInt(), (24 * scale).toInt(), (18 * scale).toInt())
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

/** Colored tile in the style of LazyMedia: a big caption on a flat color. */
class SettingsTilePresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val tile = FrameLayout(parent.context)
        tile.layoutParams = ViewGroup.LayoutParams(240, 360)
        tile.setBackgroundColor(Color.parseColor("#455A64"))
        tile.isFocusable = true
        tile.isFocusableInTouchMode = true
        val caption = TextView(parent.context)
        caption.text = "Настройки"
        caption.setTextColor(Color.WHITE)
        caption.textSize = 22f
        caption.typeface = Typeface.DEFAULT_BOLD
        caption.gravity = Gravity.CENTER
        tile.addView(caption, FrameLayout.LayoutParams(-1, -1))
        return ViewHolder(tile)
    }

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {
        val caption = ((holder.view as FrameLayout).getChildAt(0) as TextView)
        caption.text = when (item) {
            is String -> item
            is TorrentQuery -> "Искать торренты: ${item.query}"
            is LockTile -> item.label
            else -> "Настройки"
        }
        caption.textSize = if (item is OpenSettings) 22f else 16f
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

/** A category of a service page in the LazyMedia style: a colored tile with the caption. */
class CategoryPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val scale = parent.resources.displayMetrics.widthPixels / 1280f
        val tile = FrameLayout(parent.context)
        tile.layoutParams = ViewGroup.LayoutParams((250 * scale).toInt(), (260 * scale).toInt())
        tile.isFocusable = true
        tile.isFocusableInTouchMode = true
        val caption = TextView(parent.context).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((18 * scale).toInt(), 0, (18 * scale).toInt(), (24 * scale).toInt())
        }
        tile.addView(caption, FrameLayout.LayoutParams(-1, -1))
        tile.tag = caption
        return ViewHolder(tile)
    }

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {
        val pair = item as SourceCategory
        val tile = holder.view as FrameLayout
        (tile.tag as TextView).text = pair.category.title
        val color = pair.category.color
        tile.setBackgroundColor(if (tile.isFocused) lighten(color) else color)
        tile.setOnFocusChangeListener { _, focused -> tile.setBackgroundColor(if (focused) lighten(color) else color) }
    }

    private fun lighten(color: Int): Int {
        val r = (Color.red(color) + 45).coerceAtMost(255)
        val g = (Color.green(color) + 45).coerceAtMost(255)
        val b = (Color.blue(color) + 45).coerceAtMost(255)
        return Color.rgb(r, g, b)
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
