package ru.lazytv

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
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
    private val scale get() = resources.displayMetrics.widthPixels / 1280f
    private fun px(value: Int) = (value * scale).toInt()

    private lateinit var category: Category
    private lateinit var filter: CatalogFilter
    private lateinit var heading: TextView
    private lateinit var body: FrameLayout
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
        page.setPadding(px(64), px(36), px(64), px(24))
        page.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF101A14.toInt())
            cornerRadius = px(12).toFloat()
        }
        root.addView(page, FrameLayout.LayoutParams(-1, -1).apply { setMargins(px(40), px(40), px(40), px(40)) })
        val back = backButton(this, scale) { onBackPressed() }
        heading = text("", 34f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(back, LinearLayout.LayoutParams(px(56), px(56)).apply { rightMargin = px(20) })
        header.addView(heading)
        page.addView(header)
        body = FrameLayout(this)
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(16) })
        setContentView(root)
        showMain()
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
        line.setPadding(px(22), px(16), px(22), px(16))
        line.background = focusShape(scale, 0x33FFFFFF)
        line.isFocusable = true
        line.isClickable = true
        line.addView(text(name, 20f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        val shown = text(value, 17f, 0xFFCFE3D6.toInt()).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        line.addView(shown, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(24) })
        line.setOnClickListener { onClick(shown) }
        if (onKey != null) line.setOnKeyListener { _, code, event -> event.action == KeyEvent.ACTION_DOWN && onKey(code, shown) }
        return line
    }

    private fun showMain() {
        onMain = true
        val genresName = (if (category.tv) CatalogOptions.tvGenres else CatalogOptions.movieGenres)
        val rows = mutableListOf<View>()
        rows += row("Жанры", genresName.filter { it.first in filter.genres }.joinToString(", ") { it.second }.ifEmpty { "все" }) { showGenres() }
        rows += row("Страны", (CatalogOptions.popularCountries + CatalogOptions.otherCountries)
            .filter { it.first in filter.countries }.joinToString(", ") { it.second }.ifEmpty { "все" }) { showCountries() }
        rows += row("Годы", yearsText()) { showYears() }
        rows += ratingRow()
        if (!category.tv) rows += row("Возрастной рейтинг", filter.age.ifEmpty { "любой" }) { showAges() }
        rows += row("Скрывать просмотренное", if (filter.hideWatched) "да" else "нет") { shown ->
            save(filter.copy(hideWatched = !filter.hideWatched))
            shown.text = if (filter.hideWatched) "да" else "нет"
        }
        val view = listOfRows(*rows.toTypedArray())
        screen("Фильтры", view) {
            save(CatalogFilter())
            showMain()
        }
        focusFirst(view)
    }

    /** A small focusable value ("от 1", "до 10"): OK opens the list of values to pick from. */
    private fun segment(caption: String, onClick: (TextView) -> Unit): TextView = text(caption, 17f, 0xFFCFE3D6.toInt()).apply {
        setPadding(px(14), px(6), px(14), px(6))
        background = focusShape(scale, 0x33FFFFFF, radius = 4f)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick(this) }
    }

    /** A dropdown of whole numbers, the current one marked; the pick closes it. */
    private fun pickNumber(title: String, values: List<Int>, label: (Int) -> String, current: Int, onPick: (Int) -> Unit) {
        android.app.AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(values.map(label).toTypedArray(), values.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                onPick(values[which])
            }.show()
    }

    /** One line for the whole rating range: the name, then "от N" and "до N" side by side; left and right walk between them. */
    private fun ratingRow(): View {
        val line = LinearLayout(this)
        line.gravity = Gravity.CENTER_VERTICAL
        line.setPadding(px(22), px(10), px(22), px(10))
        line.addView(text("Рейтинг", 20f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        lateinit var from: TextView
        lateinit var to: TextView
        fun refresh() { from.text = "от ${filter.ratingFrom}"; to.text = "до ${filter.ratingTo}" }
        val numbers = (1..10).toList()
        from = segment("") {
            pickNumber("Рейтинг от", numbers, { it.toString() }, filter.ratingFrom) { v ->
                save(filter.copy(ratingFrom = v, ratingTo = filter.ratingTo.coerceAtLeast(v))); refresh()
            }
        }
        to = segment("") {
            pickNumber("Рейтинг до", numbers, { it.toString() }, filter.ratingTo) { v ->
                save(filter.copy(ratingTo = v, ratingFrom = filter.ratingFrom.coerceAtMost(v))); refresh()
            }
        }
        refresh()
        // at the ends of the line the focus stays; up and down go to the neighbouring rows as usual
        from.setOnKeyListener { _, code, e -> e.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_LEFT || (code == KeyEvent.KEYCODE_DPAD_RIGHT && to.requestFocus())) }
        to.setOnKeyListener { _, code, e -> e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT }
        line.addView(from, LinearLayout.LayoutParams(-2, -2))
        line.addView(to, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(12) })
        return line
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
        mark.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
        mark.buttonTintList = ColorStateList.valueOf(Color.WHITE)
        mark.isClickable = false
        mark.isFocusable = false
        val cell = FrameLayout(this)
        cell.setPadding(px(14), px(8), px(14), px(8))
        cell.background = focusShape(scale, Color.TRANSPARENT)
        cell.isFocusable = true
        cell.isClickable = true
        cell.addView(mark)
        cell.setOnClickListener {
            if (!radio) mark.toggle() else mark.isChecked = true
            onChange(mark.isChecked)
        }
        return cell
    }

    private fun grid(columns: Int, fill: (GridLayout) -> Unit): ScrollView {
        val g = GridLayout(this)
        g.columnCount = columns
        fill(g)
        return ScrollView(this).apply { addView(g) }
    }

    private fun GridLayout.addCell(view: View) {
        addView(view, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
    }

    private fun GridLayout.addCaption(caption: String) {
        addView(text(caption, 16f, 0xFF8FD4AE.toInt()).apply { setPadding(px(14), px(14), 0, px(4)); typeface = Typeface.DEFAULT_BOLD },
            GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, columnCount)).apply { width = 0 })
    }

    private fun showGenres() {
        onMain = false
        val picked = filter.genres.toMutableSet()
        val view = grid(3) { g ->
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
        fun cell(g: GridLayout, code: String, name: String) = g.addCell(choice(name, code in picked, false) { on ->
            if (on) picked += code else picked -= code
            save(filter.copy(countries = picked.toSet()))
        })
        // one column: names of different length in a several-column grid line up crookedly and the arrows jump around
        val view = grid(1) { g ->
            g.addCaption("Популярные")
            CatalogOptions.popularCountries.forEach { cell(g, it.first, it.second) }
            g.addCaption("По алфавиту")
            CatalogOptions.otherCountries.forEach { cell(g, it.first, it.second) }
        }
        screen("Страны", view) { picked.clear(); save(filter.copy(countries = emptySet())); showCountries() }
        focusFirst(view)
    }

    private fun showYears() {
        onMain = false
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        // 0 stands for "no bound"; the list runs from the current year back to 1900
        val years = listOf(0) + (now downTo 1900).toList()
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        lateinit var from: TextView
        lateinit var to: TextView
        fun refresh() {
            from.text = "от " + (if (filter.yearFrom == 0) "любого" else filter.yearFrom.toString())
            to.text = "до " + (if (filter.yearTo == 0) "любого" else filter.yearTo.toString())
        }
        fun label(y: Int) = if (y == 0) "любой" else y.toString()
        from = segment("") {
            pickNumber("Год от", years, ::label, filter.yearFrom) { v ->
                val next = if (v != 0 && filter.yearTo != 0 && filter.yearTo < v) filter.copy(yearFrom = v, yearTo = v) else filter.copy(yearFrom = v)
                save(next); refresh()
            }
        }
        to = segment("") {
            pickNumber("Год до", years, ::label, filter.yearTo) { v ->
                val next = if (v != 0 && filter.yearFrom != 0 && filter.yearFrom > v) filter.copy(yearTo = v, yearFrom = v) else filter.copy(yearTo = v)
                save(next); refresh()
            }
        }
        refresh()
        from.setOnKeyListener { _, code, e -> e.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_LEFT || (code == KeyEvent.KEYCODE_DPAD_RIGHT && to.requestFocus())) }
        to.setOnKeyListener { _, code, e -> e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT }
        val range = LinearLayout(this)
        range.gravity = Gravity.CENTER_VERTICAL
        range.setPadding(px(14), px(8), px(14), px(8))
        range.addView(text("Промежуток", 18f).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        range.addView(from, LinearLayout.LayoutParams(-2, -2))
        range.addView(to, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(12) })
        column.addView(range)
        val presets = GridLayout(this)
        presets.columnCount = 3
        presets.addCaption("Быстрый выбор")
        CatalogOptions.yearPresets(now).forEach { (name, f, t) ->
            presets.addCell(choice(name, filter.yearFrom == f && filter.yearTo == t, true) {
                save(filter.copy(yearFrom = f, yearTo = t))
                showMain()
            })
        }
        column.addView(presets)
        val view = ScrollView(this).apply { addView(column) }
        screen("Годы", view) { save(filter.copy(yearFrom = 0, yearTo = 0)); showMain() }
        focusFirst(view)
    }

    private fun showAges() {
        onMain = false
        val view = grid(1) { g ->
            g.addCell(choice("Любой", filter.age.isEmpty(), true) { save(filter.copy(age = "")); showMain() })
            CatalogOptions.ages.forEach { (mark, name) ->
                g.addCell(choice("$name  ($mark)", filter.age == mark, true) { save(filter.copy(age = mark)); showMain() })
            }
        }
        screen("Возрастной рейтинг", view) { save(filter.copy(age = "")); showMain() }
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
