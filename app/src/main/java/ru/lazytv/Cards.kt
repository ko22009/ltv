package ru.lazytv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The height of a title card is fixed (poster + the plate under it), and the skeleton and the "show all" tile take the same:
 * a row is as tall as its tallest card, so a tile or a card that is a few pixels taller makes the rows below jump while it scrolls in and out.
 */
const val TITLE_POSTER_HEIGHT = 300f
const val TITLE_INFO_HEIGHT = 86f
const val CARD_SCALE = 0.82f

/** Stand-in for a card of a row that is still loading: it keeps the row's height, so nothing jumps when the real cards come. */
object SkeletonCard

/** Grey card of the size of [TitleCard] with a light band running over it (shimmer); it takes no focus. */
class SkeletonCardView(context: Context, scale: Float, posterHeight: Float = TITLE_POSTER_HEIGHT, infoHeight: Float = TITLE_INFO_HEIGHT) : LinearLayout(context) {
    /** 0..1: where the band is on its way from the left edge to the right one. */
    private var phase = 0f
    private val sweep = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = android.animation.ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    private val bandPaint = android.graphics.Paint()

    init {
        orientation = VERTICAL
        isFocusable = false
        layoutParams = LayoutParams((240 * scale).toInt(), LayoutParams.WRAP_CONTENT).apply {
            rightMargin = (17 * scale).toInt()
            topMargin = (12 * scale).toInt()
            bottomMargin = (12 * scale).toInt()
        }
        background = GradientDrawable().apply {
            setColor(0xFF1C2029.toInt())
            cornerRadius = 4 * scale
        }
        // the same heights as the poster and the text plate of a real card
        addView(View(context), LayoutParams(-1, (posterHeight * scale).toInt()))
        addView(View(context).apply { setBackgroundColor(0xFF1E222A.toInt()) }, LayoutParams(-1, (infoHeight * scale).toInt()))
        // the band must not stick out of the rounded corners
        clipToOutline = true
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        val band = width * 0.6f
        val left = -band + (width + band) * phase
        bandPaint.shader = android.graphics.LinearGradient(
            left, 0f, left + band, 0f,
            intArrayOf(0x00FFFFFF, 0x26FFFFFF, 0x00FFFFFF), null, android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bandPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sweep.start()
    }

    override fun onDetachedFromWindow() {
        sweep.cancel()
        super.onDetachedFromWindow()
    }
}

/**
 * Card of a title in a list: the poster, then the name, and under it the year with the rating on the right.
 * The rating is no bigger than the year — the poster stays the main thing on the screen.
 */
class TitleCard(context: Context, private val scale: Float) : LinearLayout(context) {
    private fun px(value: Float) = (value * scale).toInt()

    val poster = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        // a title without a poster shows the dark surface, not a hole that looks like a failed load
        setBackgroundColor(0xFF1C2029.toInt())
    }

    /** Shown over the poster while it loads (after 150 ms: a poster from the memory or disk cache arrives sooner and never flashes it, a network one is waited for). */
    private val posterSpinner = Spinner(context, px(36f)).apply {
        visibility = View.GONE
        isFocusable = false
        isClickable = false
    }

    /** Cancels pending poster load if the card is recycled or unbound. */
    fun cancelPoster() {
        poster.cancelPosterLoad(posterSpinner)
    }

    /** Shows the poster. */
    fun showPoster(path: String?) {
        cancelPoster()
        if (path.isNullOrEmpty()) {
            poster.showPoster(null)
            return
        }
        val url = "https://image.tmdb.org/t/p/w500$path"
        if (poster.getTag(R.id.tag_poster_url) == url && poster.drawable != null) {
            return
        }
        poster.showPoster(path, posterSpinner, spinnerDelayMs = 150L)
    }

    /** The "watched" mark in the corner of the poster: a bright tick on a dark disc, it is never dimmed. */
    private val badge = ImageView(context).apply {
        setImageDrawable(PathDrawable(MenuIcons.CHECK).apply {
            setColorFilter(0xFFA5B4FC.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
        })
        setPadding(px(4f), px(4f), px(4f), px(4f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xE6101216.toInt())
        }
        visibility = View.GONE
    }

    /** The mark of a card chosen for deletion (edit mode of the history): an indigo veil and a big tick. */
    private val pick = android.widget.FrameLayout(context).apply {
        setBackgroundColor(0x994F46E5.toInt())
        visibility = View.GONE
        // a square of its own: the icon is drawn by width, a stretched view would cut it
        addView(ImageView(context).apply {
            setImageDrawable(PathDrawable(MenuIcons.CHECK).apply {
                setColorFilter(0xFFA5B4FC.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
            })
        }, android.widget.FrameLayout.LayoutParams(px(72f), px(72f), Gravity.CENTER))
    }

    fun setPicked(on: Boolean) {
        pick.visibility = if (on) View.VISIBLE else View.GONE
    }

    private val name = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * scale)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private val year = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * scale)
        maxLines = 1
        includeFontPadding = false
    }

    private val genre = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * scale)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private var year0 = ""
    private var runtime0 = 0
    /** Id of the title the card shows now: a late runtime answer for a recycled card is dropped. */
    var shownId = -1L
        private set
    /** Pending runtime fetch for this card, cancelled when the card scrolls off screen or is rebound. */
    var runtimeTask: java.util.concurrent.Future<*>? = null

    /** Same size as the name and year, at the right edge of its line; hidden when TMDB has no rating. */
    private val rating = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * scale)
        gravity = Gravity.END
        visibility = View.GONE
        includeFontPadding = false
    }

    private val info = LinearLayout(context).apply {
        orientation = VERTICAL
        // the same quiet plate the rows use, so the title and the year read on any poster
        setBackgroundColor(0xFF1E222A.toInt())
        setPadding(px(12f), px(7f), px(12f), px(7f))
        addView(name, LayoutParams(-1, -2))
        addView(genre, LayoutParams(-1, -2).apply { topMargin = px(3f) })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(year, LayoutParams(0, -2, 1f))
            addView(rating, LayoutParams(-2, -2))
        }, LayoutParams(-1, -2).apply { topMargin = px(3f) })
    }

    init {
        orientation = VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        // the gap goes on the right: the first card then starts exactly under the row caption
        // vertical room so the enlarged focused card does not touch the row caption above it
        layoutParams = LayoutParams(px(240f), LayoutParams.WRAP_CONTENT).apply {
            rightMargin = px(17f)
            topMargin = px(12f)
            bottomMargin = px(12f)
        }
        addView(android.widget.FrameLayout(context).apply {
            addView(poster, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(posterSpinner, android.widget.FrameLayout.LayoutParams(px(36f), px(36f), Gravity.CENTER))
            addView(pick, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(badge, android.widget.FrameLayout.LayoutParams(px(36f), px(36f), Gravity.END or Gravity.TOP).apply {
                topMargin = px(10f)
                rightMargin = px(10f)
            })
        }, LayoutParams(-1, px(TITLE_POSTER_HEIGHT)))
        addView(info, LayoutParams(-1, px(TITLE_INFO_HEIGHT)))
        // a solid background gives the card an outline, so the focus shadow below has a shape to be cast from
        background = GradientDrawable().apply {
            setColor(0xFF1C2029.toInt())
            cornerRadius = px(4f).toFloat()
        }
        clipToOutline = true
    }

    private var watched = false

    /** Called when the card gets the focus; the list uses it to warm up the card screen of this title. */
    var onFocused: (() -> Unit)? = null

    private fun restAlpha() = if (watched) 0.3f else 1f

    // a watched card is just as dim under the cursor as at rest: the zoom is what shows the focus
    private fun focusAlpha() = restAlpha()

    // the poster and the text plate are dimmed, the card itself and the tick badge are not
    private fun dim(value: Float, animated: Boolean) {
        for (part in listOf<View>(poster, info)) {
            if (animated) part.animate().alpha(value).setDuration(150).start() else part.alpha = value
        }
    }

    // the focused card grows instead of wearing a frame; a watched one stays as dim as at rest,
    // and the growth alone tells where the cursor is
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) onFocused?.invoke()
        dim(if (gainFocus) focusAlpha() else restAlpha(), true)
    }

    private var shown: Title? = null

    /** Long press on OK: the watched mark of the shown title is switched, the card redraws at once. */
    fun toggleWatched(): Boolean? {
        val title = shown ?: return null
        val now = !Watched.has(context, title)
        Watched.set(context, title, now)
        applyWatched(title)
        return now
    }

    private fun applyWatched(title: Title) {
        watched = Watched.has(context, title)
        dim(if (isFocused) focusAlpha() else restAlpha(), false)
        badge.visibility = if (watched) View.VISIBLE else View.GONE
    }

    fun bind(title: Title) {
        if (shownId != title.id) {
            cancelPoster()
            poster.setImageDrawable(null)
            poster.setTag(R.id.tag_poster_url, null)
        }
        name.text = title.name
        shownId = title.id
        shown = title
        applyWatched(title)
        year0 = title.year
        runtime0 = 0
        genre.text = title.genre
        genre.visibility = if (title.genre.isEmpty()) View.GONE else View.VISIBLE
        showYear()
        val value = title.rating.replace(',', '.').toDoubleOrNull()
        if (value != null && value > 0) {
            rating.text = String.format(java.util.Locale.US, "★ %.1f", value)
            rating.setTextColor(if (value >= 6.0) Color.WHITE else 0x99FFFFFF.toInt())
            rating.visibility = View.VISIBLE
        } else rating.visibility = View.GONE
    }

    /** The list answer has no runtime: it arrives later from the card details and is added to the year line. */
    fun setRuntime(id: Long, minutes: Int) {
        if (id != shownId || minutes <= 0) return
        runtime0 = minutes
        showYear()
    }

    /** A saved record (the history, the bookmarks) keeps no genre: the card details bring it a moment later. */
    fun setGenre(id: Long, value: String) {
        if (id != shownId || value.isEmpty()) return
        genre.text = value
        genre.visibility = View.VISIBLE
    }

    private fun showYear() {
        year.text = listOf(year0, formatRuntime(runtime0)).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}

/** "2 ч 32 мин" reads faster than "152 мин"; empty when the runtime is unknown. */
fun formatRuntime(minutes: Int): String = when {
    minutes <= 0 -> ""
    minutes < 60 -> "$minutes мин"
    minutes % 60 == 0 -> "${minutes / 60} ч"
    else -> "${minutes / 60} ч ${minutes % 60} мин"
}
/**
 * Room around a grid of cards (dp of the screen): the focused card grows into it, and the grid cuts what goes past it.
 * The card grows by 15% (about 15 dp at the sides, 24 dp up and down at the card scale 0.82): the side room must hold that,
 * the top one is the room plus the card's own margin.
 */
private const val GRID_EDGE_SIDE = 20
/** The grid's top padding: the room the enlarged card grows into above the first row. */
private const val GRID_EDGE_TOP = 14
/** The grid starts this much below the header, so a scrolling list is cut with a gap instead of sticking to it. */
private const val GRID_TOP_GAP = 14

/**
 * The one grid of [TitleCard]s for every page ("весь список", the catalogue, the search): the focused card grows
 * ([bridge] gets the highlight here, so a new list cannot lose it), the cards scrolled up are cut at the grid's edge
 * under the header instead of running over it. Put it in the page with [titleGridParams], which keeps the cards where
 * the page's edge is.
 */
fun titleCardGrid(context: Context, scale: Float, bridge: androidx.leanback.widget.ItemBridgeAdapter, columns: Int): androidx.leanback.widget.VerticalGridView {
    androidx.leanback.widget.FocusHighlightHelper.setupBrowseItemFocusHighlight(bridge, androidx.leanback.widget.FocusHighlight.ZOOM_FACTOR_MEDIUM, false)
    val side = (GRID_EDGE_SIDE * scale).toInt()
    // Leanback's GridLayoutManager allows secondary-axis scroll on touch drags, which slides a vertical grid sideways under a mouse
    return object : androidx.leanback.widget.VerticalGridView(context) {
        private var downX = 0f
        private var downY = 0f
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

        override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(e.x - downX)
                    val dy = kotlin.math.abs(e.y - downY)
                    if (dx > slop && dx > dy) return false
                    val locked = android.view.MotionEvent.obtain(e)
                    locked.setLocation(downX, e.y)
                    val taken = super.onInterceptTouchEvent(locked)
                    locked.recycle()
                    return taken
                }
            }
            return super.onInterceptTouchEvent(e)
        }

        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                downX = e.x
                downY = e.y
            } else if (e.actionMasked == android.view.MotionEvent.ACTION_MOVE || e.actionMasked == android.view.MotionEvent.ACTION_UP) {
                val locked = android.view.MotionEvent.obtain(e)
                locked.setLocation(downX, e.y)
                val handled = super.onTouchEvent(locked)
                locked.recycle()
                return handled
            }
            return super.onTouchEvent(e)
        }

        override fun fling(velocityX: Int, velocityY: Int): Boolean = super.fling(0, velocityY)

        override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
            if (event.action == android.view.MotionEvent.ACTION_SCROLL &&
                event.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL) != 0f &&
                event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL) == 0f
            ) return false
            return super.onGenericMotionEvent(event)
        }
    }.apply {
        id = View.generateViewId()
        setNumColumns(columns)
        setPadding(side, (GRID_EDGE_TOP * scale).toInt(), side, (16 * scale).toInt())
        // the focused card grows into the padding: clipping it there would cut the enlarged card at the page's edge
        clipToPadding = false
        setItemSpacing((6 * scale).toInt())
        adapter = bridge
        addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    clearFailedPosters()
                }
            }
        })
    }
}

/** Layout of [titleCardGrid] in a vertical page: the side room and the top gap go outside the page's padding, the cards stay on its edge. */
fun titleGridParams(scale: Float) = LinearLayout.LayoutParams(-1, 0, 1f).apply {
    val side = (GRID_EDGE_SIDE * scale).toInt()
    setMargins(-side, (GRID_TOP_GAP * scale).toInt(), -side, 0)
}
