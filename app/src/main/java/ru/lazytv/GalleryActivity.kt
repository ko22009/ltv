package ru.lazytv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

/**
 * Cover/backdrop gallery viewer: displays all TMDB covers of a title in full screen.
 * Left/right arrows navigate between covers; Up or Back goes to the Back button.
 */
class GalleryActivity : Activity() {
    private val scale get() = uiScale()
    private fun dp(value: Int) = (value * scale).toInt()

    private val backdrops = mutableListOf<String>()
    private var selectedIndex = 0
    private var headerVisible = true

    private lateinit var root: FrameLayout
    private lateinit var mainImage: ImageView
    private lateinit var imageSpinner: Spinner
    private lateinit var header: LinearLayout
    private lateinit var backBtn: View
    private lateinit var titleView: TextView
    private lateinit var counterView: TextView
    private lateinit var emptyView: TextView
    private lateinit var loadingView: LoadingView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // on a phone the covers (wide pictures) are shown sideways, the posters (tall ones) stay upright
        if (isMobileUi() && !posterMode) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val mediaId = intent.getLongExtra("id", 0L)
        val tv = intent.getBooleanExtra("tv", false)
        val name = intent.getStringExtra("name").orEmpty()
        val passedBackdrops = intent.getStringArrayListExtra("backdrops").orEmpty()

        root = FrameLayout(this).apply {
            background = calmBackground()
            isFocusable = true
            isFocusableInTouchMode = true
        }

        mainImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = false
            isClickable = true
            setOnClickListener { toggleHeader() }
        }
        root.addView(mainImage, FrameLayout.LayoutParams(-1, -1))

        imageSpinner = Spinner(this, dp(48)).apply {
            visibility = View.GONE
        }
        root.addView(imageSpinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // Top bar: Back button, movie title, and counter "1 из 75"
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(64), dp(24), dp(64), 0)
        }

        backBtn = backButton(this, scale) { finish() }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, 0, 0)
        }
        titleView = TextView(this).apply {
            text = name
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f * scale)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            maxLines = 1
        }
        counterView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * scale)
            setTextColor(0xFF94A3B8.toInt())
            setPadding(0, dp(2), 0, 0)
        }
        titleBox.addView(titleView)
        titleBox.addView(counterView)

        header.addView(backBtn, LinearLayout.LayoutParams(dp(56), dp(56)))
        header.addView(titleBox, LinearLayout.LayoutParams(-2, -2))
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        if (mobile) setupPhoneChrome()

        emptyView = TextView(this).apply {
            text = "В галерее нет обложек"
            setTextColor(0xFF94A3B8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(emptyView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        loadingView = LoadingView(this, scale, "Загружаем обложки…")
        root.addView(loadingView, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)

        if (passedBackdrops.isNotEmpty()) {
            backdrops.addAll(passedBackdrops)
            loadingView.visibility = View.GONE
            setupGallery()
        } else {
            val cached = Tmdb.peekDetails(applicationContext, mediaId, tv)
            if (cached != null) {
                loadingView.visibility = View.GONE
                backdrops.addAll(cached.allBackdrops)
                if (backdrops.isEmpty() && cached.backdrop.isNotEmpty()) backdrops.add(cached.backdrop)
                setupGallery()
            } else {
                loadingView.visibility = View.VISIBLE
                Thread {
                    val d = runCatching { Tmdb.detailsCached(applicationContext, mediaId, tv) }.getOrNull()
                    onUi {
                        loadingView.visibility = View.GONE
                        if (d != null) {
                            backdrops.clear()
                            backdrops.addAll(d.allBackdrops)
                            if (backdrops.isEmpty() && d.backdrop.isNotEmpty()) backdrops.add(d.backdrop)
                        }
                        setupGallery()
                    }
                }.start()
            }
        }
    }

    private val mobile by lazy { isMobileUi() }
    private lateinit var phoneCounter: TextView
    private lateinit var arrowLeft: View
    private lateinit var arrowRight: View

    /** Phone look: a close cross, "1 / 5" on top, the frame as a rounded card with round arrows, swipe to turn. */
    private fun setupPhoneChrome() {
        header.visibility = View.GONE
        headerVisible = false
        val side = dp(16)
        val width = resources.displayMetrics.widthPixels - 2 * side
        // the gallery lies over the card as a dimmed layer; the frame keeps its own shape
        root.background = android.graphics.drawable.ColorDrawable(0xF0101216.toInt())
        // a tap on the picture shows the next one (after the last one the first); a tap beside it closes, as before
        val nextPicture = { if (backdrops.size > 1) showImage((selectedIndex + 1) % backdrops.size) }
        root.setOnClickListener { finish() }
        mainImage.scaleType = ImageView.ScaleType.FIT_CENTER
        mainImage.adjustViewBounds = true
        mainImage.setOnClickListener { nextPicture() }
        mainImage.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
            }
        }
        mainImage.clipToOutline = true
        if (posterMode) {
            // a poster is tall: the frame takes the size of the picture inside the screen, so the rounded corners stay on the picture
            mainImage.maxWidth = width
            mainImage.maxHeight = resources.displayMetrics.heightPixels - dp(200)
            mainImage.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        } else {
            mainImage.layoutParams = FrameLayout.LayoutParams(width, -2, Gravity.CENTER)
        }

        fun round(icon: String, size: Int, pad: Int, fill: Int, action: () -> Unit) = ImageView(this).apply {
            setImageDrawable(PathDrawable(icon))
            setPadding(pad, pad, pad, pad)
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(fill) }
            setOnClickListener { action() }
        }
        // the cross is small to look at, the tap zone around it is wide (the corner of the screen), like the arrows
        val closeZone = FrameLayout(this).apply {
            isClickable = true
            contentDescription = "Закрыть"
            setOnClickListener { finish() }
            addView(round(MenuIcons.CLOSE, dp(44), dp(8), Color.TRANSPARENT) { }.apply { isClickable = false },
                FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        }
        root.addView(closeZone, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.TOP or Gravity.END).apply { topMargin = dp(2) })
        phoneCounter = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * scale)
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1C2029.toInt()); cornerRadius = dp(18).toFloat() }
        }
        // the same counter as on the banner of the title card: the same plate and the same place, in the upper right corner of the picture
        phoneCounter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        phoneCounter.setPadding(dp(12), dp(4), dp(12), dp(4))
        phoneCounter.background = android.graphics.drawable.GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = dp(14).toFloat() }
        root.addView(phoneCounter, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        mainImage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeCounter() }
        phoneCounter.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeCounter() }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeCounter() }
        arrowLeft = round(MenuIcons.CHEVRON_LEFT, dp(40), dp(8), 0x99000000.toInt()) { if (selectedIndex > 0) showImage(selectedIndex - 1) }
        arrowRight = round(MenuIcons.CHEVRON_RIGHT, dp(40), dp(8), 0x99000000.toInt()) { if (selectedIndex < backdrops.size - 1) showImage(selectedIndex + 1) }
        // the round arrow is small to look at, but the tap zone around it is wide and tall: a thumb does not miss when turning the picture
        fun zone(arrow: View, gravity: Int, step: Int) {
            arrow.isClickable = false
            val box = FrameLayout(this).apply {
                isClickable = true
                setOnClickListener { val to = selectedIndex + step; if (to in 0 until backdrops.size) showImage(to) }
                addView(arrow, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            }
            root.addView(box, FrameLayout.LayoutParams(dp(88), dp(200), Gravity.CENTER_VERTICAL or gravity))
        }
        zone(arrowLeft, Gravity.START, -1)
        zone(arrowRight, Gravity.END, 1)
    }

    /** The counter sits in the upper right corner of the picture as it is drawn (not of the view, which can be wider), inside the screen. */
    private fun placeCounter() {
        if (!::phoneCounter.isInitialized) return
        val rootAt = IntArray(2); root.getLocationInWindow(rootAt)
        val imageAt = IntArray(2); mainImage.getLocationInWindow(imageAt)
        val drawn = android.graphics.RectF(0f, 0f, mainImage.width.toFloat(), mainImage.height.toFloat())
        mainImage.drawable?.let { d ->
            drawn.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
            mainImage.imageMatrix.mapRect(drawn)
        }
        val inset = dp(10)
        val right = minOf(imageAt[0] - rootAt[0] + drawn.right, root.width.toFloat()) - inset
        val top = maxOf(imageAt[1] - rootAt[1] + drawn.top, 0f) + inset
        phoneCounter.translationX = right - phoneCounter.width
        phoneCounter.translationY = top
    }

    private fun updatePhoneChrome() {
        if (!mobile || !::phoneCounter.isInitialized) return
        phoneCounter.text = "${selectedIndex + 1} / ${backdrops.size}"
        // the picture may not be set yet: place the counter once it is
        mainImage.post { placeCounter() }
        arrowLeft.alpha = if (selectedIndex > 0) 1f else 0.3f
        arrowRight.alpha = if (selectedIndex < backdrops.size - 1) 1f else 0.3f
    }

    private val swipe by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                if (Math.abs(dx) < dp(60) || Math.abs(vx) < Math.abs(vy)) return false
                if (dx < 0 && selectedIndex < backdrops.size - 1) showImage(selectedIndex + 1)
                else if (dx > 0 && selectedIndex > 0) showImage(selectedIndex - 1)
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (mobile) swipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** The posters of the title instead of the frames: opened by a tap on the poster of the card. */
    private val posterMode by lazy { intent.getBooleanExtra("posters", false) }

    private fun imageUrl(path: String): String =
        if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/${if (posterMode) "w780" else "w1280"}$path"

    private fun setupGallery() {
        if (backdrops.isEmpty()) {
            emptyView.text = if (posterMode) "Постеров нет" else "В галерее нет обложек"
            emptyView.visibility = View.VISIBLE
            mainImage.setImageDrawable(null)
            counterView.text = ""
            backBtn.requestFocus()
            return
        }
        emptyView.visibility = View.GONE
        selectedIndex = 0
        showImage(selectedIndex)
        root.requestFocus()
    }

    private var imageSpinnerTask: Runnable? = null

    private fun showImageSpinner() {
        imageSpinnerTask?.let { root.removeCallbacks(it) }
        val task = Runnable { imageSpinner.visibility = View.VISIBLE }
        imageSpinnerTask = task
        root.postDelayed(task, 200L)
    }

    private fun hideImageSpinner() {
        imageSpinnerTask?.let { root.removeCallbacks(it) }
        imageSpinnerTask = null
        imageSpinner.visibility = View.GONE
    }

    private fun showImage(index: Int) {
        if (index !in backdrops.indices) return
        selectedIndex = index
        val path = backdrops[index]

        counterView.text = "${index + 1} из ${backdrops.size}"
        updatePhoneChrome()

        val prev = mainImage.drawable
        showImageSpinner()
        Glide.with(this)
            .load(imageUrl(path))
            .let { if (prev != null) it.placeholder(prev) else it }
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    hideImageSpinner()
                    return false
                }
                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    hideImageSpinner()
                    return false
                }
            })
            .into(mainImage)

        // Preload next and previous covers for instant navigation
        if (index + 1 < backdrops.size) {
            Glide.with(applicationContext).load(imageUrl(backdrops[index + 1])).preload()
        }
        if (index > 0) {
            Glide.with(applicationContext).load(imageUrl(backdrops[index - 1])).preload()
        }
    }

    private fun toggleHeader() {
        if (mobile) return
        headerVisible = !headerVisible
        header.visibility = if (headerVisible) View.VISIBLE else View.GONE
        if (headerVisible) root.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (!headerVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    toggleHeader()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (selectedIndex > 0) {
                        showImage(selectedIndex - 1)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (selectedIndex < backdrops.size - 1) {
                        showImage(selectedIndex + 1)
                    }
                    return true
                }
            }
        }

        // When header is visible and focus is on Back button
        if (backBtn.hasFocus()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    root.requestFocus()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // When browsing covers
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (selectedIndex > 0) {
                    showImage(selectedIndex - 1)
                    return true
                } else {
                    backBtn.requestFocus()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (selectedIndex < backdrops.size - 1) {
                    showImage(selectedIndex + 1)
                    return true
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                backBtn.requestFocus()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                toggleHeader()
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        hideImageSpinner()
        super.onDestroy()
    }
}
