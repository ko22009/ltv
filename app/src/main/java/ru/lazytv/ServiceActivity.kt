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
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/**
 * Catalog of one video service section: the category the user picked on the service page, drawn as cards.
 * A card is a release (or a serial) of the site; picking it opens its episode list.
 */
class ServiceActivity : Activity() {
    private val scale get() = resources.displayMetrics.widthPixels / 1280f
    private fun px(value: Int) = (value * scale).toInt()

    private lateinit var grid: VerticalGridView
    private lateinit var message: TextView
    private var items: List<ServiceItem> = emptyList()

    private fun label(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = Sources.byId(intent.getStringExtra("source").orEmpty())
        val category = source?.categories?.firstOrNull { it.id == intent.getStringExtra("category") }
        if (source == null || category == null) {
            finish()
            return
        }
        val root = FrameLayout(this)
        root.background = calmBackground()
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(px(64), px(36), px(64), px(16))

        val heading = LinearLayout(this)
        heading.gravity = Gravity.CENTER_VERTICAL
        heading.addView(label(source.name, 34f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        heading.addView(label(" • ${category.title}", 20f, 0xFFAFC4B6.toInt()).apply { setPadding(px(14), 0, 0, 0) })
        page.addView(heading)
        page.addView(label("${category.title} • категория сервиса", 15f, 0xFF8FB39D.toInt()).apply { setPadding(0, px(4), 0, px(12)) })

        message = label("", 16f, 0xFFAFC4B6.toInt())
        message.visibility = View.GONE
        page.addView(message)

        grid = VerticalGridView(this)
        grid.setNumColumns(6)
        grid.setPadding(0, px(8), 0, 0)
        grid.setItemSpacing(px(14))
        grid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(card()) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = bind(holder.itemView, items[position])
        }
        val loading = LoadingView(this, scale, "Загружаем категорию…")
        val body = FrameLayout(this)
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        body.addView(loading, FrameLayout.LayoutParams(-1, -1))
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        Thread {
            val result = runCatching { source.browse(category) }
            onUi {
                loading.visibility = View.GONE
                result.onSuccess {
                    loading.visibility = View.GONE
                    items = it
                    grid.adapter?.notifyDataSetChanged()
                    if (it.isEmpty()) {
                        message.text = "В этой категории пока ничего нет"
                        message.visibility = View.VISIBLE
                    } else grid.requestFocus()
                }.onFailure {
                    message.text = "Не удалось загрузить категорию: ${it.message}"
                    message.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(px(5), px(5), px(5), px(7))
        background = focusShape(scale, 0x33000000)
        addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF12201A.toInt())
        }, LinearLayout.LayoutParams(px(168), px(240)))
        addView(label("", 14f).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, px(6), 0, 0)
        }, LinearLayout.LayoutParams(px(168), -2))
        addView(label("", 12f, 0xFF8FB39D.toInt()).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(px(168), -2))
        layoutParams = ViewGroup.LayoutParams(px(178), px(300))
    }

    private fun bind(view: View, item: ServiceItem) {
        val group = view as LinearLayout
        val poster = group.getChildAt(0) as ImageView
        val title = group.getChildAt(1) as TextView
        val note = group.getChildAt(2) as TextView
        title.text = item.title
        note.text = item.note
        if (item.poster != null) Glide.with(this).load(item.poster).into(poster)
        else { Glide.with(this).clear(poster); poster.setImageDrawable(null) }
        group.setOnClickListener {
            val v = item.variant
            startActivity(
                Intent(this, EpisodesActivity::class.java).putExtra("source", v.source).putExtra("sourceId", v.sourceId)
                    .putExtra("title", v.title).putExtra("note", v.note).putExtra("ref", v.ref)
                    .putExtra("alt", v.alt).putExtra("description", v.description)
            )
        }
    }
}
