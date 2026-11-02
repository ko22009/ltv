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
 * Card of a title in a list: the poster, then the name, and under it the year with the rating on the right.
 * The rating is no bigger than the year — the poster stays the main thing on the screen.
 */
class TitleCard(context: Context, private val scale: Float) : LinearLayout(context) {
    private fun px(value: Float) = (value * scale).toInt()

    val poster = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(0xFF12201A.toInt())
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
        // a thin frame in the focus colour around the card, visible even when the poster fills the card
        setPadding(px(3f), px(3f), px(3f), px(3f))
        addView(poster, LayoutParams(-1, px(300f)))
        addView(info, LayoutParams(-1, -2))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setColor(0xFF2F7D57.toInt())
                cornerRadius = px(4f).toFloat()
            })
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
    }

    fun bind(title: Title) {
        name.text = title.name
        shownId = title.id
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