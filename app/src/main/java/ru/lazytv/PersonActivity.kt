package ru.lazytv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ItemBridgeAdapter
import com.bumptech.glide.Glide

/**
 * Actor page: the round Back and the name on top (as on the other pages), a short band with the photo, facts
 * and biography, then the films and series the person is known for in the same card grid as the catalog.
 */
class PersonActivity : Activity() {
    private val scale get() = resources.displayMetrics.widthPixels / 1280f
    private fun px(value: Int) = (value * scale).toInt()

    private val cards = ArrayObjectAdapter(CardPresenter(CARD_SCALE))
    private lateinit var bridge: ItemBridgeAdapter

    private fun label(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.background = calmBackground()
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(px(64), px(36), px(64), px(16))
        // the focused card grows past the page padding at the sides: the page must not cut it there
        page.clipToPadding = false

        // the way back for a remote with no Back key or a mouse, same round button as on the other pages
        val back = ImageView(this).apply {
            setImageDrawable(PathDrawable(MenuIcons.BACK).apply { setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN) })
            setPadding(px(12), px(12), px(12), px(12))
            background = focusShape(scale, 0x66000000, circle = true)
            isFocusable = true
            isClickable = true
            contentDescription = "Назад"
            setOnClickListener { finish() }
        }
        val fallbackName = intent.getStringExtra("name").orEmpty()
        val title = label(fallbackName, 34f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(back, LinearLayout.LayoutParams(px(56), px(56)).apply { rightMargin = px(20) })
        header.addView(title)
        page.addView(header)

        val loading = LoadingView(this, scale, "Загружаем страницу актёра…")
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        root.addView(loading, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        bridge = ItemBridgeAdapter(cards)
        bridge.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onCreate(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    (viewHolder.item as? Title)?.let { startActivity(Intent(this@PersonActivity, DetailsActivity::class.java).putExtras(it.toExtras())) }
                }
            }
        })

        val id = intent.getLongExtra("id", 0)
        Thread {
            val result = runCatching { Tmdb.person(applicationContext, id) }
            onUi {
                loading.visibility = View.GONE
                result.onSuccess { person ->
                    title.text = person.name
                    page.addView(info(person))
                    if (person.credits.isEmpty()) return@onSuccess
                    page.addView(label("ФИЛЬМОГРАФИЯ", 18f).apply { setPadding(0, px(14), 0, 0) })
                    val grid = titleCardGrid(this, scale, bridge, COLUMNS)
                    page.addView(grid, titleGridParams(scale))
                    cards.addAll(0, person.credits.map { it.title })
                    grid.requestFocus()
                }.onFailure {
                    page.addView(label("Не удалось загрузить страницу: ${it.message}", 16f, 0xFFAFC4B6.toInt()).apply {
                        setPadding(0, px(12), 0, 0)
                    })
                }
            }
        }.start()
    }

    /** A title marked watched on its card comes back dimmed in this grid too. */
    override fun onResume() {
        super.onResume()
        if (::bridge.isInitialized && cards.size() > 0) cards.notifyArrayItemRangeChanged(0, cards.size())
    }

    /** The band under the title: a small photo, the facts and the biography. */
    private fun info(person: Tmdb.PersonPage): View {
        val band = LinearLayout(this)
        band.orientation = LinearLayout.HORIZONTAL
        band.setPadding(0, px(14), 0, 0)
        // initials on a calm green plate sit under the photo: they show while it loads and for a person who has none
        val initials = person.name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
        val photo = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2F5A44.toInt(), 0xFF1B3327.toInt()))
            addView(label(initials, 40f, 0xFFB9F6CA.toInt()).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
            val image = ImageView(this@PersonActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            addView(image, FrameLayout.LayoutParams(-1, -1))
            if (!person.photo.isNullOrEmpty()) Glide.with(this@PersonActivity).load("https://image.tmdb.org/t/p/w342${person.photo}").into(image)
        }
        band.addView(photo, LinearLayout.LayoutParams(px(110), px(150)).apply { rightMargin = px(24) })
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val facts = listOf(person.birthday, person.place).filter { it.isNotEmpty() }.joinToString(" • ")
        if (facts.isNotEmpty()) texts.addView(label(facts, 16f, 0xFF8FB39D.toInt()))
        if (person.biography.isNotEmpty()) texts.addView(label(person.biography, 15f, 0xFFDCE8E0.toInt()).apply {
            setLineSpacing(0f, 1.2f)
            setPadding(0, px(if (facts.isEmpty()) 0 else 8), 0, 0)
            maxLines = if (person.biography.length > 400) 4 else 5
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        // a long biography is cut by the band: the button in the middle opens the whole text
        if (person.biography.length > 400) texts.addView(chipButton(this, scale, "Читать далее").apply {
            setOnClickListener {
                android.app.AlertDialog.Builder(this@PersonActivity)
                    .setTitle(person.name).setMessage(person.biography)
                    .setPositiveButton("Закрыть", null).show()
            }
        }, LinearLayout.LayoutParams(-2, px(CHIP_HEIGHT)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = px(8) })
        band.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return band
    }

    private companion object {
        const val COLUMNS = 5
        /** The cards are a bit smaller than in a row so five of them fit the width. */
        const val CARD_SCALE = 0.82f
    }
}
