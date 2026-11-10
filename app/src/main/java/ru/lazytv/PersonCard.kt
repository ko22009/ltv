package ru.lazytv

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/**
 * The common card plate of the pages (people on the title card, rows of episodes and torrents): a thin outline of the theme
 * on the dark plate; in the [state] (focused or selected) it fills with the indigo of the app and the outline turns light.
 * [scale] is the screen scale (`uiScale()`). Described in docs/design-system.md.
 */
fun outlinedCardBackground(
    scale: Float,
    state: Int = android.R.attr.state_focused,
    radius: Int = 14,
    fill: Int = 0xFF161920.toInt(),
    line: Int = 0xFF2E3445.toInt(),
): StateListDrawable {
    fun dp(value: Int) = (value * scale).toInt()
    val width = maxOf(1, dp(1))
    return StateListDrawable().apply {
        addState(intArrayOf(state), GradientDrawable().apply {
            setColor(FOCUS_FILL); cornerRadius = dp(radius).toFloat(); setStroke(width * 2, 0xFFA5B4FC.toInt())
        })
        addState(intArrayOf(), GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(radius).toFloat(); setStroke(width, line)
        })
    }
}

/** The card of a person (an actor, a director) as on the title card; the whole block is in docs/design-system.md. */
fun personCardBackground(scale: Float) = outlinedCardBackground(scale)
