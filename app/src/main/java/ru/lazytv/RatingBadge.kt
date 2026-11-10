package ru.lazytv

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView

/**
 * The rating plate in the lower right corner of a poster: a dark rounded plate with "★ 8.6".
 * One look for the TV lists, the phone lists and the poster of the title card (docs/design-system.md).
 */
object RatingBadge {
    /** "★ 8.6", or null when the title has no rating (empty, zero or not a number). */
    fun label(rating: String): String? {
        val value = rating.replace(',', '.').toDoubleOrNull() ?: return null
        return if (value > 0) String.format(java.util.Locale.US, "★ %.1f", value) else null
    }

    /** Gives a TextView the look of the plate; [sizePx] is the text size and [padPx] the horizontal padding, already scaled. */
    fun style(view: TextView, sizePx: Float, padPx: Int, padVerticalPx: Int) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        view.setTextColor(Color.WHITE)
        view.typeface = Typeface.DEFAULT_BOLD
        view.setBackgroundResource(R.drawable.bg_rating_pill)
        view.setPadding(padPx, padVerticalPx, padPx, padVerticalPx)
        view.includeFontPadding = false
    }
}
