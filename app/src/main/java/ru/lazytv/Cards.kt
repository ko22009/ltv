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

/** Stand-in for a card of a row that is still loading: it keeps the row's height, so nothing jumps when the real cards come. */
object SkeletonCard

/** Grey card of the size of [TitleCard] that slowly pulses; it takes no focus. */
class SkeletonCardView(context: Context, scale: Float, posterHeight: Float = 300f, infoHeight: Float = 82f) : LinearLayout(context) {
    private val pulse = android.animation.ObjectAnimator.ofFloat(this, ALPHA, 0.45f, 1f).apply {
        duration = 800
        repeatCount = android.animation.ValueAnimator.INFINITE
        repeatMode = android.animation.ValueAnimator.REVERSE
    }

    init {
        orientation = VERTICAL
        isFocusable = false
        layoutParams = LayoutParams((240 * scale).toInt(), LayoutParams.WRAP_CONTENT).apply {
            rightMargin = (17 * scale).toInt()
            topMargin = (12 * scale).toInt()
            bottomMargin = (12 * scale).toInt()
        }
        background = GradientDrawable().apply {
            setColor(0xFF1A2A22.toInt())
            cornerRadius = 4 * scale
        }
        // the same heights as the poster and the text plate of a real card
        addView(View(context), LayoutParams(-1, (posterHeight * scale).toInt()))
        addView(View(context).apply { setBackgroundColor(0xFF24352C.toInt()) }, LayoutParams(-1, (infoHeight * scale).toInt()))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulse.start()
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
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
        setBackgroundColor(0xFF12201A.toInt())
    }

    /** The "watched" mark in the corner of the poster: a bright tick on a dark disc, it is never dimmed. */
    private val badge = ImageView(context).apply {
        setImageDrawable(PathDrawable(MenuIcons.CHECK).apply {
            setColorFilter(0xFFB9F6CA.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
        })
        setPadding(px(4f), px(4f), px(4f), px(4f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xE60C140F.toInt())
        }
        visibility = View.GONE
    }

    /** The mark of a card chosen for deletion (edit mode of the history): a green veil and a big tick. */
    private val pick = android.widget.FrameLayout(context).apply {
        setBackgroundColor(0x992F7D57.toInt())
        visibility = View.GONE
        // a square of its own: the icon is drawn by width, a stretched view would cut it
        addView(ImageView(context).apply {
            setImageDrawable(PathDrawable(MenuIcons.CHECK).apply {
                setColorFilter(0xFFB9F6CA.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
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
    }

    private val year = TextView(context).apply {
        setTextColor(0xFF9FB3A9.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * scale)
        maxLines = 1
    }

    private val genre = TextView(context).apply {
        setTextColor(0xFF9FB3A9.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * scale)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private var year0 = ""
    private var runtime0 = 0
    /** Id of the title the card shows now: a late runtime answer for a recycled card is dropped. */
    var shownId = -1L
        private set

    /** Same size as the year, at the right edge of its line; hidden when TMDB has no rating. */
    private val rating = TextView(context).apply {
        setTextColor(0xFFB9F6CA.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * scale)
        gravity = Gravity.END
        visibility = View.GONE
    }

    private val info = LinearLayout(context).apply {
        orientation = VERTICAL
        // the same quiet plate the rows use, so the title and the year read on any poster
        setBackgroundColor(0xFF39423D.toInt())
        setPadding(px(12f), px(8f), px(12f), px(10f))
        addView(name, LayoutParams(-1, -2))
        addView(genre, LayoutParams(-1, -2).apply { topMargin = px(4f) })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(year, LayoutParams(0, -2, 1f))
            addView(rating, LayoutParams(-2, -2))
        }, LayoutParams(-1, -2).apply { topMargin = px(4f) })
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
            addView(pick, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(badge, android.widget.FrameLayout.LayoutParams(px(36f), px(36f), Gravity.END or Gravity.TOP).apply {
                topMargin = px(10f)
                rightMargin = px(10f)
            })
        }, LayoutParams(-1, px(300f)))
        addView(info, LayoutParams(-1, -2))
        // a solid background gives the card an outline, so the focus shadow below has a shape to be cast from
        background = GradientDrawable().apply {
            setColor(0xFF12201A.toInt())
            cornerRadius = px(4f).toFloat()
        }
        clipToOutline = true
    }

    private var watched = false

    /** Called when the card gets the focus; the list uses it to warm up the card screen of this title. */
    var onFocused: (() -> Unit)? = null

    private fun restAlpha() = if (watched) 0.3f else 1f

    // a watched card is just as dim under the cursor as at rest: the lift and the shadow show the focus
    private fun focusAlpha() = restAlpha()

    // the poster and the text plate are dimmed, the card itself and the tick badge are not
    private fun dim(value: Float, animated: Boolean) {
        for (part in listOf<View>(poster, info)) {
            if (animated) part.animate().alpha(value).setDuration(150).start() else part.alpha = value
        }
    }

    // the focused card lifts off the row and casts a shadow instead of wearing a frame; a watched one stays as dim as at rest,
    // and only the lift tells where the cursor is
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) onFocused?.invoke()
        animate().translationZ(if (gainFocus) px(24f).toFloat() else 0f).setDuration(150).start()
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
        name.text = title.name
        shownId = title.id
        shown = title
        applyWatched(title)
        year0 = title.year
        runtime0 = 0
        genre.text = title.genre
        genre.visibility = if (title.genre.isEmpty()) View.GONE else View.VISIBLE
        showYear()
        val value = title.rating.toDoubleOrNull()
        if (value != null && value > 0) {
            rating.text = "★ %.1f".format(value)
            rating.setTextColor(when {
                value >= 7.5 -> 0xFFB9F6CA.toInt()
                value >= 6.0 -> 0xFFFFE082.toInt()
                else -> 0xFFFFAB91.toInt()
            })
            rating.visibility = View.VISIBLE
        } else rating.visibility = View.GONE
    }

    /** The list answer has no runtime: it arrives later from the card details and is added to the year line. */
    fun setRuntime(id: Long, minutes: Int) {
        if (id != shownId || minutes <= 0) return
        runtime0 = minutes
        showYear()
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