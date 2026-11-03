package ru.lazytv

import android.app.Activity
import android.app.AlertDialog
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
    private val scale get() = resources.displayMetrics.widthPixels / 1280f
    private fun px(value: Int) = (value * scale).toInt()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var chips: LinearLayout
    private lateinit var chipsScroll: android.widget.HorizontalScrollView
    private lateinit var options: LinearLayout
    private lateinit var loading: LoadingView
    private lateinit var bar: SearchBar
    private var name = ""
    private var original = ""
    private var searchId = 0
    private var all: List<Torrent> = emptyList()
    private var shown: List<Torrent> = emptyList()
    private var source: String? = null
    private var dialog: AlertDialog? = null
    private var preloading = false
    // the "TorrServe is not installed" card is shown once per screen, the address line keeps the message
    private var hintShown = false

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
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(px(64), px(36), px(64), px(20))

        // the query can be changed: typed or spoken, the same bar as on the search screen
        bar = SearchBar(this, scale) { retype(it) }
        bar.setPhrase(query)
        page.addView(bar, LinearLayout.LayoutParams(-1, px(56)))

        // the line under the query bar only carries messages (nothing found, errors) and hides when empty
        status = label("", 15f, 0xFFAFC4B6.toInt())
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
        page.addView(chipsScroll, LinearLayout.LayoutParams(-1, px(44)).apply { topMargin = px(14) })
        options = LinearLayout(this)
        options.orientation = LinearLayout.HORIZONTAL
        page.addView(options, LinearLayout.LayoutParams(-1, px(44)).apply { topMargin = px(8) })

        list = ListView(this)
        list.divider = null
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.setOnItemClickListener { _, _, position, _ -> start(shown[position], name) }
        list.setOnItemLongClickListener { _, _, position, _ -> toggleFavorite(position); true }
        list.setOnKeyListener { _, keyCode, event ->
            val position = list.selectedItemPosition
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN && position >= 0) { toggleFavorite(position); true }
            else false
        }
        loading = LoadingView(this, scale, "Ищем раздачи…")
        val body = FrameLayout(this)
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        body.addView(loading, FrameLayout.LayoutParams(-1, -1))
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = px(10) })
        // opened from a title card: its poster stands to the left of the list
        val poster = intent.getStringExtra("poster")
        if (poster == null) root.addView(page, FrameLayout.LayoutParams(-1, -1))
        else {
            val image = ImageView(this)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setBackgroundColor(0xFF12201A.toInt())
            Glide.with(this).load("https://image.tmdb.org/t/p/w342$poster").into(image)
            val split = LinearLayout(this)
            split.addView(image, LinearLayout.LayoutParams(px(170), px(256)).apply { leftMargin = px(64); topMargin = px(36) })
            page.setPadding(px(32), px(36), px(64), px(20))
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
            loading.visibility = View.GONE
            all = listOf(torrent)
            select(null)
            start(torrent, name)
        } else load(query)
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

    private fun load(query: String) {
        val title = name
        val originalTitle = original
        val token = ++searchId
        all = emptyList()
        shown = emptyList()
        list.adapter = null
        chips.removeAllViews()
        options.removeAllViews()
        status.text = ""
        loading.visibility = View.VISIBLE
        // rutor and the other sources run in parallel; every source adds its rows as soon as it answers
        val pending = java.util.concurrent.atomic.AtomicInteger(2)
        fun finishPart() {
            if (pending.decrementAndGet() != 0) return
            runOnUiThread {
                if (token != searchId) return@runOnUiThread
                loading.visibility = View.GONE
                status.text = if (all.isEmpty()) "Ничего не найдено по запросу «$query»" else ""
            }
        }
        Thread {
            // the year narrows the search, but many releases do not carry it in the title: retry with the name alone
            val rutor = runCatching {
                TorrentSearch.search(query).ifEmpty { if (query != title) TorrentSearch.search(title) else emptyList() }
            }.getOrNull().orEmpty()
            addResults(rutor, token)
            finishPart()
        }.start()
        Thread {
            runCatching {
                ExtraTorrents.search(applicationContext, title, originalTitle) { _, list -> addResults(list, token) }
            }
            finishPart()
        }.start()
    }

    /** Appends one source's rows to the list and redraws the chips at once, without waiting for all sources. */
    private fun addResults(items: List<Torrent>, token: Int) {
        if (items.isEmpty()) return
        runOnUiThread {
            if (token != searchId) return@runOnUiThread
            val seen = all.mapTo(HashSet()) { it.identity() }
            val fresh = items.filter { seen.add(it.identity()) }
            if (fresh.isEmpty()) return@runOnUiThread
            val first = all.isEmpty()
            val position = list.selectedItemPosition
            all = all + fresh
            loading.visibility = View.GONE
            select(source)
            if (position > 0) list.setSelection(position)
            if (first && !bar.input.hasFocus()) list.requestFocus()
            status.text = "Ищем ещё…"
        }
    }

    /** Rebuilds the chips (counters per source) and the list for the chosen source. */
    private fun select(chosen: String?, focusOption: Int = -1) {
        val sort = TorrentSort.of(Settings.string(this, Settings.TORRENT_SORT, TorrentSort.SEEDS.key))
        val exact = Settings.bool(this, Settings.TORRENT_EXACT, false)
        val minSeeds = Settings.string(this, Settings.TORRENT_MIN_SEEDS, "0").toIntOrNull() ?: 0
        val visible = TorrentOrder.arrange(all, sort, exact, name, minSeeds)
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
        val view = label(text, 15f, Color.WHITE)
        view.gravity = Gravity.CENTER_VERTICAL
        view.setPadding(px(18), 0, px(18), 0)
        if (icon != null) {
            val size = px(22)
            view.setCompoundDrawables(PathDrawable(icon).apply { setBounds(0, 0, size, size) }, null, null, null)
            view.compoundDrawablePadding = px(10)
        }
        view.isFocusable = true
        view.isClickable = true
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(0xFF2F7D57.toInt()); cornerRadius = px(3).toFloat() })
            addState(intArrayOf(), GradientDrawable().apply {
                setColor(if (active) 0xFF2F7D57.toInt() else 0x66000000); cornerRadius = px(3).toFloat()
            })
        }
        view.setOnClickListener { action() }
        // keep the focused chip inside the visible part of the single-row slider
        view.setOnFocusChangeListener { _, focused ->
            if (focused && parent === chips) chipsScroll.post {
                chipsScroll.smoothScrollTo((view.left - px(24)).coerceAtLeast(0), 0)
            }
        }
        parent.addView(view, LinearLayout.LayoutParams(-2, px(44)).apply { rightMargin = px(10) })
    }

    /** One row: Russian title (bold), original title and release, source; on the right size, seeds and leeches. */
    private fun row(torrent: Torrent): View {
        val parts = torrent.title.split(" / ", limit = 2)
        val main = if (parts.size == 2) parts[0] else torrent.title.substringBefore(" (")
        val rest = if (parts.size == 2) parts[1] else torrent.title

        val card = LinearLayout(this)
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(px(20), px(10), px(20), px(10))
        card.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply { setColor(0xFF2F7D57.toInt()); cornerRadius = px(3).toFloat() })
            addState(intArrayOf(), GradientDrawable().apply { setColor(0x33000000); cornerRadius = px(3).toFloat() })
        }
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(label(main, 19f, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        texts.addView(label(rest, 14f, 0xFFDCE8E0.toInt()).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        texts.addView(label(torrent.source, 13f, 0xFF8FB39D.toInt()))
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        val numbers = LinearLayout(this)
        numbers.orientation = LinearLayout.VERTICAL
        numbers.gravity = Gravity.END
        numbers.addView(label(torrent.size, 14f, Color.WHITE, true))
        numbers.addView(label("▲ ${torrent.seeds}", 13f, 0xFFAFC4B6.toInt()))
        numbers.addView(label("▼ ${torrent.leeches}", 13f, 0xFFEF9A9A.toInt()))
        card.addView(numbers, LinearLayout.LayoutParams(px(110), -2))

        // the star right of the seeds: gold when the torrent is in the favorites; "right" on the row or a long press toggles it
        val marked = FavoriteTorrents.has(this, torrent)
        val star = ImageView(this)
        star.setImageDrawable(PathDrawable(if (marked) MenuIcons.STAR else MenuIcons.STAR_BORDER).apply {
            setColorFilter(if (marked) 0xFFE0B94F.toInt() else 0x66FFFFFF, android.graphics.PorterDuff.Mode.SRC_IN)
        })
        card.addView(star, LinearLayout.LayoutParams(px(30), px(30)).apply { leftMargin = px(16) })

        // ListView marks the focused row as selected: forward that to the card background
        val holder = object : FrameLayout(this) {
            override fun setSelected(selected: Boolean) { super.setSelected(selected); card.isSelected = selected }
        }
        holder.setPadding(0, px(3), 0, px(3))
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
                    }.onFailure { Toast.makeText(this, "Не удалось получить magnet-ссылку: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }.start()
            return
        }
        preloading = true
        val view = PreloadView()
        view.title.text = name
        view.file.text = torrent.title.substringBefore(" / ")
        view.info.text = "Подключение к TorrServe…"
        dialog = AlertDialog.Builder(this).setView(view.root).setOnCancelListener { preloading = false }.create().also {
            it.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            it.show()
        }
        Thread {
            runCatching { TorrServe.files(applicationContext, ExtraTorrents.magnetOf(torrent), name) }
                .onSuccess { (hash, files) ->
                    val videos = files.filter { f -> VIDEO.any { f.path.lowercase().endsWith(it) } }.ifEmpty { files }
                    onUi {
                        if (videos.size == 1) preload(hash, videos[0], name, torrent.source, view)
                        else {
                            dialog?.dismiss()
                            preloading = false
                            AlertDialog.Builder(this).setTitle("Файл для просмотра")
                                .setItems(videos.map { it.path.substringAfterLast('/') }.toTypedArray()) { _, which ->
                                    preloading = true
                                    val v = PreloadView()
                                    v.title.text = name
                                    dialog = AlertDialog.Builder(this).setView(v.root).setOnCancelListener { preloading = false }.create().also { d ->
                                        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); d.show()
                                    }
                                    preload(hash, videos[which], name, torrent.source, v)
                                }.show()
                        }
                    }
                }
                .onFailure { e ->
                    val address = Settings.string(applicationContext, Settings.TORRSERVE, "127.0.0.1:8090")
                    val offline = e is java.io.IOException && e !is java.io.FileNotFoundException
                    runOnUiThread {
                        dialog?.dismiss()
                        preloading = false
                        status.text = if (offline) "TorrServe не отвечает по адресу $address"
                        else "TorrServe: ${e.message}"
                        if (offline) showMissingTorrServe(address)
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
        if (hintShown || isFinishing) return
        hintShown = true
        val sameDevice = address.startsWith("127.") || address.startsWith("localhost")
        val where = if (sameDevice) "На этом устройстве он не установлен или не запущен."
            else "По адресу $address он не отвечает: не запущен или недоступен по сети."
        val message = "LazyTV не качает торренты сам — их раздаёт отдельная программа TorrServe (TorrServer). $where\n\n" +
            "Поставьте TorrServe на это устройство (APK ставится как обычное приложение) или, если он уже " +
            "работает на другом устройстве в сети (приставка, компьютер, NAS), укажите его адрес.\n\n" +
            "Сейчас в настройках указан $address."
        AlertDialog.Builder(this)
            .setTitle("TorrServe не установлен")
            .setMessage(message)
            .setPositiveButton("Скачать TorrServe") { _, _ -> downloadTorrServe() }
            .setNeutralButton("Указать адрес") { _, _ -> askTorrServeAddress() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /** A field for the address right on this screen: the settings are one long list, finding it there is slow. */
    private fun askTorrServeAddress() {
        val current = Settings.string(this, Settings.TORRSERVE, "127.0.0.1:8090")
        val input = android.widget.EditText(this).apply {
            setText(current)
            setSelection(current.length)
            setTextColor(Color.WHITE)
            setPadding(px(12), px(10), px(12), px(10))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), 0)
            addView(label("Например 192.168.1.10:8090 — приставка или компьютер с TorrServe", 13f, 0xFF8FB39D.toInt()))
            addView(input, LinearLayout.LayoutParams(-1, px(52)).apply { topMargin = px(8) })
        }
        AlertDialog.Builder(this)
            .setTitle("Адрес TorrServe")
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val address = input.text.toString().trim()
                    .removePrefix("http://").removePrefix("https://").trimEnd('/')
                    .ifEmpty { "127.0.0.1:8090" }
                Settings.setString(this, Settings.TORRSERVE, address)
                hintShown = false
                status.text = "Адрес TorrServe: $address"
            }
            .setNegativeButton("Отмена", null)
            .show()
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
                    runOnUiThread {
                        if (!preloading || isFinishing) return@runOnUiThread
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
        openPlayer(this, TorrServe.streamUrl(this, hash, file), name, "$source • ${file.path.substringAfterLast('/')}")
    }

    override fun onDestroy() {
        preloading = false
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Preload card: title, stage, file, peers/seeds/speed/size, buffer line and a progress bar. */
    private inner class PreloadView {
        val title = label("", 18f, Color.WHITE, true)
        val file = label("", 15f, Color.WHITE)
        val info = label("", 14f, 0xFFDCE8E0.toInt())
        val buffer = label("", 14f, 0xFFDCE8E0.toInt())
        val bar = ProgressBar(this@TorrentsActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        val root = LinearLayout(this@TorrentsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(18), px(24), px(18))
            background = GradientDrawable().apply { setColor(0xFF1B2E23.toInt()); cornerRadius = px(4).toFloat() }
            minimumWidth = px(620)
            addView(title)
            addView(label("ПРЕДЗАГРУЗКА", 14f, 0xFF8FB39D.toInt(), true).apply { setPadding(0, px(8), 0, px(4)) })
            addView(file)
            addView(info.apply { setPadding(0, px(6), 0, 0) })
            addView(buffer)
            addView(bar, LinearLayout.LayoutParams(-1, px(10)).apply { topMargin = px(8) })
        }

        fun update(s: TorrentStat) {
            info.text = "Пиры ${s.activePeers}/${s.totalPeers}   Сиды ${s.seeders}   Скорость ${"%.1f".format(s.speed * 8 / 1_000_000)} Мбит/с" +
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
