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
    private val pageAdapter = BrowseSupportFragment.MainFragmentAdapter<SearchPageFragment>(this)
    override fun getMainFragmentAdapter() = pageAdapter.also {
        // the same as the rows pages: the page is squeezed into what the menu leaves, instead of lying under it
        it.isScalingEnabled = true
    }

    private val scale get() = resources.displayMetrics.widthPixels / 1280f
    private fun px(value: Int) = (value * scale).toInt()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var searchBar: SearchBar
    private lateinit var input: EditText
    private lateinit var history: LinearLayout
    private lateinit var historyBlock: LinearLayout
    private lateinit var grid: VerticalGridView
    private lateinit var message: TextView
    private var pending: Runnable? = null

    /** Titles of the grid; after a phrase the first tile sends it to the torrent search. */
    private val cards = ArrayObjectAdapter(ClassPresenterSelector()
        .addClassPresenter(Title::class.java, CardPresenter(CARD_SCALE))
        .addClassPresenter(MoreTile::class.java, MoreTilePresenter(CARD_SCALE)))

    // the viewing history shown under the input while no phrase is typed, and its edit mode
    private lateinit var recentHeader: LinearLayout
    private lateinit var recentActions: LinearLayout
    private var recent: List<Title> = emptyList()
    /** The grid holds the viewing history (no phrase typed): only then the edit mode exists. */
    private var showingRecent = false
    private var editing = false
    private val picked = mutableSetOf<Pair<Boolean, Long>>()
    /** The phrase the grid shows results of; the torrent tile searches it. */
    private var phraseShown = ""

    private fun label(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(requireContext()).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun focusBackground(normal: Int, radius: Float = 3f, circle: Boolean = false) = focusShape(scale, normal, radius, circle)

    /** Chip with a small vector icon and a caption; focus turns it bright green. */
    private fun button(icon: String, caption: String, action: () -> Unit) = LinearLayout(requireContext()).apply {
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        background = focusBackground(0x66000000)
        addView(ImageView(requireContext()).apply {
            setImageDrawable(PathDrawable(icon).apply { setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN) })
        }, LinearLayout.LayoutParams(px(18), px(18)))
        addView(label(caption, 15f).apply { setPadding(px(10), 0, 0, 0) })
        setPadding(px(16), 0, px(18), 0)
        setOnClickListener { action() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val page = LinearLayout(requireContext())
        page.orientation = LinearLayout.VERTICAL
        // the page sits to the right of the icon rail; the top margin matches the rows of the other pages
        // (the left edge is where the cards of the rows start, to the right of the icon rail)
        page.setPadding(px(75), px(24), px(48), px(16))

        // microphone and the input
        searchBar = SearchBar(requireActivity(), scale) { submit(it) }
        input = searchBar.input
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                pending?.let { ui.removeCallbacks(it) }
                val phrase = s?.toString().orEmpty().trim()
                pending = Runnable { searchTitles(phrase) }.also { ui.postDelayed(it, 600) }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        page.addView(searchBar)

        // history
        historyBlock = LinearLayout(requireContext())
        historyBlock.orientation = LinearLayout.VERTICAL
        historyBlock.addView(label("История поиска", 22f).apply { setPadding(0, px(20), 0, px(10)) })
        history = LinearLayout(requireContext())
        historyBlock.addView(history, LinearLayout.LayoutParams(-1, px(44)))
        page.addView(historyBlock)

        recentHeader = LinearLayout(requireContext())
        recentHeader.gravity = Gravity.CENTER_VERTICAL
        recentHeader.setPadding(0, px(18), 0, px(4))
        recentHeader.visibility = View.GONE
        recentHeader.addView(label("Недавно просмотренное", 22f), LinearLayout.LayoutParams(0, -2, 1f))
        recentActions = LinearLayout(requireContext())
        recentHeader.addView(recentActions, LinearLayout.LayoutParams(-2, px(44)))
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
        grid = VerticalGridView(requireContext())
        grid.setNumColumns(COLUMNS)
        grid.setPadding(0, px(8), 0, px(16))
        grid.clipToPadding = false
        grid.setItemSpacing(px(6))
        grid.adapter = bridge
        page.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        showHistory()
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

    /** A card: opens the title, or in the edit mode of the history marks it for deletion; the tile opens the torrent search. */
    private fun open(item: Any?, view: View) {
        if (item is MoreTile) {
            remember(input.text.toString())
            startActivity(Intent(requireContext(), TorrentsActivity::class.java).putExtra("name", phraseShown).putExtra("tv", true))
            return
        }
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
        message.text = "Ищем…"
        message.visibility = View.VISIBLE
        Thread {
            val found = runCatching { Tmdb.search(requireContext().applicationContext, phrase) }
            activity?.runOnUiThread {
                if (input.text.toString().trim() != phrase) return@runOnUiThread
                val titles = found.getOrDefault(emptyList())
                phraseShown = phrase
                setCards(listOf<Any>(MoreTile("Искать торренты")) + titles)
                message.text = if (found.isFailure) "Нет связи с каталогом" else if (titles.isEmpty()) "В каталоге ничего не найдено, можно искать торренты" else ""
                message.visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE
            }
        }.start()
    }

    // --- viewing history -----------------------------------------------------------------------

    private fun showRecent() {
        if (input.text.toString().trim().length >= 2) return
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
                if (showingRecent && fixed != recent) {
                    recent = fixed
                    setCards(fixed)
                }
            }
        }.start()
    }

    /** "Редактировать" turns into the buttons of the edit mode; the one that deletes the chosen sits next to "Готово". */
    private fun renderRecentActions() {
        recentActions.removeAllViews()
        fun add(view: View) = recentActions.addView(view, LinearLayout.LayoutParams(-2, -1).apply { leftMargin = px(12) })
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
            .setMessage("Недавно просмотренное будет удалено.")
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
        history.addView(button(MenuIcons.DELETE, "Очистить") {
            Settings.setString(requireContext(), Settings.SEARCH_HISTORY, "[]")
            showHistory()
            input.requestFocus()
        }, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = px(12) })
        items.forEach { phrase ->
            history.addView(button(MenuIcons.HISTORY, phrase) {
                input.setText(phrase)
                input.setSelection(phrase.length)
                submit(phrase)
            }.also { (it.getChildAt(1) as TextView).apply { maxLines = 1; maxWidth = px(220); ellipsize = android.text.TextUtils.TruncateAt.END } },
                LinearLayout.LayoutParams(-2, -1).apply { rightMargin = px(12) })
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
