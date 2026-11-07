package ru.lazytv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.VerticalGridView

/**
 * A whole row of the catalog on one page: the same cards, in columns, scrolled down. More pages are asked from
 * TMDB while the cursor nears the end, so the list keeps going as long as the catalog has titles.
 */
class TitlesGridActivity : Activity() {
    private val scale get() = resources.displayMetrics.widthPixels / 1280f
    private fun px(value: Int) = (value * scale).toInt()

    private val cards = ArrayObjectAdapter(CardPresenter(CARD_SCALE))
    private lateinit var bridge: ItemBridgeAdapter
    private lateinit var grid: VerticalGridView
    private lateinit var loadingMore: LoadingView
    private lateinit var message: TextView
    private var nextPage = 1
    private var busy = false
    private var finished = false
    private lateinit var more: MoreTile

    private fun label(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        more = MoreTile.from(intent)
        val root = FrameLayout(this)
        root.background = calmBackground()
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(px(64), px(36), px(64), px(16))
        // the focused card grows past the page padding at the sides: the page must not cut it there
        page.clipToPadding = false
        // the way back for a remote with no Back key or a mouse, same round button as on the title card
        val back = backButton(this, scale) { finish() }
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(back, LinearLayout.LayoutParams(px(56), px(56)).apply { rightMargin = px(20) })
        header.addView(label(more.title, 34f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        page.addView(header)
        message = label("", 16f, 0xFFAFC4B6.toInt()).apply {
            visibility = View.GONE
            setPadding(0, px(12), 0, 0)
        }
        page.addView(message)

        bridge = ItemBridgeAdapter(cards)
        bridge.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onCreate(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    (viewHolder.item as? Title)?.let { startActivity(Intent(this@TitlesGridActivity, DetailsActivity::class.java).putExtras(it.toExtras())) }
                }
            }
        })
        grid = titleCardGrid(this, scale, bridge, COLUMNS)
        back.nextFocusDownId = grid.id
        grid.setOnKeyInterceptListener { event ->
            val up = event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
            if (up && grid.selectedPosition < COLUMNS) { back.requestFocus(); true } else false
        }
        grid.setOnChildViewHolderSelectedListener(object : androidx.leanback.widget.OnChildViewHolderSelectedListener() {
            override fun onChildViewHolderSelected(parent: androidx.recyclerview.widget.RecyclerView, child: androidx.recyclerview.widget.RecyclerView.ViewHolder?, position: Int, subposition: Int) {
                // two rows before the end: the next page is asked for
                if (position >= cards.size() - COLUMNS * 2) loadNext()
            }
        })
        loadingMore = LoadingView(this, scale, "Загружаем…")
        val body = FrameLayout(this)
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        body.addView(loadingMore, FrameLayout.LayoutParams(-1, -1))
        page.addView(body, titleGridParams(scale))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        loadNext(back)
    }

    /** A title marked watched on its card comes back dimmed in this grid too. */
    override fun onResume() {
        super.onResume()
        if (::bridge.isInitialized && cards.size() > 0) cards.notifyArrayItemRangeChanged(0, cards.size())
    }

    private fun loadNext(fallbackFocus: View? = null) {
        if (busy || finished) return
        busy = true
        val page = nextPage
        val appContext = applicationContext
        if (cards.size() == 0) {
            loadingMore.caption("Загружаем…")
            loadingMore.layoutParams = FrameLayout.LayoutParams(-1, -1)
        } else {
            loadingMore.caption("")
            loadingMore.layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(16) }
        }
        loadingMore.visibility = View.VISIBLE
        Thread {
            val result = runCatching { more.loadPage(appContext, page) }
            onUi {
                busy = false
                loadingMore.visibility = View.GONE
                result.onSuccess { loaded ->
                    // titles already shown are not added twice when TMDB repeats one on the next page
                    val fresh = loaded.titles.filter { t -> (0 until cards.size()).none { (cards.get(it) as? Title)?.id == t.id } }
                    if (loaded.ended) finished = true else nextPage = page + 1
                    val first = cards.size() == 0
                    if (fresh.isNotEmpty()) cards.addAll(cards.size(), fresh)
                    if (first && cards.size() > 0) grid.requestFocus()
                    if (loaded.ended && cards.size() == 0) {
                        message.text = "Ничего не найдено"
                        message.visibility = View.VISIBLE
                        fallbackFocus?.requestFocus()
                    } else if (fresh.isEmpty() && !loaded.ended) {
                        // a page that held only watched titles adds nothing: the next one is asked for at once
                        loadNext(fallbackFocus)
                    }
                }.onFailure {
                    finished = true
                    if (cards.size() == 0) {
                        message.text = Errors.explain(it)
                        message.visibility = View.VISIBLE
                        fallbackFocus?.requestFocus()
                    }
                }
            }
        }.start()
    }

    private companion object {
        const val COLUMNS = 5
        /** The cards are a bit smaller than in a row so five of them fit the width. */
        const val CARD_SCALE = 0.82f
    }
}
