package ru.lazytv.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import ru.lazytv.DetailsActivity
import ru.lazytv.Errors
import ru.lazytv.History
import ru.lazytv.R
import ru.lazytv.Settings
import ru.lazytv.Title
import ru.lazytv.Tmdb
import ru.lazytv.databinding.FragmentMobileGridBinding
import ru.lazytv.toExtras

/** Search by name: the phrases typed before as chips, the recently opened titles when nothing is typed, TMDB results otherwise. */
class SearchFragment : Fragment() {
    private var _binding: FragmentMobileGridBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: MobileTitleAdapter
    private var job: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMobileGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = MobileTitleAdapter { title ->
            startActivity(Intent(requireContext(), DetailsActivity::class.java).putExtras(title.toExtras()))
        }
        binding.recycler.layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), mobileColumns(resources))
        binding.recycler.adapter = adapter
        binding.searchInput.visibility = View.VISIBLE
        binding.chipGroup.isSingleSelection = false
        binding.chipGroup.isSelectionRequired = false
        binding.searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                search(binding.searchInput.text.toString())
                true
            } else false
        }
        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { if (s.isNullOrBlank()) showIdle() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        showIdle()
    }

    private fun saved(): List<String> {
        val array = runCatching { JSONArray(Settings.string(requireContext(), Settings.SEARCH_HISTORY, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun remember(phrase: String) {
        val list = (listOf(phrase) + saved().filter { !it.equals(phrase, true) }).take(12)
        Settings.setString(requireContext(), Settings.SEARCH_HISTORY, JSONArray(list).toString())
    }

    /** Nothing typed: the earlier phrases and the titles opened recently. */
    private fun showIdle() {
        job?.cancel()
        binding.loading.visibility = View.GONE
        val phrases = saved()
        binding.chipGroup.removeAllViews()
        phrases.forEach { phrase ->
            val chip = layoutInflater.inflate(R.layout.chip_mobile_filter, binding.chipGroup, false) as Chip
            chip.text = phrase
            chip.isCheckable = false
            chip.setOnClickListener {
                binding.searchInput.setText(phrase)
                binding.searchInput.setSelection(phrase.length)
                search(phrase)
            }
            binding.chipGroup.addView(chip)
        }
        binding.chipScroll.visibility = if (phrases.isEmpty()) View.GONE else View.VISIBLE
        val recent = History.all(requireContext())
        adapter.setItems(recent)
        binding.sectionTitle.text = "НЕДАВНО ОТКРЫТЫЕ"
        binding.sectionTitle.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyText.text = "Введите название фильма или сериала"
        binding.emptyText.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun search(raw: String) {
        val phrase = raw.trim()
        if (phrase.length < 2) return
        (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        remember(phrase)
        binding.chipScroll.visibility = View.GONE
        binding.sectionTitle.visibility = View.GONE
        binding.emptyText.visibility = View.GONE
        binding.loading.visibility = View.VISIBLE
        adapter.setItems(emptyList())
        job?.cancel()
        job = viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext().applicationContext
            val result = withContext(Dispatchers.IO) { runCatching { Tmdb.search(ctx, phrase) } }
            val b = _binding ?: return@launch
            b.loading.visibility = View.GONE
            result.onSuccess {
                adapter.setItems(it)
                b.emptyText.text = "Ничего не найдено. Попробуйте другое название"
                b.emptyText.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }.onFailure {
                b.emptyText.text = Errors.explain(it)
                b.emptyText.visibility = View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // the history changes while the cards are opened: redraw the idle state only when nothing is typed
        if (_binding != null && binding.searchInput.text.isNullOrBlank()) showIdle()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** Columns of the card grid: three on a phone, more on a wide screen. */
fun mobileColumns(resources: android.content.res.Resources): Int {
    val m = resources.displayMetrics
    val widthDp = m.widthPixels / m.density
    return when {
        widthDp >= 800 -> 5
        widthDp >= 600 -> 4
        else -> 3
    }
}
