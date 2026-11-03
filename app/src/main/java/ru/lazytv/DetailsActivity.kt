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
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * scale)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(20), 0, dp(20), 0)
        setCompoundDrawables(PathDrawable(icon).apply { setBounds(0, 0, dp(22), dp(22)) }, null, null, null)
        compoundDrawablePadding = dp(10)
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
        layoutParams = LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(12) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("name").orEmpty()
        posterPath = intent.getStringExtra("poster")
        // "recently opened": the card is put into the list when it is opened, not only when watching starts
        if (savedInstanceState == null) History.add(this, historyTitle())
        val baseRows = listOf(
            "Тип" to if (intent.getBooleanExtra("tv", false)) "Сериал" else "Фильм",
            "Год" to intent.getStringExtra("year").orEmpty(),
            "Рейтинг" to intent.getStringExtra("rating").orEmpty(),
        )

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF101A14.toInt())

        // a calm dark band; the frame of the film (TMDB backdrop) fades in under a dark veil once it is known
        val banner = FrameLayout(this)
        banner.setBackgroundColor(0xFF15251B.toInt())
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        banner.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        // the poster is not borrowed as a stand-in: until the wide backdrop is there, a spinner turns on the band
        backdrop.alpha = 0f
        bannerSpinner = Spinner(this, dp(44))
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

        // the way back for a remote with no Back key or a mouse: a round button in the corner, same as the Back key
        val back = ImageView(this).apply {
            setImageDrawable(PathDrawable(MenuIcons.BACK).apply { setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN) })
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = focusShape(scale, 0x66000000, circle = true)
            isFocusable = true
            isClickable = true
            contentDescription = "Назад"
            setOnClickListener { finish() }
        }
        root.addView(back, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.TOP).apply {
            topMargin = dp(32); leftMargin = dp(64)
        })

        val bar = LinearLayout(this)
        bar.setBackgroundColor(0xFF1B2E23.toInt())
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(389), 0, 0, 0)
        // watching opens the first variant found on the video sites; without any it falls back to torrents
        watchButton = button(MenuIcons.PLAY, "Смотреть") { variants.firstOrNull()?.let { openVariant(it) } ?: openTorrents(name) }
        bar.addView(watchButton)
        val title = titleFrom(intent.extras!!)
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
        bar.addView(button(MenuIcons.DOWNLOAD, "Торрент") { openTorrents(name) })
        bar.addView(button(MenuIcons.TRAILER, "Трейлер на YouTube") { openTrailer(title) })
        // left and right walk the buttons of the bar: at the right end there is nothing to go to, the focus stays;
        // at the left end the key goes on to the back button as usual
        for (i in 0 until bar.childCount) bar.getChildAt(i).setOnKeyListener { view, code, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) false
            else if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                val step = if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                val next = bar.getChildAt(bar.indexOfChild(view) + step)
                if (next != null) next.requestFocus()
                next != null || step > 0
            } else false
        }
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(360) })

        posterView = ImageView(this)
        posterView.scaleType = ImageView.ScaleType.CENTER_CROP
        posterView.setBackgroundColor(0xFF1B2E23.toInt())
        posterPath?.let { Glide.with(this).load("https://image.tmdb.org/t/p/w500$it").into(posterView) }
            ?: posterView.setImageResource(R.drawable.no_poster)
        root.addView(posterView, FrameLayout.LayoutParams(dp(170), dp(256)).apply {
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
        loadDetails(title, name, baseRows, original, table)
        val scroll = ScrollView(this)
        scroll.isFocusable = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(info)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = dp(444); leftMargin = dp(389); rightMargin = dp(64)
        })

        setContentView(root)
        bar.getChildAt(0).requestFocus()
    }

    /** Fills in what the list does not carry: original title, runtime, age, genres, countries, director, cast. */
    private fun loadDetails(
        title: Title, name: String, baseRows: List<Pair<String, String>>, original: TextView, table: LinearLayout,
    ) {
        Thread {
            val d = runCatching { Tmdb.details(applicationContext, title.id, title.tv) }.getOrNull()
            originalTitle = d?.originalTitle.orEmpty()
            // the variants are not shown on the card any more: the "Смотреть" button opens the first of them
            variants = Sources.searchCached(applicationContext, name, originalTitle, title.year)
            if (d == null) {
                onUi { bannerSpinner.visibility = View.GONE }
                return@Thread
            }
            onUi {
                if (posterPath == null && d.poster.isNotEmpty()) {
                    posterPath = d.poster
                    Glide.with(this).load("https://image.tmdb.org/t/p/w500${d.poster}").into(posterView)
                }
                if (d.backdrop.isNotEmpty()) {
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
                ))
                val info = table.parent as? LinearLayout
                if (info != null && d.castPeople.isNotEmpty() && info.findViewWithTag<View>("cast") == null) {
                    val row = castRow(d.castPeople)
                    row.tag = "cast"
                    info.addView(row)
                }
            }
        }.start()
    }

    /** Facts as a table: the caption on the left, the value on the right, a thin line between rows. Empty values are skipped. */
    private fun fillTable(table: LinearLayout, rows: List<Pair<String, String>>) {
        table.removeAllViews()
        rows.filter { it.second.isNotBlank() }.forEachIndexed { i, (caption, value) ->
            if (i > 0) table.addView(View(this).apply { setBackgroundColor(0x22FFFFFF) }, LinearLayout.LayoutParams(-1, 1))
            val row = LinearLayout(this)
            row.setPadding(0, dp(8), 0, dp(8))
            row.addView(text(caption, 15f, 0xFF8FB39D.toInt()), LinearLayout.LayoutParams(dp(150), -2))
            row.addView(text(value, 16f, 0xFFDCE8E0.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
            table.addView(row)
        }
    }

    private var variants: List<Variant> = emptyList()
    private var originalTitle = ""
    private var posterPath: String? = null
    private lateinit var posterView: ImageView
    private lateinit var backdrop: ImageView
    private lateinit var bannerSpinner: Spinner

    /** Cast of the film: a caption and a carousel of round photos with the name and the role; OK opens the actor page. */
    private fun castRow(people: List<Tmdb.Person>): View {
        val block = LinearLayout(this)
        block.orientation = LinearLayout.VERTICAL
        block.addView(text("В РОЛЯХ", 18f).apply { setPadding(0, dp(14), 0, dp(8)) })
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val photoSize = dp(92)
        people.forEach { person ->
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
            }
            val photo = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                // the empty circle stays while the photo loads and for an actor who has none
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2A3A31.toInt()) }
            }
            if (person.photo.isNotEmpty()) {
                Glide.with(this).load("https://image.tmdb.org/t/p/w185${person.photo}").circleCrop().into(photo)
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
    private var opening = false

    /**
     * The release is asked for its videos first: a film with one video starts in the player straight from this card,
     * and only a release with several (a series) opens the episode page. No empty page flashes in between.
     */
    private fun openVariant(v: Variant) {
        if (opening) return
        opening = true
        History.add(this, historyTitle())
        val caption = watchButton.text
        watchButton.text = "ЗАГРУЖАЕМ…"
        Thread {
            val episodes = runCatching { Sources.all.first { it.id == v.sourceId }.episodes(v) }.getOrNull()
            onUi {
                opening = false
                watchButton.text = caption
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
                .putExtra("poster", intent.getStringExtra("poster"))
        )
    }

    /** The history record: with the poster found through TMDB, when the list did not pass one to the card. */
    private fun historyTitle(): Title {
        val title = titleFrom(intent.extras!!)
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
