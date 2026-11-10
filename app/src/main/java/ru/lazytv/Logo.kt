package ru.lazytv

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * The LTV brand mark: three fat letters with round ends, one warm cream colour. Drawn as strokes (not a font), so it looks
 * the same on every device. The same shapes are used for the launcher icon (docs/design-system.md, "Логотип").
 * Non-focusable decorative mark.
 */
class LogoView(context: Context, private val capHeightDp: Float) : View(context) {
    private val density = context.resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = LOGO_COLOR
    }

    init {
        isFocusable = false
        isClickable = false
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val h = (capHeightDp * density).toInt()
        setMeasuredDimension(resolveSize((h * WIDTH_PER_HEIGHT).toInt(), widthSpec), resolveSize(h, heightSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val h = capHeightDp * density
        val w = h * WIDTH_PER_HEIGHT
        // centred in whatever room the parent gave
        canvas.save()
        canvas.translate((width - w) / 2f, (height - h) / 2f)
        paint.strokeWidth = STROKE * h
        // the corner of the L is rounder than the stroke alone gives
        paint.pathEffect = CornerPathEffect(0.16f * h)
        canvas.drawPath(letters(h), paint)
        canvas.restore()
    }

    companion object {
        const val LOGO_COLOR = 0xFFFFF4E3.toInt()
        /** Width of "LTV" as a part of the height of a letter. */
        const val WIDTH_PER_HEIGHT = 2.74f
        /** Thickness of a stroke as a part of the height of a letter. */
        const val STROKE = 0.26f

        /** The strokes of L, T and V on a letter of height [h]; the same numbers draw the launcher icon. */
        fun letters(h: Float): Path {
            val half = STROKE / 2f
            fun x(v: Float) = v * h
            return Path().apply {
                // L
                moveTo(x(half), x(half)); lineTo(x(half), x(1f - half)); lineTo(x(0.81f - half), x(1f - half))
                // T: the bar, then the stem
                moveTo(x(0.70f + half), x(half)); lineTo(x(0.70f + 0.91f - half), x(half))
                moveTo(x(0.70f + 0.455f), x(half)); lineTo(x(0.70f + 0.455f), x(1f - half))
                // V
                moveTo(x(1.67f + half), x(half)); lineTo(x(1.67f + 0.535f), x(1f - half)); lineTo(x(1.67f + 1.07f - half), x(half))
            }
        }
    }
}

object Logo {
    /** The mark; [textSizeSp] is kept from the old text logo and gives the height of the letters (dp). */
    fun create(context: Context, textSizeSp: Float = 16f): View = LogoView(context, textSizeSp)
}
