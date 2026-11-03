package ru.lazytv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.VerticalGridView
import org.json.JSONArray

/** The "search torrents for this phrase" tile (still referenced by the list presenters). */
data class TorrentQuery(val query: String)

/**
 * The search page of the menu, in the LazyMedia layout: microphone and torrent-mode buttons next to the input, the search
 * history with "clear", and the found titles below. In torrent mode the phrase goes straight to the
 * torrent list. While no phrase is typed the grid shows the viewing history, which can be edited in place.
 * The cards are the ones of the rest of the app ([CardPresenter], [MoreTilePresenter]), not their own copies.
 */
class SearchPageFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {
    private val pageAdapter = object : BrowseSupportFragment.MainFragmentAdapter<SearchPageFragment>(this) {
        // while the menu slides, the page is a flat picture that is only moved: no redrawing of dozens of cards per frame
        override fun onTransitionStart() {
            super.onTransitionStart()
            view?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        override fun onTransitionEnd() {
            super.onTransitionEnd()
            view?.setLayerType(View.LAYER_TYPE_NONE, null)
        }

        // the rows pages are dimmed while the menu has the focus; this page does the same, so that the move from one page
        // to another looks the same whichever page it is
        // (expand = the page is expanded, the menu is away)
        override fun setExpand(expand: Boolean) {
            super.setExpand(expand)
            view?.animate()?.alpha(if (expand) 1f else 0.55f)?.setDuration(200)?.start()
        }
    }
    override fun getMainFragmentAdapter() = pageAdapter.also {
        // the same as the rows pages: the page is squeezed into what the menu leaves, instead of lying under it
        it.isScalingEnabled = true
    }

    private lateinit var loader: LoadingView
    private val scale get() = resources.displayMetrics.widthPixels / 1280f
    private fun px(value: Int) = (value * scale).toInt()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var searchBar: SearchBar
    private lateinit var input: EditText
    private lateinit var history: LinearLayout
    private lateinit var historyBlock: LinearLayout
    private lateinit var clearHistory: View
    private lateinit var grid: VerticalGridView
    private lateinit var message: TextView
    private var pending: Runnable? = null

    /** Titles of the grid: the found ones, or the viewing history while no phrase is typed. */
    private val cards = ArrayObjectAdapter(ClassPresenterSelector()
        .addClassPresenter(Title::class.java, CardPresenter(CARD_SCALE)))

    // the viewing history shown under the input while no phrase is typed, and its edit mode
    private lateinit var recentHeader: LinearLayout
    private lateinit var recentActions: LinearLayout
    private var recent: List<Title> = emptyList()
    /** The grid holds the viewing history (no phrase typed): only then the edit mode exists. */
    private var showingRecent = false
    private var editing = false
    private val picked = mutableSetOf<Pair<Boolean, Long>>()

    private fun label(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(requireContext()).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** The button of the design system ([chipButton], the same as "Фильтры"), with an icon. */
    private fun button(icon: String, caption: String, action: () -> Unit) =
        chipButton(requireContext(), scale, caption, icon).apply { setOnClickListener { action() } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val page = LinearLayout(requireContext())
        page.orientation = LinearLayout.VERTICAL
        // the page sits to the right of the icon rail; the top margin matches the rows of the other pages
        // (the left edge is where the cards of the rows start, to the right of the icon rail)
        page.setPadding(px(92), px(24), px(48), px(16))
        // the focused card grows past its cell: the page must not cut it
        page.clipChildren = false
        page.clipToPadding = false

        // microphone and the input
        searchBar = SearchBar(requireActivity(), scale) { submit(it) }
        input = searchBar.input
        // the browse screen does not pass the ids of the next view, so down and up between the field, the buttons and the cards are taken here
        // down from the input goes to the nearest thing below: the "clear" of the search history, then the buttons of the viewing history
        searchBar.onDown = {
            (if (historyBlock.isShown) clearHistory
            else if (showingRecent && recentActions.childCount > 0) recentActions.getChildAt(0)
            else grid).requestFocus()
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                pending?.let { ui.removeCallbacks(it) }
                val phrase = s?.toString().orEmpty().trim()
                pending = Runnable { searchTitles(phrase) }.also { ui.postDelayed(it, 600) }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        // the bar ends where the last card of a row ends: the free part of a grid cell (cell minus the card) is cut off on the right
        // (the grid gives each column a whole-pixel width after the gaps of item spacing; the cards sit at the left of the columns)
        val gridWidth = resources.displayMetrics.widthPixels - px(92) - px(48)
        val pitch = (gridWidth - (COLUMNS - 1) * px(6)) / COLUMNS + px(6)
        val barCut = (gridWidth - (COLUMNS - 1) * pitch - (240 * CARD_SCALE * scale).toInt()).coerceAtLeast(0)

        // history
        historyBlock = LinearLayout(requireContext())
        historyBlock.orientation = LinearLayout.VERTICAL
        // the caption with its "clear" button beside it, as the viewing history below has its own buttons
        val historyHeader = LinearLayout(requireContext())
        historyHeader.gravity = Gravity.CENTER_VERTICAL
        historyHeader.setPadding(0, px(18), 0, px(12))
        historyHeader.addView(label("История поиска", 22f), LinearLayout.LayoutParams(-2, -2))
        clearHistory = button(MenuIcons.DELETE, "Очистить") {
            Settings.setString(requireContext(), Settings.SEARCH_HISTORY, "[]")
            showHistory()
            input.requestFocus()
        }
        // right of "clear" there is nothing: the focus must not jump to the microphone; up goes back to the search field,
        // down goes to the first phrase (the nearest search often lands on the second one)
        clearHistory.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false
            else when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_DPAD_UP -> { input.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val first = history.getChildAt(0)
                    if (first != null) { first.requestFocus(); revealChip(first) }
                    first != null
                }
                else -> false
            }
        }
        historyHeader.addView(clearHistory, LinearLayout.LayoutParams(-2, px(CHIP_HEIGHT)).apply { leftMargin = px(8) })
        historyBlock.addView(historyHeader)
        history = LinearLayout(requireContext())
        // a slider: up to 12 phrases do not fit the screen, the remote scrolls the row as the focus goes right
        val historyScroll = android.widget.HorizontalScrollView(requireContext())
        historyScroll.isHorizontalScrollBarEnabled = false
        historyScroll.addView(history, FrameLayout.LayoutParams(-2, px(CHIP_HEIGHT)))
        historyBlock.addView(historyScroll, LinearLayout.LayoutParams(-1, -2))
        // the bar and the history share one column of the width of the cards' row: both are cut at the last card,
        // otherwise the phrases run past it into the free part of the grid cell
        val top = LinearLayout(requireContext())
        top.orientation = LinearLayout.VERTICAL
        top.addView(searchBar, LinearLayout.LayoutParams(-1, -2))
        top.addView(historyBlock)
        page.addView(top, LinearLayout.LayoutParams(-1, -2).apply { rightMargin = barCut })

        recentHeader = LinearLayout(requireContext())
        recentHeader.gravity = Gravity.CENTER_VERTICAL
        recentHeader.setPadding(0, px(18), 0, px(4))
        recentHeader.visibility = View.GONE
        recentHeader.addView(label("Недавно открытое", 22f), LinearLayout.LayoutParams(-2, -2))
        recentActions = LinearLayout(requireContext())
        recentHeader.addView(recentActions, LinearLayout.LayoutParams(-2, px(CHIP_HEIGHT)).apply { leftMargin = px(8) })
        page.addView(recentHeader)

        message = label("", 16f, 0xFFAFC4B6.toInt())
        message.setPadding(0, px(14), 0, 0)
        message.visibility = View.GONE
        page.addView(message)

        val bridge = ItemBridgeAdapter(cards)
        bridge.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onCreate(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener { open(viewHolder.item, viewHolder.itemView) }
            }

            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                val title = viewHolder.item as? Title
                (viewHolder.itemView as? TitleCard)?.setPicked(title != null && showingRecent && editing && key(title) in picked)
            }
        })
        grid = titleCardGrid(requireContext(), scale, bridge, COLUMNS)
        // up from the first row of the history goes to its buttons: the nearest view above the card is not the button, which sits to the side
        grid.setOnKeyInterceptListener { event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                showingRecent && grid.selectedPosition in 0 until COLUMNS && recentActions.childCount > 0
            ) recentActions.getChildAt(0).requestFocus() else false
        }
        loader = LoadingView(requireContext(), scale, "")
        loader.visibility = View.GONE
        val body = FrameLayout(requireContext())
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        body.addView(loader, FrameLayout.LayoutParams(-1, -1))
        page.addView(body, titleGridParams(scale))
        showHistory()
        // full screen width whatever the menu leaves: while the menu slides, the container changes its width every
        // frame and the grid would be laid out again each time (a jerk); a fixed width lets the page just slide
        page.layoutParams = FrameLayout.LayoutParams(resources.displayMetrics.widthPixels, ViewGroup.LayoutParams.MATCH_PARENT)
        return page
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tellBrowse()
    }

    /** The browse screen waits for a page to say that it is made and filled; this page is ready at once. */
    private fun tellBrowse() {
        pageAdapter.fragmentHost?.notifyViewCreated(pageAdapter)
        pageAdapter.fragmentHost?.notifyDataReady(pageAdapter)
    }

    /** The answer of the voice recognizer arrives at the activity and is passed here by the menu screen. */
    fun voiceResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (::searchBar.isInitialized) searchBar.handleResult(requestCode, resultCode, data)
    }

    private fun key(title: Title) = title.tv to title.id

    /** A card: opens the title, or in the edit mode of the history marks it for deletion. */
    private fun open(item: Any?, view: View) {
        val title = item as? Title ?: return
        if (showingRecent && editing) {
            if (!picked.remove(key(title))) picked.add(key(title))
            (view as? TitleCard)?.setPicked(key(title) in picked)
            renderRecentActions()
            return
        }
        remember(input.text.toString())
        startActivity(Intent(requireContext(), DetailsActivity::class.java).putExtras(title.toExtras()))
    }

    private fun setCards(items: List<Any>) {
        cards.clear()
        cards.addAll(0, items)
    }

    /** The history changes while a card is open (a title is watched): the grid is filled again on return. */
    override fun onResume() {
        super.onResume()
        tellBrowse()
        showRecent()
    }

    private fun submit(phrase: String) {
        val text = phrase.trim()
        if (text.length < 2) return
        remember(text)
        searchTitles(text)
    }

    private fun searchTitles(phrase: String) {
        if (phrase.length < 2) {
            showRecent()
            return
        }
        // a phrase replaces the history in the grid, and the edit mode ends with it
        showingRecent = false
        editing = false
        picked.clear()
        recentHeader.visibility = View.GONE
        // the old cards go away and a spinner stands in the middle of the page until the answer comes
        message.visibility = View.GONE
        setCards(emptyList())
        loader.visibility = View.VISIBLE
        Thread {
            val found = runCatching { Tmdb.search(requireContext().applicationContext, phrase) }
            activity?.runOnUiThread {
                if (!isAdded || input.text.toString().trim() != phrase) return@runOnUiThread
                loader.visibility = View.GONE
                val titles = found.getOrDefault(emptyList())
                setCards(titles)
                message.text = if (found.isFailure) "Нет связи с каталогом" else if (titles.isEmpty()) "В каталоге ничего не найдено" else ""
                message.visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE
            }
        }.start()
    }

    // --- viewing history -----------------------------------------------------------------------

    private fun showRecent() {
        if (input.text.toString().trim().length >= 2) return
        loader.visibility = View.GONE
        recent = History.all(requireContext())
        showingRecent = recent.isNotEmpty()
        if (!showingRecent) {
            editing = false
            picked.clear()
        }
        // marks of titles that are gone from the history make no sense
        picked.retainAll(recent.map { key(it) }.toSet())
        setCards(recent)
        recentHeader.visibility = if (showingRecent) View.VISIBLE else View.GONE
        message.visibility = View.GONE
        renderRecentActions()
        if (recent.any { it.poster == null }) Thread {
            val fixed = History.withPosters(requireContext().applicationContext, recent)
            activity?.runOnUiThread {
                if (isAdded && showingRecent && fixed != recent) {
                    recent = fixed
                    // replaced one by one: clearing the grid would take the remote focus off the cards
                    if (cards.size() == fixed.size) fixed.forEachIndexed { i, t -> if (cards.get(i) != t) cards.replace(i, t) }
                    else setCards(fixed)
                }
            }
        }.start()
    }

    /** "Редактировать" turns into the buttons of the edit mode; the one that deletes the chosen sits next to "Готово". */
    private fun renderRecentActions() {
        recentActions.removeAllViews()
        fun add(view: View) {
            // the remote goes from the buttons straight down to the cards
            view.nextFocusDownId = grid.id
            // and up to the input: the field is wider than the button and the search for the nearest view above may miss it
            if (input.id == View.NO_ID) input.id = View.generateViewId()
            view.nextFocusUpId = input.id
            // left and right walk the buttons of the row; at the ends the focus stays instead of jumping to the microphone
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false
                else when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { input.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val step = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                        recentActions.getChildAt(recentActions.indexOfChild(view) + step)?.requestFocus()
                        true
                    }
                    else -> false
                }
            }
            recentActions.addView(view, LinearLayout.LayoutParams(-2, -1).apply { leftMargin = px(12) })
        }
        if (editing) {
            add(button(MenuIcons.DELETE, "Очистить всё") { askClearRecent() })
            if (picked.isNotEmpty()) add(button(MenuIcons.DELETE, "Удалить (${picked.size})") { removePicked() })
            add(button(MenuIcons.CHECK, "Готово") { setEditing(false) })
        } else add(button(MenuIcons.EDIT, "Редактировать") { setEditing(true) })
    }

    private fun setEditing(on: Boolean) {
        editing = on
        picked.clear()
        cards.notifyArrayItemRangeChanged(0, cards.size())
        renderRecentActions()
        // the buttons were made again: the focus goes to the one that ends or starts the mode
        recentActions.getChildAt(recentActions.childCount - 1)?.requestFocus()
    }

    private fun removePicked() {
        History.remove(requireContext(), recent.filter { key(it) in picked })
        picked.clear()
        showRecent()
        recentActions.getChildAt(recentActions.childCount - 1)?.requestFocus()
    }

    private fun askClearRecent() {
        android.app.AlertDialog.Builder(requireContext(), android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Очистить весь список?")
            .setMessage("Недавно открытое будет удалено.")
            .setPositiveButton("Очистить") { _, _ ->
                History.clear(requireContext())
                picked.clear()
                editing = false
                showRecent()
                input.requestFocus()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // --- search history ------------------------------------------------------------------------

    private fun saved(): List<String> {
        val raw = Settings.string(requireContext(), Settings.SEARCH_HISTORY, "[]")
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun remember(phrase: String) {
        val text = phrase.trim()
        if (text.length < 2) return
        val list = (listOf(text) + saved().filter { !it.equals(text, true) }).take(12)
        Settings.setString(requireContext(), Settings.SEARCH_HISTORY, JSONArray(list).toString())
        showHistory()
    }

    private fun showHistory() {
        history.removeAllViews()
        val items = saved()
        historyBlock.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        items.forEachIndexed { index, phrase ->
            val chip = button(MenuIcons.HISTORY, phrase) {
                input.setText(phrase)
                input.setSelection(phrase.length)
                submit(phrase)
            }.also { (it.getChildAt(1) as TextView).apply { maxLines = 1; maxWidth = px(220); ellipsize = android.text.TextUtils.TruncateAt.END } }
            // the row is a slider: the focus walks the phrases itself, also those out of sight, and stays at the ends
            chip.setOnKeyListener { _, code, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    val step = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    val next = history.getChildAt(index + step)
                    if (next != null) {
                        next.requestFocus()
                        revealChip(next)
                    }
                    true
                } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    // the "clear" stands right above the phrases: up goes to it, as the eye expects
                    clearHistory.requestFocus()
                    true
                } else false
            }
            history.addView(chip, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = px(12) })
        }
    }

    /** Scrolls the phrase row so the focused chip is fully seen, not cut by the row's edge. */
    private fun revealChip(chip: View) {
        val scroll = history.parent as? android.widget.HorizontalScrollView ?: return
        val pad = px(12)
        val left = chip.left - pad
        val right = chip.right + pad - scroll.width
        when {
            left < scroll.scrollX -> scroll.smoothScrollTo(left.coerceAtLeast(0), 0)
            right > scroll.scrollX -> scroll.smoothScrollTo(right.coerceAtLeast(0), 0)
        }
    }

    override fun onDestroyView() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private companion object {
        // the same grid as the "show all" page of a row
        const val COLUMNS = 5
        const val CARD_SCALE = 0.82f
    }
}
