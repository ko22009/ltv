package ru.lazytv

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/**
 * The card of a person (an actor, a director) as on the title card: a thin outline of the theme on the dark plate;
 * under the cursor it fills with the indigo of the app and the outline turns light. [scale] is the screen scale (`uiScale()`).
 * The whole block (heading "В ролях", the row of cards) is described in docs/design-system.md.
 */
fun personCardBackground(scale: Float): StateListDrawable {
    fun dp(value: Int) = (value * scale).toInt()
    val line = maxOf(1, dp(1))
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(FOCUS_FILL); cornerRadius = dp(14).toFloat(); setStroke(line * 2, 0xFFA5B4FC.toInt())
        })
        addState(intArrayOf(), GradientDrawable().apply {
            setColor(0xFF161920.toInt()); cornerRadius = dp(14).toFloat(); setStroke(line, 0xFF2E3445.toInt())
        })
    }
}
