package ru.lazytv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

/**
 * Card of a title in the LazyMedia layout (design/lmd-card.png): green banner, poster on the left,
 * a darker action bar, then title, facts and description. Back returns to the lists.
 */
class DetailsActivity : Activity() {
    /** The layout is drawn for a 1280-wide screen and scaled to the real width, so it looks the same on any TV. */
    private val scale get() = uiScale()

    private fun dp(value: Int) = (value * scale).toInt()

    /** Phone layout (portrait, touch): a value for the TV screen and a value for the phone. */
    private val mobile by lazy { isMobileUi() }
    private fun m(tv: Int, phone: Int) = if (mobile) phone else tv

    private fun text(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /** When the screen was made: the second OK of a double press on the card must not press the first button of the bar. */
    private val openedAt = android.os.SystemClock.elapsedRealtime()

    /** The flat button of the bar under the banner: it keeps the bar's own look and fits all the buttons in one line. */
    private fun button(icon: String, label: String, labeled: Boolean = false, action: () -> Unit) = TextView(this).apply {
        // on a phone the bookmark is an icon tile; [labeled] buttons are the row of actions with a caption under the icon
        val iconOnly = mobile && !labeled
        text = if (iconOnly) "" else if (labeled) label else label.uppercase()
        contentDescription = label
        setSingleLine()
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * scale)
        setTextColor(cursorColors(context, Color.WHITE))
        gravity = Gravity.CENTER
        setPadding(dp(if (iconOnly) 16 else 12), 0, dp(if (iconOnly) 16 else 12), 0)
        val firstIcon = PathDrawable(icon).apply { setBounds(0, 0, dp(22), dp(22)) }
        if (labeled) {
            setCompoundDrawables(null, firstIcon, null, null)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * scale)
            setPadding(0, dp(8), 0, dp(8))
            tag = "top"
        } else setCompoundDrawables(firstIcon, null, null, null)
        compoundDrawablePadding = dp(if (iconOnly) 0 else if (labeled) 6 else 8)
        // the icon follows the focus like the caption (dark on the light cursor)
        compoundDrawableTintList = cursorColors(context, Color.WHITE)
        // a left drawable sticks to the left edge of a wide button: the padding puts the lone icon in the middle
        if (iconOnly) addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val pad = (((r - l) - dp(22)) / 2).coerceAtLeast(0)
            if (v.paddingLeft != pad) v.setPadding(pad, 0, pad, 0)
        }
        isFocusable = true
        isClickable = true
        background = StateListDrawable().apply {
            // on a phone a tap is shown with a lighter plate, not with the teal of the remote's cursor
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setColor(if (mobile) TOUCH_FILL else FOCUS_FILL)
                cornerRadius = dp(if (mobile) TITLE_CARD_RADIUS.toInt() else SMALL_RADIUS.toInt()).toFloat()
            })
            if (mobile) addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                setColor(TOUCH_FILL)
                cornerRadius = dp(TITLE_CARD_RADIUS.toInt()).toFloat()
            })
            // on a phone the icons are dark rounded tiles
            if (iconOnly) addState(intArrayOf(), GradientDrawable().apply {
                setColor(PLATE_FILL)
                cornerRadius = dp(TITLE_CARD_RADIUS.toInt()).toFloat()
            })
        }
        setOnClickListener {
            // a quick double press on the card lands here: the first moments ignore clicks, so the video does not start by itself
            if (android.os.SystemClock.elapsedRealtime() - openedAt > 500) action()
        }
        // on a phone the five icons share the width of the row evenly
        layoutParams = if (labeled) LinearLayout.LayoutParams(0, -1, 1f)
        else if (iconOnly) LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(8) } else LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(8) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("name").orEmpty()
        val title = titleFrom(intent.extras ?: Bundle())
        currentName = name
        currentTitle = title
        posterPath = intent.getStringExtra("poster")
        // "recently opened": the card is put into the list when it is opened, not only when watching starts
        if (savedInstanceState == null && (title.id > 0L || name.isNotEmpty())) History.add(this, historyTitle())
        val rawRating = intent.getStringExtra("rating").orEmpty().replace(',', '.')
        val shownRating = if ((rawRating.toDoubleOrNull() ?: 0.0) > 0.0) rawRating else ""
        val baseRows = listOf(
            "Тип" to if (intent.getBooleanExtra("tv", false)) "Сериал" else "Фильм",
            // the year stands here until the details arrive with the full date
            RELEASE to intent.getStringExtra("year").orEmpty(),
            // the rating stands in the facts of the card, not on its poster
            "Рейтинг" to shownRating,
        )

        val root = FrameLayout(this)
        root.setBackgroundColor(PAGE_BG)
        // on a phone the banner, the buttons and the poster are the top of one scrolling page, so the poster scrolls away
        // with them instead of riding over the facts; on the TV they lie directly on the screen
        val stage = if (mobile) FrameLayout(this).apply { clipChildren = false; clipToPadding = false } else root

        // a calm dark band; the frame of the film (TMDB backdrop) fades in under a dark veil once it is known
        val banner = FrameLayout(this)
        banner.setBackgroundColor(BACKDROP_PLATE)
        // the stub stands under the frame from the start: the spinner turns on it, and the frame fades in over it
        banner.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.no_backdrop) },
            FrameLayout.LayoutParams(-1, -1))
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        banner.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        // a light dim on the frame: the white logo and the buttons stay readable on a bright still
        banner.addView(View(this).apply { setBackgroundColor(OVERLAY_SOFT) }, FrameLayout.LayoutParams(-1, -1))
        bannerSpinner = Spinner(this, dp(SPINNER_IMAGE)).apply { visibility = View.GONE }
        banner.addView(bannerSpinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        if (mobile) {
            // on a phone the banner is a rounded card under the back button: a gallery icon in the middle,
            // the number of the frame in the corner and the dots at the bottom
            banner.background = GradientDrawable().apply { setColor(PLATE_FILL); cornerRadius = dp(TITLE_CARD_RADIUS.toInt()).toFloat() }
            banner.clipToOutline = true
            // a tap opens the gallery, a swipe turns the frame
            val swipe = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent) = true
                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { openGallery(); return true }
                override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                    val dx = e2.x - (e1?.x ?: return false)
                    if (Math.abs(dx) < dp(50) || Math.abs(vx) < Math.abs(vy)) return false
                    stepBackdrop(if (dx < 0) 1 else -1)
                    return true
                }
            })
            var downX = 0f
            var downY = 0f
            banner.setOnTouchListener { v, ev ->
                if (ev.action == android.view.MotionEvent.ACTION_DOWN) { downX = ev.x; downY = ev.y }
                // a sideways move belongs to the banner, an up-down one still scrolls the page
                else if (ev.action == android.view.MotionEvent.ACTION_MOVE && Math.abs(ev.x - downX) > Math.abs(ev.y - downY) + dp(8))
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                swipe.onTouchEvent(ev)
            }
            // the gallery mark: a dark round plate in the lower right corner, the same plate as the back button
            banner.addView(ImageView(this).apply {
                setImageDrawable(PathDrawable(MenuIcons.GALLERY).apply { setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN) })
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(OVERLAY_LIGHT) }
                contentDescription = "Галерея"
            }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.BOTTOM).apply { rightMargin = dp(12); bottomMargin = dp(12) })
            bannerCounter = text("", 14f).apply {
                setPadding(dp(12), dp(4), dp(12), dp(4))
                background = GradientDrawable().apply { setColor(OVERLAY_LIGHT); cornerRadius = dp(TITLE_CARD_RADIUS.toInt()).toFloat() }
                visibility = View.GONE
            }
            banner.addView(bannerCounter, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { topMargin = dp(12); rightMargin = dp(12) })
            bannerDots = LinearLayout(this).apply { gravity = Gravity.CENTER; visibility = View.GONE }
            banner.addView(bannerDots, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(12) })
            stage.addView(banner, FrameLayout.LayoutParams(-1, dp(178)).apply { topMargin = dp(12); leftMargin = dp(16); rightMargin = dp(16) })
        } else {
            stage.addView(banner, FrameLayout.LayoutParams(-1, dp(360)))
        }

        val passedBackdrop = intent.getStringExtra("backdrop") ?: title.backdrop
        if (!passedBackdrop.isNullOrEmpty()) {
            loadBackdrop(passedBackdrop)
        }

        // the back stands on the frame: a crisp translucent dark circle keeps the arrow readable on any still
        fun softDisc(fill: Int) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(fill)
        }
        // the way back for a remote with no Back key or a mouse: a round button in the corner, same as the Back key
        // on a phone the touch area is wider than the disc (12 dp all round): a finger that misses the disc still hits Back
        // and does not open the gallery under it
        val reach = if (mobile) 12 else 0
        fun disc(fill: Int): android.graphics.drawable.Drawable =
            if (reach == 0) softDisc(fill) else android.graphics.drawable.InsetDrawable(softDisc(fill), dp(reach))
        val back = ImageView(this).apply {
            setImageDrawable(PathDrawable(MenuIcons.BACK))
            imageTintList = cursorColors(this@DetailsActivity, Color.WHITE)
            setPadding(dp(12 + reach), dp(12 + reach), dp(12 + reach), dp(12 + reach))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), disc(if (mobile) 0xCC343B47.toInt() else FOCUS_FILL))
                addState(intArrayOf(), disc(OVERLAY_LIGHT))
            }
            isFocusable = true
            isClickable = true
            id = View.generateViewId()
            contentDescription = "Назад"
            setOnClickListener { finish() }
        }
        // on a phone the back button stands on the banner itself, in its upper left corner
        stage.addView(back, FrameLayout.LayoutParams(dp(m(56, 48) + 2 * reach), dp(m(56, 48) + 2 * reach), Gravity.START or Gravity.TOP).apply {
            topMargin = dp(m(32, 22) - reach); leftMargin = dp(m(64, 26) - reach)
        })

        val bar = LinearLayout(this)
        if (!mobile) bar.setBackgroundColor(BACKDROP_PLATE)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(m(389, 0)), 0, dp(m(24, 0)), 0)
        // watching opens the first variant found on the video sites; without any it falls back to torrents
        watchButton = button(MenuIcons.PLAY, "Смотреть") { startWatching(name) }.apply {
            id = View.generateViewId()
            isLongClickable = true
            setOnLongClickListener {
                if (android.os.SystemClock.elapsedRealtime() - openedAt > 500) {
                    chooseSource(name)
                }
                true
            }
        }
        back.nextFocusDownId = watchButton.id
        back.setOnKeyListener { _, code, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    watchButton.requestFocus()
                    true
                } else false
            } else false
        }
        watchButton.nextFocusUpId = back.id
        watchButton.nextFocusLeftId = back.id
        watchSpinner = Spinner(this, dp(SPINNER_INLINE)).apply {
            visibility = View.GONE
            isFocusable = false
            isClickable = false
        }
        if (mobile) watchButton.apply {
            // a wide button in the accent colour of the app with the caption; the corner is the same as the bookmark tile beside it (14 dp)
            text = "Смотреть"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * scale)
            typeface = Typeface.DEFAULT_BOLD
            compoundDrawablePadding = dp(10)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setColor(FOCUS_FILL); cornerRadius = dp(TITLE_CARD_RADIUS.toInt()).toFloat()
                })
                addState(intArrayOf(), GradientDrawable().apply { setColor(FOCUS_FILL); cornerRadius = dp(TITLE_CARD_RADIUS.toInt()).toFloat() })
            }
            // the icon stays next to the caption: padding puts the pair in the middle of the wide button
            addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                val tv = v as TextView
                val pad = (((r - l) - dp(22) - tv.compoundDrawablePadding - tv.paint.measureText(tv.text.toString())) / 2).toInt().coerceAtLeast(0)
                if (tv.paddingLeft != pad) tv.setPadding(pad, 0, pad, 0)
            }
        }
        val watchSlot = FrameLayout(this).apply {
            if (mobile) addView(watchButton, FrameLayout.LayoutParams(-1, dp(44)))
            else addView(watchButton, FrameLayout.LayoutParams(-2, dp(44), Gravity.CENTER))
            addView(watchSpinner, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        // on a phone the watch button stands under the title; the bar below holds the five icons
        if (!mobile) bar.addView(watchSlot, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(8) })

        val galleryButton = button(MenuIcons.GALLERY, "Галерея") { openGallery() }
        galleryButton.nextFocusUpId = back.id
        if (!mobile) bar.addView(galleryButton)

        lateinit var favorite: TextView
        // filled bookmark: the title is saved in a folder; outline: it is not
        fun bookmarkIcon() = PathDrawable(if (Favorites.folderOf(this, title) != null) MenuIcons.BOOKMARK else MenuIcons.BOOKMARK_BORDER)
            .apply { setBounds(0, 0, dp(22), dp(22)) }
        // the icon of a captioned button stands above its caption, of the others at the left
        fun TextView.putIcon(d: Drawable) = if (tag == "top") setCompoundDrawables(null, d, null, null) else setCompoundDrawables(d, null, null, null)
        // one bookmark: the title is either in the favorites or not, no folders to choose from
        favorite = button(MenuIcons.BOOKMARK, "Закладки") {
            val saved = Favorites.folderOf(this, title) != null
            Favorites.set(this, title, if (saved) null else Folder.FAVORITES)
            favorite.putIcon(bookmarkIcon())
            toast(if (saved) "Убрано из закладок" else "В закладках")
        }
        favorite.nextFocusUpId = back.id
        favorite.putIcon(bookmarkIcon())
        // on a phone the bookmark stands beside the watch button, not in the row of actions
        if (!mobile) bar.addView(favorite)
        // "watched": filled while marked; the lists show a marked title dimmed so it does not draw the eye
        lateinit var seen: TextView
        fun seenIcon() = PathDrawable(if (Watched.has(this, title)) MenuIcons.CHECK else MenuIcons.CHECK_BORDER)
            .apply { setBounds(0, 0, dp(22), dp(22)) }
        seen = button(MenuIcons.CHECK_BORDER, "Просмотрено", labeled = mobile) {
            Watched.set(this, title, !Watched.has(this, title))
            seen.putIcon(seenIcon())
            toast(if (Watched.has(this, title)) "Отмечено просмотренным" else "Отметка снята")
        }
        seen.nextFocusUpId = back.id
        seen.putIcon(seenIcon())
        bar.addView(seen)
        // thin line between the captioned actions of the phone
        fun divider() { if (mobile) bar.addView(View(this).apply { setBackgroundColor(PLATE_LINE) }, LinearLayout.LayoutParams(dp(1).coerceAtLeast(1), dp(32))) }
        divider()
        val torrentButton = button(MenuIcons.DOWNLOAD, "Торрент", labeled = mobile) { openTorrents(name) }.apply {
            nextFocusUpId = back.id
            // the cursor is on the button: the torrent search starts now, not after the press
            setOnFocusChangeListener { _, has -> if (has) startTorrentSearch(name) }
        }
        val trailerButton = button(MenuIcons.TRAILER, "Трейлер", labeled = mobile) { openTrailer(title) }.apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = 0
            nextFocusUpId = back.id
        }
        bar.addView(torrentButton)
        divider()
        bar.addView(trailerButton)
        // left and right walk the buttons of the bar: at the right end there is nothing to go to, the focus stays;
        // at the left end the key goes on to the back button as usual
        // on a phone the gallery opens from the banner, not from the row
        val barButtons = listOf(watchButton, galleryButton, favorite, seen, torrentButton, trailerButton).filter { !mobile || it !== galleryButton }
        barButtons.forEachIndexed { index, btn ->
            btn.setOnKeyListener { _, code, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) false
                else if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    val step = if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    val next = barButtons.getOrNull(index + step)
                    if (next != null) {
                        next.requestFocus()
                        true
                    } else if (step < 0) {
                        back.requestFocus()
                        true
                    } else true
                } else if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    val target = (if (overviewView.text.isNotEmpty()) overviewView else null) ?: firstDirectorLink ?: firstCastCard
                    target?.requestFocus() ?: false
                } else if (btn === watchButton && (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER)) {
                    if (event.isLongPress || event.repeatCount == 15) {
                        chooseSource(name)
                        true
                    } else false
                } else false
            }
        }
        if (!mobile) {
            root.addView(bar,FrameLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(360) })
        }

        posterView = ImageView(this)
        posterView.scaleType = ImageView.ScaleType.CENTER_CROP
        posterView.setBackgroundColor(PLATE_FILL)
        posterSpinner = Spinner(this, dp(SPINNER_IMAGE))
        posterView.showPoster(posterPath, posterSpinner)
        val posterBox = FrameLayout(this).apply {
            // a tap enlarges the poster in the gallery layer; the TV poster is not focusable, so only the phone uses it
            if (mobile) setOnClickListener { openPosters() }
            // rounded corners on the phone and on the TV, the same as the poster cards of the lists
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            clipToOutline = true
            addView(posterView, FrameLayout.LayoutParams(-1, -1))
            addView(posterSpinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            // on a phone a magnifier in the upper right corner tells that a tap enlarges the poster
            if (mobile) addView(ImageView(this@DetailsActivity).apply {
                setImageDrawable(PathDrawable(MenuIcons.SEARCH_ICON).apply { setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN) })
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(OVERLAY_LIGHT) }
            }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.END or Gravity.TOP).apply { topMargin = dp(8); rightMargin = dp(8) })
            // the same label as on the cards: the film is in the cinemas now or comes soon
            val mark = Cinema.caption(currentTitle.id, currentTitle.tv)
            if (mark != null) {
                addView(Cinema.label(this@DetailsActivity) { dp(it.toInt()) }.apply { text = mark; visibility = android.view.View.VISIBLE },
                    FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP).apply { topMargin = dp(8); leftMargin = dp(8) })
            }
        }
        // on the TV the poster lies over the banner; on a phone it stands in the main block, left of the title
        if (!mobile) root.addView(posterBox, FrameLayout.LayoutParams(dp(170), dp(256)).apply { leftMargin = dp(176); topMargin = dp(228) })

        val scroll = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
        }

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        val original = text("", 16f, TEXT_MUTED).apply {
            includeFontPadding = false
            // one line, like the heading: a long original title must not push the lines below and the button down
            if (mobile) { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        }
        // the original title is one grey bar of its own line height until the details come, so the lines below do not move
        skeletonText(original, dp(220))
        if (mobile) {
            val titles = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // no extra font padding above and below the lines: a tall script (devanagari) otherwise pushes the title and the button down past the poster
                titleView = text(name, 24f, bold = true).apply {
                    includeFontPadding = false
                    // one line, so the block by the poster keeps its height; a long title opens whole in a window on a tap
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                addView(titleView)
                addView(original)
                genresLine = text("", 16f, TEXT_MUTED).apply {
                    includeFontPadding = false; setPadding(0, dp(4), 0, 0)
                    // the full list of genres stands in the table below
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }
                skeletonText(genresLine, dp(170))
                metaLine = text(intent.getStringExtra("year").orEmpty(), 15f, TEXT_LIGHT).apply {
                    includeFontPadding = false; setPadding(0, dp(4), 0, 0)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }
                addView(genresLine)
                addView(metaLine)
                // a tap on any line of the block opens all of them whole in a window, but only when some line is cut ("...") on screen
                val lines = listOf(titleView, original, genresLine, metaLine)
                val tapOpensWhole = View.OnClickListener {
                    fun cut(v: android.widget.TextView) = v.layout?.let { l -> l.lineCount > 0 && l.getEllipsisCount(l.lineCount - 1) > 0 } ?: false
                    if (lines.none { cut(it) }) return@OnClickListener
                    val rest = lines.drop(1).map { it.text.toString() }.filter { it.isNotEmpty() && it != DASH }
                    showText(this@DetailsActivity, titleView.text.toString(), rest.joinToString("\n\n"))
                }
                lines.forEach { it.setOnClickListener(tapOpensWhole) }
                // the watch button with the bookmark stands at the level of the poster's lower edge, under the facts line
                minimumHeight = dp(166)
                addView(View(this@DetailsActivity), LinearLayout.LayoutParams(-1, 0, 1f))
                // one split button, no gap: "Смотреть" on a dark plate (a little lighter) and the bookmark tile on a darker one;
                // a lighter plate under the finger
                fun half(rest: Int, left: Boolean) = StateListDrawable().apply {
                    val r = dp(TITLE_CARD_RADIUS.toInt()).toFloat()
                    val radii = if (left) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(TOUCH_FILL); cornerRadii = radii })
                    addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(TOUCH_FILL); cornerRadii = radii })
                    addState(intArrayOf(), GradientDrawable().apply { setColor(rest); cornerRadii = radii })
                }
                addView(LinearLayout(this@DetailsActivity).apply {
                    watchButton.background = half(WATCH_PLATE, left = true)
                    favorite.background = half(PLATE_FILL, left = false)
                    addView(watchSlot, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(favorite, LinearLayout.LayoutParams(dp(48), dp(44)))
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
            val head = LinearLayout(this).apply {
                setPadding(0, dp(16), 0, 0)
                addView(posterBox, LinearLayout.LayoutParams(dp(108), dp(162)).apply { rightMargin = dp(16) })
                // the block is lifted by 4 dp (the capital letters of the title stand at the poster's upper edge) and is 4 dp taller (its minimum
                // height), so the button ends exactly at the poster's lower edge
                addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = -dp(4) })
            }
            info.addView(head)
            // under the poster the row of three captioned actions
            info.addView(bar, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(16) })
        } else {
            titleView = text(name, 34f)
            info.addView(titleView)
        }
        val table = LinearLayout(this)
        table.orientation = LinearLayout.VERTICAL
        table.setPadding(0, dp(16), 0, dp(16))
        fillTable(table, factRows(baseRows.map { if (it.first == RELEASE) it.first to "" else it }))
        if (!mobile) {
            // the TV page reads: titles, the description, then the facts (the table is added below the description)
            info.addView(original)
        }

        val rawOverview = intent.getStringExtra("overview").orEmpty()
        overviewView = text(rawOverview, 18f, TEXT_LIGHT).apply {
            setLineSpacing(0f, 1.25f)
            // the description takes the focus only on the TV (so the remote can scroll it); on a phone it is plain text of the page
            if (!mobile) {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isFocusable = true
            isClickable = true
            background = StateListDrawable().apply {
                // a text block is not filled under the cursor (a big teal slab): a white border shows the focus
                val focused = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1).coerceAtLeast(1), Color.WHITE)
                    cornerRadius = dp(SMALL_RADIUS.toInt()).toFloat()
                }
                val normal = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(SMALL_RADIUS.toInt()).toFloat()
                }
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), normal)
            }
            setOnClickListener {
                if (text.isNotEmpty()) {
                    showText(this@DetailsActivity, name.toString(), text)
                }
            }
            setOnKeyListener { _, code, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) false
                else when (code) {
                    // the description is three lines, nothing to read by scrolling: down goes straight to the director (the page
                    // brings him into view), without first scrolling the page past him and back
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> (firstDirectorLink ?: firstCastCard)?.requestFocus() ?: false
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        scroll.smoothScrollTo(0, 0)
                        watchButton.requestFocus()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    else -> false
                }
            }
            } else setPadding(0, dp(8), 0, dp(8))
        }
        // the page shows three lines (the height of the grey bars that stand in for it); the whole text opens in a window on a tap
        overviewView.maxLines = 3
        overviewView.ellipsize = android.text.TextUtils.TruncateAt.END
        fun showFullOverview() {
            if (overviewView.text.isNotEmpty()) {
                showText(this@DetailsActivity, name.toString(), overviewView.text)
            }
        }
        if (mobile) overviewView.setOnClickListener { showFullOverview() }
        // the sign that the text opens: a "read in full" link with a chevron under the three lines
        moreRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (rawOverview.isEmpty()) View.GONE else View.VISIBLE
            setOnClickListener { showFullOverview() }
            addView(text("Читать полностью", 16f, TEXT_SECONDARY, bold = true))
            addView(ImageView(this@DetailsActivity).apply {
                setImageDrawable(PathDrawable(MenuIcons.CHEVRON_RIGHT).apply { setColorFilter(TEXT_SECONDARY, android.graphics.PorterDuff.Mode.SRC_IN) })
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
        }
        info.addView(overviewView, LinearLayout.LayoutParams(-1, -2).apply {
            if (mobile) topMargin = dp(8)
            // on the TV the text has 12 dp of padding for the focus border: the border goes out to the left so the text lines up with the title
            else { leftMargin = -dp(12); topMargin = dp(12) }
        })
        if (mobile) info.addView(moreRow, LinearLayout.LayoutParams(-2, dp(32)))
        if (!mobile) {
            // the same on the TV: grey bars for the facts, the description and the cast until the details come, so the page does not jump
            if (rawOverview.isEmpty()) {
                overviewView.visibility = View.GONE
                repeat(3) { info.addView(skeletonBar(-1, dp(18), dp(10))) }
            }
            info.addView(table)
            info.addView(skeletonBar(-1, dp(150), dp(24)))
        }
        // on a phone the description comes first, then the facts under the heading "О фильме"
        if (mobile) {
            info.addView(text("О фильме", 18f, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            info.addView(table)
            // the facts and the description are not there yet: grey bars of the same kind keep the page from jumping
            if (rawOverview.isEmpty()) overviewView.visibility = View.GONE
            if (rawOverview.isEmpty()) info.addView(skeletonBar(dp(140), dp(18), dp(7)), info.indexOfChild(moreRow) + 1)
            if (rawOverview.isEmpty()) repeat(3) { info.addView(skeletonBar(-1, dp(15), dp(8)), info.indexOfChild(overviewView) + 1) }
            // the cast block, as the details will fill it
            info.addView(skeletonBar(-1, dp(150), dp(24)))
        }

        loadDetails(title, name, baseRows, original, table, scroll)
        if (mobile) {
            // the page: the top (banner, buttons, poster) and under it the facts; everything scrolls together
            info.setPadding(dp(16), 0, dp(16), dp(16))
            scroll.clipChildren = false
            scroll.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                addView(stage, LinearLayout.LayoutParams(-1, dp(190)))
                addView(info, LinearLayout.LayoutParams(-1, -2))
            })
            root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        } else {
            // air under the last block (the cast cards): the page ends above the screen edge, not at it
            // 12 dp of room on the left: the focus border of the description (which sticks out to the left) is not cut by the scroll
            info.setPadding(dp(16), 0, 0, dp(32))
            // without this the padding itself clips the border that sticks out to the left
            info.clipToPadding = false
            info.clipChildren = false
            scroll.addView(info)
            root.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply {
                topMargin = dp(444); leftMargin = dp(389 - 16); rightMargin = dp(64)
            })
        }

        setContentView(root)
        if (!mobile) watchButton.requestFocus()
    }

    /** Phone only: the genres and the "year, age, length" lines to the right of the poster, filled when the details arrive. */
    private lateinit var genresLine: TextView
    private lateinit var metaLine: TextView

    /** Phone card: grey placeholders for what the details have not brought yet; they all go when the details arrive or fail. */
    private val skeletons = mutableListOf<View>()

    private fun skeletonBar(width: Int, height: Int, top: Int): View = View(this).apply {
        background = GradientDrawable().apply { setColor(PLATE_FILL); cornerRadius = dp(SMALL_RADIUS.toInt()).toFloat() }
        layoutParams = LinearLayout.LayoutParams(width, height).apply { topMargin = top }
        skeletons.add(this)
    }

    /** True until the details come: the table shows all its rows, an unknown value as a grey bar. */
    private lateinit var moreRow: LinearLayout
    /** The heading of the card: it can be replaced by the Russian title once the details are in. */
    private lateinit var titleView: TextView

    /** The details are in (or failed): a line that stays empty is taken out, so no blank gap is left where the data is missing. */
    private fun dropEmpty(original: TextView, sameTitle: Boolean = false) {
        // the lines by the poster show a dash when TMDB has nothing (an original title equal to the title is no gap: its line goes);
        // a missing description is not marked, the block just is not there
        if (sameTitle) original.visibility = View.GONE
        else {
            original.visibility = View.VISIBLE
            if (original.text.isEmpty()) original.text = DASH
        }
        val noOverview = overviewView.text.isEmpty()
        overviewView.visibility = if (noOverview) View.GONE else View.VISIBLE
        if (::moreRow.isInitialized) moreRow.visibility = if (noOverview) View.GONE else View.VISIBLE
        if (::genresLine.isInitialized) {
            genresLine.visibility = View.VISIBLE
            if (genresLine.text.isEmpty()) genresLine.text = DASH
        }
    }

    /** The rows every card has: a value that is not known is shown as a dash, not left out. */
    private fun withDashes(rows: List<Pair<String, String>>): List<Pair<String, String>> {
        val always = setOf(RELEASE, "Рейтинг", "Длительность", "Возраст", "Жанр", "Страна", "Режиссёр") + (if (currentTitle.tv) setOf("Сезонов") else emptySet())
        // no age rating known: the lowest one, as the owner asked
        return rows.map { (caption, value) -> if (value.isBlank() && caption in always) caption to DASH else caption to value }
    }

    private fun factRows(base: List<Pair<String, String>>): List<Pair<String, String>> =
        base + listOfNotNull("Длительность", if (currentTitle.tv) "Сезонов" else null, "Возраст", "Жанр", "Страна", "Режиссёр").map { it to "" }

    private var tablePending = true

    private val skeletonTexts = mutableListOf<TextView>()

    /** An empty line of text drawn as a grey bar of exactly its own height (a narrower one), so filling it in moves nothing. */
    private fun skeletonText(tv: TextView, width: Int) {
        tv.background = android.graphics.drawable.InsetDrawable(
            GradientDrawable().apply { setColor(PLATE_FILL); cornerRadius = dp(SMALL_RADIUS.toInt()).toFloat() }, 0, dp(3), 0, dp(3))
        tv.layoutParams = LinearLayout.LayoutParams(width, -2)
        skeletonTexts.add(tv)
    }

    private fun hideSkeletons() {
        skeletonTexts.forEach { it.background = null; it.layoutParams = LinearLayout.LayoutParams(-1, -2) }
        skeletonTexts.clear()
        skeletons.forEach { it.visibility = View.GONE }
        skeletons.clear()
        if (::overviewView.isInitialized) overviewView.visibility = View.VISIBLE
    }

    private var bannerSpinnerTask: Runnable? = null

    private fun showBannerSpinner() {
        bannerSpinnerTask?.let { bannerSpinner.removeCallbacks(it) }
        val task = Runnable { bannerSpinner.visibility = View.VISIBLE }
        bannerSpinnerTask = task
        bannerSpinner.postDelayed(task, 200L)
    }

    private fun hideBannerSpinner() {
        bannerSpinnerTask?.let { bannerSpinner.removeCallbacks(it) }
        bannerSpinnerTask = null
        bannerSpinner.visibility = View.GONE
    }

    private fun loadBackdrop(backdropPath: String) {
        currentBackdrop = backdropPath
        showBannerSpinner()
        val started = System.currentTimeMillis()
        Glide.with(this).load("https://image.tmdb.org/t/p/w1280$backdropPath")
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    hideBannerSpinner()
                    return false
                }

                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    hideBannerSpinner()
                    android.util.Log.i("LazyTV", "backdrop $backdropPath: $dataSource, ${System.currentTimeMillis() - started} ms")
                    backdrop.animate().alpha(1f).setDuration(250).start()
                    return false
                }
            }).into(backdrop)
    }

    private fun applyDetails(
        d: Tmdb.Details, name: String, baseRows: List<Pair<String, String>>, original: TextView, table: LinearLayout, scroll: ScrollView,
    ) {
        hideSkeletons()
        tablePending = false
        if (posterPath.isNullOrEmpty() && d.poster.isNotEmpty()) {
            posterPath = d.poster
            posterView.showPoster(d.poster, posterSpinner)
        }
        val backdropPath = d.backdrop.ifEmpty { d.backdrops.firstOrNull().orEmpty() }
        if (backdropPath.isNotEmpty()) {
            if (backdropPath != currentBackdrop) {
                loadBackdrop(backdropPath)
            }
        } else if (currentBackdrop.isEmpty()) {
            hideBannerSpinner()
        }
        fun hasCyrillic(s: String) = s.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' }
        // the list gave the title in its own script (hindi...) while TMDB has the Russian one: that is the heading, for us;
        // the title in the original script goes to the second line
        if (!hasCyrillic(name) && hasCyrillic(d.title) && !d.title.equals(name, true)) titleView.text = d.title
        val shownTitle = titleView.text.toString()
        // a russian original that only repeats the heading is no second line
        val repeatsRussian = d.originalTitle.equals(shownTitle, true) && hasCyrillic(d.originalTitle)
        if (d.originalTitle.isNotEmpty() && !repeatsRussian) original.text = d.originalTitle.uppercase()
        if (overviewView.text.isEmpty() && d.overview.isNotEmpty()) overviewView.text = d.overview
        if (::moreRow.isInitialized) moreRow.visibility = if (overviewView.text.isEmpty()) View.GONE else View.VISIBLE
        if (mobile) {
            genresLine.text = d.genres.joinToString(", ")
            val year = d.date.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) } ?: baseRows.firstOrNull { it.first == RELEASE }?.second.orEmpty()
            // a missing part is left out (the table below has its own row with a dash); a dash only when nothing is known
            metaLine.text = listOf(year, d.age, formatRuntime(d.runtime)).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { DASH }
        }
        // the release date in full ("16 декабря 2026"); the year of the list stays when TMDB has no exact date
        // a film shown in the cinemas again years later gets its own line: the premiere and the cinema run are different dates
        val rows = baseRows.flatMap { (caption, value) ->
            if (caption != RELEASE) listOf(caption to value)
            else listOf(
                caption to formatReleaseDate(d.date).ifEmpty { value },
                "В кино с" to formatReleaseDate(d.cinemaDate).let { if (it.isEmpty()) "" else "$it (повторный прокат)" },
            )
        }
        fillTable(table, withDashes(rows + listOf(
            "Длительность" to (formatRuntime(d.runtime)),
            "Сезонов" to (if (d.seasons > 0) d.seasons.toString() else ""),
            "Следующая серия" to d.nextEpisode,
            "Возраст" to d.age,
            "Жанр" to d.genres.joinToString(", "),
            "Страна" to d.countries.joinToString(", "),
            "Режиссёр" to d.director,
        )), d.directors, scroll)
        val info = table.parent as? LinearLayout
        // directors already stand in the table above as cards: the cast block holds the actors only
        val mergedPeople = d.castPeople
        if (info != null && mergedPeople.isNotEmpty() && info.findViewWithTag<View>("cast") == null) {
            val row = castRow(mergedPeople, scroll)
            row.tag = "cast"
            info.addView(row)
        }
        dropEmpty(original, repeatsRussian)
        galleryPosters = d.posters
        galleryBackdrops = d.allBackdrops
        startBackdrops(d.backdrops)
    }

    /** Fills in what the list does not carry: original title, runtime, age, genres, countries, director, cast. */
    private fun loadDetails(
        title: Title, name: String, baseRows: List<Pair<String, String>>, original: TextView, table: LinearLayout, scroll: ScrollView,
    ) {
        val cached = Tmdb.peekDetails(applicationContext, title.id, title.tv)
        if (cached != null) {
            originalTitle = cached.originalTitle
            applyDetails(cached, name, baseRows, original, table, scroll)
        }
        Thread {
            try {
                val d = cached ?: runCatching { Tmdb.detailsCached(applicationContext, title.id, title.tv) }.getOrNull()
                android.util.Log.i("LazyTV", "card: details ${System.currentTimeMillis() - cardStarted} ms")
                originalTitle = d?.originalTitle.orEmpty()
                if (d == null) {
                    onUi {
                        hideBannerSpinner(); hideSkeletons()
                        // no details: the year of the list is all there is, shown as it is
                        tablePending = false
                        fillTable(table, withDashes(factRows(baseRows)))
                        dropEmpty(original)
                    }
                } else if (cached == null) {
                    onUi { applyDetails(d, name, baseRows, original, table, scroll) }
                }
                // the variants are not shown on the card any more: the "Смотреть" button opens the first of them
                val kept = Favorites.folderOf(applicationContext, title) != null
                variants = runCatching {
                    Sources.searchCached(applicationContext, name, originalTitle, title.year, title.tv, kept) { partial ->
                        variants = partial
                        // a film of the right year is enough to start: the slower sites keep searching for the list
                        if (!title.tv && partial.any { Sources.isExact(it, title.year, listOf(name, originalTitle)) }) quickReady.countDown()
                    }
                }.getOrDefault(emptyList())
                android.util.Log.i("LazyTV", "card: sources ready ${System.currentTimeMillis() - cardStarted} ms, variants ${variants.size}")
                warmUp(name, title)
            } finally {
                detailsReady.countDown()
                quickReady.countDown()
            }
        }.start()
    }

    /** Facts as a table: the caption on the left, the value on the right, a thin line between rows; directors are cards that open PersonActivity. */
    private fun fillTable(
        table: LinearLayout, rows: List<Pair<String, String>>,
        directors: List<Tmdb.Person> = emptyList(), pageScroll: ScrollView? = null,
    ) {
        table.removeAllViews()
        firstDirectorLink = null
        val linkedDirectors = directors.filter { it.id > 0L }
        // until the details come every row of the card stands with its caption and a grey bar for the value (no half-known year)
        rows.filter { it.second.isNotBlank() || tablePending }.forEachIndexed { i, (caption, value) ->
            if (i > 0) table.addView(View(this).apply { setBackgroundColor(0x22FFFFFF) }, LinearLayout.LayoutParams(-1, 1))
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))
            row.addView(text(caption, m(17, 15).toFloat(), TEXT_MUTED), LinearLayout.LayoutParams(dp(m(160, 120)), -2))
            if (value.isBlank()) {
                // a grey bar of the height the filled value will have (one line of the value text; the director is a tile),
                // so the rows do not move when the details come
                val bar = text(" ", 18f, Color.WHITE)
                bar.background = android.graphics.drawable.InsetDrawable(
                    GradientDrawable().apply { setColor(PLATE_FILL); cornerRadius = dp(SMALL_RADIUS.toInt()).toFloat() }, 0, dp(3), 0, dp(3))
                // a background drawable brings its own padding (the 3dp inset): without this the bar is 6dp taller than the filled line
                bar.setPadding(0, 0, 0, 0)
                if (caption == "Режиссёр") bar.minimumHeight = dp(if (mobile) 44 else 40)
                row.addView(bar, LinearLayout.LayoutParams(dp(140), -2))
            } else if (caption == "Режиссёр" && linkedDirectors.isNotEmpty()) {
                val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val cardViews = mutableListOf<View>()
                linkedDirectors.forEachIndexed { idx, dir ->
                    // the same card as the cast (outline, teal under the cursor), but the name only
                    val card = text(dir.name, 16f, Color.WHITE, bold = true).apply {
                        setTextColor(cursorColors(this@DetailsActivity, Color.WHITE))
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(16), dp(8), dp(16), dp(8))
                        isFocusable = true
                        isClickable = true
                        background = personCardBackground(scale)
                        if (mobile) {
                            // on a phone the director is a tile like the bookmark button: the same plate, corner and height
                            background = StateListDrawable().apply {
                                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(TOUCH_FILL); cornerRadius = dp(TITLE_CARD_RADIUS.toInt()).toFloat() })
                                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(TOUCH_FILL); cornerRadius = dp(TITLE_CARD_RADIUS.toInt()).toFloat() })
                                addState(intArrayOf(), GradientDrawable().apply { setColor(PLATE_FILL); cornerRadius = dp(TITLE_CARD_RADIUS.toInt()).toFloat() })
                            }
                            typeface = Typeface.DEFAULT
                            minimumHeight = dp(44)
                        }
                        setOnClickListener {
                            startActivity(Intent(this@DetailsActivity, PersonActivity::class.java)
                                .putExtra("id", dir.id).putExtra("name", dir.name))
                        }
                        setOnKeyListener { _, code, event ->
                            if (event.action != android.view.KeyEvent.ACTION_DOWN) false
                            else when (code) {
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    val step = if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                                    cardViews.getOrNull(idx + step)?.requestFocus()
                                    true
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                    // the description stands above the facts: up goes to it, without it to the buttons
                                    if (::overviewView.isInitialized && overviewView.text.isNotEmpty()) overviewView.requestFocus()
                                    else {
                                        pageScroll?.smoothScrollTo(0, 0)
                                        watchButton.requestFocus()
                                    }
                                    true
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> firstCastCard?.requestFocus() ?: false
                                else -> false
                            }
                        }
                    }
                    if (firstDirectorLink == null) firstDirectorLink = card
                    cardViews.add(card)
                    cards.addView(card, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
                }
                val cardsScroll = android.widget.HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    isFocusable = false
                    addView(cards)
                }
                row.addView(cardsScroll, LinearLayout.LayoutParams(0, -2, 1f))
            } else if (caption == "Рейтинг" && value != DASH) {
                val ratingVal = value.replace(',', '.').toDoubleOrNull()
                val color = if (ratingVal != null && ratingVal < 6.0) 0x99FFFFFF.toInt() else Color.WHITE
                row.addView(text("★ $value", 18f, color), LinearLayout.LayoutParams(0, -2, 1f))
            } else {
                val valueView = text(value, 18f, Color.WHITE)
                row.addView(valueView, LinearLayout.LayoutParams(0, -2, 1f))
            }
            table.addView(row)
        }
    }

    private val detailsReady = java.util.concurrent.CountDownLatch(1)
    /** Opens when all sources answered or the first film of the right year is found: "Смотреть" does not wait longer. */
    private val quickReady = java.util.concurrent.CountDownLatch(1)
    private val cardStarted = System.currentTimeMillis()

    /** Waits for the sources: until the right film is found, or all answered, or (with something found) six seconds passed. */
    private fun awaitSources() {
        val limit = cardStarted + 30_000L
        while (!quickReady.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            val now = System.currentTimeMillis()
            // a series shows its list of sources earlier: the dialog lets the viewer pick, the films open the right one on their own
            val patience = if (currentTitle.tv) 3_000L else 6_000L
            if (now > limit || (variants.isNotEmpty() && now - cardStarted > patience)) return
        }
    }
    @Volatile private var variants: List<Variant> = emptyList()
    @Volatile
    private var originalTitle = ""
    private var currentName = ""
    private lateinit var currentTitle: Title
    private var galleryPosters: List<String> = emptyList()
    private var galleryBackdrops: List<String> = emptyList()
    private var posterPath: String? = null
    private lateinit var posterView: ImageView
    private lateinit var posterSpinner: Spinner
    private lateinit var backdrop: ImageView
    private lateinit var bannerSpinner: Spinner
    private var currentBackdrop = ""
    private var firstDirectorLink: View? = null
    private var firstCastCard: View? = null
    private lateinit var overviewView: TextView

    /** A tap on the poster: the same layer as the gallery, with the posters of the title (the current one first). */
    private fun openPosters() {
        val list = (listOf(posterPath.orEmpty()) + galleryPosters).filter { it.isNotEmpty() }.distinct()
        if (list.isEmpty()) return
        startActivity(
            Intent(this, GalleryActivity::class.java)
                .putExtra("name", currentName.ifEmpty { currentTitle.name })
                .putExtra("posters", true)
                .putStringArrayListExtra("backdrops", ArrayList(list))
        )
    }

    private fun openGallery() {
        val title = currentTitle
        val name = currentName.ifEmpty { title.name }
        startActivity(
            Intent(this, GalleryActivity::class.java)
                .putExtra("id", title.id)
                .putExtra("tv", title.tv)
                .putExtra("name", name)
                .putStringArrayListExtra("backdrops", ArrayList(galleryBackdrops))
        )
    }

    // Backdrop covers: up to 5 TMDB backdrops cycling every 20 seconds, next cover preloaded in advance
    private val backdropHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val backdrops = mutableListOf<String>()
    private var backdropIndex = 0
    private var backdropsStarted = false
    private val backdropTick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || backdrops.size < 2) return
            showBackdropAt((backdropIndex + 1) % backdrops.size)
            backdropHandler.postDelayed(this, 20_000L)
        }
    }

    /** Swipe on the phone banner: a step to the next or the previous frame; the 20 second timer starts over. */
    private fun stepBackdrop(step: Int) {
        if (backdrops.size < 2) return
        showBackdropAt((backdropIndex + step + backdrops.size) % backdrops.size)
        backdropHandler.removeCallbacks(backdropTick)
        backdropHandler.postDelayed(backdropTick, 20_000L)
    }

    private fun showBackdropAt(next: Int) {
        run {
            val url = backdrops[next]
            Glide.with(this@DetailsActivity).load(url).centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                        backdrops.remove(url)
                        if (backdropIndex >= backdrops.size) backdropIndex = 0
                        return true
                    }

                    override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                        backdropIndex = backdrops.indexOf(url).coerceAtLeast(0)
                        updateBannerIndicators()
                        return false
                    }
                }).into(backdrop)
            val ahead = backdrops[(next + 1) % backdrops.size]
            Glide.with(applicationContext).load(ahead).preload()
        }
    }

    private fun startBackdropSlideshow(items: List<String>) {
        if (backdropsStarted) return
        val all = items.map {
            if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w1280$it"
        }.distinct().take(5)
        if (all.size < 2) return
        backdropsStarted = true
        backdrops.clear()
        backdrops.addAll(all)
        backdropIndex = 0
        updateBannerIndicators()
        Glide.with(applicationContext).load(backdrops[1]).preload()
        backdropHandler.postDelayed(backdropTick, 20_000L)
    }

    private fun startBackdrops(items: List<String>) = startBackdropSlideshow(items)

    private lateinit var bannerCounter: TextView
    private lateinit var bannerDots: LinearLayout

    /** Phone banner: "2 / 5" in the corner and the dots at the bottom follow the frame on show. */
    private fun updateBannerIndicators() {
        if (!::bannerCounter.isInitialized || backdrops.size < 2) return
        bannerCounter.text = "${backdropIndex + 1} / ${backdrops.size}"
        bannerCounter.visibility = View.VISIBLE
        bannerDots.visibility = View.VISIBLE
        bannerDots.removeAllViews()
        for (i in backdrops.indices) {
            bannerDots.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == backdropIndex) Color.WHITE else 0x66FFFFFF) }
            }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { leftMargin = dp(4); rightMargin = dp(4) })
        }
    }

    override fun onResume() {
        super.onResume()
        if (backdropsStarted && backdrops.size >= 2) {
            backdropHandler.removeCallbacks(backdropTick)
            backdropHandler.postDelayed(backdropTick, 20_000L)
        }
    }

    /** TV: the request in onCreate comes before the window is shown and can be lost; with no focus anywhere the cursor goes to "Смотреть". */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isMobileUi() && currentFocus == null && ::watchButton.isInitialized) watchButton.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        backdropHandler.removeCallbacks(backdropTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        hideBannerSpinner()
        backdropHandler.removeCallbacksAndMessages(null)
    }

    /** Cast of the film: a caption and a carousel of round photos with the name and role; OK opens PersonActivity. */
    private fun castRow(people: List<Tmdb.Person>, pageScroll: ScrollView): View {
        val block = LinearLayout(this)
        block.orientation = LinearLayout.VERTICAL
        // on a phone the heading is the same as "О фильме" above it (18) and stands close under the facts; the TV keeps its large one
        block.addView(
            if (mobile) text("В ролях", 18f, bold = true).apply { setPadding(0, 0, 0, dp(10)) }
            else text("В ролях", 22f, bold = true).apply { setPadding(0, dp(8), 0, dp(12)) },
            LinearLayout.LayoutParams(-1, -2).apply { if (mobile) topMargin = dp(8) },
        )
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val photoSize = dp(96)
        firstCastCard = null
        people.forEachIndexed { index, person ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                // tight: the text column is as wide as the photo plus a little, the plate holds no more air than that
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isFocusable = true
                isClickable = true
                background = personCardBackground(scale, outline = false)  // the cast cards: the plate without the resting outline (a trial)
                setOnClickListener {
                    startActivity(Intent(this@DetailsActivity, PersonActivity::class.java)
                        .putExtra("id", person.id).putExtra("name", person.name))
                }
                setOnKeyListener { _, code, event ->
                    if (event.action != android.view.KeyEvent.ACTION_DOWN) false
                    else when (code) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val step = if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                            row.getChildAt(index + step)?.requestFocus()
                            true
                        }
                        // the cast is the last block: down stays here (the page would otherwise creep a little further by itself)
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> true
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            // only the focus moves: the ScrollView itself brings the focused director into view by the smallest step
                            // (a scroll to the very top flew the page past the director)
                            (firstDirectorLink ?: (if (::overviewView.isInitialized && overviewView.text.isNotEmpty()) overviewView else null) ?: watchButton).requestFocus()
                            true
                        }
                        else -> false
                    }
                }
            }
            if (firstCastCard == null) firstCastCard = card
            // on the TV the cursor reaching the cast scrolls the page to its very end at once, not by the step that just shows the card
            if (!mobile) card.setOnFocusChangeListener { _, has ->
                if (has) pageScroll.post {
                    val content = pageScroll.getChildAt(0) ?: return@post
                    pageScroll.smoothScrollTo(0, (content.height - pageScroll.height).coerceAtLeast(0))
                }
            }
            // initials on a calm teal circle sit under the photo: they show while it loads and for an actor who has none
            val initials = person.name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
            val photo = FrameLayout(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(PLACEHOLDER_FROM, PLACEHOLDER_TO))
                    .apply { shape = GradientDrawable.OVAL }
                // the round outline of the background cuts the photo into a circle
                clipToOutline = true
                addView(text(initials, 34f, TEXT_SECONDARY).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(-1, -1))
                val image = ImageView(this@DetailsActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(image, FrameLayout.LayoutParams(-1, -1))
                if (person.photo.isNotEmpty()) Glide.with(image).load("https://image.tmdb.org/t/p/w185${person.photo}")
                    .transition(DrawableTransitionOptions.withCrossFade()).into(image)
            }
            card.addView(photo, LinearLayout.LayoutParams(photoSize, photoSize))
            // one line each: with two-line names and roles the tallest card made all the others tall, with empty space under the text
            // the texts take the caption colour of the cursor while the card is focused (they follow the state of the card)
            card.addView(text(person.name, 16f, bold = true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                isDuplicateParentStateEnabled = true
                setTextColor(cursorColors(this@DetailsActivity, Color.WHITE))
            }, LinearLayout.LayoutParams(dp(112), -2).apply { topMargin = dp(8) })
            if (person.role.isNotEmpty()) card.addView(text(person.role, 13f, TEXT_MUTED).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                isDuplicateParentStateEnabled = true
                setTextColor(cursorColors(this@DetailsActivity, TEXT_MUTED))
            }, LinearLayout.LayoutParams(dp(112), -2).apply { topMargin = dp(4) })
            // all the cards of the row are as tall as the tallest one
            row.addView(card, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = dp(12) })
        }
        val scroll = android.widget.HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.clipToPadding = false
        scroll.addView(row)
        block.addView(scroll)
        return block
    }

    /** Starting to watch (a site variant or a torrent) puts the title into the history. */
    private lateinit var watchButton: TextView
    private lateinit var watchSpinner: Spinner
    private var opening = false

    /** Spins an overlay arc over "Смотреть" instead of changing its caption so the action bar never shifts width. */
    private fun setWatchLoading(loading: Boolean) {
        opening = loading
        watchSpinner.visibility = if (loading) View.VISIBLE else View.GONE
        watchButton.setTextColor(if (loading) android.content.res.ColorStateList.valueOf(0x44FFFFFF) else cursorColors(this, Color.WHITE))
        watchButton.compoundDrawables[0]?.alpha = if (loading) 68 else 255
    }

    private var lastSourceChoiceAt = 0L

    /** Opens a source selection dialog on long-pressing "Смотреть". */
    private fun chooseSource(name: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSourceChoiceAt < 1000L || opening) return
        lastSourceChoiceAt = now
        if (detailsReady.count > 0) {
            setWatchLoading(true)
            Thread {
                detailsReady.await(30, java.util.concurrent.TimeUnit.SECONDS)
                onUi {
                    setWatchLoading(false)
                    showSourceDialog(name)
                }
            }.start()
            return
        }
        showSourceDialog(name)
    }

    private fun showSourceDialog(name: String, allYears: Boolean = false) {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        val (shown, hidden) = Sources.forYear(variants, currentTitle.year, currentTitle.tv, allYears, listOf(name, originalTitle))

        if (allYears) {
            items.add("‹ Назад")
            actions.add { showSourceDialog(name, false) }
        }
        shown.forEach { v ->
            val label = buildString {
                append(v.source)
                if (v.title.isNotEmpty() && !v.title.equals(name, ignoreCase = true)) {
                    append(", ")
                    append(v.title)
                }
                if (v.note.isNotEmpty()) {
                    append(", ")
                    append(v.note)
                }
            }
            items.add(label)
            actions.add { openVariant(v, false) }
        }

        if (hidden > 0) {
            items.add("Другие годы и части ($hidden)")
            actions.add { showSourceDialog(name, true) }
        }
        // the torrents belong to the main list only, not to the expanded "other years" one
        if (!allYears) {
            items.add("Торренты (TorrServe)")
            actions.add { openTorrents(name) }
        }

        showChoice(this, "Выбор источника", items, -1) { which -> actions.getOrNull(which)?.invoke() }
    }

    /** Waits for the background site search if the user clicks "Смотреть" immediately on opening the card. */
    private fun startWatching(name: String) {
        if (opening) return
        if (Settings.string(this, Settings.SOURCE_MODE, "sites") == "torrents") {
            openTorrents(name)
            return
        }
        if (quickReady.count > 0) {
            setWatchLoading(true)
            Thread {
                awaitSources()
                onUi {
                    setWatchLoading(false)
                    openDefaultVariant(name)
                }
            }.start()
            return
        }
        openDefaultVariant(name)
    }

    /**
     * A film opens its first release; a series with several releases (seasons, sources) asks which one first, because the
     * first one is not necessarily the season the viewer wants.
     */
    private fun openDefaultVariant(name: String) {
        if (currentTitle.tv && variants.size > 1) {
            showSourceDialog(name)
            return
        }
        val first = Sources.best(variants, currentTitle.year, currentTitle.tv, listOf(name, originalTitle))
        // a film of another year only (a part or a remake of the same name): the user picks from the list instead of getting a guess
        if (first != null && !currentTitle.tv && currentTitle.year.isNotEmpty() && Sources.yearRank(first, currentTitle.year, listOf(name, originalTitle)) == 2) {
            Toast.makeText(this, "Точной копии нет, показаны похожие", Toast.LENGTH_LONG).show()
            showSourceDialog(name)
            return
        }
        first?.let { openVariant(it, true) } ?: openTorrents(name)
    }

    private fun excludeVariant(v: Variant) {
        variants = variants.filterNot { it.sourceId == v.sourceId && it.ref == v.ref }
        Sources.removeCachedVariant(
            applicationContext, currentName.ifEmpty { currentTitle.name }, originalTitle, currentTitle.year, currentTitle.tv, v
        )
    }

    /**
     * The release is asked for its videos first: a film with one video starts in the player straight from this card,
     * and only a release with several (a series) opens the episode page. No empty page flashes in between.
     */
    private fun openVariant(v: Variant, fromDefault: Boolean = false) {
        if (opening) return
        setWatchLoading(true)
        Thread {
            // a silent site (white list) must not hold the button: after 6 s the next source is tried; the slow request goes on in
            // the background and fills the cache, so the record is not thrown away for good
            val answer = java.util.concurrent.atomic.AtomicReference<List<Episode>?>(null)
            val asking = Thread { answer.set(runCatching { Sources.episodesCached(v) }.getOrNull()) }
            asking.start()
            asking.join(6000)
            val timedOut = asking.isAlive
            val episodes = answer.get()
            onUi {
                setWatchLoading(false)
                if (episodes.isNullOrEmpty()) {
                    if (timedOut) variants = variants.filterNot { it.sourceId == v.sourceId && it.ref == v.ref } else excludeVariant(v)
                    Toast.makeText(
                        this, if (timedOut) "Источник ${v.source} не отвечает" else "В источнике ${v.source} нет видео, запись исключена",
                        Toast.LENGTH_SHORT,
                    ).show()
                    val targetName = currentName.ifEmpty { currentTitle.name }
                    if (fromDefault) {
                        openDefaultVariant(targetName)
                    } else {
                        showSourceDialog(targetName)
                    }
                    return@onUi
                }
                History.add(this, historyTitle())
                // only a film with one video starts at once; a series always shows its episodes to choose from
                if (episodes.size == 1 && !currentTitle.tv) {
                    val wanted = listOf(currentName.ifEmpty { currentTitle.name }, originalTitle)
                    PlaybackFallback.arm(
                        currentTitle.id,
                        variants.filter { it.sourceId != v.sourceId || it.ref != v.ref }
                            .filter { Sources.yearRank(it, currentTitle.year, wanted) != 2 },
                        currentName.ifEmpty { currentTitle.name }, originalTitle, currentTitle.year, posterPath,
                    )
                    playEpisode(
                        this, v, episodes, 0, choose = false,
                        mediaTitle = currentName.ifEmpty { currentTitle.name },
                        originalTitle = originalTitle, year = currentTitle.year, tv = currentTitle.tv, poster = posterPath,
                        tmdbId = currentTitle.id,
                    )
                } else startActivity(
                    Intent(this, EpisodesActivity::class.java).putExtra("source", v.source).putExtra("sourceId", v.sourceId)
                        .putExtra("title", v.title).putExtra("note", v.note).putExtra("ref", v.ref)
                        .putExtra("alt", v.alt).putExtra("description", v.description)
                        .putExtra("mediaTitle", currentName.ifEmpty { currentTitle.name })
                        .putExtra("originalTitle", originalTitle)
                        .putExtra("year", currentTitle.year).putExtra("tv", currentTitle.tv)
                        .putExtra("poster", posterPath)
                        .putExtra("tmdbId", currentTitle.id)
                )
            }
        }.start()
    }

    /**
     * Ahead of the "Смотреть" press: a film asks its first source for the video list (the press then starts at once), and when
     * there is no site for it, or the torrents are chosen in the settings, the torrent search starts too.
     */
    private fun warmUp(name: String, title: Title) {
        val first = Sources.best(variants, title.year, title.tv, listOf(name, originalTitle))
            ?.takeIf { Sources.yearRank(it, title.year, listOf(name, originalTitle)) != 2 || title.tv }
        if (first != null && !title.tv) Thread {
            val eps = runCatching { Sources.episodesCached(first) }.getOrNull()
            if (eps != null && eps.isEmpty()) {
                onUi { excludeVariant(first) }
            }
        }.start()
        if (variants.isEmpty() || Settings.string(this, Settings.SOURCE_MODE, "sites") == "torrents") startTorrentSearch(name)
    }

    private fun startTorrentSearch(name: String) {
        val title = currentTitle
        val query = if (title.tv || title.year.isEmpty()) name else "$name ${title.year}"
        TorrentPrefetch.start(applicationContext, query, name, originalTitle)
    }

    private fun openTorrents(name: String) {
        History.add(this, historyTitle())
        startActivity(
            Intent(this, TorrentsActivity::class.java).putExtra("name", name).putExtra("original", originalTitle)
                .putExtra("year", intent.getStringExtra("year")).putExtra("tv", intent.getBooleanExtra("tv", false))
                .putExtra("poster", posterPath)
                .putExtra("tmdbId", currentTitle.id)
                .putExtra("rating", currentTitle.rating).putExtra("genre", currentTitle.genre)
        )
    }

    /** The history record: with the poster and backdrop found through TMDB, when the list did not pass them to the card. */
    private fun historyTitle(): Title {
        val title = titleFrom(intent.extras ?: Bundle())
        val withPoster = if (title.poster == null && posterPath != null) title.copy(poster = posterPath) else title
        return if (withPoster.backdrop == null && currentBackdrop.isNotEmpty()) withPoster.copy(backdrop = currentBackdrop) else withPoster
    }

    /** Opens the trailer found through TMDB; falls back to a YouTube search when there is none. */
    private fun openTrailer(title: Title) {
        Thread {
            val key = runCatching { Tmdb.trailerKey(applicationContext, title.id, title.tv) }.getOrNull()
            val link = if (key != null) "https://www.youtube.com/watch?v=$key"
            else "https://www.youtube.com/results?search_query=" + Uri.encode("${title.name} трейлер")
            onUi {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                    .onFailure { toast("Не найдено приложение для открытия YouTube") }
            }
        }.start()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        /** The caption of the date row of the card's table. */
        const val RELEASE = "Дата выхода"
    }
}

/** What stands where a fact of the card is not known. */
private const val DASH = "–"

