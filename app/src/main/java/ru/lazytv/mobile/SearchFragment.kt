package ru.lazytv.mobile

import ru.lazytv.TEXT_MUTED
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
import ru.lazytv.DialogButton
import ru.lazytv.Errors
import ru.lazytv.History
import ru.lazytv.R
import ru.lazytv.Settings
import ru.lazytv.Title
import ru.lazytv.Tmdb
import ru.lazytv.databinding.FragmentMobileGridBinding
import ru.lazytv.showMessage
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
        // long press on a recently opened title takes it out of the history (only while nothing is typed)
        adapter.onItemLongClick = { title ->
            if (binding.searchInput.text.isNullOrBlank()) {
                History.remove(requireContext(), listOf(title))
                adapter.removeItem(title)
                android.widget.Toast.makeText(requireContext(), "Убрано из недавних", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        binding.searchInput.visibility = View.VISIBLE
        binding.clearRecentButton.setOnClickListener { askClearRecent() }
        setupMicrophone()
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

    /** The system speech dialog fills the field and runs the search; it needs no microphone permission of the app. */
    private val speech = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val phrase = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (_binding != null && !phrase.isNullOrBlank()) {
            binding.searchInput.setText(phrase)
            binding.searchInput.setSelection(phrase.length)
            search(phrase)
        }
    }

    /** The microphone at the right end of the field, the same icon as on the TV search bar. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupMicrophone() {
        val input = binding.searchInput
        val size = (24 * resources.displayMetrics.density).toInt()
        val mic = ru.lazytv.PathDrawable(ru.lazytv.MenuIcons.MIC).apply {
            setBounds(0, 0, size, size)
            setColorFilter(TEXT_MUTED, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        input.setCompoundDrawablesRelative(input.compoundDrawablesRelative[0], null, mic, null)
        input.setOnTouchListener { v, event ->
            val inMic = event.x > v.width - v.paddingRight - size - v.paddingRight
            if (event.action == android.view.MotionEvent.ACTION_UP && inMic) {
                val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                runCatching { speech.launch(intent) }
                    .onFailure { android.widget.Toast.makeText(requireContext(), "Голосовой ввод недоступен на этом устройстве", android.widget.Toast.LENGTH_SHORT).show() }
                true
            } else false
        }
    }

    private fun saved(): List<String> {
        val array = runCatching { JSONArray(Settings.string(requireContext(), Settings.SEARCH_HISTORY, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun forget(phrase: String) {
        val list = saved().filter { !it.equals(phrase, true) }
        Settings.setString(requireContext(), Settings.SEARCH_HISTORY, JSONArray(list).toString())
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
            // the cross takes this one phrase out of the history
            chip.isCloseIconVisible = true
            chip.closeIconTint = android.content.res.ColorStateList.valueOf(TEXT_MUTED)
            chip.setOnCloseIconClickListener { forget(phrase); showIdle() }
            chip.setOnClickListener {
                binding.searchInput.setText(phrase)
                binding.searchInput.setSelection(phrase.length)
                search(phrase)
            }
            binding.chipGroup.addView(chip)
        }
        if (phrases.size > 1) {
            val clear = layoutInflater.inflate(R.layout.chip_mobile_filter, binding.chipGroup, false) as Chip
            clear.text = "Очистить всё"
            clear.isCheckable = false
            clear.setTextColor(TEXT_MUTED)
            clear.setOnClickListener {
                Settings.setString(requireContext(), Settings.SEARCH_HISTORY, "[]")
                showIdle()
            }
            binding.chipGroup.addView(clear)
        }
        binding.chipScroll.visibility = if (phrases.isEmpty()) View.GONE else View.VISIBLE
        val recent = History.all(requireContext())
        adapter.setItems(recent)
        binding.sectionTitle.text = blockHeading("Недавно открытое", "Удержание убирает")
        binding.sectionTitle.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        binding.clearRecentButton.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        binding.sectionHeader.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
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
        binding.sectionHeader.visibility = View.GONE
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

    private fun askClearRecent() {
        showMessage(requireActivity(), "Очистить весь список?", message = "Недавно открытое будет удалено.", buttons = listOf(
            DialogButton("Отмена"),
            DialogButton("Очистить") {
                History.clear(requireContext())
                showIdle()
            },
        ))
    }

    override fun onResume() {
        super.onResume()
        // the history changes while the cards are opened: redraw the idle state only when nothing is typed
        if (_binding != null && binding.searchInput.text.isNullOrBlank()) showIdle()
        else if (_binding != null) adapter.refreshWatched()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** Columns of the card grid: three on a phone, more on a wide screen. */
/** The heading of a phone block with a small grey line under it ("Продолжить просмотр" / "Удержание убирает"): the same on every page. */
fun blockHeading(title: String, hint: String): CharSequence = android.text.SpannableStringBuilder().apply {
    append(title)
    setSpan(android.text.style.AbsoluteSizeSpan(22, true), 0, length, 0)
    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
    setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.WHITE), 0, length, 0)
    val start = length + 1
    append("\n").append(hint)
    setSpan(android.text.style.AbsoluteSizeSpan(13, true), start, length, 0)
    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), start, length, 0)
}

fun mobileColumns(resources: android.content.res.Resources): Int {
    val m = resources.displayMetrics
    val widthDp = m.widthPixels / m.density
    return when {
        widthDp >= 800 -> 5
        widthDp >= 600 -> 4
        else -> 3
    }
}
