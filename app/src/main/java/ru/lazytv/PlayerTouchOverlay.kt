package ru.lazytv

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Touch gesture overlay for phones and tablets:
 * - Single tap: toggle control overlay
 * - Double tap: rewind 10s (left), fast-forward 10s (right), play/pause (center)
 * - Vertical drag: brightness (left side) and media volume (right side)
 */
class PlayerTouchOverlay(
    context: Context,
    private val activity: Activity,
    private val onSeek: (deltaMs: Long) -> Unit,
    private val onTogglePlay: () -> Boolean,
    private val onToggleControls: () -> Unit,
    private val onUserAction: () -> Unit,
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private val gestureIndicator: LinearLayout
    private val gestureIcon: ImageView
    private val gestureText: TextView
    private val gestureProgress: ProgressBar

    private val seekBubbleLeft: TextView
    private val seekBubbleRight: TextView

    private val hideIndicatorRunnable: Runnable
    private val pauseSign: ImageView

    private var isDragging = false
    private var dragMode = DRAG_NONE
    private var initialValue = 0f

    init {
        // Floating volume/brightness indicator (centered pill)
        gestureIndicator = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(0xCC161920.toInt())
                cornerRadius = dp(16).toFloat()
            }
            visibility = View.GONE
        }

        gestureIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            }
        }
        gestureIndicator.addView(gestureIcon)

        gestureText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        gestureIndicator.addView(gestureText)

        gestureProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x66FFFFFF)
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(6))
        }
        gestureIndicator.addView(gestureProgress)

        addView(gestureIndicator, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // Double-tap seek bubbles (-10s / +10s)
        seekBubbleLeft = createSeekBubble("-10 сек")
        seekBubbleRight = createSeekBubble("+10 сек")
        addView(seekBubbleLeft, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = dp(60)
        })
        addView(seekBubbleRight, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END).apply {
            marginEnd = dp(60)
        })

        // the big play / pause sign that flashes in the middle after a double tap
        pauseSign = ImageView(context).apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC161920.toInt()) }
            visibility = View.GONE
        }
        addView(pauseSign, LayoutParams(dp(84), dp(84), Gravity.CENTER))

        hideIndicatorRunnable = Runnable {
            gestureIndicator.visibility = View.GONE
            seekBubbleLeft.visibility = View.GONE
            seekBubbleRight.visibility = View.GONE
        }
    }

    private fun createSeekBubble(caption: String) = TextView(context).apply {
        text = caption
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(24), dp(14), dp(24), dp(14))
        background = GradientDrawable().apply {
            // a neutral dark plate, the same as the volume and brightness indicator
            setColor(0xCC161920.toInt())
            cornerRadius = dp(16).toFloat()
        }
        visibility = View.GONE
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onToggleControls()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val w = width.toFloat()
            when {
                e.x < w * 0.35f -> {
                    onSeek(-10_000L)
                    showSeekFeedback(isForward = false)
                }
                e.x > w * 0.65f -> {
                    onSeek(10_000L)
                    showSeekFeedback(isForward = true)
                }
                else -> showPauseSign(playing = onTogglePlay())
            }
            onUserAction()
            return true
        }

        override fun onDown(e: MotionEvent): Boolean {
            isDragging = false
            dragMode = DRAG_NONE
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e1 == null) return false
            val w = width.toFloat()
            val h = height.toFloat()
            if (h <= 0) return false

            val totalDx = Math.abs(e2.x - e1.x)
            val totalDy = Math.abs(e2.y - e1.y)

            if (!isDragging) {
                if (totalDy > totalDx && totalDy > dp(16)) {
                    isDragging = true
                    dragMode = if (e1.x < w * 0.5f) DRAG_BRIGHTNESS else DRAG_VOLUME
                    initialValue = if (dragMode == DRAG_BRIGHTNESS) getBrightness() else getVolumeFraction()
                } else {
                    return false
                }
            }

            if (isDragging) {
                val deltaY = (e1.y - e2.y) / (h * 0.75f)
                val target = (initialValue + deltaY).coerceIn(0f, 1f)
                if (dragMode == DRAG_BRIGHTNESS) {
                    setBrightness(target)
                } else if (dragMode == DRAG_VOLUME) {
                    setVolumeFraction(target)
                }
                onUserAction()
                return true
            }
            return false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            if (isDragging) {
                isDragging = false
                dragMode = DRAG_NONE
                postDelayedHide(1000)
            }
        }
        return handled || super.onTouchEvent(event)
    }

    /** The sign of the new state (pause bars when the video was just stopped, the triangle when it goes on): grows in, then fades out. */
    private fun showPauseSign(playing: Boolean) {
        pauseSign.setImageDrawable(PathDrawable(if (playing) MenuIcons.PLAY else MenuIcons.PAUSE).apply {
            setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
        })
        pauseSign.animate().cancel()
        pauseSign.visibility = View.VISIBLE
        pauseSign.alpha = 0f
        pauseSign.scaleX = 0.7f
        pauseSign.scaleY = 0.7f
        pauseSign.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).withEndAction {
            pauseSign.animate().alpha(0f).setStartDelay(350).setDuration(250).withEndAction {
                pauseSign.visibility = View.GONE
            }.start()
        }.start()
    }

    private fun showSeekFeedback(isForward: Boolean) {
        seekBubbleLeft.visibility = if (!isForward) View.VISIBLE else View.GONE
        seekBubbleRight.visibility = if (isForward) View.VISIBLE else View.GONE
        postDelayedHide(700)
    }

    private fun getBrightness(): Float {
        val lp = activity.window.attributes
        if (lp.screenBrightness >= 0f) return lp.screenBrightness
        return try {
            android.provider.Settings.System.getInt(
                activity.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                128
            ) / 255f
        } catch (_: Exception) {
            0.5f
        }
    }

    private fun setBrightness(value: Float) {
        val lp = activity.window.attributes
        lp.screenBrightness = value.coerceIn(0.01f, 1.0f)
        activity.window.attributes = lp

        val pct = (lp.screenBrightness * 100).toInt()
        gestureIcon.setImageDrawable(PathDrawable(MenuIcons.brightnessIcon()))
        gestureText.text = "Яркость: $pct%"
        gestureProgress.progress = pct
        gestureIndicator.visibility = View.VISIBLE
        handler.removeCallbacks(hideIndicatorRunnable)
    }

    private fun getVolumeFraction(): Float {
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return cur.toFloat() / maxVolume.coerceAtLeast(1)
    }

    private fun setVolumeFraction(fraction: Float) {
        val index = Math.round(fraction * maxVolume).coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)

        val pct = (index * 100) / maxVolume.coerceAtLeast(1)
        gestureIcon.setImageDrawable(PathDrawable(MenuIcons.AUDIO_TRACK))
        gestureText.text = "Громкость: $pct%"
        gestureProgress.progress = pct
        gestureIndicator.visibility = View.VISIBLE
        handler.removeCallbacks(hideIndicatorRunnable)
    }

    private fun postDelayedHide(delayMs: Long) {
        handler.removeCallbacks(hideIndicatorRunnable)
        handler.postDelayed(hideIndicatorRunnable, delayMs)
    }

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density).toInt()
    }

    companion object {
        private const val DRAG_NONE = 0
        private const val DRAG_BRIGHTNESS = 1
        private const val DRAG_VOLUME = 2
    }
}

/** Returns icon string for brightness. */
private fun MenuIcons.brightnessIcon(): String = MenuIcons.ASPECT
