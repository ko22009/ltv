package ru.lazytv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * A catalogue list ("Фильмы", "Сериалы") as one long grid: the filter and sort buttons on top, the cards under them,
 * the next TMDB page asked for while the cursor nears the end. There are no rows of collections and no
 * "Результаты" tile any more: everything is one list, so the remote only ever scrolls down.
 */
class CatalogPageFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {
    private val pageAdapter = object : BrowseSupportFragment.MainFragmentAdapter<CatalogPageFragment>(this) {
        // while the menu slides, the page is a flat picture that is only moved: no redrawing of dozens of cards per frame
        override fun onTransitionStart() {
            super.onTransitionStart()
            view?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        override fun onTransitionEnd() {
            super.onTransitionEnd()
            view?.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    override fun getMainFragmentAdapter() = pageAdapter.also {
        // scaled like the rows pages and the search page, so the left edge is the same on all of them with the menu open and closed
        it.isScalingEnabled = true
    }

    private val scale get() = resources.displayMetrics.widthPixels / 1280f
    private fun px(value: Int) = (value * scale).toInt()

    /** The catalogue list of this page; the id comes from the menu row. */
    private val category: Category get() = Tmdb.categories.first { it.id == requireArguments().getString("category") }

    private lateinit var filterChip: LinearLayout
    private lateinit var sortChip: LinearLayout
    private lateinit var grid: VerticalGridView
    private lateinit var message: TextView

    private val cards = ArrayObjectAdapter(
        androidx.leanback.widget.ClassPresenterSelector()
            .addClassPresenter(Title::class.java, CardPresenter(CARD_SCALE))
            .addClassPresenter(SkeletonCard::class.java, SkeletonPresenter(CARD_SCALE)),
    )
    /** Grey cards stand in the grid until the first page comes. */
    private var skeletons = false
    /** The filter and sort the list was built with: compared on return to know when it has to be asked again. */
    private var signature = ""
    private var nextPage = 1
    private var busy = false
    private var finished = false
    private var loadJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val page = LinearLayout(requireContext())
        page.orientation = LinearLayout.VERTICAL
        // the same left edge as the rows of the other pages (to the right of the icon rail) and the top margin of the rows
        page.setPadding(px(92), px(21), px(48), px(16))
        // the focused card grows past its cell: the page must not cut it
        page.clipChildren = false
        page.clipToPadding = false

        // the name of the page, like the title row of the other pages, and the buttons of the list in the same line
        val header = LinearLayout(requireContext())
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = android.view.Gravity.CENTER_VERTICAL
        page.addView(header)
        header.addView(TextView(requireContext()).apply {
            text = category.title
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 28f * scale)
        })

        // the buttons of the list: the filters and the sort order, made by the one button of the design system
        val buttons = LinearLayout(requireContext())
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = android.view.Gravity.CENTER_VERTICAL
        buttons.setPadding(px(28), 0, 0, 0)
        filterChip = chipButton(requireContext(), scale, "Фильтры", MenuIcons.FILTER).apply {
            setOnClickListener {
                startActivity(Intent(requireContext(), FiltersActivity::class.java).putExtra("category", category.id))
            }
        }
        sortChip = chipButton(requireContext(), scale, "Сортировка", MenuIcons.SORT).apply { setOnClickListener { chooseSort() } }
        buttons.addView(filterChip, LinearLayout.LayoutParams(-2, px(52)).apply { rightMargin = px(16) })
        buttons.addView(sortChip, LinearLayout.LayoutParams(-2, px(52)))
        // a catalogue without its own sort and filters shows only the list
        if (!CatalogFilter.supports(category.id)) buttons.visibility = View.GONE
        header.addView(buttons)

        message = TextView(requireContext()).apply {
            setTextColor(0xFFAFC4B6.toInt())
            textSize = 16f
            setPadding(0, px(10), 0, 0)
            visibility = View.GONE
        }
        page.addView(message)

        val bridge = ItemBridgeAdapter(cards)
        bridge.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onCreate(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    (viewHolder.item as? Title)?.let {
                        startActivity(Intent(requireContext(), DetailsActivity::class.java).putExtras(it.toExtras()))
                    }
                }
            }
        })
        // the focused card grows a little, as on the overview, so it is clear at once which one is chosen
        androidx.leanback.widget.FocusHighlightHelper.setupBrowseItemFocusHighlight(bridge, androidx.leanback.widget.FocusHighlight.ZOOM_FACTOR_MEDIUM, false)
        grid = VerticalGridView(requireContext())
        grid.id = View.generateViewId()
        grid.setNumColumns(COLUMNS)
        grid.setPadding(0, px(18), 0, px(16))
        grid.clipToPadding = false
        grid.clipChildren = false
        grid.setItemSpacing(px(6))
        // leanback 1.0 keeps the cursor inside the grid at its first row: up from there is sent to the buttons by hand
        grid.setOnKeyInterceptListener { event ->
            val up = event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && event.action == android.view.KeyEvent.ACTION_DOWN
            if (up && grid.selectedPosition < COLUMNS && filterChip.isShown) filterChip.requestFocus() else false
        }
        grid.adapter = bridge
        grid.setOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
            override fun onChildViewHolderSelected(
                parent: RecyclerView, child: RecyclerView.ViewHolder?, position: Int, subposition: Int,
            ) {
                // two rows before the end: the next page is asked for
                if (position >= cards.size() - COLUMNS * 2) loadNext()
            }
        })
        // the remote goes from the buttons straight down to the cards
        for (chip in listOf(filterChip, sortChip)) chip.nextFocusDownId = grid.id

        page.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        // full screen width whatever the menu leaves: while the menu slides, the container changes its width every
        // frame and the grid would be laid out again each time (a jerk); a fixed width lets the page just slide
        page.layoutParams = FrameLayout.LayoutParams(resources.displayMetrics.widthPixels, ViewGroup.LayoutParams.MATCH_PARENT)
        return page
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // the browse screen waits for a page to say that it is made and filled; the grid fills itself right after
        pageAdapter.fragmentHost?.notifyViewCreated(pageAdapter)
        pageAdapter.fragmentHost?.notifyDataReady(pageAdapter)
        signature = CatalogFilter.signature(requireContext(), category.id)
        renderButtons()
        startOver()
    }

    /** The captions of the buttons after a choice on the filters or sort screens. */
    private fun renderButtons() {
        if (!CatalogFilter.supports(category.id)) return
        val ctx = requireContext()
        val filter = CatalogFilter.load(ctx, category.id)
        filterChip.chipCaption = if (filter.count > 0) "Фильтры · ${filter.count}" else "Фильтры"
        sortChip.chipCaption = "Сортировка · ${CatalogFilter.sort(ctx, category.id).label.replaceFirstChar { it.lowercase() }}"
    }

    /** The sort choice is a small window over the page: the list is asked again with the new order at once. */
    private fun chooseSort() {
        val ctx = requireContext()
        val all = CatalogSort.values()
        android.app.AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Сортировка")
            .setSingleChoiceItems(all.map { it.label }.toTypedArray(), all.indexOf(CatalogFilter.sort(ctx, category.id))) { dialog, which ->
                CatalogFilter.saveSort(ctx, category.id, all[which])
                dialog.dismiss()
                signature = CatalogFilter.signature(ctx, category.id)
                renderButtons()
                startOver()
            }
            .show()
    }

    /** The filters or the sort could have changed on their own screens: the list is built from the first page again. */
    override fun onResume() {
        super.onResume()
        if (!::grid.isInitialized) return
        val current = CatalogFilter.signature(requireContext(), category.id)
        if (current != signature) {
            signature = current
            renderButtons()
            startOver()
        } else if (cards.size() > 0) {
            // a title marked watched on its card comes back dimmed in this grid too
            cards.notifyArrayItemRangeChanged(0, cards.size())
        }
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        super.onDestroyView()
    }

    private fun startOver() {
        loadJob?.cancel()
        cards.clear()
        nextPage = 1
        busy = false
        finished = false
        message.visibility = View.GONE
        repeat(COLUMNS * 2) { cards.add(SkeletonCard) }
        skeletons = true
        loadNext()
    }

    /** Asks TMDB for the next page; pages made up only of watched titles are skipped. */
    private fun loadNext() {
        if (busy || finished || !isAdded) return
        busy = true
        val page = nextPage
        val appContext = requireContext().applicationContext
        val more = MoreTile("${category.title}: Результаты", category = category.id, sort = CatalogFilter.sort(appContext, category.id).sort, filtered = true)
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { more.loadPage(appContext, page) } }
            if (!isActive || !isAdded) return@launch
            busy = false
            if (skeletons) {
                cards.clear()
                skeletons = false
            }
            result.onSuccess { loaded ->
                // titles already shown are not added twice when TMDB repeats one on the next page
                val fresh = loaded.titles.filter { t -> (0 until cards.size()).none { (cards.get(it) as? Title)?.id == t.id } }
                if (loaded.ended) finished = true else nextPage = page + 1
                if (fresh.isNotEmpty()) cards.addAll(cards.size(), fresh)
                if (loaded.ended && cards.size() == 0) {
                    message.text = "Ничего не найдено"
                    message.visibility = View.VISIBLE
                } else if (fresh.isEmpty() && !loaded.ended) {
                    // a page that held only watched titles adds nothing: the next one is asked for at once
                    loadNext()
                }
            }.onFailure {
                finished = true
                if (cards.size() == 0) {
                    message.text = "Не удалось загрузить: ${it.message}"
                    message.visibility = View.VISIBLE
                }
            }
        }
    }

    private companion object {
        // the same grid as the "show all" page of a row and the search page
        const val COLUMNS = 5
        const val CARD_SCALE = 0.82f
    }
}
