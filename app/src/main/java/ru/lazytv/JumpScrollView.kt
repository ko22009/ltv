package ru.lazytv

import android.content.Context
import android.graphics.Rect
import android.view.FocusFinder
import android.view.View
import android.widget.ScrollView

/**
 * A ScrollView for pages with big cards under the remote. The stock one moves the focus to the next card only when the card is
 * within half a screen; a row of tall cards is farther, so the first press of "down" only scrolled and a second one was needed.
 * Here a card within a whole screen takes the focus at once, and the page follows it.
 */
open class JumpScrollView(context: Context) : ScrollView(context) {
    init {
        // the stock view keeps a fading edge (12dp) clear of the focused card: a card that is wholly on the screen, but near its
        // edge, still moved the page a little. No fading edge, no such shift.
        isVerticalFadingEdgeEnabled = false
    }

    override fun getMaxScrollAmount(): Int = height

    /**
     * "Down" on the last card: the stock view scrolls the rest of the page (the bottom padding, a caption) a little. When the
     * focused view is fully shown and there is nothing to focus below, the page stays where it is.
     */
    override fun arrowScroll(direction: Int): Boolean {
        val current: View? = findFocus()
        if (direction == View.FOCUS_DOWN && current != null && current !== this &&
            FocusFinder.getInstance().findNextFocus(this, current, direction) == null
        ) {
            val box = Rect()
            current.getDrawingRect(box)
            offsetDescendantRectToMyCoords(current, box)
            if (box.top >= scrollY && box.bottom <= scrollY + height) return true
        }
        return super.arrowScroll(direction)
    }
}
