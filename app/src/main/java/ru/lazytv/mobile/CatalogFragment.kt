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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lazytv.Category
import ru.lazytv.DetailsActivity
import ru.lazytv.R
import ru.lazytv.Title
import ru.lazytv.Tmdb
import ru.lazytv.databinding.FragmentMobileCatalogBinding
import ru.lazytv.toExtras

class CatalogFragment : Fragment() {
    private var _binding: FragmentMobileCatalogBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: MobileTitleAdapter
    private var currentPage = 1
    private var isLoading = false
    private var currentMode = MODE_MOVIES

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMobileCatalogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val columns = calculateColumns()
        val layoutManager = GridLayoutManager(requireContext(), columns)
        binding.catalogRecyclerView.layoutManager = layoutManager

        adapter = MobileTitleAdapter { title ->
            startActivity(Intent(requireContext(), DetailsActivity::class.java).putExtras(title.toExtras()))
        }
        binding.catalogRecyclerView.adapter = adapter

        // Pagination
        binding.catalogRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isLoading) return
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                if (lastVisibleItem + 6 >= totalItemCount) {
                    loadNextPage()
                }
            }
        })

        // Swipe to refresh
        binding.swipeRefresh.setColorSchemeColors(0xFF818CF8.toInt())
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(0xFF1E222A.toInt())
        binding.swipeRefresh.setOnRefreshListener {
            loadFirstPage()
        }

        // Chip selection
        binding.catalogChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val newMode = when {
                checkedIds.contains(R.id.chipSerials) -> MODE_SERIALS
                checkedIds.contains(R.id.chipCinema) -> MODE_CINEMA
                else -> MODE_MOVIES
            }
            if (newMode != currentMode) {
                currentMode = newMode
                loadFirstPage()
            }
        }

        loadFirstPage()
    }

    private fun calculateColumns(): Int {
        val displayMetrics = resources.displayMetrics
        val widthDp = displayMetrics.widthPixels / displayMetrics.density
        return when {
            widthDp >= 800 -> 5
            widthDp >= 600 -> 4
            else -> 3
        }
    }

    private fun loadFirstPage() {
        currentPage = 1
        isLoading = true
        binding.loadingIndicator.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val list = fetchTitles(currentPage)
            adapter.setItems(list)
            isLoading = false
            _binding?.loadingIndicator?.visibility = View.GONE
            _binding?.swipeRefresh?.isRefreshing = false
        }
    }

    private fun loadNextPage() {
        isLoading = true
        val nextPage = currentPage + 1

        viewLifecycleOwner.lifecycleScope.launch {
            val more = fetchTitles(nextPage)
            if (more.isNotEmpty()) {
                currentPage = nextPage
                adapter.addItems(more)
            }
            isLoading = false
        }
    }

    private suspend fun fetchTitles(page: Int): List<Title> = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext emptyList<Title>()
        try {
            when (currentMode) {
                MODE_MOVIES -> {
                    val cat = Tmdb.categories.firstOrNull { it.id == "movies" }
                        ?: Category("movies", "Фильмы", false, "", 0, true)
                    Tmdb.load(ctx, cat, "popularity.desc", page)
                }
                MODE_SERIALS -> {
                    val cat = Tmdb.categories.firstOrNull { it.id == "serials" }
                        ?: Category("serials", "Сериалы", true, "", 0, true)
                    Tmdb.load(ctx, cat, "popularity.desc", page)
                }
                MODE_CINEMA -> {
                    Tmdb.loadCollection(ctx, Tmdb.Collection("В кино", false, "now_playing", "&region=RU"), page)
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val MODE_MOVIES = 0
        private const val MODE_SERIALS = 1
        private const val MODE_CINEMA = 2
    }
}
