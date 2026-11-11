package ru.lazytv

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable

/** Calm dark background of the list screens: one flat colour. A gradient between two near-black greys has only a few
 *  8-bit steps and shows as visible bands on phone screens. */
fun calmBackground(): Drawable = ColorDrawable(PAGE_BG)

/** The bars of the phone take the page colour, so they merge with [calmBackground]. */
fun Activity.barsLikeCalmBackground() {
    window.statusBarColor = PAGE_BG
    window.navigationBarColor = PAGE_BG
}
