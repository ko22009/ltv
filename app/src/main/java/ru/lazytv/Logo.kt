package ru.lazytv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Modern LTV brand logo component.
 * "L" in indigo accent (#818CF8), "TV" in crisp white (#FFFFFF).
 * Non-focusable decorative brand mark.
 */
object Logo {
    fun create(
        context: Context,
        textSizeSp: Float = 16f,
        badge: Boolean = false,
        textColorL: Int = 0xFF818CF8.toInt(),
        textColorTv: Int = Color.WHITE
    ): View {
        val tv = TextView(context).apply {
            val textStr = "LTV"
            val span = SpannableString(textStr)
            span.setSpan(ForegroundColorSpan(textColorL), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(ForegroundColorSpan(textColorTv), 1, 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = span
            textSize = textSizeSp
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            isFocusable = false
            isClickable = false
        }

        if (!badge) return tv

        val density = context.resources.displayMetrics.density
        val padH = (8 * density).toInt()
        val padV = (4 * density).toInt()
        return FrameLayout(context).apply {
            isFocusable = false
            isClickable = false
            background = GradientDrawable().apply {
                setColor(0xFF1E222A.toInt())
                cornerRadius = 6 * density
                setStroke((1 * density).toInt(), 0x336366F1)
            }
            setPadding(padH, padV, padH, padV)
            addView(tv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
    }
}
