package ru.lazytv

import android.app.Activity
import android.graphics.drawable.GradientDrawable

/** Calm dark background of the list screens: a barely visible vertical shade, no pattern. */
fun calmBackground() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BACKDROP_PLATE, PAGE_BOTTOM))

/** The bars of the phone take the two ends of [calmBackground]: the status bar the top one, the navigation bar the bottom one. */
fun Activity.barsLikeCalmBackground() {
    window.statusBarColor = BACKDROP_PLATE
    window.navigationBarColor = PAGE_BOTTOM
}
