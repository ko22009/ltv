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
    }

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
        layoutParams = LayoutParams(px(240f), LayoutParams.WRAP_CONTENT).apply { leftMargin = px(17f) }
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
        year.text = title.year
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
}