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
class LogoView(context: Context, private val capHeightDp: Float, markColor: Int = LOGO_COLOR, private val arcColor: Int? = null) : View(context) {
    private val density = context.resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = markColor
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = arcColor ?: markColor
    }

    init {
        isFocusable = false
        isClickable = false
    }

    /** Height of the whole mark in letter heights: with the arc under the letters it is taller. */
    private val totalHeights get() = if (arcColor != null) ARC_TOTAL else 1f

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val h = (capHeightDp * density).toInt()
        setMeasuredDimension(resolveSize((h * WIDTH_PER_HEIGHT).toInt(), widthSpec), resolveSize((h * totalHeights).toInt(), heightSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val h = capHeightDp * density
        val w = h * WIDTH_PER_HEIGHT
        // centred in whatever room the parent gave
        canvas.save()
        canvas.translate((width - w) / 2f, (height - h * totalHeights) / 2f)
        paint.strokeWidth = STROKE * h
        // the corner of the L is rounder than the stroke alone gives
        paint.pathEffect = CornerPathEffect(0.16f * h)
        canvas.drawPath(letters(h), paint)
        if (arcColor != null) {
            // the arc of the app icon under the letters: a bit narrower than the word, the middle higher than the ends
            arcPaint.strokeWidth = ARC_STROKE * h
            val x0 = w * (1 - ARC_WIDTH) / 2f
            val x1 = w - x0
            val apex = h * (1 + ARC_GAP)
            val end = apex + ARC_RISE * h
            val arc = Path().apply {
                moveTo(x0, end)
                quadTo(w / 2f, apex - ARC_RISE * h, x1, end)
            }
            canvas.drawPath(arc, arcPaint)
        }
        canvas.restore()
    }

    companion object {
        const val LOGO_COLOR = 0xFFFFF4E3.toInt()
        /** The arc under the letters (as parts of a letter height): the gap to the letters, how much the middle stands above the ends, thickness, the width as a part of the word, and the height of the whole mark. */
        const val ARC_GAP = 0.32f
        const val ARC_RISE = 0.21f
        const val ARC_STROKE = 0.10f
        const val ARC_WIDTH = 0.82f
        const val ARC_TOTAL = 1f + ARC_GAP + ARC_RISE + ARC_STROKE / 2f
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
    fun create(context: Context, textSizeSp: Float = 16f, color: Int = LogoView.LOGO_COLOR, arcColor: Int? = null): View = LogoView(context, textSizeSp, color, arcColor)
}

/**
 * The glow of the app icon for a tall narrow strip (the TV menu): a wide ellipse whose source sits in the middle of the lower edge,
 * a little beyond it. The brightest at the edge, a bright core and a soft long tail, fading upwards and to the sides.
 */
class BottomGlowView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isFocusable = false
        isClickable = false
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val shader = android.graphics.RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xA000EBC3.toInt(), 0x5C00EBC3, 0x2000EBC3, 0x0000EBC3),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        // a unit circle stretched into the ellipse: wider than the strip, the centre just under the lower edge
        shader.setLocalMatrix(android.graphics.Matrix().apply {
            postScale(w * 0.75f, h * 0.78f)
            postTranslate(w / 2f, h * 1.12f)
        })
        paint.shader = shader
        canvas.drawRect(0f, 0f, w, h, paint)
    }
}
