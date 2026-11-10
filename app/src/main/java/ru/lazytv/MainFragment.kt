package ru.lazytv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FAVORITES = "ЗАКЛАДКИ"
private const val SETTINGS = "НАСТРОЙКИ"

/** One section of the settings, shown as its own card on the settings page. */
data class SettingsSection(val title: String, val description: String)

val SETTINGS_SECTIONS = listOf(
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

class MainFragment : Fragment() {
    private val CONTENT_CONTAINER_ID = View.generateViewId()

    private var shown = ""
    private var watchedMarks = ""

    private var currentPage = ""
    /** The page the menu must open after an update; empty when the menu starts as usual (docs/workflow.md). */
    private var pageToRestore = ""
    private var searchPage: SearchPageFragment? = null

    private lateinit var rootLayout: RootLayout
    private lateinit var contentContainer: FrameLayout
    private lateinit var railContainer: LinearLayout
    private val navItems = mutableListOf<NavItemView>()

    private val uiHandler = Handler(Looper.getMainLooper())
    private val switchPageDebounce = Runnable {
        val focusedItem = navItems.firstOrNull { it.isFocused }
        if (focusedItem != null && focusedItem.name != currentPage) {
            openPage(focusedItem.name, requestContentFocus = false)
        }
    }

    fun voiceResult(requestCode: Int, resultCode: Int, data: Intent?) {
        searchPage?.voiceResult(requestCode, resultCode, data)
    }

    fun permissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        searchPage?.permissionsResult(requestCode, permissions, grantResults)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (arguments?.getBoolean(RESTORE, false) == true) {
            pageToRestore = LastScreen.page(requireContext())
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scale = resources.displayMetrics.widthPixels / 1280f
        val railWidth = (68 * scale).toInt()
        val cell = (44 * scale).toInt()
        val sidePadding = (railWidth - cell) / 2
        val topPadding = (27 * scale).toInt()
        val bottomPadding = (16 * scale).toInt()

        rootLayout = RootLayout(ctx)

        contentContainer = FrameLayout(ctx).apply {
            id = CONTENT_CONTAINER_ID
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        rootLayout.addView(contentContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val railPanel = FrameLayout(ctx).apply {
            setBackgroundColor(0xFF161920.toInt())
        }

        val railScroll = object : ScrollView(ctx) {
            init {
                isVerticalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
            }
        }

        railContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(sidePadding, topPadding, sidePadding, bottomPadding + (48 * scale).toInt())
        }
        railScroll.addView(railContainer, FrameLayout.LayoutParams(railWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        railPanel.addView(railScroll, FrameLayout.LayoutParams(railWidth, ViewGroup.LayoutParams.MATCH_PARENT))

        val railLogo = Logo.create(ctx, textSizeSp = 9f).apply {
            isFocusable = false
            isClickable = false
        }
        railPanel.addView(railLogo, FrameLayout.LayoutParams(railWidth, (36 * scale).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (14 * scale).toInt()
        })

        rootLayout.addView(railPanel, FrameLayout.LayoutParams(railWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START or Gravity.TOP))

        return rootLayout
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildNavRail()

        // a page saved by an older version (the removed overview) is not in the menu: start from the home page
        val initialPage = pageToRestore.takeIf { saved -> navItems.any { it.name == saved } } ?: homePage()
        pageToRestore = ""
        openPage(initialPage, requestContentFocus = true)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (handleBack()) return
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        // the lists are warmed in the background: the pages and the rows open with the cards, not with skeletons
        val warmContext = requireContext().applicationContext
        Thread {
            runCatching {
                Tmdb.visibleCategories(warmContext).forEach { category ->
                    if (!CatalogFilter.supports(category.id)) return@forEach
                    val filter = CatalogFilter.load(warmContext, category.id)
                    // only the lists: posters are requested by the cards when they come on screen
                    Tmdb.load(warmContext, category, CatalogFilter.sort(warmContext, category.id).sort, 1, filter.query(category.tv))
                }
            }
        }.start()
    }

    private fun buildNavRail() {
        val ctx = requireContext()
        shown = menuSignature(ctx)
        watchedMarks = Watched.revision(ctx)
        navItems.clear()
        railContainer.removeAllViews()

        val scale = resources.displayMetrics.widthPixels / 1280f
        val cell = (44 * scale).toInt()
        val iconSize = (28 * scale).toInt()
        val gap = (8 * scale).toInt()

        val names = mutableListOf<String>()
        Tmdb.visibleCategories(ctx).forEach { names.add(it.title.uppercase()) }
        names.add(SEARCH_HEADER)
        names.add(FAVORITES)
        names.add(SETTINGS)

        names.forEachIndexed { index, name ->
            val item = NavItemView(ctx, name, cell, iconSize)
            navItems.add(item)
            val lp = LinearLayout.LayoutParams(cell, cell).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                if (index < names.lastIndex) bottomMargin = gap
            }
            railContainer.addView(item, lp)
        }
    }

    private fun rebuildNavRail() {
        val tx = childFragmentManager.beginTransaction()
        childFragmentManager.fragments.forEach { tx.remove(it) }
        tx.commitAllowingStateLoss()
        // done now: otherwise openPage still finds the removed page by its tag, takes it for shown and the screen stays empty
        childFragmentManager.executePendingTransactions()
        buildNavRail()
        val target = if (navItems.any { it.name == currentPage }) currentPage else homePage()
        openPage(target, requestContentFocus = false)
    }

    /** The first page of the menu: the first visible category, bookmarks when every category is hidden. */
    private fun homePage(): String =
        Tmdb.visibleCategories(requireContext()).firstOrNull()?.title?.uppercase() ?: FAVORITES

    private fun currentNavItem(): NavItemView? =
        navItems.firstOrNull { it.name == currentPage } ?: navItems.firstOrNull()

    private fun isDescendant(ancestor: View, descendant: View): Boolean {
        var p = descendant.parent
        while (p != null) {
            if (p === ancestor) return true
            p = p.parent
        }
        return false
    }

    private fun requestContentFocus() {
        if (!::contentContainer.isInitialized) return
        // a catalog page opens on its first card, not on the filter buttons above it. Asked before the check below:
        // on a cold start the system has already put the focus on the first button by then
        val catalog = childFragmentManager.findFragmentByTag(currentPage) as? CatalogPageFragment
        if (catalog != null && catalog.focusFirstCard()) return
        if ((childFragmentManager.findFragmentByTag(currentPage) as? FavoritesPageFragment)?.focusFirst() == true) return
        if (contentContainer.findFocus() != null) return
        if (currentPage == SEARCH_HEADER) {
            val mic = searchPage?.micView
            if (mic != null && mic.isShown) {
                mic.requestFocus()
                return
            }
        }
        val railItem = currentNavItem()
        val target = if (railItem != null) {
            FocusFinder.getInstance().findNextFocus(rootLayout, railItem, View.FOCUS_RIGHT)
        } else null
        if (target != null && isDescendant(contentContainer, target)) {
            target.requestFocus()
            return
        }
        contentContainer.requestFocus(View.FOCUS_RIGHT)
    }

    private fun openPage(name: String, requestContentFocus: Boolean = false) {
        if (!isAdded) return
        val currentFrag = childFragmentManager.findFragmentByTag(currentPage)
        if (name == currentPage && currentFrag != null && currentFrag.isVisible) {
            if (requestContentFocus) requestContentFocus()
            return
        }
        currentPage = name
        navItems.forEach { it.updateState() }

        if (pageToRestore.isEmpty()) {
            LastScreen.rememberPage(requireContext(), name)
        }

        val tx = childFragmentManager.beginTransaction()
        val target = childFragmentManager.findFragmentByTag(name)
        // every other page is hidden, not only the ones that report themselves visible: a page whose view is not
        // attached yet (fast switching, slow TV) used to stay shown under the new one, titles drawn over each other
        childFragmentManager.fragments.forEach { f ->
            if (f !== target && !f.isHidden) tx.hide(f)
        }
        if (target != null) {
            tx.show(target)
            if (target is SearchPageFragment) searchPage = target
        } else {
            val fragment = createPageFragment(name)
            tx.add(CONTENT_CONTAINER_ID, fragment, name)
        }
        tx.commitAllowingStateLoss()
        childFragmentManager.executePendingTransactions()

        if (requestContentFocus) {
            contentContainer.post {
                requestContentFocus()
            }
        }
    }

    /**
     * Back button handling:
     * 1. If focus is in page content -> moves focus to the left rail.
     * 2. If focus is already in rail and not on the home page -> switches to the home page and focuses it.
     * 3. If focus is in rail and on the home page -> returns false to let system exit app.
     */
    fun handleBack(): Boolean {
        if (!::contentContainer.isInitialized) return false
        val focused = rootLayout.findFocus()
        if (focused != null && isDescendant(contentContainer, focused)) {
            currentNavItem()?.requestFocus()
            return true
        }
        if (currentPage != homePage()) {
            openPage(homePage(), requestContentFocus = false)
            currentNavItem()?.requestFocus()
            return true
        }
        return false
    }

    inner class NavItemView(
        context: Context,
        val name: String,
        private val cell: Int,
        iconSize: Int
    ) : FrameLayout(context) {
        private val iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(MenuIcons.forName(name))
        }

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = name
            val iconLp = LayoutParams(iconSize, iconSize, Gravity.CENTER)
            addView(iconView, iconLp)
            updateState()

            setOnFocusChangeListener { _, _ ->
                // all the plates are repainted at once: the selection follows the focus without waiting for the page to switch
                navItems.forEach { it.updateState() }
                if (isFocused) {
                    uiHandler.removeCallbacks(switchPageDebounce)
                    uiHandler.postDelayed(switchPageDebounce, 100L)
                }
            }
            setOnClickListener {
                uiHandler.removeCallbacks(switchPageDebounce)
                if (currentPage != name) {
                    openPage(name, requestContentFocus = true)
                } else {
                    requestContentFocus()
                }
            }
        }

        fun updateState() {
            // while the focus is in the rail, the item under it is the chosen one (the page follows a moment later):
            // two plates at once, the old page and the new focus, made the menu look like it lagged
            val railFocus = navItems.firstOrNull { it.isFocused }
            val isCur = name == (railFocus?.name ?: currentPage)
            val focused = isFocused
            // the current page is told by the white icon alone (no outline): only the focus gets the plate
            background = when {
                focused -> plate(FOCUS_FILL, cell)
                else -> null
            }
            val iconColor = when {
                focused || isCur -> Color.WHITE
                else -> 0xFF94A3B8.toInt()
            }
            iconView.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
            if (event?.action != KeyEvent.ACTION_DOWN) return super.onKeyDown(keyCode, event)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    uiHandler.removeCallbacks(switchPageDebounce)
                    if (currentPage != name) {
                        openPage(name, requestContentFocus = true)
                    } else {
                        requestContentFocus()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> return true
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val index = navItems.indexOf(this)
                    if (index == 0) return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val index = navItems.indexOf(this)
                    if (index == navItems.lastIndex) return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }
    }

    fun focusMenu(): Boolean {
        return currentNavItem()?.requestFocus() ?: false
    }

    inner class RootLayout(context: Context) : FrameLayout(context) {
        /** Time of the last "left" key: told apart a held or fast-tapped key from a separate press at the edge. */
        private var lastLeftAt = 0L

        override fun focusSearch(focused: View?, direction: Int): View? {
            if (direction == View.FOCUS_LEFT && focused != null) {
                val content = findViewById<View>(CONTENT_CONTAINER_ID) as? ViewGroup
                if (content != null && isDescendant(content, focused)) {
                    val nextInContent = FocusFinder.getInstance().findNextFocus(content, focused, View.FOCUS_LEFT)
                    if (nextInContent != null) {
                        return nextInContent
                    }
                    return currentNavItem()
                }
            }
            return super.focusSearch(focused, direction)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    // a held or fast-tapped key: the row is still scrolling and the left neighbour may not be laid out yet,
                    // so "nothing on the left" is not the edge; only a separate press at the edge opens the menu
                    val rapid = event.repeatCount > 0 || event.eventTime - lastLeftAt < 350L
                    lastLeftAt = event.eventTime
                    val content = findViewById<View>(CONTENT_CONTAINER_ID) as? ViewGroup
                    val focused = findFocus()
                    if (content != null && focused != null && isDescendant(content, focused)) {
                        val next = FocusFinder.getInstance().findNextFocus(content, focused, View.FOCUS_LEFT)
                        if (next == null) {
                            if (rapid) return true
                            val target = currentNavItem()
                            if (target != null) {
                                target.requestFocus()
                                return true
                            }
                        }
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun plate(color: Int, size: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = size / 4f
    }

    private fun menuSignature(ctx: Context): String =
        Tmdb.visibleCategories(ctx).joinToString(",") { it.id } + "|" +
            Settings.string(ctx, Settings.LANGUAGE, "ru-RU")

    private var clockDialog: android.app.AlertDialog? = null

    /**
     * A wrong device clock breaks every HTTPS request, so the warning is a window that cannot be closed with Back:
     * it goes away by itself once the clock is right. The check runs again each time the app comes back (from the
     * clock settings, for one). Limit: if the check cannot reach the network, the window stays until a request succeeds.
     */
    private fun checkClock() {
        val app = requireContext().applicationContext
        Thread {
            ClockCheck.checkOnce()
            val host = activity ?: return@Thread
            host.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (!ClockCheck.skewed) {
                    clockDialog?.dismiss()
                    clockDialog = null
                } else if (clockDialog == null) {
                    clockDialog = android.app.AlertDialog.Builder(host, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Сбились дата и время")
                        .setMessage(ClockCheck.MESSAGE)
                        .setCancelable(true)
                        .setPositiveButton("Настроить часы", null)
                        .setNegativeButton("Пропустить") { d, _ -> d.dismiss() }
                        .create().also { dialog ->
                            dialog.setCanceledOnTouchOutside(true)
                            dialog.show()
                            // set after show(): the default handler would close the window on the button press
                            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                val flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                runCatching { app.startActivity(Intent(android.provider.Settings.ACTION_DATE_SETTINGS).addFlags(flags)) }
                                    .onFailure { runCatching { app.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(flags)) } }
                            }
                        }
                }
            }
        }.start()
    }

    override fun onDestroyView() {
        clockDialog?.dismiss()
        clockDialog = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        checkClock()
        val ctx = requireContext()
        if (pageToRestore.isEmpty()) LastScreen.rememberPage(ctx, currentPage)
        if (shown != menuSignature(ctx)) {
            rebuildNavRail()
            return
        }
    }

    private fun createPageFragment(name: String): Fragment {
        if (name == SEARCH_HEADER) return SearchPageFragment().also { searchPage = it }
        if (name == SETTINGS) return SettingsPageFragment()
        if (name == FAVORITES) return FavoritesPageFragment()
        val category = Tmdb.visibleCategories(requireContext()).firstOrNull { it.title.uppercase() == name }
        if (category != null) {
            return CatalogPageFragment().apply {
                arguments = Bundle().apply { putString("category", category.id) }
            }
        }
        // a name the menu no longer has (a category hidden in the settings): an empty page until the menu is rebuilt
        return Fragment()
    }

    companion object {
        const val RESTORE = "restore"
    }
}

fun titleFrom(extras: Bundle) = Title(
    extras.getLong("id"), extras.getString("name").orEmpty(), extras.getString("overview").orEmpty(),
    extras.getString("poster"), extras.getBoolean("tv"), extras.getString("year").orEmpty(),
    extras.getString("rating").orEmpty(),
    genre = extras.getString("genre").orEmpty(),
    backdrop = extras.getString("backdrop"),
)

fun Title.toExtras() = Bundle().apply {
    putLong("id", id)
    putString("name", name)
    putString("overview", overview)
    putString("poster", poster)
    putBoolean("tv", tv)
    putString("year", year)
    putString("rating", rating)
    putString("genre", genre)
    putString("backdrop", backdrop)
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


class SkeletonPresenter(private val cardScale: Float = CARD_SCALE) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder =
        ViewHolder(SkeletonCardView(parent.context, parent.context.uiScale() * cardScale))

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {}

    override fun onUnbindViewHolder(holder: ViewHolder) {}
}

class CardPresenter(private val cardScale: Float = CARD_SCALE) : Presenter() {
    private companion object {
        val runtimePool: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(3)
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder =
        ViewHolder(TitleCard(parent.context, parent.context.uiScale() * cardScale))

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {
        val card = holder.view as TitleCard
        val title = item as Title
        card.bind(title)
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
            // the short answer (runtime, genres) for every card of a row; the full details only for the focused one below
            val details = runCatching { Tmdb.rowInfo(appContext, title.id, title.tv) }.getOrNull()
            if (card.shownId != title.id) return@submit
            val minutes = details?.runtime ?: 0
            if (minutes > 0) card.post { card.setRuntime(title.id, minutes) }
            val genres = details?.genres?.joinToString(", ").orEmpty()
            if (title.genre.isEmpty() && genres.isNotEmpty()) card.post { card.setGenre(title.id, genres) }
        }
        val warmUp = Runnable {
            if (card.isFocused && card.shownId == title.id) runtimePool.execute {
                val backdrop = title.backdrop?.ifEmpty { null }
                    ?: runCatching { Tmdb.detailsCached(appContext, title.id, title.tv).backdrop }.getOrDefault("")
                if (backdrop.isNotEmpty()) card.post {
                    Glide.with(appContext).load("https://image.tmdb.org/t/p/w1280$backdrop").preload()
                }
            }
        }
        card.onFocused = {
            card.removeCallbacks(warmUp)
            card.postDelayed(warmUp, 300)
        }
        val poster = title.poster
        card.showPoster(poster)
    }

    override fun onUnbindViewHolder(holder: ViewHolder) {
        val card = holder.view as TitleCard
        card.runtimeTask?.cancel(false)
        card.runtimeTask = null
        card.cancelPoster()
    }
}

class PlainTextPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val scale = parent.context.uiScale()
        val view = TextView(parent.context).apply {
            setTextColor(0xFFCBD5E1.toInt())
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

class SettingsPageFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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
        val tiles = ItemList(SettingsSectionPresenter()).apply { addAll(0, SETTINGS_SECTIONS) }
        tiles.listener = object : ItemList.Listener() {
            override fun onCreate(holder: ItemList.Holder) {
                holder.itemView.setOnClickListener {
                    (holder.item as? SettingsSection)?.let { section ->
                        startActivity(Intent(requireContext(), SettingsActivity::class.java).putExtra("section", section.title))
                    }
                }
            }
        }
        val gap = px(12)
        val side = px(20)
        val grid = titleCardGrid(requireContext(), scale, tiles, COLUMNS, spacing = gap)
        val gridWidth = COLUMNS * px(290) + (COLUMNS - 1) * gap + 2 * side
        page.addView(grid, LinearLayout.LayoutParams(gridWidth, 0, 1f).apply {
            setMargins(-side, px(2), 0, 0)
        })
        return page
    }

    private companion object {
        const val COLUMNS = 3
    }
}

class SettingsSectionPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val scale = parent.context.uiScale()
        fun dp(v: Int) = (v * scale).toInt()
        val tile = FrameLayout(parent.context)
        tile.layoutParams = ViewGroup.LayoutParams(dp(290), dp(124))
        tile.background = textTilePlate(scale)
        tile.isFocusable = true
        tile.isFocusableInTouchMode = true
        val texts = LinearLayout(parent.context)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(16), dp(14), dp(16), dp(14))
        val title = TextView(parent.context).apply {
            setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; maxLines = 2
        }
        val description = TextView(parent.context).apply {
            setTextColor(0xFF94A3B8.toInt()); textSize = 13f; maxLines = 3; setPadding(0, dp(8), 0, 0)
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
        @Suppress("UNCHECKED_CAST")
        val texts = tile.tag as Pair<TextView, TextView>
        // the grey description is unreadable on the focus colour, so it turns white with the focus
        fun paint(focused: Boolean) {
            texts.second.setTextColor(if (focused) Color.WHITE else 0xFF94A3B8.toInt())
        }
        paint(tile.isFocused)
        tile.setOnFocusChangeListener { _, focused -> paint(focused) }
        texts.first.text = section.title
        texts.second.text = section.description
    }

    override fun onUnbindViewHolder(holder: ViewHolder) {}
}

class FavoriteEpisodePresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val scale = parent.context.uiScale()
        fun dp(v: Int) = (v * scale).toInt()
        val tile = FrameLayout(parent.context)
        tile.layoutParams = ViewGroup.LayoutParams(dp(280), dp(136))
        tile.background = textTilePlate(scale)
        tile.isFocusable = true
        tile.isFocusableInTouchMode = true
        val texts = LinearLayout(parent.context)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(16), dp(14), dp(16), dp(14))
        val series = TextView(parent.context).apply {
            setTextColor(Color.WHITE); textSize = 16f; maxLines = 3; typeface = Typeface.DEFAULT_BOLD
        }
        val episode = TextView(parent.context).apply {
            setTextColor(0xFFCBD5E1.toInt()); textSize = 13f; maxLines = 2; setPadding(0, dp(8), 0, 0)
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
        @Suppress("UNCHECKED_CAST")
        val texts = tile.tag as Triple<TextView, TextView, TextView>
        texts.first.text = favorite.seriesTitle
        texts.second.text = favorite.episodeTitle
        texts.third.text = "★"
    }

    override fun onUnbindViewHolder(holder: ViewHolder) {}
}

class TorrentCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val scale = parent.context.uiScale()
        fun dp(v: Int) = (v * scale).toInt()
        val tile = FrameLayout(parent.context)
        tile.layoutParams = ViewGroup.LayoutParams(dp(240), dp(136))
        tile.background = textTilePlate(scale)
        tile.isFocusable = true
        tile.isFocusableInTouchMode = true
        val texts = LinearLayout(parent.context)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(14), dp(14), dp(14), dp(14))
        val title = TextView(parent.context).apply { setTextColor(Color.WHITE); textSize = 15f; maxLines = 3; typeface = Typeface.DEFAULT_BOLD }
        val info = TextView(parent.context).apply { setTextColor(0xFF94A3B8.toInt()); textSize = 12f; setPadding(0, dp(8), 0, 0) }
        texts.addView(title)
        texts.addView(info)
        tile.addView(texts, FrameLayout.LayoutParams(-1, -1))
        tile.tag = Pair(title, info)
        return ViewHolder(tile)
    }

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {
        val torrent = item as Torrent
        val tile = holder.view as FrameLayout
        @Suppress("UNCHECKED_CAST")
        val pair = tile.tag as Pair<TextView, TextView>
        pair.first.text = torrent.title
        pair.second.text = "${torrent.source}, ${torrent.size}"
    }

    override fun onUnbindViewHolder(holder: ViewHolder) {}
}
