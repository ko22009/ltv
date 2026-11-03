package ru.lazytv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/** Rotating light-green arc: the preloader of lists, the card and the torrent screens. */
class Spinner(context: Context, private val size: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = size / 10f
        color = 0xFF8FB39D.toInt()
    }
    private val track = Paint(paint).apply { color = 0x33FFFFFF }
    private var angle = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(size, size)

    override fun onDraw(canvas: Canvas) {
        val pad = paint.strokeWidth
        val box = RectF(pad, pad, size - pad, size - pad)
        canvas.drawArc(box, 0f, 360f, false, track)
        canvas.drawArc(box, angle, 100f, false, paint)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
}

/** Centered spinner with a caption; `scale` is the 1280-wide design scale of the host screen. */
class LoadingView(context: Context, scale: Float, caption: String = "Загрузка…") : LinearLayout(context) {
    private val label = TextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(Spinner(context, (72 * scale).toInt()))
        label.text = caption
        label.gravity = Gravity.CENTER
        label.setTextColor(0xFFAFC4B6.toInt())
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * scale)
        label.setPadding(0, (16 * scale).toInt(), 0, 0)
        addView(label)
        // a spinner alone: no empty caption line that would push it off the centre
        if (caption.isEmpty()) label.visibility = GONE
    }

    fun caption(value: String) { label.text = value }
}

/** Hands a background result to the screen, but only while it is alive: a closed screen gets nothing (Glide throws on a destroyed one). */
fun android.app.Activity.onUi(block: () -> Unit) = runOnUiThread { if (!isFinishing && !isDestroyed) block() }
