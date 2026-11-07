package ru.lazytv

import android.graphics.drawable.GradientDrawable

/** Calm dark background of the list screens: a barely visible vertical shade, no pattern. */
fun calmBackground() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF161920.toInt(), 0xFF0E1014.toInt()))
