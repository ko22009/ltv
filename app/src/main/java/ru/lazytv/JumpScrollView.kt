package ru.lazytv

import android.content.Context
import android.widget.ScrollView

/**
 * A ScrollView for pages with big cards under the remote. The stock one moves the focus to the next card only when the card is
 * within half a screen; a row of tall cards is farther, so the first press of "down" only scrolled and a second one was needed.
 * Here a card within a whole screen takes the focus at once, and the page follows it.
 */
open class JumpScrollView(context: Context) : ScrollView(context) {
    override fun getMaxScrollAmount(): Int = height
}
