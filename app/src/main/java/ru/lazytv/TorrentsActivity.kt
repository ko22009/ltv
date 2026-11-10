package ru.lazytv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.bumptech.glide.Glide

/** The chip that lists the starred torrents instead of one source. */
private const val FAVORITES = "★"

/**
 * Torrent search in the LazyMedia layout: the query bar, source chips with counters, rows with the
 * title, original title, source, size and seeds. Picking a row sends it to TorrServe, shows the preload
 * dialog and then starts playback.
 */
class TorrentsActivity : Activity() {
    private val scale get() = uiScale()
    private fun px(value: Int) = (value * scale).toInt()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var chips: LinearLayout
    private lateinit var chipsScroll: android.widget.HorizontalScrollView
    private lateinit var options: LinearLayout
    private lateinit var loading: LoadingView
    private lateinit var back: ImageView
    private lateinit var bar: SearchBar
    private var name = ""
    private var original = ""
    private var searchId = 0
    private var searching = false
    private var all: List<Torrent> = emptyList()
    private var shown: List<Torrent> = emptyList()
    private var source: String? = null
    /** The voice-over the list is narrowed to; empty means any. Not remembered: every title has its own ones. */
    private var voice = ""
    private var dialog: android.app.Dialog? = null
    private var preloading = false
    // the "TorrServe is not installed" card is shown once per screen, the address line keeps the message
    private var hintShown = false
    /** Opened from the bookmarks with a magnet: no page of torrents is shown, only the preload; the screen closes after it. */
    private var direct = false

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra("name").orEmpty()
        original = intent.getStringExtra("original").orEmpty()
        val year = intent.getStringExtra("year").orEmpty()
        val tv = intent.getBooleanExtra("tv", false)
        val query = if (tv || year.isEmpty()) name else "$name $year"

        val root = FrameLayout(this)
        root.background = calmBackground()
        barsLikeCalmBackground()
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        if (isMobileUi()) page.setPadding(px(16), px(16), px(16), px(12)) else page.setPadding(px(64), px(36), px(64), px(16))

        // the query can be changed: typed or spoken, the same bar as on the search screen, with Back to its left
        back = backButton(this, scale) { finish() }
        bar = SearchBar(this, scale) { retype(it) }
        bar.setPhrase(query)
        fun focusBelowHeader(): Boolean = when {
            chips.childCount > 0 -> chips.getChildAt(0).requestFocus()
            options.childCount > 0 -> options.getChildAt(0).requestFocus()
            shown.isNotEmpty() -> list.requestFocus()
            else -> false
        }
        bar.onLeft = { back.requestFocus() }
        bar.onDown = { focusBelowHeader() }
        back.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false
            else when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> bar.mic.requestFocus()
                KeyEvent.KEYCODE_DPAD_DOWN -> focusBelowHeader()
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> true
                else -> false
            }
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(px(56), px(56)).apply { rightMargin = px(16) })
            addView(bar, LinearLayout.LayoutParams(0, px(56), 1f))
        }
        page.addView(header, LinearLayout.LayoutParams(-1, px(56)))

        // the line under the query bar only carries messages (nothing found, errors) and hides when empty
        status = label("", 16f, TEXT_SECONDARY)
        status.visibility = View.GONE
        status.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { status.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        status.setPadding(0, px(8), 0, px(8))
        page.addView(status)

        chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        // one row, scrolled by the D-pad: the chips never wrap to a second line and never stick out of the edge
        chipsScroll = android.widget.HorizontalScrollView(this)
        chipsScroll.isHorizontalScrollBarEnabled = false
        chipsScroll.isFocusable = false
        chipsScroll.addView(chips, android.widget.FrameLayout.LayoutParams(-2, -1))
        page.addView(chipsScroll, LinearLayout.LayoutParams(-1, px(44)).apply { topMargin = px(16) })
        options = LinearLayout(this)
        options.orientation = LinearLayout.HORIZONTAL
        if (isMobileUi()) {
            // the option buttons are wider than a phone: one row, scrolled sideways
            page.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(options, FrameLayout.LayoutParams(-2, -1))
            }, LinearLayout.LayoutParams(-1, px(44)).apply { topMargin = px(8) })
        } else page.addView(options, LinearLayout.LayoutParams(-1, px(44)).apply { topMargin = px(8) })

        list = ListView(this)
        list.divider = null
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.setOnItemClickListener { _, _, position, _ -> start(shown[position], name) }
        list.setOnItemLongClickListener { _, _, position, _ -> toggleFavorite(position); true }
        list.setOnKeyListener { _, keyCode, event ->
            val position = list.selectedItemPosition
            if (event.action != KeyEvent.ACTION_DOWN) false
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && position >= 0) { toggleFavorite(position); true }
            else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position <= 0) {
                when {
                    options.childCount > 0 -> options.getChildAt(0).requestFocus()
                    chips.childCount > 0 -> chips.getChildAt(0).requestFocus()
                    else -> back.requestFocus()
                }
                true
            } else false
        }
        loading = LoadingView(this, scale, "Ищем раздачи…").apply {
            // the list area starts below the chips, so its middle looks low: lifted by the height of the buttons above it
            setPadding(0, 0, 0, px(190))
        }
        val body = FrameLayout(this)
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        body.addView(loading, FrameLayout.LayoutParams(-1, -1))
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(12) })
        // opened from a title card: its poster stands to the left of the list
        val poster = intent.getStringExtra("poster")
        if (poster == null || isMobileUi()) root.addView(page, FrameLayout.LayoutParams(-1, -1))
        else {
            val image = ImageView(this)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setBackgroundColor(PLATE_FILL)
            val posterSpinner = Spinner(this, px(SPINNER_IMAGE))
            // the same size as the cards use (w500): a poster already in the cache is not downloaded again
            image.showPoster(poster, posterSpinner)
            val posterSlot = FrameLayout(this).apply {
                addView(image, FrameLayout.LayoutParams(-1, -1))
                addView(posterSpinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
            val split = LinearLayout(this)
            split.addView(posterSlot, LinearLayout.LayoutParams(px(170), px(256)).apply { leftMargin = px(64); topMargin = px(36) })
            page.setPadding(px(32), px(36), px(64), px(16))
            split.addView(page, LinearLayout.LayoutParams(0, -1, 1f))
            root.addView(split, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        // opened from the "favorite torrents" row of the home screen: skip the search, go straight to preload
        val directMagnet = intent.getStringExtra("magnet")
        if (directMagnet != null) {
            val torrent = Torrent(
                name, intent.getStringExtra("size").orEmpty(), intent.getIntExtra("seeds", 0),
                intent.getIntExtra("leeches", 0), directMagnet, intent.getStringExtra("source").orEmpty(),
            )
            direct = true
            root.visibility = View.INVISIBLE
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            loading.visibility = View.GONE
            all = listOf(torrent)
            select(null)
            start(torrent, name)
        } else {
            back.requestFocus()
            load(query)
        }
    }

    /** A phrase typed or spoken in the bar replaces the title: the original name of the film no longer applies. */
    private fun retype(phrase: String) {
        val text = phrase.trim()
        if (text.length < 2) return
        name = text
        original = text
        load(text)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        bar.handleResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        bar.handlePermissionsResult(requestCode, permissions, grantResults)
    }

    private fun load(query: String) {
        val title = name
        val originalTitle = original
        val token = ++searchId
        searching = true
        all = emptyList()
        shown = emptyList()
        voice = ""
        list.adapter = null
        chips.removeAllViews()
        options.removeAllViews()
        status.text = ""
        bar.setLoading(false)
        loading.visibility = View.VISIBLE
        // rutor and the other sources run in parallel; every source adds its rows as soon as it answers
        val pending = java.util.concurrent.atomic.AtomicInteger(1)
        fun finishPart() {
            if (pending.decrementAndGet() != 0) return
            onUi {
                if (token != searchId) return@onUi
                searching = false
                bar.setLoading(false)
                loading.visibility = View.GONE
                if (all.isEmpty()) {
                    status.text = if (ClockCheck.skewed) ClockCheck.MESSAGE else "Ничего не найдено по запросу «$query»"
                }
            }
        }
        // the search may already be running or finished: the card starts it ahead of the screen (see TorrentPrefetch)
        val job = TorrentPrefetch.start(applicationContext, query, title, originalTitle)
        TorrentPrefetch.join(job, { items -> addResults(items, token) }, { finishPart() })
    }

    /** Appends one source's rows to the list and redraws the chips at once, without waiting for all sources. */
    private fun addResults(items: List<Torrent>, token: Int) {
        if (items.isEmpty()) return
        onUi {
            if (token != searchId) return@onUi
            val seen = all.mapTo(HashSet()) { it.identity() }
            val fresh = items.filter { seen.add(it.identity()) }
            if (fresh.isEmpty()) return@onUi
            val first = all.isEmpty()
            val position = list.selectedItemPosition
            all = all + fresh
            loading.visibility = View.GONE
            select(source)
            if (position > 0) list.setSelection(position)
            if (first && !bar.input.hasFocus() && !bar.mic.hasFocus()) list.requestFocus()
            if (searching) bar.setLoading(true)
        }
    }

    /** Rebuilds the chips (counters per source) and the list for the chosen source. */
    private fun select(chosen: String?, focusOption: Int = -1) {
        val sort = TorrentSort.of(Settings.string(this, Settings.TORRENT_SORT, TorrentSort.SEEDS.key))
        val exact = Settings.bool(this, Settings.TORRENT_EXACT, false)
        val minSeeds = Settings.string(this, Settings.TORRENT_MIN_SEEDS, "0").toIntOrNull() ?: 0
        // the voice-overs offered are those of the releases that pass the other filters; a choice that is gone is dropped
        val voices = TorrentVoice.available(TorrentOrder.arrange(all, sort, exact, name, minSeeds))
        if (voice !in voices) voice = ""
        val visible = TorrentOrder.arrange(all, sort, exact, name, minSeeds, voice)
        val groups = visible.groupBy { it.source }
        val saved = FavoriteTorrents.all(this)
        source = chosen?.takeIf { it in groups || (it == FAVORITES && saved.isNotEmpty()) }
        chips.removeAllViews()
        options.removeAllViews()
        chip(chips, "Все (${visible.size})", source == null) { select(null) }
        groups.forEach { (group, items) -> chip(chips, "$group (${items.size})", source == group) { select(group) } }
        if (saved.isNotEmpty()) chip(chips, "Избранное (${saved.size})", source == FAVORITES,
            if (source == FAVORITES) MenuIcons.STAR else MenuIcons.STAR_BORDER) { select(FAVORITES) }
        chip(options, sort.label, false, MenuIcons.SORT) {
            Settings.setString(this, Settings.TORRENT_SORT, sort.next().key)
            select(source, 0)
        }
        chip(options, "Точное совпадение по названию: " + if (exact) "вкл" else "выкл", exact) {
            Settings.setBool(this, Settings.TORRENT_EXACT, !exact)
            select(source, 1)
        }
        chip(options, if (minSeeds > 0) "Сидов от $minSeeds" else "Сиды: любые", minSeeds > 0) {
            val next = Settings.MIN_SEEDS[(Settings.MIN_SEEDS.indexOf(minSeeds) + 1) % Settings.MIN_SEEDS.size]
            Settings.setString(this, Settings.TORRENT_MIN_SEEDS, next.toString())
            select(source, 2)
        }
        // only when the titles name at least one voice-over: the button goes through them one by one, then back to "any"
        if (voices.isNotEmpty()) chip(options, if (voice.isEmpty()) "Озвучка: любая" else "Озвучка: $voice", voice.isNotEmpty()) {
            val choices = listOf("") + voices
            voice = choices[(choices.indexOf(voice) + 1) % choices.size]
            select(source, 3)
        }
        if (focusOption >= 0) options.getChildAt(focusOption)?.requestFocus()
        shown = when (source) {
            null -> visible
            FAVORITES -> saved
            else -> visible.filter { it.source == source }
        }
        if (all.isNotEmpty() && shown.isEmpty()) status.text = "Нет раздач с точным совпадением названия «$name»"
        else if (all.isNotEmpty()) status.text = ""
        list.adapter = object : ArrayAdapter<Torrent>(this, 0, shown) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup) = row(getItem(position)!!)
        }
    }

    private fun chip(parent: LinearLayout, text: String, active: Boolean, icon: String? = null, action: () -> Unit) {
        val view = label(text, 16f, Color.WHITE)
        view.gravity = Gravity.CENTER_VERTICAL
        view.setPadding(px(16), 0, px(16), 0)
        if (icon != null) {
            val size = px(22)
            view.setCompoundDrawables(PathDrawable(icon).apply { setBounds(0, 0, size, size) }, null, null, null)
            view.compoundDrawablePadding = px(10)
        }
        view.isFocusable = true
        view.isClickable = true
        // the chosen source is the light plate like the active season; the focus fills it with the teal
        styleChoice(view, view, active, scale, plate = OVERLAY_SOFT, restText = Color.WHITE)
        // a dark icon on the light plate (the white one would vanish there); the cursor turns the plate teal, the icon white again
        if (icon != null && active) view.compoundDrawables[0]?.setColorFilter(ACTIVE_TEXT, android.graphics.PorterDuff.Mode.SRC_IN)
        view.setOnClickListener { action() }
        // keep the focused chip inside the visible part of the single-row slider
        view.setOnFocusChangeListener { _, focused ->
            if (focused && parent === chips) chipsScroll.post {
                chipsScroll.smoothScrollTo((view.left - px(24)).coerceAtLeast(0), 0)
            }
        }
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false
            else when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val step = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    parent.getChildAt(parent.indexOfChild(view) + step)?.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // go to Back instead of the search EditText so moving up does not pop up the soft keyboard
                    if (parent === options && chips.childCount > 0) chips.getChildAt(0).requestFocus()
                    else back.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    when {
                        parent === chips && options.childCount > 0 -> options.getChildAt(0).requestFocus()
                        shown.isNotEmpty() -> list.requestFocus()
                    }
                    true
                }
                else -> false
            }
        }
        parent.addView(view, LinearLayout.LayoutParams(-2, px(44)).apply { rightMargin = px(12) })
    }

    /** One row: Russian title (bold), original title and release, source; on the right size, seeds and leeches. */
    private fun row(torrent: Torrent): View {
        val marked = FavoriteTorrents.has(this, torrent)
        val card = torrentRowCard(this, torrent, marked)
        // on a touch screen the bookmark is a button of its own (there is no "right" key)
        if (isMobileUi()) card.findViewWithTag<View>("star")?.setOnClickListener { toggleFavorite(shown.indexOf(torrent)) }

        // ListView marks the focused row as selected: forward that to the card background
        val holder = object : FrameLayout(this) {
            override fun setSelected(selected: Boolean) { super.setSelected(selected); card.isSelected = selected }
        }
        holder.setPadding(0, px(4), 0, px(4))
        holder.addView(card)
        return holder
    }

    private fun toggleFavorite(position: Int) {
        val added = FavoriteTorrents.toggle(this, shown.getOrNull(position) ?: return)
        Toast.makeText(this, if (added) "Добавлено в избранное" else "Убрано из избранного", Toast.LENGTH_SHORT).show()
        // the counter of the favorites chip changes too, and in the favorites list a removed row disappears
        select(source)
        list.requestFocus()
        list.setSelection(position.coerceAtMost(maxOf(shown.size - 1, 0)))
    }

    private fun start(torrent: Torrent, name: String) {
        if (preloading) return
        // as in LazyMedia Deluxe: hand the magnet to whatever torrent app is installed, Android shows the chooser
        if (Settings.string(this, Settings.TORRENT_CLIENT, "internal") == "external") {
            preloading = true
            Thread {
                val magnet = runCatching { ExtraTorrents.magnetOf(torrent) }
                onUi {
                    preloading = false
                    magnet.onSuccess {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                            .onFailure { Toast.makeText(this, "Нет приложения для magnet-ссылок", Toast.LENGTH_LONG).show() }
                    }.onFailure { Toast.makeText(this, "Не удалось получить magnet-ссылку: ${Errors.explain(it)}", Toast.LENGTH_LONG).show() }
                }
            }.start()
            return
        }
        preloading = true
        val view = PreloadView()
        view.title.text = name
        view.file.text = torrent.title.substringBefore(" / ")
        view.info.text = "Подключение к TorrServe…"
        dialog = showBare(this, view.root) { preloading = false; if (direct) finish() }
        Thread {
            runCatching { TorrServe.files(applicationContext, ExtraTorrents.magnetOf(torrent), name) }
                .onSuccess { (hash, files) ->
                    val videos = files.filter { f -> VIDEO.any { f.path.lowercase().endsWith(it) } }.ifEmpty { files }
                    onUi {
                        if (videos.size == 1) preload(hash, videos[0], name, torrent.source, view)
                        else {
                            dialog?.dismiss()
                            preloading = false
                            showChoice(this, "Файл для просмотра", videos.map { it.path.substringAfterLast('/') }, -1) { which ->
                                preloading = true
                                val v = PreloadView()
                                v.title.text = name
                                dialog = showBare(this, v.root) { preloading = false; if (direct) finish() }
                                preload(hash, videos[which], name, torrent.source, v)
                            }
                        }
                    }
                }
                .onFailure { e ->
                    val address = Settings.string(applicationContext, Settings.TORRSERVE, "127.0.0.1:8090")
                    val offline = e is java.io.IOException && e !is java.io.FileNotFoundException
                    onUi {
                        dialog?.dismiss()
                        preloading = false
                        status.text = if (offline) "TorrServe не отвечает по адресу $address"
                        else "TorrServe: ${Errors.explain(e)}"
                        if (offline) showMissingTorrServe(address)
                        else if (direct) {
                            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                }
        }.start()
    }

    /**
     * TorrServe is a separate program, LazyTV has no torrent client of its own: it only asks TorrServe to
     * download the release. When nothing answers on the address, the program is most likely just not installed,
     * so the user gets a card with the download page and the way to point LazyTV at another device.
     */
    private fun showMissingTorrServe(address: String) {
        if ((hintShown && !direct) || isFinishing) return
        hintShown = true
        val sameDevice = address.startsWith("127.") || address.startsWith("localhost")
        val where = if (sameDevice) "На этом устройстве он не установлен или не запущен."
            else "По адресу $address он не отвечает: не запущен или недоступен по сети."
        val message = "LazyTV не качает торренты сам: их раздаёт отдельная программа TorrServe (TorrServer). $where\n\n" +
            "Поставьте TorrServe на это устройство (APK ставится как обычное приложение). После установки " +
            "запустите TorrServe и нажмите в нём кнопку «Установить»: без этого сервер не заработает. " +
            "Если TorrServe уже работает на другом устройстве в сети (приставка, компьютер, NAS), укажите его адрес.\n\n" +
            "Сейчас в настройках указан $address."
        showMessage(this, "TorrServe не установлен", message = message, buttons = listOf(
            DialogButton("Скачать TorrServe") { downloadTorrServe() },
            DialogButton("Указать адрес") { askTorrServeAddress() },
            DialogButton("Закрыть") { if (direct) finish() },
        ), onCancel = { if (direct) finish() })
    }

    // back from the player or from the preload: a screen that was only a doorway for a bookmark closes
    override fun onRestart() {
        super.onRestart()
        if (direct) finish()
    }

    /** A field for the address right on this screen: the settings are one long list, finding it there is slow. */
    private fun askTorrServeAddress() {
        val current = Settings.string(this, Settings.TORRSERVE, "127.0.0.1:8090")
        val input = android.widget.EditText(this).apply {
            setText(current)
            setSelection(current.length)
            setTextColor(Color.WHITE)
            setPadding(px(12), px(12), px(12), px(12))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Например, 192.168.1.10:8090 (приставка или компьютер с TorrServe)", 13f, TEXT_MUTED))
            addView(input, LinearLayout.LayoutParams(-1, px(52)).apply { topMargin = px(8) })
        }
        showMessage(this, "Адрес TorrServe", content = box, initialFocus = input, buttons = listOf(
            DialogButton("Сохранить") {
                val address = input.text.toString().trim()
                    .removePrefix("http://").removePrefix("https://").trimEnd('/')
                    .ifEmpty { "127.0.0.1:8090" }
                Settings.setString(this, Settings.TORRSERVE, address)
                hintShown = false
                status.text = "Адрес TorrServe: $address"
            },
            DialogButton("Отмена"),
        ))
    }

    private fun downloadTorrServe() = Links.open(this, TorrServe.DOWNLOAD)

    /** Polls TorrServe until the buffer is filled (or a minute passes), showing peers, speed and buffer, then plays. */
    private fun preload(hash: String, file: TorrentFile, name: String, source: String, view: PreloadView) {
        view.file.text = file.path.substringAfterLast('/')
        Thread { runCatching { TorrServe.preload(applicationContext, hash, file) } }.start()
        val started = System.currentTimeMillis()
        val poll = object : Runnable {
            override fun run() {
                if (!preloading || isFinishing) return
                Thread {
                    val stat = runCatching { TorrServe.stat(applicationContext, hash) }.getOrNull()
                    onUi {
                        if (!preloading || isFinishing) return@onUi
                        var ready = System.currentTimeMillis() - started > 60_000
                        if (stat != null) {
                            view.update(stat)
                            if (stat.preloadSize > 0 && stat.preloaded >= stat.preloadSize) ready = true
                        }
                        if (ready) {
                            dialog?.dismiss()
                            preloading = false
                            play(hash, file, name, source)
                        } else ui.postDelayed(this, 1000)
                    }
                }.start()
            }
        }
        poll.run()
    }

    private fun play(hash: String, file: TorrentFile, name: String, source: String) {
        val original = intent.getStringExtra("original").orEmpty()
        val year = intent.getStringExtra("year").orEmpty()
        val tv = intent.getBooleanExtra("tv", false)
        val poster = intent.getStringExtra("poster")
        openPlayer(
            this, TorrServe.streamUrl(this, hash, file), name, "$source • ${file.path.substringAfterLast('/')}",
            mediaTitle = name, originalTitle = original, year = year, tv = tv, poster = poster,
            tmdbId = intent.getLongExtra("tmdbId", 0L),
        )
    }

    override fun onDestroy() {
        preloading = false
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Preload card: title, stage, file, peers/seeds/speed/size, buffer line and a progress bar. */
    private inner class PreloadView {
        val title = label("", 18f, Color.WHITE, true)
        // fixed number of lines for every text: the card keeps its height while the numbers change (a wrapping line made it jump)
        val file = label("", 16f, Color.WHITE).apply { setLines(2); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        val info = label("", 14f, TEXT_SECONDARY).apply { setLines(2) }
        val buffer = label("", 14f, TEXT_SECONDARY).apply { setLines(1); ellipsize = android.text.TextUtils.TruncateAt.END }
        val bar = ProgressBar(this@TorrentsActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            // the white line of the player and the spinners
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x66FFFFFF)
        }
        val root = LinearLayout(this@TorrentsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(16), px(24), px(16))
            background = GradientDrawable().apply { setColor(PLATE_FILL); cornerRadius = px(TITLE_CARD_RADIUS.toInt()).toFloat() }
            if (!isMobileUi()) minimumWidth = px(620)
            addView(title)
            addView(label("ПРЕДЗАГРУЗКА", 14f, TEXT_MUTED, true).apply { setPadding(0, px(8), 0, px(4)) })
            addView(file)
            addView(info.apply { setPadding(0, px(8), 0, 0) })
            addView(buffer)
            addView(bar, LinearLayout.LayoutParams(-1, px(10)).apply { topMargin = px(8) })
        }

        fun update(s: TorrentStat) {
            // two short lines, none wraps and the card keeps its height: peers and seeds, then the speed and the size together
            info.text = "Пиры ${s.activePeers}/${s.totalPeers}   Сиды ${s.seeders}\n" +
                "Скорость ${"%.1f".format(s.speed * 8 / 1_000_000)} Мбит/с" +
                (if (s.size > 0) "   Размер ${"%.1f".format(s.size / 1_073_741_824.0)} ГБ" else "")
            if (s.preloadSize > 0) {
                val percent = s.preloaded * 100.0 / s.preloadSize
                buffer.text = "Буфер ${"%.1f".format(percent)}% ${"%.1f".format(s.preloaded / 1_048_576.0)} МБ/${s.preloadSize / 1_048_576} МБ"
                bar.progress = (percent * 10).toInt().coerceIn(0, 1000)
            } else buffer.text = s.status
        }
    }

    private companion object {
        val VIDEO = listOf(".mkv", ".mp4", ".avi", ".mov", ".ts", ".m2ts", ".wmv", ".webm")
    }
}
