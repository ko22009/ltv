package ru.lazytv

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

/**
 * Gallery viewer for a movie or TV show: all TMDB posters and backdrop covers in full detail.
 * Remote left/right navigates thumbnails while updating the large preview; OK opens pure full-screen mode.
 */
class GalleryActivity : Activity() {
    private val scale get() = resources.displayMetrics.widthPixels / 1280f
    private fun dp(value: Int) = (value * scale).toInt()

    private enum class Tab { POSTERS, BACKDROPS }

    private var currentTab = Tab.POSTERS
    private val posters = mutableListOf<String>()
    private val backdrops = mutableListOf<String>()
    private var selectedIndex = 0
    private var isFullscreen = false

    private lateinit var mainImage: ImageView
    private lateinit var imageSpinner: Spinner
    private lateinit var header: LinearLayout
    private lateinit var backBtn: View
    private lateinit var titleView: TextView
    private lateinit var counterView: TextView
    private lateinit var tabPosters: LinearLayout
    private lateinit var tabBackdrops: LinearLayout
    private lateinit var thumbnailStrip: HorizontalScrollView
    private lateinit var thumbnailContainer: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var loadingView: LoadingView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mediaId = intent.getLongExtra("id", 0L)
        val tv = intent.getBooleanExtra("tv", false)
        val name = intent.getStringExtra("name").orEmpty()
        val poster = intent.getStringExtra("poster")
        val passedPosters = intent.getStringArrayListExtra("posters").orEmpty()
        val passedBackdrops = intent.getStringArrayListExtra("backdrops").orEmpty()

        val root = FrameLayout(this).apply {
            background = calmBackground()
        }

        // Full-frame image view for the selected item
        mainImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = false
            isClickable = true
            setOnClickListener { toggleFullscreen() }
        }
        root.addView(mainImage, FrameLayout.LayoutParams(-1, -1))

        imageSpinner = Spinner(this, dp(48), Color.WHITE).apply {
            visibility = View.GONE
        }
        root.addView(imageSpinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // Top bar: Back, title + counter, and tab chips (Posters / Backdrops)
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(64), dp(24), dp(64), 0)
        }

        backBtn = backButton(this, scale) { finish() }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        titleView = TextView(this).apply {
            text = name
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f * scale)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            maxLines = 1
        }
        counterView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * scale)
            setTextColor(0xFF9FB3A9.toInt())
            setPadding(0, dp(2), 0, 0)
        }
        titleBox.addView(titleView)
        titleBox.addView(counterView)

        val spacer = View(this)

        tabPosters = chipButton(this, scale, "ПОСТЕРЫ").apply {
            this.id = View.generateViewId()
            setOnClickListener {
                if (currentTab != Tab.POSTERS) {
                    currentTab = Tab.POSTERS
                    selectedIndex = 0
                    rebuildThumbnails()
                }
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false
                else when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusCurrentThumbnail()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        backBtn.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        tabBackdrops.requestFocus()
                        true
                    }
                    else -> false
                }
            }
        }

        tabBackdrops = chipButton(this, scale, "ОБЛОЖКИ").apply {
            this.id = View.generateViewId()
            layoutParams = (layoutParams as ViewGroup.MarginLayoutParams).apply { leftMargin = dp(10) }
            setOnClickListener {
                if (currentTab != Tab.BACKDROPS) {
                    currentTab = Tab.BACKDROPS
                    selectedIndex = 0
                    rebuildThumbnails()
                }
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false
                else when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusCurrentThumbnail()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        tabPosters.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    else -> false
                }
            }
        }

        backBtn.nextFocusRightId = tabPosters.id
        backBtn.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusCurrentThumbnail()
                true
            } else false
        }

        header.addView(backBtn, LinearLayout.LayoutParams(dp(56), dp(56)))
        header.addView(titleBox, LinearLayout.LayoutParams(-2, -2))
        header.addView(spacer, LinearLayout.LayoutParams(0, 0, 1f))
        header.addView(tabPosters)
        header.addView(tabBackdrops)
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Bottom thumbnail row
        thumbnailContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        thumbnailStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(64), dp(8), dp(64), dp(8))
            addView(thumbnailContainer, FrameLayout.LayoutParams(-2, -2))
        }
        root.addView(thumbnailStrip, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = dp(24)
        })

        emptyView = TextView(this).apply {
            text = "В галерее нет изображений"
            setTextColor(0xFF9FB3A9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(emptyView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        loadingView = LoadingView(this, scale, "Загружаем галерею…")
        root.addView(loadingView, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)

        if (passedPosters.isNotEmpty() || passedBackdrops.isNotEmpty()) {
            posters.addAll(passedPosters)
            backdrops.addAll(passedBackdrops)
            loadingView.visibility = View.GONE
            if (posters.isEmpty() && backdrops.isNotEmpty()) currentTab = Tab.BACKDROPS
            rebuildThumbnails()
        } else {
            if (!poster.isNullOrEmpty()) posters.add(poster)
            loadingView.visibility = View.VISIBLE
            Thread {
                val d = runCatching { Tmdb.detailsCached(applicationContext, mediaId, tv) }.getOrNull()
                onUi {
                    loadingView.visibility = View.GONE
                    if (d != null) {
                        posters.clear()
                        posters.addAll(d.posters)
                        backdrops.clear()
                        backdrops.addAll(d.allBackdrops)
                    }
                    if (posters.isEmpty() && !poster.isNullOrEmpty()) posters.add(poster)
                    if (posters.isEmpty() && backdrops.isNotEmpty()) currentTab = Tab.BACKDROPS
                    rebuildThumbnails()
                }
            }.start()
        }
    }

    private fun imageUrl(path: String, size: String = "w1280"): String =
        if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/$size$path"

    private fun rebuildThumbnails() {
        thumbnailContainer.removeAllViews()
        val list = if (currentTab == Tab.POSTERS) posters else backdrops

        // Update tab styling: selected tab gets darker green plate (#2A5540), inactive gets default (#1F3228)
        tabPosters.background = focusShape(scale, if (currentTab == Tab.POSTERS) 0xFF2A5540.toInt() else 0xFF1F3228.toInt(), radius = 6f)
        tabBackdrops.background = focusShape(scale, if (currentTab == Tab.BACKDROPS) 0xFF2A5540.toInt() else 0xFF1F3228.toInt(), radius = 6f)
        tabPosters.chipCaption = if (posters.isNotEmpty()) "ПОСТЕРЫ ${posters.size}" else "ПОСТЕРЫ"
        tabBackdrops.chipCaption = if (backdrops.isNotEmpty()) "ОБЛОЖКИ ${backdrops.size}" else "ОБЛОЖКИ"

        if (list.isEmpty()) {
            val otherList = if (currentTab == Tab.POSTERS) backdrops else posters
            if (otherList.isNotEmpty()) {
                currentTab = if (currentTab == Tab.POSTERS) Tab.BACKDROPS else Tab.POSTERS
                rebuildThumbnails()
                return
            }
            emptyView.visibility = View.VISIBLE
            mainImage.setImageDrawable(null)
            counterView.text = ""
            backBtn.requestFocus()
            return
        }

        emptyView.visibility = View.GONE
        val isPoster = currentTab == Tab.POSTERS
        val cardW = if (isPoster) dp(70) else dp(140)
        val cardH = if (isPoster) dp(105) else dp(79)

        list.forEachIndexed { index, path ->
            val card = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(cardW, cardH).apply {
                    if (index > 0) leftMargin = dp(10)
                }
                isFocusable = true
                isClickable = true
                background = focusShape(scale, 0xFF1F3228.toInt(), radius = 6f)
                clipToOutline = true

                val thumb = ImageView(this@GalleryActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                addView(thumb, FrameLayout.LayoutParams(-1, -1))
                Glide.with(this@GalleryActivity)
                    .load(imageUrl(path, "w300"))
                    .into(thumb)

                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) selectImage(index)
                }
                setOnClickListener { toggleFullscreen() }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) false
                    else when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            val target = if (currentTab == Tab.POSTERS) tabPosters else tabBackdrops
                            target.requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (index == 0) {
                                backBtn.requestFocus()
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            index == list.size - 1
                        }
                        else -> false
                    }
                }
            }
            thumbnailContainer.addView(card)
        }

        selectedIndex = selectedIndex.coerceIn(0, list.size - 1)
        selectImage(selectedIndex)
        focusCurrentThumbnail()
    }

    private fun focusCurrentThumbnail() {
        val target = thumbnailContainer.getChildAt(selectedIndex) ?: thumbnailContainer.getChildAt(0)
        target?.requestFocus() ?: backBtn.requestFocus()
    }

    private fun selectImage(index: Int) {
        val list = if (currentTab == Tab.POSTERS) posters else backdrops
        if (index !in list.indices) return
        selectedIndex = index
        val path = list[index]

        val typeCaption = if (currentTab == Tab.POSTERS) "Постер" else "Обложка"
        counterView.text = "$typeCaption ${index + 1} из ${list.size}"

        imageSpinner.visibility = View.VISIBLE
        Glide.with(this)
            .load(imageUrl(path, "w1280"))
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    imageSpinner.visibility = View.GONE
                    return false
                }
                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    imageSpinner.visibility = View.GONE
                    return false
                }
            })
            .into(mainImage)

        thumbnailContainer.getChildAt(index)?.let { child ->
            thumbnailStrip.requestChildRectangleOnScreen(child, Rect(0, 0, child.width, child.height), false)
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        header.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        thumbnailStrip.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        if (!isFullscreen) focusCurrentThumbnail()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isFullscreen && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    toggleFullscreen()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (selectedIndex > 0) {
                        selectedIndex--
                        selectImage(selectedIndex)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val list = if (currentTab == Tab.POSTERS) posters else backdrops
                    if (selectedIndex < list.size - 1) {
                        selectedIndex++
                        selectImage(selectedIndex)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
