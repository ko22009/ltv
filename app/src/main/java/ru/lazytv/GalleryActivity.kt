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

        imageSpinner = Spinner(this, dp(48), Color.WHITE).apply {
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

    private fun imageUrl(path: String): String =
        if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w1280$path"

    private fun setupGallery() {
        if (backdrops.isEmpty()) {
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
