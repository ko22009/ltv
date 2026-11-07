package ru.lazytv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Opens a stream in the player chosen in the settings: the built-in ExoPlayer (default) or the system's choice. */
fun openPlayer(
    activity: Activity, url: String, name: String, meta: String = "",
    playlist: String = "", index: Int = 0,
    mediaTitle: String = "", originalTitle: String = "", year: String = "", tv: Boolean = false, poster: String? = null,
) {
    if (Settings.string(activity, Settings.PLAYER, "internal") == "external") {
        runCatching {
            activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*"))
        }.onFailure { Toast.makeText(activity, "Нет внешнего плеера", Toast.LENGTH_LONG).show() }
    } else {
        activity.startActivity(
            android.content.Intent(activity, PlayerActivity::class.java).putExtra("url", url).putExtra("name", name)
                .putExtra("meta", meta).putExtra("playlist", playlist).putExtra("index", index)
                .putExtra("mediaTitle", mediaTitle).putExtra("originalTitle", originalTitle)
                .putExtra("year", year).putExtra("tv", tv).putExtra("poster", poster)
        )
    }
}

/** One item of the auto-next queue: the stream, its title, the source/quality line, episode caption, watch key and available streams. */
data class PlayItem(
    val url: String,
    val title: String,
    val meta: String,
    val label: String = "",
    val watchKey: String = "",
    val streams: List<Pair<String, String>> = emptyList(),
)

/**
 * Built-in player with its own control row (title, audio/subtitle track pick, aspect ratio, quality/speed) instead of ExoPlayer's
 * bare default — the LazyMedia Deluxe screen it follows shows the same: title line, source/quality line, round
 * buttons. The position is remembered per address and restored when the setting is on.
 */
class PlayerActivity : Activity() {
    private val scale get() = resources.displayMetrics.widthPixels / 1280f
    private fun dp(value: Int) = (value * scale).toInt()

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var url: String
    private lateinit var view: PlayerView
    private lateinit var overlay: View
    private lateinit var playPause: ImageView
    private lateinit var seek: SeekBar
    private lateinit var position: TextView
    private lateinit var duration: TextView
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private var baseMeta = ""
    private var mediaTitle = ""
    private var originalTitle = ""
    private var mediaYear = ""
    private var isTv = false
    private var posterPath: String? = null
    private lateinit var bottomContainer: LinearLayout
    private var queue: List<PlayItem> = emptyList()
    private var queueIndex = 0
    private var episodeScroll: android.widget.HorizontalScrollView? = null
    private lateinit var episodeButtons: LinearLayout
    private val episodeViews = mutableListOf<TextView>()
    private var resize = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var userSeeking = false
    /** True while the screen is closing: ExoPlayer's own "release timed out" is not a playback error then. */
    private var releasing = false
    private var errorDialog: android.app.AlertDialog? = null
    private var lastBackAt = 0L
    private var playbackSpeed = 1.0f

    private val saver = object : Runnable {
        override fun run() {
            save()
            handler.postDelayed(this, 10_000)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val exo = player
            if (exo != null && !userSeeking) {
                val dur = exo.duration
                if (dur > 0 && dur != C.TIME_UNSET) {
                    seek.max = (dur / 1000).toInt()
                    seek.progress = (exo.currentPosition / 1000).toInt()
                    duration.text = stamp(dur)
                }
                position.text = stamp(exo.currentPosition)
                playPause.setImageDrawable(PathDrawable(if (exo.isPlaying) MenuIcons.PAUSE else MenuIcons.PLAY).apply {
                    setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
                })
                updateStreamMeta(exo)
            }
            handler.postDelayed(this, 500)
        }
    }

    /** Appends live video parameters (resolution, codec, bitrate, buffer in seconds) to the top subtitle line. */
    private fun updateStreamMeta(exo: ExoPlayer) {
        val parts = mutableListOf<String>()
        if (baseMeta.isNotEmpty()) parts.add(baseMeta)
        exo.videoFormat?.let { vf ->
            if (vf.width > 0 && vf.height > 0) parts.add("${vf.width}×${vf.height}")
            videoCodecName(vf.sampleMimeType)?.let { parts.add(it) }
            if (vf.bitrate > 0) parts.add("%.1f Мбит/с".format(vf.bitrate / 1_000_000.0))
        }
        val bufSec = ((exo.bufferedPosition - exo.currentPosition) / 1000).coerceAtLeast(0)
        if (bufSec > 0) parts.add("буфер ${bufSec} с")
        val combined = parts.joinToString(" · ")
        if (metaView.text.toString() != combined) {
            metaView.text = combined
            metaView.visibility = if (combined.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun videoCodecName(mime: String?): String? = when (mime) {
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4"
        else -> null
    }

    private val hider = Runnable { setControlsVisible(false) }

    private fun stamp(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        url = intent.getStringExtra("url").orEmpty()
        val name = intent.getStringExtra("name").orEmpty()
        val meta = intent.getStringExtra("meta").orEmpty()
        mediaTitle = intent.getStringExtra("mediaTitle").orEmpty().ifEmpty { name.substringBefore(" • ").trim() }
        originalTitle = intent.getStringExtra("originalTitle").orEmpty()
        mediaYear = intent.getStringExtra("year").orEmpty()
        isTv = intent.getBooleanExtra("tv", false)
        posterPath = intent.getStringExtra("poster")
        queue = parseQueue(intent.getStringExtra("playlist").orEmpty())
        queueIndex = intent.getIntExtra("index", 0).coerceIn(0, maxOf(queue.size - 1, 0))
        if (queue.isNotEmpty()) url = queue[queueIndex].url

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        view = PlayerView(this)
        view.useController = false
        android.util.Log.i("LazyTV", "open stream on " + Uri.parse(url).host)
        // otherwise the full-screen video surface sits in the D-pad focus search too and steals it from the buttons
        view.isFocusable = false
        view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        overlay = buildOverlay(name, meta)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        val exo = ExoPlayer.Builder(this).build()
        view.player = exo
        applyVoicePreference(exo)
        exo.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        if (Settings.bool(this, Settings.REMEMBER_POSITION)) {
            val saved = savedPosition()
            if (saved > 5_000L) {
                exo.seekTo(saved)
                Toast.makeText(this, "Продолжено с ${stamp(saved)}", Toast.LENGTH_SHORT).show()
            }
        }
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("LazyTV", "player error: " + error.errorCodeName, error)
                if (releasing || isFinishing) return
                showErrorDialog(error)
            }

            override fun onPlaybackStateChanged(state: Int) {
                android.util.Log.i("LazyTV", "player state " + state)
                if (state == Player.STATE_ENDED) {
                    clearPosition()
                    advance()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (overlay.visibility == View.VISIBLE) resetHideTimer()
            }
        })
        exo.prepare()
        exo.playWhenReady = true
        player = exo
        handler.postDelayed(saver, 10_000)
        handler.post(ticker)
        showControls()
    }

    /** Applies the default voice/subtitle preference from settings before the stream starts. */
    private fun applyVoicePreference(exo: ExoPlayer) {
        val voice = Settings.string(this, Settings.VOICE, "any")
        val builder = exo.trackSelectionParameters.buildUpon()
        when (voice) {
            "ru" -> builder.setPreferredAudioLanguage("ru").setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            "original" -> builder
                .setPreferredAudioLanguages("ja", "en", "ko", "zh", "fr", "de", "es", "it")
                .setPreferredTextLanguage("ru")
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        }
        exo.trackSelectionParameters = builder.build()
    }

    // --- the control row -------------------------------------------------------------------------

    private fun parseQueue(json: String): List<PlayItem> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val array = org.json.JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val streams = mutableListOf<Pair<String, String>>()
                o.optJSONArray("s")?.let { sArr ->
                    for (j in 0 until sArr.length()) {
                        val so = sArr.getJSONObject(j)
                        val q = so.optString("q")
                        val u = so.optString("u")
                        if (q.isNotEmpty() && u.isNotEmpty()) streams.add(q to u)
                    }
                }
                PlayItem(o.getString("u"), o.optString("t"), o.optString("m"), o.optString("e"), o.optString("k"), streams)
            }
        }.getOrDefault(emptyList())
    }

    /** The next episode of the queue when the setting is on; the player is reused, only the media item changes. */
    private fun advance() {
        if (releasing || !Settings.bool(this, Settings.AUTO_NEXT) || queueIndex + 1 >= queue.size) {
            showControls()
            return
        }
        switchTo(queueIndex + 1, toast = true, saveCurrent = false)
    }

    /** Switches to another episode of the queue (also used by the episode buttons in the control row). */
    private fun switchTo(index: Int, toast: Boolean, saveCurrent: Boolean = true) {
        if (releasing) return
        val exo = player ?: return
        val item = queue.getOrNull(index) ?: return
        if (index == queueIndex) return
        if (saveCurrent) save()
        queueIndex = index
        url = item.url
        if (item.watchKey.isNotEmpty()) EpisodeState.setWatched(this, item.watchKey, true)
        titleView.text = item.title
        baseMeta = item.meta
        metaView.text = item.meta
        metaView.visibility = if (item.meta.isEmpty()) View.GONE else View.VISIBLE
        seek.progress = 0
        position.text = "0:00"
        duration.text = "0:00"
        exo.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        if (Settings.bool(this, Settings.REMEMBER_POSITION)) {
            val saved = savedPosition()
            if (saved > 5_000L) exo.seekTo(saved)
        }
        exo.prepare()
        exo.playWhenReady = true
        updateEpisodeButtons()
        scrollToActiveEpisode()
        resetHideTimer()
        if (toast) Toast.makeText(this, "Далее: ${item.label.ifEmpty { item.title }}", Toast.LENGTH_SHORT).show()
    }

    private fun scrollToActiveEpisode() {
        val scroll = episodeScroll ?: return
        val activeView = episodeViews.getOrNull(queueIndex) ?: return
        scroll.post {
            val targetX = (activeView.left - (scroll.width - activeView.width) / 2).coerceAtLeast(0)
            scroll.smoothScrollTo(targetX, 0)
        }
    }

    private fun label(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun round(icon: String, big: Boolean = false, action: () -> Unit) = ImageView(this).apply {
        setImageDrawable(PathDrawable(icon).apply { setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN) })
        val pad = dp(if (big) 14 else 10)
        setPadding(pad, pad, pad, pad)
        isFocusable = true
        isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(0xFF6366F1.toInt()); shape = GradientDrawable.OVAL })
            addState(intArrayOf(), GradientDrawable().apply { setColor(0x55000000); shape = GradientDrawable.OVAL })
        }
        setOnClickListener { resetHideTimer(); action() }
    }

    private fun buildOverlay(name: String, meta: String): View {
        val overlay = FrameLayout(this)

        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        top.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
        top.setPadding(dp(48), dp(28), dp(48), dp(60))
        titleView = label(queue.getOrNull(queueIndex)?.title ?: name, 24f, Color.WHITE, true)
        baseMeta = queue.getOrNull(queueIndex)?.meta ?: meta
        metaView = label(baseMeta, 15f, 0xFFCBD5E1.toInt()).apply {
            setPadding(0, dp(6), 0, 0)
            visibility = if (baseMeta.isEmpty()) View.GONE else View.VISIBLE
        }
        if (titleView.text.isNotEmpty()) top.addView(titleView)
        top.addView(metaView)
        overlay.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this)
        bottomContainer = bottom
        bottom.orientation = LinearLayout.VERTICAL
        bottom.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
        bottom.setPadding(dp(48), dp(60), dp(48), dp(28))

        val seekRow = LinearLayout(this)
        seekRow.gravity = Gravity.CENTER_VERTICAL
        position = label("0:00", 14f, 0xFFCBD5E1.toInt())
        duration = label("0:00", 14f, 0xFFCBD5E1.toInt())
        seek = SeekBar(this)
        seek.isFocusable = true
        seek.progressTintList = android.content.res.ColorStateList.valueOf(0xFF6366F1.toInt())
        seek.thumbTintList = android.content.res.ColorStateList.valueOf(0xFFA5B4FC.toInt())
        seek.setOnFocusChangeListener { _, hasFocus ->
            seek.progressTintList = android.content.res.ColorStateList.valueOf(if (hasFocus) 0xFF818CF8.toInt() else 0xFF6366F1.toInt())
            seek.thumbTintList = android.content.res.ColorStateList.valueOf(if (hasFocus) Color.WHITE else 0xFFA5B4FC.toInt())
        }
        seek.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val sign = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                        seekByStep(sign * seekStepMs(event.repeatCount))
                        resetHideTimer()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        playPause.requestFocus()
                        resetHideTimer()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        togglePlay()
                        resetHideTimer()
                        true
                    }
                    else -> false
                }
            } else false
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, byUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) { userSeeking = false; player?.seekTo(sb.progress * 1000L) }
        })
        seekRow.addView(position)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12); rightMargin = dp(12) })
        seekRow.addView(duration)
        bottom.addView(seekRow)

        val buttons = LinearLayout(this)
        buttons.gravity = Gravity.CENTER_VERTICAL
        buttons.setPadding(0, dp(14), 0, 0)
        buttons.addView(round(MenuIcons.REPLAY10) { seekByStep(-10_000L) },
            LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(18) })
        playPause = round(MenuIcons.PAUSE, true) { togglePlay() }
        buttons.addView(playPause, LinearLayout.LayoutParams(dp(68), dp(68)).apply { rightMargin = dp(18) })
        buttons.addView(round(MenuIcons.FORWARD10) { seekByStep(10_000L) },
            LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(32) })
        buttons.addView(round(MenuIcons.ASPECT) { cycleAspect() }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(10) })
        buttons.addView(round(MenuIcons.AUDIO_TRACK) { trackDialog(C.TRACK_TYPE_AUDIO, "Звуковая дорожка") },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(10) })
        buttons.addView(round(MenuIcons.SUBTITLES) { trackDialog(C.TRACK_TYPE_TEXT, "Субтитры") },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(10) })
        buttons.addView(round(MenuIcons.GEAR) { qualityAndSpeedDialog() },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(10) })
        buttons.addView(round(MenuIcons.LINK) { changeSourceDialog() },
            LinearLayout.LayoutParams(dp(52), dp(52)))
        bottom.addView(buttons)

        rebuildEpisodeStrip()

        overlay.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return overlay
    }

    /** Rebuilds the episode strip at the bottom of the player when the series or source changes. */
    private fun rebuildEpisodeStrip() {
        episodeScroll?.let { bottomContainer.removeView(it) }
        episodeScroll = null
        episodeViews.clear()
        if (queue.size > 1) {
            episodeButtons = LinearLayout(this)
            episodeButtons.orientation = LinearLayout.HORIZONTAL
            queue.forEachIndexed { index, item ->
                val caption = item.label.ifEmpty { item.title.substringAfter(" • ", "Серия ${index + 1}") }
                val button = label(caption, 15f, 0xFFCBD5E1.toInt()).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(18), 0, dp(18), 0)
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { switchTo(index, toast = false) }
                }
                episodeViews.add(button)
                episodeButtons.addView(button, LinearLayout.LayoutParams(-2, dp(46)).apply { rightMargin = dp(10) })
            }
            updateEpisodeButtons()
            val scroll = android.widget.HorizontalScrollView(this)
            scroll.isHorizontalScrollBarEnabled = false
            scroll.addView(episodeButtons)
            episodeScroll = scroll
            bottomContainer.addView(scroll, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) })
            scrollToActiveEpisode()
        }
    }

    /** Opens a dialog to switch to another video site or torrents without exiting the player. */
    private fun changeSourceDialog() {
        val title = mediaTitle.ifEmpty { titleView.text.toString().substringBefore(" • ").trim() }
        if (title.isEmpty()) {
            Toast.makeText(this, "Название фильма неизвестно", Toast.LENGTH_SHORT).show()
            return
        }
        val cached = Sources.cachedOnly(this, title, originalTitle, mediaYear)
        if (cached != null) {
            showSourcePicker(cached)
            return
        }
        Toast.makeText(this, "Поиск источников…", Toast.LENGTH_SHORT).show()
        Thread {
            val list = Sources.searchCached(applicationContext, title, originalTitle, mediaYear)
            handler.post {
                if (!isFinishing && !releasing) {
                    showSourcePicker(list)
                }
            }
        }.start()
    }

    private fun showSourcePicker(variants: List<Variant>) {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        val currentItem = queue.getOrNull(queueIndex)

        variants.forEach { v ->
            val isCurrent = currentItem?.let {
                (v.ref.isNotEmpty() && it.watchKey.contains(v.ref)) ||
                    (it.meta.contains(v.source, ignoreCase = true) && it.title.startsWith(v.title))
            } ?: false
            val label = buildString {
                if (isCurrent) append("✓ ")
                append(v.source)
                if (v.title.isNotEmpty() && !v.title.equals(mediaTitle, ignoreCase = true)) {
                    append(" • ")
                    append(v.title)
                }
                if (v.note.isNotEmpty()) {
                    append(" • ")
                    append(v.note)
                }
            }
            items.add(label)
            actions.add {
                if (isCurrent) {
                    Toast.makeText(this, "Этот источник уже воспроизводится", Toast.LENGTH_SHORT).show()
                } else {
                    switchSource(v)
                }
            }
        }

        val isTorrent = url.contains(":8090") || baseMeta.contains("torr", ignoreCase = true)
        items.add((if (isTorrent) "✓ " else "") + "Торренты (TorrServe)")
        actions.add {
            save()
            startActivity(
                android.content.Intent(this, TorrentsActivity::class.java)
                    .putExtra("name", mediaTitle)
                    .putExtra("original", originalTitle)
                    .putExtra("year", mediaYear)
                    .putExtra("tv", isTv)
                    .putExtra("poster", posterPath)
            )
            finish()
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Смена источника")
            .setItems(items.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun switchSource(v: Variant) {
        val exo = player ?: return
        val pos = exo.currentPosition
        Toast.makeText(this, "Загрузка: ${v.source}…", Toast.LENGTH_SHORT).show()
        Thread {
            val episodes = runCatching { Sources.byId(v.sourceId)?.episodes(v) }.getOrNull()
            handler.post {
                if (isFinishing || releasing) return@post
                if (episodes.isNullOrEmpty()) {
                    Toast.makeText(this@PlayerActivity, "Не удалось загрузить видео из ${v.source}", Toast.LENGTH_SHORT).show()
                    return@post
                }
                applyNewVariant(v, episodes, pos)
            }
        }.start()
    }

    private fun applyNewVariant(v: Variant, episodes: List<Episode>, savedPos: Long) {
        val exo = player ?: return
        val wanted = Settings.string(this, Settings.QUALITY, "best")
        val newQueue = mutableListOf<PlayItem>()
        episodes.forEach { ep ->
            val stream = streamFor(ep, wanted, null)
            newQueue.add(
                PlayItem(
                    url = stream.second,
                    title = "${v.title} • ${ep.title}",
                    meta = "${v.source} • ${stream.first}",
                    label = shortEpisode(ep.title),
                    watchKey = EpisodeState.key(v.sourceId, v.ref, ep.title),
                    streams = ep.streams
                )
            )
        }
        val targetIndex = queueIndex.coerceIn(0, episodes.size - 1)
        queue = newQueue
        queueIndex = targetIndex
        val item = queue[targetIndex]
        save()
        url = item.url
        if (item.watchKey.isNotEmpty()) EpisodeState.setWatched(this, item.watchKey, true)
        titleView.text = item.title
        baseMeta = item.meta
        metaView.text = item.meta
        metaView.visibility = if (item.meta.isEmpty()) View.GONE else View.VISIBLE
        seek.progress = 0
        position.text = "0:00"
        duration.text = "0:00"
        exo.stop()
        exo.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        if (savedPos > 5_000L) {
            exo.seekTo(savedPos)
        }
        exo.prepare()
        exo.playWhenReady = true
        rebuildEpisodeStrip()
        resetHideTimer()
        Toast.makeText(this, "Источник: ${v.source}", Toast.LENGTH_SHORT).show()
    }

    /** Repaints the episode strip: red for the current episode, indigo while focused. */
    private fun updateEpisodeButtons() {
        episodeViews.forEachIndexed { index, view ->
            val active = index == queueIndex
            view.setTextColor(if (active) Color.WHITE else 0xFFCBD5E1.toInt())
            view.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setColor(0xFF6366F1.toInt()); cornerRadius = dp(3).toFloat()
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(if (active) 0xFFE57373.toInt() else 0x55000000)
                    cornerRadius = dp(3).toFloat()
                })
            }
        }
    }

    private fun togglePlay() {
        val exo = player ?: return
        exo.playWhenReady = !exo.playWhenReady
    }

    private fun cycleAspect() {
        resize = when (resize) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        view.resizeMode = resize
        Toast.makeText(this, when (resize) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Растянуть по высоте"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Растянуть на весь экран"
            else -> "Исходные пропорции"
        }, Toast.LENGTH_SHORT).show()
    }

    /** Everything the format actually reports: language, channel layout, codec, bitrate — not just a placeholder name. */
    private fun trackLabel(format: androidx.media3.common.Format, index: Int, type: Int): String {
        val parts = mutableListOf<String>()
        // a label that just spells out the language in English ("English") duplicates the Russian name added below
        val englishLangName = format.language?.let { runCatching { java.util.Locale(it).displayLanguage }.getOrNull() }
        format.label?.takeUnless { it.equals(englishLangName, true) }?.let { parts.add(it) }
        format.language?.let { lang ->
            val name = runCatching { java.util.Locale(lang).getDisplayLanguage(java.util.Locale("ru")) }.getOrNull()
            parts.add(if (!name.isNullOrBlank() && !name.equals(lang, true)) name.replaceFirstChar { it.uppercase() } else lang.uppercase())
        }
        if (format.channelCount != androidx.media3.common.Format.NO_VALUE) {
            parts.add(when (format.channelCount) {
                1 -> "моно"; 2 -> "стерео"; 6 -> "5.1"; 8 -> "7.1"
                else -> "${format.channelCount} кан."
            })
        }
        codecName(format.sampleMimeType)?.let { parts.add(it) }
        if (format.bitrate != androidx.media3.common.Format.NO_VALUE && format.bitrate > 0) parts.add("${format.bitrate / 1000} кбит/с")
        return parts.joinToString(" · ").ifEmpty { if (type == C.TRACK_TYPE_TEXT) "Субтитры ${index + 1}" else "Дорожка ${index + 1}" }
    }

    private fun codecName(mime: String?): String? = when (mime) {
        "audio/mp4a-latm" -> "AAC"; "audio/ac3" -> "AC-3"; "audio/eac3" -> "E-AC-3"; "audio/opus" -> "Opus"
        "audio/mpeg" -> "MP3"; "audio/vnd.dts" -> "DTS"; "audio/vnd.dts.hd" -> "DTS-HD"; "audio/flac" -> "FLAC"
        "text/vtt" -> "WebVTT"; "application/x-subrip" -> "SRT"; "text/x-ssa" -> "SSA/ASS"
        "application/cea-608" -> "CEA-608"; "application/cea-708" -> "CEA-708"
        else -> null
    }

    /** Audio or subtitle tracks found in the stream, picked by hand; "Выключить" clears a text-track override. */
    private fun trackDialog(type: Int, title: String) {
        val exo = player ?: return
        val groups = exo.currentTracks.groups.filter { it.type == type }
        if (groups.isEmpty()) {
            Toast.makeText(this, if (type == C.TRACK_TYPE_TEXT) "В потоке нет субтитров" else "В потоке одна звуковая дорожка", Toast.LENGTH_SHORT).show()
            return
        }
        val entries = mutableListOf<String>()
        val picks = mutableListOf<Pair<androidx.media3.common.TrackGroup, Int>?>()
        var checkedIndex = -1
        if (type == C.TRACK_TYPE_TEXT) {
            entries.add("Выключить")
            picks.add(null)
            if (exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || groups.none { it.isSelected }) {
                checkedIndex = 0
            }
        }
        var index = 0
        groups.forEach { group ->
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i) && checkedIndex == -1) checkedIndex = entries.size
                entries.add(trackLabel(group.getTrackFormat(i), index, type))
                picks.add(group.mediaTrackGroup to i)
                index++
            }
        }
        android.app.AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(entries.toTypedArray(), checkedIndex) { dialog, which ->
                val pick = picks[which]
                val builder = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
                if (pick != null) builder.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(pick.first, pick.second))
                else builder.setTrackTypeDisabled(type, true)
                exo.trackSelectionParameters = builder.build()
                dialog.dismiss()
            }.show()
    }

    /** Quality and playback speed picker opened by the gear button in the player. */
    private fun qualityAndSpeedDialog() {
        val exo = player ?: return
        val itemStreams = queue.getOrNull(queueIndex)?.streams.orEmpty()
        val videoGroups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        val hasVideoTracks = videoGroups.sumOf { it.length } > 1
        val speedLabel = "Скорость (${if (playbackSpeed == 1.0f) "1×" else "${playbackSpeed}×"})"
        if (itemStreams.size <= 1 && !hasVideoTracks) {
            speedDialog()
            return
        }
        val currentQuality = baseMeta.substringAfterLast(" • ", "").takeIf { it.isNotEmpty() } ?: "Авто"
        val options = arrayOf("Качество ($currentQuality)", speedLabel)
        android.app.AlertDialog.Builder(this).setTitle("Параметры воспроизведения")
            .setItems(options) { _, which ->
                if (which == 0) qualityDialog(itemStreams, videoGroups) else speedDialog()
            }.show()
    }

    private fun qualityDialog(
        itemStreams: List<Pair<String, String>>,
        videoGroups: List<androidx.media3.common.Tracks.Group>,
    ) {
        val exo = player ?: return
        if (itemStreams.size > 1) {
            val labels = itemStreams.map { it.first }.toTypedArray()
            val checked = itemStreams.indexOfFirst { it.second == url }.coerceAtLeast(0)
            android.app.AlertDialog.Builder(this).setTitle("Качество потока")
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    val (qName, qUrl) = itemStreams[which]
                    if (qUrl != url) {
                        val pos = exo.currentPosition
                        val wasPlaying = exo.playWhenReady
                        save()
                        url = qUrl
                        val sourcePrefix = baseMeta.substringBefore(" • ", baseMeta)
                        baseMeta = if (sourcePrefix.isNotEmpty()) "$sourcePrefix • $qName" else qName
                        updateStreamMeta(exo)
                        exo.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                        exo.seekTo(pos)
                        exo.prepare()
                        exo.playWhenReady = wasPlaying
                        Toast.makeText(this, "Качество: $qName", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }.show()
            return
        }
        val entries = mutableListOf("Авто")
        val picks = mutableListOf<Pair<androidx.media3.common.TrackGroup, Int>?>(null)
        var checked = if (exo.trackSelectionParameters.overrides.none { it.key.type == C.TRACK_TYPE_VIDEO }) 0 else -1
        videoGroups.forEach { group ->
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                val label = buildString {
                    if (f.height > 0) append("${f.height}p") else append("Поток ${entries.size}")
                    if (f.bitrate > 0) append(" · %.1f Мбит/с".format(f.bitrate / 1_000_000.0))
                }
                if (checked == -1 && group.isTrackSelected(i)) checked = entries.size
                entries.add(label)
                picks.add(group.mediaTrackGroup to i)
            }
        }
        android.app.AlertDialog.Builder(this).setTitle("Качество видео")
            .setSingleChoiceItems(entries.toTypedArray(), checked) { dialog, which ->
                val pick = picks[which]
                val builder = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                if (pick != null) builder.setOverrideForType(TrackSelectionOverride(pick.first, pick.second))
                exo.trackSelectionParameters = builder.build()
                dialog.dismiss()
            }.show()
    }

    private fun speedDialog() {
        val exo = player ?: return
        val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = arrayOf("0.75×", "1.0× (обычная)", "1.25×", "1.5×", "2.0×")
        val checked = speeds.indexOfFirst { it == playbackSpeed }.coerceAtLeast(1)
        android.app.AlertDialog.Builder(this).setTitle("Скорость воспроизведения")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                playbackSpeed = speeds[which]
                exo.setPlaybackSpeed(playbackSpeed)
                Toast.makeText(this, "Скорость: ${labels[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }.show()
    }

    private fun seekStepMs(repeatCount: Int): Long = when {
        repeatCount > 15 -> 60_000L
        repeatCount > 5 -> 30_000L
        else -> 10_000L
    }

    private fun seekByStep(deltaMs: Long) {
        val exo = player ?: return
        val dur = exo.duration
        val target = (exo.currentPosition + deltaMs).coerceAtLeast(0L).let {
            if (dur > 0 && dur != C.TIME_UNSET) it.coerceAtMost(dur) else it
        }
        exo.seekTo(target)
        position.text = stamp(target)
        if (dur > 0 && dur != C.TIME_UNSET) seek.progress = (target / 1000).toInt()
    }

    // --- show/hide ---------------------------------------------------------------------------------

    private fun setControlsVisible(visible: Boolean) {
        overlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) { resetHideTimer(); playPause.requestFocus() } else handler.removeCallbacks(hider)
    }

    private fun showControls() = setControlsVisible(true)

    private fun resetHideTimer() {
        handler.removeCallbacks(hider)
        if (player?.isPlaying == true) {
            handler.postDelayed(hider, 4000)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); showControls(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.playWhenReady = true; showControls(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.playWhenReady = false; showControls(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekByStep(seekStepMs(event.repeatCount)); showControls(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekByStep(-seekStepMs(event.repeatCount)); showControls(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (queueIndex + 1 < queue.size) switchTo(queueIndex + 1, toast = true)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (queueIndex > 0) switchTo(queueIndex - 1, toast = true)
                    return true
                }
            }
            if (overlay.visibility == View.VISIBLE) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    setControlsVisible(false)
                    return true
                }
                resetHideTimer()
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && currentFocus is ImageView) {
                    seek.requestFocus()
                    return true
                }
                // down from the round buttons goes straight to the episode strip
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && currentFocus is ImageView && episodeViews.isNotEmpty()) {
                    (episodeViews.getOrNull(queueIndex) ?: episodeViews.first()).requestFocus()
                    return true
                }
            } else {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackAt < 2500L) {
                            finish()
                        } else {
                            lastBackAt = now
                            Toast.makeText(this, "Если хотите выйти, нажмите ещё раз кнопку «Назад»", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                    // seek directly without opening the bar, so repeated Left/Right presses keep seeking instead of walking buttons
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        seekByStep(-10_000L)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        seekByStep(10_000L)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> {
                        togglePlay()
                        showControls()
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                        return super.dispatchKeyEvent(event)
                    }
                    else -> {
                        if (KeyEvent.isModifierKey(event.keyCode)) return super.dispatchKeyEvent(event)
                        showControls()
                        return true
                    }
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP && overlay.visibility != View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Stable episode key when playing from a series/film source, so switching quality or mirror keeps the resume time. */
    private fun positionKey(): String = queue.getOrNull(queueIndex)?.watchKey?.takeIf { it.isNotEmpty() } ?: url

    private fun savedPosition(): Long {
        val prefs = getSharedPreferences("positions", 0)
        return prefs.getLong(positionKey(), 0L).takeIf { it > 0L } ?: prefs.getLong(url, 0L)
    }

    private fun clearPosition() {
        getSharedPreferences("positions", 0).edit().remove(positionKey()).remove(url).apply()
    }

    private fun save() {
        val exo = player ?: return
        if (Settings.bool(this, Settings.REMEMBER_POSITION)) {
            val pos = exo.currentPosition
            val dur = exo.duration
            if (dur > 0 && dur != C.TIME_UNSET && (pos >= dur - 90_000L || pos * 100 / dur >= 95)) {
                clearPosition()
            } else if (pos >= 5_000L) {
                getSharedPreferences("positions", 0).edit().putLong(positionKey(), pos).apply()
            }
        }
    }

    private fun showErrorDialog(error: PlaybackException) {
        errorDialog?.dismiss()
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle("Ошибка воспроизведения")
            .setMessage(Errors.explainPlayer(error))
            .setPositiveButton("Открыть во внешнем плеере") { _, _ ->
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*"))
                    finish()
                }.onFailure {
                    Toast.makeText(this, "Нет внешнего плеера", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Закрыть") { _, _ -> finish() }
        val cause = error.cause ?: error
        if (Errors.isSsl(cause)) {
            builder.setNeutralButton("Настройки даты") { _, _ -> Errors.openDateSettings(this) }
        }
        errorDialog = builder.show()
    }

    override fun onStop() {
        super.onStop()
        errorDialog?.dismiss()
        errorDialog = null
        save()
        releasing = true
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        finish()
    }
}
