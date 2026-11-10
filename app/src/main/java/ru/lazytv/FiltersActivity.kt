package ru.lazytv

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView

/**
 * Filters of a catalogue list (films or series): the main screen lists the filters with their values, and each
 * one opens its own screen — genres, countries, years, age mark. Every choice is saved at once, and the list
 * behind this screen is drawn again when the user comes back.
 */
class FiltersActivity : Activity() {
    private val scale get() = uiScale()
    private fun px(value: Int) = (value * scale).toInt()

    private lateinit var category: Category
    private lateinit var filter: CatalogFilter
    private lateinit var heading: TextView
    private lateinit var body: FrameLayout
    private lateinit var backBtn: View
    private var searchBar: SearchBar? = null
    private lateinit var reset: android.view.View
    private var resetAction: () -> Unit = {}
    private var onMain = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        category = Tmdb.categories.first { it.id == intent.getStringExtra("category") }
        filter = CatalogFilter.load(this, category.id)
        val root = FrameLayout(this)
        // the window is translucent: the list stays behind, dimmed, and the filters lie over it as a panel
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        if (isMobileUi()) page.setPadding(px(16), px(16), px(16), px(12)) else page.setPadding(px(64), px(36), px(64), px(24))
        page.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(PAGE_BG)
            cornerRadius = if (isMobileUi()) 0f else px(12).toFloat() // a whole page on a phone, a panel on a TV
        }
        root.addView(page, FrameLayout.LayoutParams(-1, -1).apply { val gap = if (isMobileUi()) 0 else px(40); setMargins(gap, gap, gap, gap) })
        backBtn = backButton(this, scale) { onBackPressed() }
        heading = text("", if (isMobileUi()) 24f else 34f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(backBtn, LinearLayout.LayoutParams(px(56), px(56)).apply { rightMargin = px(16) })
        header.addView(heading)
        page.addView(header)
        body = FrameLayout(this)
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(16) })
        setContentView(root)
        showMain()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        searchBar?.handleResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        searchBar?.handlePermissionsResult(requestCode, permissions, grantResults)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (onMain) super.onBackPressed() else showMain()
    }

    private fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
    }

    private fun save(next: CatalogFilter) {
        filter = next
        CatalogFilter.save(this, category.id, next)
    }

    private fun screen(title: String, content: View, onReset: () -> Unit) {
        heading.text = title
        resetAction = onReset
        body.removeAllViews()
        body.addView(content, FrameLayout.LayoutParams(-1, -1))
    }

    private fun listOfRows(vararg rows: View): ScrollView {
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        rows.forEach { column.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) }) }
        return ScrollView(this).apply { addView(column) }
    }

    /** One line of the main screen: the name on the left, the chosen value on the right. */
    private fun row(name: String, value: String, onKey: ((Int, TextView) -> Boolean)? = null, onClick: (TextView) -> Unit): View {
        val line = LinearLayout(this)
        line.gravity = Gravity.CENTER_VERTICAL
        line.setPadding(px(24), px(16), px(24), px(16))
        line.background = focusShape(scale, PLATE_FILL, radius = TITLE_CARD_RADIUS)
        line.isFocusable = true
        line.isClickable = true
        // the name keeps its width; the chosen value takes the rest (right-aligned) and is cut with "..." when it is long
        line.addView(text(name, 20f).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1 }, LinearLayout.LayoutParams(-2, -2))
        val shown = text(value, 17f, TEXT_SECONDARY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.END }
        line.addView(shown, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(24) })
        line.setOnClickListener { onClick(shown) }
        if (onKey != null) line.setOnKeyListener { _, code, event -> event.action == KeyEvent.ACTION_DOWN && onKey(code, shown) }
        return line
    }

    private fun showMain() {
        onMain = true
        searchBar = null
        if (::backBtn.isInitialized) backBtn.setOnKeyListener(null)
        val genresName = (if (category.tv) CatalogOptions.tvGenres else CatalogOptions.movieGenres)
        val rows = mutableListOf<View>()
        rows += row("Жанры", genresName.filter { it.first in filter.genres }.joinToString(", ") { it.second }.ifEmpty { "все" }) { showGenres() }
        rows += row("Страны", (CatalogOptions.popularCountries + CatalogOptions.otherCountries)
            .filter { it.first in filter.countries }.joinToString(", ") { it.second }.ifEmpty { "все" }) { showCountries() }
        rows += row("Годы", yearsText()) { showYears() }
        if (!category.tv) {
            fun releaseText() = CatalogOptions.releases.first { it.first == filter.release }.second.lowercase()
            rows += row("Выход", releaseText()) { shown ->
                val keys = CatalogOptions.releases.map { it.first }
                pickLabel("Выход", CatalogOptions.releases.map { it.second }, keys.indexOf(filter.release).coerceAtLeast(0)) { which ->
                    save(filter.copy(release = keys[which]))
                    shown.text = releaseText()
                }
            }
        }
        rows += row("Рейтинг", ratingText()) { shown -> pickRating(shown) }
        rows += row("Возрастной рейтинг", filter.age.ifEmpty { "любой" }) { shown ->
            val marks = listOf("") + CatalogOptions.ages.map { it.first }
            val labels = listOf("Любой") + CatalogOptions.ages.map { "${it.second}  (${it.first})" }
            pickLabel("Возрастной рейтинг", labels, marks.indexOf(filter.age)) { which ->
                save(filter.copy(age = marks[which]))
                shown.text = filter.age.ifEmpty { "любой" }
            }
        }
        rows += row("Скрывать просмотренное", if (filter.hideWatched) "да" else "нет") { shown ->
            pickLabel("Скрывать просмотренное", listOf("Да", "Нет"), if (filter.hideWatched) 0 else 1) { which ->
                save(filter.copy(hideWatched = which == 0))
                shown.text = if (filter.hideWatched) "да" else "нет"
            }
        }
        val view = listOfRows(*rows.toTypedArray())
        screen("Фильтры", view) {
            save(CatalogFilter())
            showMain()
        }
        focusFirst(view)
    }

    /** The app's dialog with one choice of several captions, the current one marked; the pick closes it. */
    private fun pickLabel(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        showChoice(this, title, labels, current, onPick = onPick)
    }

    /** A dropdown of whole numbers, the current one marked; the pick closes it. */
    private fun pickNumber(title: String, values: List<Int>, label: (Int) -> String, current: Int, onPick: (Int) -> Unit) {
        pickLabel(title, values.map(label), values.indexOf(current)) { onPick(values[it]) }
    }

    private fun ratingText(): String =
        if (filter.ratingFrom == 1 && filter.ratingTo == 10) "любой" else "${filter.ratingFrom}–${filter.ratingTo}"

    /** Two-step dialog to pick rating range: "от" (reset, or 1..10), then "до" (from..10). */
    private fun pickRating(shown: TextView) {
        val numbers = (1..10).toList()
        // the first row resets the range, the numbers follow it
        val labels = listOf("Любой (1–10)") + numbers.map { if (it == 1) "1" else it.toString() }
        val currentFrom = if (filter.ratingFrom == 1 && filter.ratingTo == 10) 0 else numbers.indexOf(filter.ratingFrom) + 1
        showChoice(this, "Рейтинг от", labels, currentFrom) { whichFrom ->
            if (whichFrom == 0) {
                save(filter.copy(ratingFrom = 1, ratingTo = 10))
                shown.text = ratingText()
                return@showChoice
            }
            val fromVal = numbers[whichFrom - 1]
            if (fromVal == 10) {
                save(filter.copy(ratingFrom = 10, ratingTo = 10))
                shown.text = ratingText()
            } else {
                val toNumbers = (fromVal..10).toList()
                val toLabels = toNumbers.map { if (it == 10) "10  (без ограничений)" else it.toString() }
                val currentToIdx = toNumbers.indexOf(filter.ratingTo.coerceAtLeast(fromVal))
                showChoice(this, "Рейтинг до", toLabels, if (currentToIdx >= 0) currentToIdx else toNumbers.size - 1) { whichTo ->
                    save(filter.copy(ratingFrom = fromVal, ratingTo = toNumbers[whichTo]))
                    shown.text = ratingText()
                }
            }
        }
    }

    private fun yearsText(): String = when {
        filter.yearFrom == 0 && filter.yearTo == 0 -> "все"
        filter.yearFrom == 0 -> "до ${filter.yearTo}"
        filter.yearFrom == filter.yearTo -> "${filter.yearFrom}"
        else -> "${filter.yearFrom}–${filter.yearTo}"
    }

    /** A cell of a choice screen: a mark and a caption; OK flips the mark. */
    private fun choice(name: String, checked: Boolean, radio: Boolean, onChange: (Boolean) -> Unit): View {
        val mark: CompoundButton = if (radio) RadioButton(this) else CheckBox(this)
        mark.text = name
        mark.isChecked = checked
        mark.setTextColor(Color.WHITE)
        mark.setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (isMobileUi()) 16f else 18f) * scale)
        mark.buttonTintList = ColorStateList.valueOf(Color.WHITE)
        mark.isClickable = false
        mark.isFocusable = false
        val cell = FrameLayout(this)
        cell.setPadding(px(if (isMobileUi()) 8 else 14), px(8), px(if (isMobileUi()) 8 else 14), px(8))
        cell.background = focusShape(scale, Color.TRANSPARENT)
        cell.isFocusable = true
        cell.isClickable = true
        cell.addView(mark, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        cell.setOnClickListener {
            if (!radio) mark.toggle() else mark.isChecked = true
            onChange(mark.isChecked)
        }
        return cell
    }

    private fun grid(columns: Int, fill: (GridLayout) -> Unit): ScrollView {
        val g = GridLayout(this).apply {
            columnCount = columns
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        fill(g)
        return ScrollView(this).apply {
            isFillViewport = true
            addView(g)
        }
    }

    private fun GridLayout.addCell(view: View) {
        val colSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.FILL, 1f)
        val rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.FILL, 0f)
        addView(view, GridLayout.LayoutParams(rowSpec, colSpec).apply {
            width = 0
            height = FrameLayout.LayoutParams.WRAP_CONTENT
        })
    }

    private fun GridLayout.addCaption(caption: String) {
        val colSpec = GridLayout.spec(0, columnCount, GridLayout.FILL, 1f)
        val rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.FILL, 0f)
        addView(text(caption, 16f, 0xFFFFFFFF.toInt()).apply { setPadding(px(16), px(16), 0, px(4)); typeface = Typeface.DEFAULT_BOLD },
            GridLayout.LayoutParams(rowSpec, colSpec).apply {
                width = 0
                height = FrameLayout.LayoutParams.WRAP_CONTENT
            })
    }

    private fun showGenres() {
        onMain = false
        searchBar = null
        if (::backBtn.isInitialized) backBtn.setOnKeyListener(null)
        val picked = filter.genres.toMutableSet()
        // three columns are too narrow for a phone: the captions broke in the middle of a word
        val view = grid(if (isMobileUi()) 2 else 3) { g ->
            (if (category.tv) CatalogOptions.tvGenres else CatalogOptions.movieGenres).forEach { (id, name) ->
                g.addCell(choice(name, id in picked, false) { on ->
                    if (on) picked += id else picked -= id
                    save(filter.copy(genres = picked.toSet()))
                })
            }
        }
        screen("Жанры", view) { picked.clear(); save(filter.copy(genres = emptySet())); showGenres() }
        focusFirst(view)
    }

    private fun showCountries() {
        onMain = false
        val picked = filter.countries.toMutableSet()
        val allCountries = (CatalogOptions.popularCountries + CatalogOptions.otherCountries)
            .distinctBy { it.first }
            .sortedBy { it.second }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        fun populateGrid(query: String) {
            val q = query.trim().lowercase()
            val g = GridLayout(this).apply {
                columnCount = 1
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            }
            fun cell(code: String, name: String) = g.addCell(choice(name, code in picked, false) { on ->
                if (on) picked += code else picked -= code
                save(filter.copy(countries = picked.toSet()))
            })

            if (q.isEmpty()) {
                g.addCaption("Популярные")
                CatalogOptions.popularCountries.forEach { cell(it.first, it.second) }
                g.addCaption("По алфавиту")
                CatalogOptions.otherCountries.forEach { cell(it.first, it.second) }
            } else {
                val matches = allCountries.filter { it.second.lowercase().contains(q) || it.first.lowercase().contains(q) }
                if (matches.isEmpty()) {
                    val emptyText = text("Ничего не найдено", 18f, TEXT_MUTED).apply {
                        setPadding(px(16), px(24), px(16), px(24))
                    }
                    val colSpec = GridLayout.spec(0, 1, GridLayout.FILL, 1f)
                    val rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.FILL, 0f)
                    g.addView(emptyText, GridLayout.LayoutParams(rowSpec, colSpec).apply {
                        width = 0
                        height = FrameLayout.LayoutParams.WRAP_CONTENT
                    })
                } else {
                    matches.forEach { cell(it.first, it.second) }
                }
            }
            scroll.removeAllViews()
            scroll.addView(g)
        }

        fun focusFirstCountry(): Boolean {
            fun firstFocusable(v: View): View? {
                if (v.isFocusable && v !is ScrollView) return v
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
                return null
            }
            return firstFocusable(scroll)?.requestFocus() ?: false
        }

        val bar = SearchBar(this, scale) { query ->
            populateGrid(query)
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(currentFocus?.windowToken ?: window?.decorView?.windowToken, 0)
            scroll.post { focusFirstCountry() }
        }.apply {
            input.hint = "Поиск страны"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(56)).apply {
                bottomMargin = px(12)
            }
            input.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    populateGrid(s?.toString().orEmpty())
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        searchBar = bar
        bar.onDown = {
            focusFirstCountry()
        }
        bar.onLeft = {
            backBtn.requestFocus()
        }

        backBtn.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                bar.mic.requestFocus()
                true
            } else false
        }

        container.addView(bar)
        container.addView(scroll)
        populateGrid("")

        screen("Страны", container) { picked.clear(); save(filter.copy(countries = emptySet())); showCountries() }
        focusFirst(scroll)
    }

    private fun showYears() {
        onMain = false
        searchBar = null
        if (::backBtn.isInitialized) backBtn.setOnKeyListener(null)
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        // 0 stands for "no bound"; the list runs from the current year back to 1900
        val years = listOf(0) + (now downTo 1900).toList()
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        fun label(y: Int) = if (y == 0) "любой" else y.toString()
        val fromRow = row("Год от", if (filter.yearFrom == 0) "любой" else filter.yearFrom.toString()) { _ ->
            pickNumber("Год от", years, ::label, filter.yearFrom) { v ->
                val next = if (v != 0 && filter.yearTo != 0 && filter.yearTo < v) filter.copy(yearFrom = v, yearTo = v) else filter.copy(yearFrom = v)
                save(next)
                showYears()
            }
        }
        val toRow = row("Год до", if (filter.yearTo == 0) "любой" else filter.yearTo.toString()) { _ ->
            pickNumber("Год до", years, ::label, filter.yearTo) { v ->
                val next = if (v != 0 && filter.yearFrom != 0 && filter.yearFrom > v) filter.copy(yearTo = v, yearFrom = v) else filter.copy(yearTo = v)
                save(next)
                showYears()
            }
        }
        column.addView(fromRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
        column.addView(toRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(12) })
        val presets = GridLayout(this).apply {
            // two columns on a phone: "2020–2026" does not fit in a third of the width
            columnCount = if (isMobileUi()) 2 else 3
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        presets.addCaption("Быстрый выбор")
        CatalogOptions.yearPresets(now).forEach { (name, f, t) ->
            presets.addCell(choice(name, filter.yearFrom == f && filter.yearTo == t, true) {
                save(filter.copy(yearFrom = f, yearTo = t))
                showMain()
            })
        }
        column.addView(presets)
        val view = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
        screen("Годы", view) { save(filter.copy(yearFrom = 0, yearTo = 0)); showMain() }
        focusFirst(view)
    }

    /** Puts the focus on the first focusable view of a screen, however deep it sits (the old focus is gone with the old screen). */
    private fun focusFirst(scroll: ScrollView) {
        fun firstFocusable(v: View): View? {
            if (v.isFocusable && v !is ScrollView) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
            return null
        }
        scroll.post { firstFocusable(scroll)?.requestFocus() }
    }
}
