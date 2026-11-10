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
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Touch gesture overlay for phones and tablets:
 * - Single tap: toggle control overlay
 * - Double tap: rewind 10s (left), fast-forward 10s (right), play/pause (center)
 * - Vertical drag: brightness (left half of the screen) and media volume (right half); the sign of what changes stands
 *   on the same side as the finger: a sun and a bar on the left for the brightness, a speaker and a bar on the right for the volume
 */
class PlayerTouchOverlay(
    context: Context,
    private val activity: Activity,
    private val onSeek: (deltaMs: Long) -> Unit,
    /** Toggles the playback; returns true when the video plays now. */
    private val onTogglePlay: () -> Boolean,
    /** A single tap; the argument is where it landed, as a share of the height (0 top, 1 bottom). */
    private val onToggleControls: (Float) -> Unit,
    private val onUserAction: () -> Unit,
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    // the sign of the drag: icon, percent and a vertical bar, on the side of the finger
    private val dragSign: LinearLayout
    private val dragIcon: ImageView
    private val dragText: TextView
    private val dragFill: View
    private val barHeight = dp(120)

    private val seekBubbleLeft: TextView
    private val seekBubbleRight: TextView
    private val pauseSign: ImageView

    private val hideIndicatorRunnable: Runnable

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    /** One distance from the side edge for the seek bubbles and the brightness / volume sign: 15% of the screen width. */
    private val sideInset = (resources.displayMetrics.widthPixels * 0.15f).toInt()
    private var downX = 0f
    private var downY = 0f
    private var dragMode = DRAG_NONE
    private var startValue = 0f
    private var lastVolumeIndex = -1

    init {
        dragSign = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(OVERLAY_SOFT)
                cornerRadius = dp(TITLE_CARD_RADIUS.toInt()).toFloat()
            }
            visibility = View.GONE
        }
        dragIcon = ImageView(context)
        dragSign.addView(dragIcon, LinearLayout.LayoutParams(dp(28), dp(28)))
        dragText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        dragSign.addView(dragText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4); bottomMargin = dp(8) })
        // the bar: a grey track and a white fill that grows from the bottom
        val track = FrameLayout(context).apply {
            background = GradientDrawable().apply { setColor(0x66FFFFFF); cornerRadius = dp(3).toFloat() }
        }
        dragFill = View(context).apply {
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(3).toFloat() }
        }
        track.addView(dragFill, LayoutParams(-1, 0, Gravity.BOTTOM))
        dragSign.addView(track, LinearLayout.LayoutParams(dp(6), barHeight))
        addView(dragSign, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = sideInset
        })

        // Double-tap seek bubbles (-10s / +10s): about 15% of the screen from the edge, so the bubble sits near the middle of its tap zone
        seekBubbleLeft = createSeekBubble("-10")
        seekBubbleRight = createSeekBubble("+10")
        addView(seekBubbleLeft, LayoutParams(dp(84), dp(84), Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = sideInset
        })
        addView(seekBubbleRight, LayoutParams(dp(84), dp(84), Gravity.CENTER_VERTICAL or Gravity.END).apply {
            marginEnd = sideInset
        })

        // the big play / pause sign that flashes in the middle after a double tap
        pauseSign = ImageView(context).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(OVERLAY_SOFT) }
            visibility = View.GONE
        }
        addView(pauseSign, LayoutParams(dp(84), dp(84), Gravity.CENTER))

        hideIndicatorRunnable = Runnable {
            dragSign.visibility = View.GONE
            seekBubbleLeft.visibility = View.GONE
            seekBubbleRight.visibility = View.GONE
        }
    }

    private fun createSeekBubble(caption: String) = TextView(context).apply {
        text = caption
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        // a round disc of the size of the pause sign (84 dp), the same dark plate
        layoutParams = LayoutParams(dp(84), dp(84))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(OVERLAY_SOFT)
        }
        visibility = View.GONE
    }

    /** Taps only: the drags are handled in [onTouchEvent] by hand, so that they start at once and never get lost. */
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onToggleControls(if (height > 0) e.y / height else 0.5f)
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

        override fun onDown(e: MotionEvent): Boolean = true
    })

    /** The finger went down in the strip at the top or bottom edge of the screen: such a touch never starts a drag. */
    private var edgeStart = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragMode = DRAG_NONE
                // a swipe that starts at the very top (the device shade) or bottom (the home gesture) is the system's, not a volume or brightness drag
                edgeStart = event.y < dp(40) || event.y > height - dp(32)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DRAG_NONE && !edgeStart) {
                    val dx = Math.abs(event.x - downX)
                    val dy = Math.abs(event.y - downY)
                    // a mostly vertical move beyond the touch slop starts the drag at once (no long dead zone, no giving up on a slightly slanted finger)
                    if (dy > slop && dy > dx) startDrag(event)
                }
                if (dragMode != DRAG_NONE) {
                    applyDrag(event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != DRAG_NONE) {
                    dragMode = DRAG_NONE
                    postDelayedHide(700)
                    return true
                }
            }
        }
        // while the finger drags, the tap detector does not see the moves
        if (dragMode == DRAG_NONE) return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
        return true
    }

    private fun startDrag(event: MotionEvent) {
        dragMode = if (downX < width * 0.5f) DRAG_BRIGHTNESS else DRAG_VOLUME
        startValue = if (dragMode == DRAG_BRIGHTNESS) getBrightness() else getVolumeFraction()
        lastVolumeIndex = -1
        // the detector must not take the touch for a tap any more
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        gestureDetector.onTouchEvent(cancel)
        cancel.recycle()
        // the sign stands on the side of the finger: the sun on the left, the speaker on the right
        (dragSign.layoutParams as LayoutParams).apply {
            gravity = Gravity.CENTER_VERTICAL or (if (dragMode == DRAG_BRIGHTNESS) Gravity.START else Gravity.END)
            // left/right margins, not start/end: start/end changed on live params are not resolved again and the right one got lost
            leftMargin = if (dragMode == DRAG_BRIGHTNESS) sideInset else 0
            rightMargin = if (dragMode == DRAG_VOLUME) sideInset else 0
            marginStart = leftMargin
            marginEnd = rightMargin
        }
        dragSign.requestLayout()
        dragIcon.setImageDrawable(PathDrawable(if (dragMode == DRAG_BRIGHTNESS) MenuIcons.BRIGHTNESS else MenuIcons.VOLUME).apply {
            setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
        })
        handler.removeCallbacks(hideIndicatorRunnable)
        dragSign.visibility = View.VISIBLE
        onUserAction()
    }

    private fun applyDrag(y: Float) {
        // four fifths of the screen height are one full range: a swipe over a half of that changes the value by a half
        val range = (height * 0.8f).coerceAtLeast(1f)
        val target = (startValue + (downY - y) / range).coerceIn(0f, 1f)
        if (dragMode == DRAG_BRIGHTNESS) setBrightness(target) else setVolumeFraction(target)
        onUserAction()
    }

    private fun showSign(fraction: Float) {
        val pct = Math.round(fraction * 100)
        dragText.text = "$pct%"
        dragFill.layoutParams = (dragFill.layoutParams as LayoutParams).apply { height = (barHeight * fraction).toInt() }
        dragFill.requestLayout()
    }

    /** The sign of the new state (pause bars when the video was just stopped, the triangle when it goes on): quick in, quick out. */
    private fun showPauseSign(playing: Boolean) {
        pauseSign.setImageDrawable(PathDrawable(if (playing) MenuIcons.PLAY else MenuIcons.PAUSE).apply {
            setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
        })
        pauseSign.animate().cancel()
        pauseSign.visibility = View.VISIBLE
        pauseSign.alpha = 0f
        pauseSign.scaleX = 0.8f
        pauseSign.scaleY = 0.8f
        pauseSign.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(70).withEndAction {
            pauseSign.animate().alpha(0f).setStartDelay(120).setDuration(130).withEndAction {
                pauseSign.visibility = View.GONE
            }.start()
        }.start()
    }

    fun showSeekFeedback(isForward: Boolean) {
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
        showSign(lp.screenBrightness)
    }

    private fun getVolumeFraction(): Float {
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return cur.toFloat() / maxVolume.coerceAtLeast(1)
    }

    private fun setVolumeFraction(fraction: Float) {
        val index = Math.round(fraction * maxVolume).coerceIn(0, maxVolume)
        // the system is asked only when the step changes (the volume has 15 or so steps, the finger gives hundreds of moves)
        if (index != lastVolumeIndex) {
            lastVolumeIndex = index
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        }
        showSign(index.toFloat() / maxVolume.coerceAtLeast(1))
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
