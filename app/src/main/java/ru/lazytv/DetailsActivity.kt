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
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

/**
 * Card of a title in the LazyMedia layout (design/lmd-card.png): green banner, poster on the left,
 * a darker action bar, then title, facts and description. Back returns to the lists.
 */
class DetailsActivity : Activity() {
    /** The layout is drawn for a 1280-wide screen and scaled to the real width, so it looks the same on any TV. */
    private val scale get() = resources.displayMetrics.widthPixels / 1280f

    private fun dp(value: Int) = (value * scale).toInt()

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
    private fun button(icon: String, label: String, action: () -> Unit) = TextView(this).apply {
        text = label.uppercase()
        setSingleLine()
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * scale)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        setCompoundDrawables(PathDrawable(icon).apply { setBounds(0, 0, dp(22), dp(22)) }, null, null, null)
        compoundDrawablePadding = dp(8)
        isFocusable = true
        isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setColor(0xFF2A4A39.toInt())
                cornerRadius = dp(2).toFloat()
            })
        }
        setOnClickListener {
            // a quick double press on the card lands here: the first moments ignore clicks, so the video does not start by itself
            if (android.os.SystemClock.elapsedRealtime() - openedAt > 500) action()
        }
        layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(8) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("name").orEmpty()
        val title = titleFrom(intent.extras ?: Bundle())
        posterPath = intent.getStringExtra("poster")
        // "recently opened": the card is put into the list when it is opened, not only when watching starts
        if (savedInstanceState == null && (title.id > 0L || name.isNotEmpty())) History.add(this, historyTitle())
        val rawRating = intent.getStringExtra("rating").orEmpty().replace(',', '.')
        val shownRating = if ((rawRating.toDoubleOrNull() ?: 0.0) > 0.0) rawRating else ""
        val baseRows = listOf(
            "Тип" to if (intent.getBooleanExtra("tv", false)) "Сериал" else "Фильм",
            "Год" to intent.getStringExtra("year").orEmpty(),
            "Рейтинг" to shownRating,
        )

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF101A14.toInt())

        // a calm dark band; the frame of the film (TMDB backdrop) fades in under a dark veil once it is known
        val banner = FrameLayout(this)
        banner.setBackgroundColor(0xFF15251B.toInt())
        // the stub stands under the frame from the start: the spinner turns on it, and the frame fades in over it
        banner.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.no_backdrop) },
            FrameLayout.LayoutParams(-1, -1))
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        banner.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        // a light dim on the frame: the white logo and the buttons stay readable on a bright still
        banner.addView(View(this).apply { setBackgroundColor(0x40000000) }, FrameLayout.LayoutParams(-1, -1))
        bannerSpinner = Spinner(this, dp(44))
        // the details are already known (prefetched in the list): the frame comes from the cache, no spinner needed
        if (Tmdb.isCached(this, title.id, title.tv)) bannerSpinner.visibility = View.GONE
        banner.addView(bannerSpinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(banner, FrameLayout.LayoutParams(-1, dp(360)))

        val appName = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.FIT_END
            contentDescription = "Lazy TV"
        }
        root.addView(appName, FrameLayout.LayoutParams(dp(250), dp(86), Gravity.END or Gravity.TOP).apply {
            topMargin = dp(24); rightMargin = dp(64)
        })

        // the back stands on the frame: a crisp translucent dark circle keeps the arrow readable on any still
        fun softDisc(fill: Int) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(fill)
        }
        // the way back for a remote with no Back key or a mouse: a round button in the corner, same as the Back key
        val back = ImageView(this).apply {
            setImageDrawable(PathDrawable(MenuIcons.BACK).apply { setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN) })
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), softDisc(0xFF2F7D57.toInt()))
                addState(intArrayOf(), softDisc(0x99000000.toInt()))
            }
            isFocusable = true
            isClickable = true
            id = View.generateViewId()
            contentDescription = "Назад"
            setOnClickListener { finish() }
        }
        root.addView(back, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.TOP).apply {
            topMargin = dp(32); leftMargin = dp(64)
        })

        val bar = LinearLayout(this)
        bar.setBackgroundColor(0xFF1B2E23.toInt())
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(389), 0, dp(24), 0)
        // watching opens the first variant found on the video sites; without any it falls back to torrents
        watchButton = button(MenuIcons.PLAY, "Смотреть") { startWatching(name) }.apply { id = View.generateViewId() }
        back.nextFocusDownId = watchButton.id
        watchSpinner = Spinner(this, dp(24), Color.WHITE).apply {
            visibility = View.GONE
            isFocusable = false
            isClickable = false
        }
        val watchSlot = FrameLayout(this).apply {
            addView(watchButton, FrameLayout.LayoutParams(-2, dp(44)))
            addView(watchSpinner, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        bar.addView(watchSlot, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(8) })
        lateinit var favorite: TextView
        // filled bookmark: the title is saved in a folder; outline: it is not
        fun bookmarkIcon() = PathDrawable(if (Favorites.folderOf(this, title) != null) MenuIcons.BOOKMARK else MenuIcons.BOOKMARK_BORDER)
            .apply { setBounds(0, 0, dp(22), dp(22)) }
        // one bookmark: the title is either in the favorites or not, no folders to choose from
        favorite = button(MenuIcons.BOOKMARK, "Закладки") {
            val saved = Favorites.folderOf(this, title) != null
            Favorites.set(this, title, if (saved) null else Folder.FAVORITES)
            favorite.setCompoundDrawables(bookmarkIcon(), null, null, null)
            toast(if (saved) "Убрано из закладок" else "В закладках")
        }
        favorite.setCompoundDrawables(bookmarkIcon(), null, null, null)
        bar.addView(favorite)
        // "watched": filled while marked; the lists show a marked title dimmed so it does not draw the eye
        lateinit var seen: TextView
        fun seenIcon() = PathDrawable(if (Watched.has(this, title)) MenuIcons.CHECK else MenuIcons.CHECK_BORDER)
            .apply { setBounds(0, 0, dp(22), dp(22)) }
        seen = button(MenuIcons.CHECK_BORDER, "Просмотрено") {
            Watched.set(this, title, !Watched.has(this, title))
            seen.setCompoundDrawables(seenIcon(), null, null, null)
            toast(if (Watched.has(this, title)) "Отмечено просмотренным" else "Отметка снята")
        }
        seen.setCompoundDrawables(seenIcon(), null, null, null)
        bar.addView(seen)
        val torrentButton = button(MenuIcons.DOWNLOAD, "Торрент") { openTorrents(name) }
        val trailerButton = button(MenuIcons.TRAILER, "Трейлер на YouTube") { openTrailer(title) }.apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = 0
        }
        bar.addView(torrentButton)
        bar.addView(trailerButton)
        // left and right walk the buttons of the bar: at the right end there is nothing to go to, the focus stays;
        // at the left end the key goes on to the back button as usual
        val barButtons = listOf(watchButton, favorite, seen, torrentButton, trailerButton)
        barButtons.forEachIndexed { index, btn ->
            btn.setOnKeyListener { _, code, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) false
                else if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    val step = if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    val next = barButtons.getOrNull(index + step)
                    if (next != null) next.requestFocus()
                    next != null || step > 0
                } else if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    val target = firstDirectorLink ?: firstCastCard
                    target?.requestFocus() ?: false
                } else false
            }
        }
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(360) })

        posterView = ImageView(this)
        posterView.scaleType = ImageView.ScaleType.CENTER_CROP
        posterView.setBackgroundColor(0xFF1B2E23.toInt())
        posterSpinner = Spinner(this, dp(40))
        posterView.showPoster(posterPath, posterSpinner)
        root.addView(FrameLayout(this).apply {
            addView(posterView, FrameLayout.LayoutParams(-1, -1))
            addView(posterSpinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }, FrameLayout.LayoutParams(dp(170), dp(256)).apply {
            leftMargin = dp(176); topMargin = dp(228)
        })

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.addView(text(name, 34f))
        val original = text("", 16f, 0xFFAFC4B6.toInt())
        val table = LinearLayout(this)
        table.orientation = LinearLayout.VERTICAL
        table.setPadding(0, dp(14), 0, dp(14))
        fillTable(table, baseRows)
        info.addView(original)
        info.addView(table)
        info.addView(text(intent.getStringExtra("overview").orEmpty(), 16f, 0xFFDCE8E0.toInt()).apply {
            setLineSpacing(0f, 1.2f)
        })
        val scroll = ScrollView(this)
        scroll.isFocusable = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(info)
        loadDetails(title, name, baseRows, original, table, scroll)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = dp(444); leftMargin = dp(389); rightMargin = dp(64)
        })

        setContentView(root)
        watchButton.requestFocus()
    }

    /** Fills in what the list does not carry: original title, runtime, age, genres, countries, director, cast. */
    private fun loadDetails(
        title: Title, name: String, baseRows: List<Pair<String, String>>, original: TextView, table: LinearLayout, scroll: ScrollView,
    ) {
        Thread {
            try {
                val d = runCatching { Tmdb.detailsCached(applicationContext, title.id, title.tv) }.getOrNull()
                originalTitle = d?.originalTitle.orEmpty()
                // the variants are not shown on the card any more: the "Смотреть" button opens the first of them
                variants = runCatching { Sources.searchCached(applicationContext, name, originalTitle, title.year) }.getOrDefault(emptyList())
                if (d == null) {
                    onUi { bannerSpinner.visibility = View.GONE }
                    return@Thread
                }
                onUi {
                    if (posterPath.isNullOrEmpty() && d.poster.isNotEmpty()) {
                        posterPath = d.poster
                        posterView.showPoster(d.poster, posterSpinner)
                    }
                    if (d.backdrop.isNotEmpty()) {
                        // the spinner is already turning: it stays until the frame is there, and goes away when there is none
                        Glide.with(this).load("https://image.tmdb.org/t/p/original${d.backdrop}")
                            .listener(object : RequestListener<Drawable> {
                                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                                    bannerSpinner.visibility = View.GONE
                                    return false
                                }

                                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                                    bannerSpinner.visibility = View.GONE
                                    backdrop.animate().alpha(1f).setDuration(250).start()
                                    return false
                                }
                            }).into(backdrop)
                    } else bannerSpinner.visibility = View.GONE
                    if (d.originalTitle.isNotEmpty() && !d.originalTitle.equals(name, true)) original.text = d.originalTitle.uppercase()
                    fillTable(table, baseRows + listOf(
                        "Длительность" to (formatRuntime(d.runtime)),
                        "Сезонов" to (if (d.seasons > 0) d.seasons.toString() else ""),
                        "Следующая серия" to d.nextEpisode,
                        "Возраст" to d.age,
                        "Жанр" to d.genres.joinToString(", "),
                        "Страна" to d.countries.joinToString(", "),
                        "Режиссёр" to d.director,
                    ), d.directors, scroll)
                    val info = table.parent as? LinearLayout
                    // directors already stand in the table above as links: the cast block holds the actors only
                    val mergedPeople = d.castPeople
                    if (info != null && mergedPeople.isNotEmpty() && info.findViewWithTag<View>("cast") == null) {
                        val row = castRow(mergedPeople, false, scroll)
                        row.tag = "cast"
                        info.addView(row)
                    }
                }
            } finally {
                detailsReady.countDown()
            }
        }.start()
    }

    /** Facts as a table: the caption on the left, the value on the right, a thin line between rows; directors link to PersonActivity. */
    private fun fillTable(
        table: LinearLayout, rows: List<Pair<String, String>>,
        directors: List<Tmdb.Person> = emptyList(), pageScroll: ScrollView? = null,
    ) {
        table.removeAllViews()
        firstDirectorLink = null
        val linkedDirectors = directors.filter { it.id > 0L }
        rows.filter { it.second.isNotBlank() }.forEachIndexed { i, (caption, value) ->
            if (i > 0) table.addView(View(this).apply { setBackgroundColor(0x22FFFFFF) }, LinearLayout.LayoutParams(-1, 1))
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))
            row.addView(text(caption, 15f, 0xFF8FB39D.toInt()), LinearLayout.LayoutParams(dp(150), -2))
            if (caption == "Режиссёр" && linkedDirectors.isNotEmpty()) {
                val linksRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val linkViews = mutableListOf<View>()
                linkedDirectors.forEachIndexed { idx, dir ->
                    val link = TextView(this).apply {
                        text = dir.name
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * scale)
                        setTextColor(android.content.res.ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                            intArrayOf(Color.WHITE, 0xFFB9F6CA.toInt()),
                        ))
                        setPadding(dp(10), dp(3), dp(10), dp(3))
                        background = focusShape(scale, 0x26FFFFFF, radius = 4f)
                        isFocusable = true
                        isClickable = true
                        setOnClickListener {
                            startActivity(Intent(this@DetailsActivity, PersonActivity::class.java)
                                .putExtra("id", dir.id).putExtra("name", dir.name))
                        }
                        setOnKeyListener { _, code, event ->
                            if (event.action != android.view.KeyEvent.ACTION_DOWN) false
                            else when (code) {
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    val step = if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                                    linkViews.getOrNull(idx + step)?.requestFocus()
                                    true
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                    pageScroll?.smoothScrollTo(0, 0)
                                    watchButton.requestFocus()
                                    true
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    firstCastCard?.requestFocus() ?: false
                                }
                                else -> false
                            }
                        }
                    }
                    if (firstDirectorLink == null) firstDirectorLink = link
                    linkViews.add(link)
                    linksRow.addView(link, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
                }
                val linksScroll = android.widget.HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    isFocusable = false
                    addView(linksRow)
                }
                row.addView(linksScroll, LinearLayout.LayoutParams(0, -2, 1f))
            } else {
                row.addView(text(value, 16f, 0xFFDCE8E0.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
            }
            table.addView(row)
        }
    }

    private val detailsReady = java.util.concurrent.CountDownLatch(1)
    @Volatile
    private var variants: List<Variant> = emptyList()
    @Volatile
    private var originalTitle = ""
    private var posterPath: String? = null
    private lateinit var posterView: ImageView
    private lateinit var posterSpinner: Spinner
    private lateinit var backdrop: ImageView
    private lateinit var bannerSpinner: Spinner
    private var firstDirectorLink: View? = null
    private var firstCastCard: View? = null

    /** Cast and directors of the film: a caption and a carousel of round photos with the name and role; OK opens PersonActivity. */
    private fun castRow(people: List<Tmdb.Person>, hasDirectors: Boolean, pageScroll: ScrollView): View {
        val block = LinearLayout(this)
        block.orientation = LinearLayout.VERTICAL
        block.addView(text(if (hasDirectors) "РЕЖИССЁР И В РОЛЯХ" else "В РОЛЯХ", 18f).apply { setPadding(0, dp(14), 0, dp(8)) })
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val photoSize = dp(92)
        firstCastCard = null
        people.forEachIndexed { index, person ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isFocusable = true
                isClickable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                        setColor(0xFF2F7D57.toInt()); cornerRadius = dp(6).toFloat()
                    })
                    addState(intArrayOf(), GradientDrawable().apply {
                        setColor(0x33000000); cornerRadius = dp(6).toFloat()
                    })
                }
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
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            // ScrollView does not scroll back when focus leaves it upwards, so bring the facts back into view
                            pageScroll.smoothScrollTo(0, 0)
                            (firstDirectorLink ?: watchButton).requestFocus()
                            true
                        }
                        else -> false
                    }
                }
            }
            if (firstCastCard == null) firstCastCard = card
            // initials on a calm green circle sit under the photo: they show while it loads and for an actor who has none
            val initials = person.name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
            val photo = FrameLayout(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2F5A44.toInt(), 0xFF1B3327.toInt()))
                    .apply { shape = GradientDrawable.OVAL }
                // the round outline of the background cuts the photo into a circle
                clipToOutline = true
                addView(text(initials, 30f, 0xFFB9F6CA.toInt()).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(-1, -1))
                val image = ImageView(this@DetailsActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(image, FrameLayout.LayoutParams(-1, -1))
                if (person.photo.isNotEmpty()) Glide.with(this@DetailsActivity).load("https://image.tmdb.org/t/p/w185${person.photo}").into(image)
            }
            card.addView(photo, LinearLayout.LayoutParams(photoSize, photoSize))
            card.addView(text(person.name, 14f).apply {
                gravity = Gravity.CENTER; maxLines = 2; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(6), 0, 0)
            }, LinearLayout.LayoutParams(dp(112), -2))
            if (person.role.isNotEmpty()) card.addView(text(person.role, 12f, 0xFF8FB39D.toInt()).apply {
                gravity = Gravity.CENTER; maxLines = 2
            }, LinearLayout.LayoutParams(dp(112), -2))
            row.addView(card, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
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
        watchButton.setTextColor(if (loading) 0x44FFFFFF else Color.WHITE)
        watchButton.compoundDrawables[0]?.alpha = if (loading) 68 else 255
    }

    /** Waits for the background site search if the user clicks "Смотреть" immediately on opening the card. */
    private fun startWatching(name: String) {
        if (opening) return
        if (Settings.string(this, Settings.SOURCE_MODE, "sites") == "torrents") {
            openTorrents(name)
            return
        }
        if (detailsReady.count > 0) {
            setWatchLoading(true)
            Thread {
                detailsReady.await(15, java.util.concurrent.TimeUnit.SECONDS)
                onUi {
                    setWatchLoading(false)
                    variants.firstOrNull()?.let { openVariant(it) } ?: openTorrents(name)
                }
            }.start()
            return
        }
        variants.firstOrNull()?.let { openVariant(it) } ?: openTorrents(name)
    }

    /**
     * The release is asked for its videos first: a film with one video starts in the player straight from this card,
     * and only a release with several (a series) opens the episode page. No empty page flashes in between.
     */
    private fun openVariant(v: Variant) {
        if (opening) return
        setWatchLoading(true)
        History.add(this, historyTitle())
        Thread {
            val episodes = runCatching { Sources.all.firstOrNull { it.id == v.sourceId }?.episodes(v) }.getOrNull()
            onUi {
                setWatchLoading(false)
                if (episodes != null && episodes.size == 1) playEpisode(this, v, episodes, 0)
                else startActivity(
                    Intent(this, EpisodesActivity::class.java).putExtra("source", v.source).putExtra("sourceId", v.sourceId)
                        .putExtra("title", v.title).putExtra("note", v.note).putExtra("ref", v.ref)
                        .putExtra("alt", v.alt).putExtra("description", v.description)
                )
            }
        }.start()
    }

    private fun openTorrents(name: String) {
        History.add(this, historyTitle())
        startActivity(
            Intent(this, TorrentsActivity::class.java).putExtra("name", name).putExtra("original", originalTitle)
                .putExtra("year", intent.getStringExtra("year")).putExtra("tv", intent.getBooleanExtra("tv", false))
                .putExtra("poster", posterPath)
        )
    }

    /** The history record: with the poster found through TMDB, when the list did not pass one to the card. */
    private fun historyTitle(): Title {
        val title = titleFrom(intent.extras ?: Bundle())
        return if (title.poster == null && posterPath != null) title.copy(poster = posterPath) else title
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
}
