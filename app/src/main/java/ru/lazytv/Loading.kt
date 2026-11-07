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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Rotating arc: the preloader of lists, the card and the torrent screens. */
class Spinner(context: Context, private val size: Int, arcColor: Int = 0xFFA5B4FC.toInt()) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = (size / 10f).coerceAtLeast(2.5f)
        color = arcColor
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

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }
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
        label.setTextColor(0xFFCBD5E1.toInt())
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

private val failedPosters = java.util.concurrent.ConcurrentHashMap<String, Long>()
private const val FAILED_POSTER_TTL_MS = 8 * 1000L
private const val SPINNER_DELAY_MS = 600L

fun clearFailedPosters() = failedPosters.clear()

/** Cancels any pending spinner task, retry task and hides the spinner. */
fun ImageView.cancelPosterLoad(spinner: View? = null) {
    (getTag(R.id.tag_spinner_task) as? Runnable)?.let {
        removeCallbacks(it)
        setTag(R.id.tag_spinner_task, null)
    }
    (getTag(R.id.tag_retry_task) as? Runnable)?.let {
        removeCallbacks(it)
        setTag(R.id.tag_retry_task, null)
    }
    spinner?.visibility = View.GONE
}

/**
 * Loads a TMDB poster into the view; the spinner shows until the picture is there.
 * A title without a poster, or a failed load, ends with the stub picture, never with an empty plate.
 */
fun ImageView.showPoster(
    path: String?,
    spinner: View? = null,
    size: String = "w500",
    stub: Int = R.drawable.no_poster,
    retryCount: Int = 0,
    spinnerDelayMs: Long = SPINNER_DELAY_MS,
) {
    cancelPosterLoad(spinner)
    val url = if (path.isNullOrEmpty()) "" else "https://image.tmdb.org/t/p/$size$path"
    if (url.isNotEmpty() && getTag(R.id.tag_poster_url) == url && drawable != null) {
        spinner?.visibility = View.GONE
        return
    }
    val glide = com.bumptech.glide.Glide.with(context)
    val failedAt = if (path.isNullOrEmpty()) null else failedPosters[path]
    // a poster that failed moments ago is not requested on every scroll step, while a later retry after network recovery works
    if (path.isNullOrEmpty() || (failedAt != null && System.currentTimeMillis() - failedAt < FAILED_POSTER_TTL_MS)) {
        glide.clear(this)
        setTag(R.id.tag_poster_url, null)
        spinner?.visibility = View.GONE
        setImageResource(stub)
        return
    }
    if (spinner != null && retryCount == 0) {
        val task = Runnable {
            setTag(R.id.tag_spinner_task, null)
            if (drawable == null) {
                spinner.visibility = View.VISIBLE
            } else {
                spinner.visibility = View.GONE
            }
        }
        setTag(R.id.tag_spinner_task, task)
        postDelayed(task, spinnerDelayMs)
    }
    val done = object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
        override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?,
                                  target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean {
            cancelPosterLoad(spinner)
            setTag(R.id.tag_poster_url, null)
            if (retryCount < 2) {
                val nextRetry = retryCount + 1
                val retryTask = Runnable {
                    setTag(R.id.tag_retry_task, null)
                    showPoster(path, spinner, size, stub, nextRetry, spinnerDelayMs)
                }
                setTag(R.id.tag_retry_task, retryTask)
                postDelayed(retryTask, 1200L * nextRetry)
            } else {
                failedPosters[path] = System.currentTimeMillis()
            }
            return false
        }
        override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                                     dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
            cancelPosterLoad(spinner)
            setTag(R.id.tag_poster_url, url)
            failedPosters.remove(path)
            return false
        }
    }
    glide.load(url)
        .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
        .error(stub)
        .listener(done)
        .into(this)
}
