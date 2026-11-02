package ru.lazytv

import android.graphics.drawable.GradientDrawable

/** Calm dark background of the list screens: a barely visible vertical shade, no pattern. */
fun calmBackground() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF16261D.toInt(), 0xFF0F1913.toInt()))
