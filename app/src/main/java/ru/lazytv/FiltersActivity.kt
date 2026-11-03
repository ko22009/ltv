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
        root.background = calmBackground()
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(px(64), px(36), px(64), px(24))
        val back = ImageView(this).apply {
            setImageDrawable(PathDrawable(MenuIcons.BACK).apply { setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN) })
            setPadding(px(12), px(12), px(12), px(12))
            background = focusShape(scale, 0x66000000, circle = true)
            isFocusable = true
            isClickable = true
            contentDescription = "Назад"
            setOnClickListener { onBackPressed() }
        }
        heading = text("", 34f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(back, LinearLayout.LayoutParams(px(56), px(56)).apply { rightMargin = px(20) })
        header.addView(heading)
        page.addView(header)
        body = FrameLayout(this)
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(16) })
        reset = chipButton(this, scale, "Сбросить").apply { setOnClickListener { resetAction() } }
        page.addView(reset, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(12) })
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
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
        rows += stepper("Рейтинг от", filter.ratingFrom) { v -> save(filter.copy(ratingFrom = v.coerceAtMost(filter.ratingTo))); filter.ratingFrom }
        rows += stepper("Рейтинг до", filter.ratingTo) { v -> save(filter.copy(ratingTo = v.coerceAtLeast(filter.ratingFrom))); filter.ratingTo }
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

    /** A rating bound: left and right on the remote (or OK) move it by one. */
    private fun stepper(name: String, start: Int, apply: (Int) -> Int): View {
        var current = start
        fun label() = "‹  $current  ›"
        return row(name, label(), onKey = { code, shown ->
            val step = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
            if (step == 0) false else {
                current = apply((current + step).coerceIn(1, 10))
                shown.text = label()
                true
            }
        }) { shown ->
            // OK walks the value up and starts over after the top, for a remote that is slow to repeat the arrows
            current = apply(if (current >= 10) 1 else current + 1)
            shown.text = label()
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
        val view = grid(3) { g ->
            g.addCaption("Популярные")
            CatalogOptions.popularCountries.forEach { cell(g, it.first, it.second) }
            // the caption takes a whole row, so the next cells start on a fresh line
            g.addCaption("По алфавиту")
            CatalogOptions.otherCountries.forEach { cell(g, it.first, it.second) }
        }
        screen("Страны", view) { picked.clear(); save(filter.copy(countries = emptySet())); showCountries() }
        focusFirst(view)
    }

    private fun showYears() {
        onMain = false
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val view = grid(3) { g ->
            CatalogOptions.yearPresets(now).forEach { (name, from, to) ->
                g.addCell(choice(name, filter.yearFrom == from && filter.yearTo == to, true) {
                    save(filter.copy(yearFrom = from, yearTo = to))
                    showMain()
                })
            }
        }
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

    private fun focusFirst(scroll: ScrollView) {
        scroll.post { (scroll.getChildAt(0) as? android.view.ViewGroup)?.let { g ->
            (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it.isFocusable }?.requestFocus()
        } }
    }
}
