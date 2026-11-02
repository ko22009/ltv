package ru.lazytv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.json.JSONArray

/** The "search torrents for this phrase" tile (still referenced by the list presenters). */
data class TorrentQuery(val query: String)

/**
 * Search in the LazyMedia layout: microphone and torrent-mode buttons next to the input, the search
 * history with "clear", and the found titles below. In torrent mode the phrase goes straight to the
 * torrent list.
 */
class SearchActivity : Activity() {
    private val scale get() = resources.displayMetrics.widthPixels / 1280f
    private fun px(value: Int) = (value * scale).toInt()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var searchBar: SearchBar
    private lateinit var input: EditText
    private lateinit var history: LinearLayout
    private lateinit var historyBlock: LinearLayout
    private lateinit var grid: VerticalGridView
    private lateinit var message: TextView
    private var results: List<Any> = emptyList()
    private var pending: Runnable? = null

    private fun label(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun focusBackground(normal: Int, radius: Float = 3f, circle: Boolean = false) = focusShape(scale, normal, radius, circle)

    /** Chip with a small vector icon and a caption; focus turns it bright green. */
    private fun button(icon: String, caption: String, action: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        background = focusBackground(0x66000000)
        addView(ImageView(this@SearchActivity).apply {
            setImageDrawable(PathDrawable(icon).apply { setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN) })
        }, LinearLayout.LayoutParams(px(18), px(18)))
        addView(label(caption, 15f).apply { setPadding(px(10), 0, 0, 0) })
        setPadding(px(16), 0, px(18), 0)
        setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.background = calmBackground()
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(px(64), px(36), px(64), px(16))

        // microphone and the input
        searchBar = SearchBar(this, scale) { submit(it) }
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
        historyBlock = LinearLayout(this)
        historyBlock.orientation = LinearLayout.VERTICAL
        historyBlock.addView(label("История поиска", 22f).apply { setPadding(0, px(20), 0, px(10)) })
        history = LinearLayout(this)
        historyBlock.addView(history, LinearLayout.LayoutParams(-1, px(44)))
        page.addView(historyBlock)

        message = label("", 16f, 0xFFAFC4B6.toInt())
        message.setPadding(0, px(14), 0, 0)
        message.visibility = View.GONE
        page.addView(message)

        grid = VerticalGridView(this)
        grid.setNumColumns(6)
        grid.setPadding(0, px(14), 0, 0)
        grid.setItemSpacing(px(14))
        grid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = results.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(card()) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = bind(holder.itemView, results[position])
        }
        page.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        showHistory()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(px(4), px(4), px(4), px(4))
        background = focusBackground(0x33000000)
        addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF12201A.toInt()) },
            LinearLayout.LayoutParams(px(160), px(230)))
        addView(label("", 14f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, px(6), 0, 0) },
            LinearLayout.LayoutParams(px(160), -2))
        layoutParams = ViewGroup.LayoutParams(px(168), px(300))
    }

    /** The first tile of the results sends the same phrase to the torrent search; the rest are catalog titles. */
    private fun bind(view: View, item: Any) {
        val group = view as LinearLayout
        val poster = group.getChildAt(0) as ImageView
        val caption = group.getChildAt(1) as TextView
        if (item is TorrentQuery) {
            Glide.with(this).clear(poster)
            poster.setImageDrawable(null)
            poster.setBackgroundColor(0xFF1B2E23.toInt())
            caption.text = "Торренты: ${item.query}"
            group.setOnClickListener {
                remember(input.text.toString())
                startActivity(Intent(this, TorrentsActivity::class.java).putExtra("name", item.query).putExtra("tv", true))
            }
            return
        }
        val title = item as Title
        poster.setBackgroundColor(0xFF12201A.toInt())
        if (title.poster != null) Glide.with(this).load("https://image.tmdb.org/t/p/w342${title.poster}").into(poster)
        else { Glide.with(this).clear(poster); poster.setImageDrawable(null) }
        caption.text = title.name + if (title.year.isNotEmpty()) " (${title.year})" else ""
        group.setOnClickListener {
            remember(input.text.toString())
            startActivity(Intent(this, DetailsActivity::class.java).putExtras(title.toExtras()))
        }
    }

    private fun submit(phrase: String) {
        val text = phrase.trim()
        if (text.length < 2) return
        remember(text)
        searchTitles(text)
    }

    private fun searchTitles(phrase: String) {
        if (phrase.length < 2) {
            results = emptyList()
            grid.adapter?.notifyDataSetChanged()
            message.visibility = View.GONE
            return
        }
        message.text = "Ищем…"
        message.visibility = View.VISIBLE
        Thread {
            val found = runCatching { Tmdb.search(applicationContext, phrase) }
            runOnUiThread {
                if (input.text.toString().trim() != phrase) return@runOnUiThread
                val titles = found.getOrDefault(emptyList())
                results = listOf<Any>(TorrentQuery(phrase)) + titles
                grid.adapter?.notifyDataSetChanged()
                message.text = if (found.isFailure) "Нет связи с каталогом" else if (titles.isEmpty()) "В каталоге ничего не найдено, можно искать торренты" else ""
                message.visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        searchBar.handleResult(requestCode, resultCode, data)
    }

    // --- history -------------------------------------------------------------------------------

    private fun saved(): List<String> {
        val raw = Settings.string(this, Settings.SEARCH_HISTORY, "[]")
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun remember(phrase: String) {
        val text = phrase.trim()
        if (text.length < 2) return
        val list = (listOf(text) + saved().filter { !it.equals(text, true) }).take(12)
        Settings.setString(this, Settings.SEARCH_HISTORY, JSONArray(list).toString())
        showHistory()
    }

    private fun showHistory() {
        history.removeAllViews()
        val items = saved()
        historyBlock.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        history.addView(button(MenuIcons.DELETE, "Очистить") {
            Settings.setString(this, Settings.SEARCH_HISTORY, "[]")
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

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
