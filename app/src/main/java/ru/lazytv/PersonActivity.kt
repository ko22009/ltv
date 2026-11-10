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
import com.bumptech.glide.Glide
import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

/**
 * Actor page: the round Back and the name on top (as on the other pages), a short band with the photo, facts
 * and biography, then the films and series the person is known for in the same card grid as the catalog.
 */
class PersonActivity : Activity() {
    private val scale get() = uiScale()
    private fun px(value: Int) = (value * scale).toInt()

    private val cards by lazy { ItemList(CardPresenter(CARD_SCALE)) }
    private val columns get() = if (isMobileUi()) MOBILE_COLUMNS else COLUMNS
    /** The "read more" button of a long biography; null when the text fits. */
    private var readMore: View? = null

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
        barsLikeCalmBackground()
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        if (isMobileUi()) page.setPadding(px(16), px(16), px(16), px(8)) else page.setPadding(px(64), px(36), px(64), px(16))
        // the focused card grows past the page padding at the sides: the page must not cut it there
        page.clipToPadding = false

        // the way back for a remote with no Back key or a mouse, same round button as on the other pages
        val back = backButton(this, scale) { finish() }
        val fallbackName = intent.getStringExtra("name").orEmpty()
        val title = label(fallbackName, if (isMobileUi()) 24f else 34f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(back, LinearLayout.LayoutParams(px(56), px(56)).apply { rightMargin = px(20) })
        header.addView(title)
        page.addView(header)

        val loading = LoadingView(this, scale, "Загружаем страницу…")
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        root.addView(loading, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        cards.listener = object : ItemList.Listener() {
            override fun onCreate(holder: ItemList.Holder) {
                holder.itemView.setOnClickListener {
                    (holder.item as? Title)?.let { startActivity(Intent(this@PersonActivity, DetailsActivity::class.java).putExtras(it.toExtras())) }
                }
            }
        }

        val id = intent.getLongExtra("id", 0)
        Thread {
            val result = runCatching { Tmdb.person(applicationContext, id) }
            onUi {
                loading.visibility = View.GONE
                result.onSuccess { person ->
                    title.text = person.name
                    page.addView(info(person), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
                    // from the cards the focus search skips a button that sits off their column: Up goes to it by hand
                    val reach: View = readMore ?: back
                    if (person.credits.isEmpty()) {
                        readMore?.let { back.nextFocusDownId = it.id; it.nextFocusUpId = back.id }
                        reach.requestFocus()
                        return@onSuccess
                    }
                    // the heading looks like «В ролях» on the title card
                    page.addView(label("Фильмография", 22f, bold = true).apply { setPadding(0, px(20), 0, px(4)) })
                    if (isMobileUi()) {
                        // the same cards as the films page of the phone (rating on the poster), not the TV cards
                        val list = androidx.recyclerview.widget.RecyclerView(this).apply {
                            layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@PersonActivity, columns)
                            clipToPadding = false
                            setPadding(0, px(6), 0, px(16))
                            adapter = ru.lazytv.mobile.MobileTitleAdapter { title ->
                                startActivity(Intent(this@PersonActivity, DetailsActivity::class.java).putExtras(title.toExtras()))
                            }.also { it.setItems(person.credits.map { c -> c.title }) }
                        }
                        page.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
                        return@onSuccess
                    }
                    val grid = titleCardGrid(this, scale, cards, columns)
                    page.addView(grid, titleGridParams(scale))
                    cards.addAll(0, person.credits.map { it.title })
                    back.nextFocusDownId = (readMore ?: grid).id
                    grid.keyIntercept = { e ->
                        val up = e.action == android.view.KeyEvent.ACTION_DOWN && e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
                        // only from the first row of the grid: the upper rows are reached by the grid itself
                        if (up && grid.selectedPosition < columns) { reach.requestFocus(); true } else false
                    }
                    readMore?.let { button ->
                        button.nextFocusUpId = back.id
                        button.nextFocusDownId = grid.id
                    }
                    grid.selectedPosition = 0
                    if (!isMobileUi()) grid.post {
                        grid.requestFocus()
                        grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    }
                }.onFailure { err ->
                    page.addView(label(Errors.explain(err), 16f, 0xFFCBD5E1.toInt()).apply {
                        setPadding(0, px(12), 0, 0)
                    })
                    val action = Errors.actionOf(err)
                    if (action != null) {
                        val fix = chipButton(this, scale, action.caption).apply {
                            this.id = View.generateViewId()
                            nextFocusUpId = back.id
                            setOnClickListener { action.run(this@PersonActivity) }
                        }
                        back.nextFocusDownId = fix.id
                        page.addView(fix, LinearLayout.LayoutParams(-2, px(CHIP_HEIGHT)).apply { topMargin = px(12) })
                        fix.requestFocus()
                    } else back.requestFocus()
                }
            }
        }.start()
    }

    /** A title marked watched on its card comes back dimmed in this grid too. */
    override fun onResume() {
        super.onResume()
        if (cards.size() > 0) cards.notifyArrayItemRangeChanged(0, cards.size())
    }

    /** The band under the title: a small photo, the facts and the biography. */
    private fun info(person: Tmdb.PersonPage): View {
        val band = LinearLayout(this)
        band.orientation = LinearLayout.HORIZONTAL
        // the band is a card of the same look as the people cards of the title card (not focusable: only the resting outline shows)
        band.background = personCardBackground(scale)
        band.setPadding(px(14), px(14), px(14), px(14))
        // initials on a calm teal plate sit under the photo: they show while it loads and for a person who has none
        val initials = person.name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
        val photo = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF283048.toInt(), 0xFF181D2A.toInt()))
                .apply { cornerRadius = px(TITLE_CARD_RADIUS.toInt()).toFloat() }
            clipToOutline = true
            addView(label(initials, 40f, 0xFFCBD5E1.toInt()).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
            val image = ImageView(this@PersonActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            addView(image, FrameLayout.LayoutParams(-1, -1))
            if (!person.photo.isNullOrEmpty()) {
                val photoSpinner = Spinner(this@PersonActivity, px(SPINNER_CARD))
                addView(photoSpinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                Glide.with(image).load("https://image.tmdb.org/t/p/w342${person.photo}")
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                            photoSpinner.visibility = View.GONE
                            return false
                        }
                        override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                            photoSpinner.visibility = View.GONE
                            return false
                        }
                    }).into(image)
            }
        }
        band.addView(photo, LinearLayout.LayoutParams(px(110), px(150)).apply { rightMargin = px(24) })
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val facts = listOf(person.role, person.birthday, person.place).filter { it.isNotEmpty() }.joinToString(" • ")
        // no font padding: the first line starts at the top edge of the photo, and both texts share the left edge
        if (facts.isNotEmpty()) texts.addView(label(facts, 16f, 0xFF94A3B8.toInt()).apply { includeFontPadding = false })
        if (person.biography.isNotEmpty()) texts.addView(label(person.biography, 15f, 0xFFCBD5E1.toInt()).apply {
            includeFontPadding = false
            setLineSpacing(0f, 1.2f)
            setPadding(0, px(if (facts.isEmpty()) 0 else 10), 0, 0)
            maxLines = if (person.biography.length > 400) 4 else 5
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        // a long biography is cut by the band: the button in the middle opens the whole text
        if (person.biography.length > 400) texts.addView(chipButton(this, scale, "Читать далее").apply {
            id = View.generateViewId()
            readMore = this
            setOnClickListener {
                showText(this@PersonActivity, person.name, person.biography)
            }
        }, LinearLayout.LayoutParams(-2, px(CHIP_HEIGHT)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = px(8) })
        band.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return band
    }

    private companion object {
        const val COLUMNS = 5
        const val MOBILE_COLUMNS = 3
        /** The cards are a bit smaller than in a row so five of them fit the width. */
        const val CARD_SCALE = 0.82f
    }
}
