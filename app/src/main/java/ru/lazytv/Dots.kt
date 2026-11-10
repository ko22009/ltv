package ru.lazytv

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * The dot pattern of the LazyMedia header: staggered rows of soft dots, fading out to the right.
 * Drawn on a 1280-wide grid and scaled to the view, so it looks the same on any TV.
 */
class DotsDrawable(private val color: Int = 0x2E94A3B8, private val fade: Boolean = true) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    override fun draw(canvas: Canvas) {
        val grid = bounds.width() / 49f
        if (grid < 1f) return
        val step = grid
        val radius = grid * 0.11f
        var row = 0
        var y = bounds.top + step / 2
        while (y < bounds.bottom + step) {
            var x = bounds.left + (if (row % 2 == 0) 0f else step / 2) - step / 2
            while (x < bounds.right + step) {
                val alpha = if (!fade) 255 else (255 * (1f - (x - bounds.left) / bounds.width().toFloat())).toInt().coerceIn(0, 255)
                paint.alpha = (alpha * 0.55f).toInt()
                canvas.drawCircle(x, y, radius, paint)
                x += step
            }
            row++
            y += step * 0.86f
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
