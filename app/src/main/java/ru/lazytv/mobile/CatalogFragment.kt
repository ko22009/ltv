package ru.lazytv.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lazytv.CatalogFilter
import ru.lazytv.CatalogSort
import ru.lazytv.DetailsActivity
import ru.lazytv.Errors
import ru.lazytv.FiltersActivity
import ru.lazytv.MoreTile
import ru.lazytv.Tmdb
import ru.lazytv.databinding.FragmentMobileCatalogBinding
import ru.lazytv.showChoice
import ru.lazytv.toExtras

/** Films or series ("category" argument): the filter and sort buttons on top, the cards in a vertical grid, more pages while scrolling. */
class CatalogFragment : Fragment() {
    private var _binding: FragmentMobileCatalogBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: MobileTitleAdapter
    private val category get() = Tmdb.categories.first { it.id == requireArguments().getString("category", "movies") }
    private var nextPage = 1
    private var busy = false
    private var finished = false
    private var loadJob: Job? = null

    /** The filter and the sort the list was built with: compared on return to know when it must be asked again. */
    private var signature = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMobileCatalogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val layoutManager = GridLayoutManager(requireContext(), mobileColumns(resources))
        binding.catalogRecyclerView.layoutManager = layoutManager
        adapter = MobileTitleAdapter { title ->
            startActivity(Intent(requireContext(), DetailsActivity::class.java).putExtras(title.toExtras()))
        }
        binding.catalogRecyclerView.adapter = adapter
        binding.catalogRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && layoutManager.findLastVisibleItemPosition() + 6 >= layoutManager.itemCount) loadNext()
            }
        })

        binding.swipeRefresh.setColorSchemeColors(android.graphics.Color.WHITE)
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(ru.lazytv.PLATE_FILL)
        binding.swipeRefresh.setOnRefreshListener { startOver() }

        binding.filterButton.setOnClickListener {
            startActivity(Intent(requireContext(), FiltersActivity::class.java).putExtra("category", category.id))
        }
        binding.sortButton.setOnClickListener { chooseSort() }
        binding.resetButton.setOnClickListener {
            CatalogFilter.save(requireContext(), category.id, CatalogFilter())
            startOver()
        }
        startOver()
    }

    /** The captions of the buttons and the line with what is chosen. */
    private fun renderButtons() {
        val ctx = requireContext()
        val filter = CatalogFilter.load(ctx, category.id)
        binding.filterButton.text = if (filter.count > 0) "Фильтры · ${filter.count}" else "Фильтры"
        binding.sortButton.text = CatalogFilter.sort(ctx, category.id).label
        // the basket is always drawn; without filters it is dimmed and does nothing
        binding.resetButton.isEnabled = filter.count > 0
        binding.resetButton.alpha = if (filter.count > 0) 1f else 0.35f
        val chosen = filter.describe(category)
        binding.filtersLine.text = chosen
        binding.filtersLine.visibility = if (chosen.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun chooseSort() {
        val ctx = requireContext()
        val all = CatalogSort.values()
        showChoice(requireActivity(), "Сортировка", all.map { it.label }, all.indexOf(CatalogFilter.sort(ctx, category.id)), binding.sortButton) { which ->
            CatalogFilter.saveSort(ctx, category.id, all[which])
            startOver()
        }
    }

    /** The filters or the sort were changed on their own screen: the list is built from the first page again. */
    override fun onResume() {
        super.onResume()
        if (_binding != null && signature != CatalogFilter.signature(requireContext(), category.id)) startOver()
    }

    private var quietRetries = 0

    private fun startOver() {
        loadJob?.cancel()
        quietRetries = 0
        val ctx = requireContext()
        signature = CatalogFilter.signature(ctx, category.id)
        renderButtons()
        adapter.setItems(emptyList())
        nextPage = 1
        busy = false
        finished = false
        binding.messageText.visibility = View.GONE
        binding.loadingIndicator.visibility = View.VISIBLE
        loadNext()
    }

    /** Asks TMDB for the next page; a page made up only of watched titles (with "hide watched") is skipped. */
    private fun loadNext() {
        if (busy || finished || _binding == null) return
        busy = true
        val page = nextPage
        val ctx = requireContext().applicationContext
        val list = MoreTile(category.title, category = category.id, sort = CatalogFilter.sort(ctx, category.id).sort, filtered = true)
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { list.loadPage(ctx, page) } }
            val b = _binding ?: return@launch
            busy = false
            b.loadingIndicator.visibility = View.GONE
            b.swipeRefresh.isRefreshing = false
            result.onSuccess { loaded ->
                if (loaded.ended) finished = true else nextPage = page + 1
                adapter.addItems(loaded.titles)
                if (loaded.ended && adapter.itemCount == 0) {
                    b.messageText.text = "Ничего не найдено.\nИзмените или сбросьте фильтры"
                    b.messageText.visibility = View.VISIBLE
                } else if (loaded.titles.isEmpty() && !loaded.ended) {
                    loadNext()
                }
            }.onFailure {
                // the first start: the block bypass is still being picked, the first request may fail; ask again quietly before blaming the network
                if (Errors.isNetwork(it) && adapter.itemCount == 0 && quietRetries < 2) {
                    quietRetries++
                    b.loadingIndicator.visibility = View.VISIBLE
                    kotlinx.coroutines.delay(3000)
                    loadNext()
                    return@onFailure
                }
                finished = true
                if (adapter.itemCount == 0) {
                    b.messageText.text = Errors.explain(it)
                    b.messageText.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadJob?.cancel()
        _binding = null
    }
}
