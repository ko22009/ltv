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
    tmdbId: Long = 0L,
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
                .putExtra("year", year).putExtra("tv", tv).putExtra("poster", poster).putExtra("tmdbId", tmdbId)
        )
        // no cross-fade: the episode page showed through the opening player and its colours blinked
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
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
    private var resumeWhenOnline = false
    private var everReady = false
    private lateinit var waitingDots: View
    private var bufferEmpty = false
    private var offlineNoticeShown = false
    private lateinit var touchOverlay: PlayerTouchOverlay
    /** The Back button in the top left of the controls: up from the progress bar lands on it. */
    private lateinit var playerBack: View
    private lateinit var pauseBadge: View
    private fun updatePauseBadge() {
        if (!::pauseBadge.isInitialized || !::overlay.isInitialized) return
        val paused = player?.let { !it.playWhenReady && it.playbackState == Player.STATE_READY } == true
        // on pause the sign stays in the middle whether the controls are open or hidden (they take the top and the bottom only),
        // so it does not blink when they are shown and hidden
        // the dots take the same place while the buffer is empty: the pause sign steps aside
        val dotsShown = ::waitingDots.isInitialized && waitingDots.visibility == View.VISIBLE
        pauseBadge.visibility = if (paused && !dotsShown) View.VISIBLE else View.GONE
    }
    // the dots only while nothing plays: an empty buffer or a stopped player; offline with a buffer left shows nothing
    private fun showWaiting() {
        if (!::waitingDots.isInitialized) return
        // buffering with more than ~1.5 s already loaded ahead is not "buffer ran out" (the player can report it while it loads on)
        val runOut = bufferEmpty && (player?.let { it.bufferedPosition - it.currentPosition < 1500 } ?: true)
        val show = runOut || resumeWhenOnline
        // the notice comes when nothing plays (the buffer has run out, or the stream has not started yet) and there is no network:
        // once per stall, also when the network drops while the dots are already there
        if (show && !Connectivity.online && !offlineNoticeShown && !isFinishing) {
            offlineNoticeShown = true
            Toast.makeText(this, "Нет интернета. Просмотр продолжится, когда связь вернётся", Toast.LENGTH_LONG).show()
        }
        if (!show || Connectivity.online) offlineNoticeShown = false
        waitingDots.visibility = if (show) View.VISIBLE else View.GONE
        updatePauseBadge()
        bufferSpinner.visibility = View.GONE
    }
    private val onlineListener: (Boolean) -> Unit = { online ->
        if (online && resumeWhenOnline && !isFinishing) {
            resumeWhenOnline = false
            player?.let { it.prepare(); it.play() }
        }
        showWaiting()
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var url: String
    private lateinit var view: PlayerView
    private lateinit var bufferSpinner: View
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
    /** TMDB id of the title on the card the playback started from; 0 when unknown (then nothing goes to "continue watching"). */
    private var tmdbId = 0L
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
    private var errorDialog: android.app.Dialog? = null
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
                    seek.secondaryProgress = (exo.bufferedPosition / 1000).toInt()
                    duration.text = stamp(dur)
                }
                position.text = stamp(exo.currentPosition)
                playPause.setImageDrawable(PathDrawable(if (exo.isPlaying) MenuIcons.PAUSE else MenuIcons.PLAY))
                updateStreamMeta(exo)
            }
            updateSeekColors()
            updatePauseBadge()
            showWaiting()
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
        Connectivity.listen(onlineListener)
        if (!DeviceType.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        url = intent.getStringExtra("url").orEmpty()
        val name = intent.getStringExtra("name").orEmpty()
        val meta = intent.getStringExtra("meta").orEmpty()
        mediaTitle = intent.getStringExtra("mediaTitle").orEmpty().ifEmpty { name.substringBefore(" • ").trim() }
        originalTitle = intent.getStringExtra("originalTitle").orEmpty()
        mediaYear = intent.getStringExtra("year").orEmpty()
        isTv = intent.getBooleanExtra("tv", false)
        posterPath = intent.getStringExtra("poster")
        tmdbId = intent.getLongExtra("tmdbId", 0L)
        queue =parseQueue(intent.getStringExtra("playlist").orEmpty())
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
        // the buffering circle is the app's own spinner (the same as on the card and in the lists), not the default blue one of the player
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        bufferSpinner = Spinner(this, dp(SPINNER_SCREEN))
        root.addView(bufferSpinner, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        // no internet: the same place, a circle with three moving dots; the playback goes on by itself when the network is back
        // the same size as the pause sign (84 real dp), so the two replace each other without a jump
        waitingDots = WaitingDots(this, (84 * resources.displayMetrics.density).toInt())
        waitingDots.visibility = View.GONE
        root.addView(waitingDots, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        // on pause with the controls hidden the sign stays, so nobody takes the still picture for a freeze
        pauseBadge = ImageView(this).apply {
            setImageDrawable(PathDrawable(MenuIcons.PAUSE).apply { setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN) })
            // exactly the size of the sign that flashes after a double tap (PlayerTouchOverlay: 84 dp, padding 16 dp, real dp),
            // so the static sign is the same disc that stays on the screen
            val density = resources.displayMetrics.density
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { setColor(OVERLAY_SOFT); shape = GradientDrawable.OVAL }
            visibility = View.GONE
        }
        // the sign is added above the touch layer and the controls (below): it must catch the tap itself, one tap resumes the video
        pauseBadge.isClickable = true
        pauseBadge.setOnClickListener { togglePlay(); resetHideTimer() }
        // the window may start under the status bar: the spinner is moved up so it stands in the middle of the screen, not of the window
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val at = IntArray(2)
            root.getLocationOnScreen(at)
            val shift = resources.displayMetrics.heightPixels / 2f - (at[1] + root.height / 2f)
            bufferSpinner.translationY = shift
            waitingDots.translationY = shift
            pauseBadge.translationY = shift
        }

        touchOverlay = PlayerTouchOverlay(
            context = this,
            activity = this,
            onSeek = { deltaMs -> seekByStep(deltaMs) },
            onTogglePlay = { togglePlay() },
            onToggleControls = { at ->
                if (overlay.visibility == View.VISIBLE) setControlsVisible(false)
                // on a phone the controls come only from a tap at the top or the bottom (a quarter of the height each):
                // a tap in the middle does nothing, so the picture is not covered by accident
                else if (DeviceType.isTv(this) || at < 0.25f || at > 0.75f) setControlsVisible(true)
            },
            onUserAction = { resetHideTimer() }
        )
        root.addView(touchOverlay, FrameLayout.LayoutParams(-1, -1))

        overlay = buildOverlay(name, meta)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        // above the controls too (they are a full-screen layer): the sign catches its own tap
        val badgeSize = (84 * resources.displayMetrics.density).toInt()
        root.addView(pauseBadge, FrameLayout.LayoutParams(badgeSize, badgeSize, android.view.Gravity.CENTER))
        // with the controls shown the empty part of the panel passes the touches on to the gesture layer below it:
        // the double tap (pause in the middle, 10 seconds on the sides) and the drags work with the panel open too,
        // a single tap hides the panel as before
        if (!DeviceType.isTv(this)) overlay.setOnTouchListener { _, ev -> touchOverlay.onTouchEvent(ev) }
        setContentView(root)
        hideSystemBars()

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
                // the connection dropped: no error dialog, the playback goes on from the same place when the network is back
                if (!Connectivity.online) {
                    resumeWhenOnline = true
                    showWaiting()
                    return
                }
                // a film opened from the card: the next source starts by itself
                if (!everReady && PlaybackFallback.next(this@PlayerActivity, tmdbId)) return
                showErrorDialog(error)
            }

            override fun onPlaybackStateChanged(state: Int) {
                android.util.Log.i("LazyTV", "player state " + state)
                if (state == Player.STATE_READY) everReady = true
                // the buffer is empty: the same circle with dots as without internet (no separate rotating arc over the video)
                bufferEmpty = state == Player.STATE_BUFFERING
                showWaiting()
                updateSeekColors()
                if (state == Player.STATE_ENDED) {
                    clearPosition()
                    advance()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (overlay.visibility == View.VISIBLE) resetHideTimer()
                updatePauseBadge()
            }
        })
        exo.prepare()
        exo.playWhenReady = true
        player = exo
        // a stream that neither fails nor starts (a silent site) must not hold the screen: after 15 s the next source is tried
        handler.postDelayed({
            if (!everReady && !isFinishing && !releasing) PlaybackFallback.next(this, tmdbId)
        }, 15_000)
        handler.postDelayed(saver, 10_000)
        handler.post(ticker)
        // on opening the controls stay hidden: the film is still loading and the screen shows only that; a tap or the remote's
        // arrows bring them (down opens the panel on the progress bar)
        setControlsVisible(false)
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
        if (item.url.isEmpty()) {
            // the link of this episode was not asked for yet (HDRezka series): ask now, then switch
            Toast.makeText(this, "Загрузка серии…", Toast.LENGTH_SHORT).show()
            Thread {
                val streams = LazyLinks.resolve(item.watchKey)
                handler.post {
                    if (releasing || isFinishing) return@post
                    if (streams.isEmpty()) {
                        Toast.makeText(this, "Не удалось получить ссылку на серию", Toast.LENGTH_LONG).show()
                        return@post
                    }
                    val pick = pickStream(streams, Settings.string(this, Settings.QUALITY, "best"))
                    val meta = if (item.meta.contains(" • ")) item.meta.substringBeforeLast(" • ") + " • " + pick.first else item.meta
                    queue = queue.toMutableList().also { it[index] = item.copy(url = pick.second, meta = meta, streams = streams) }
                    switchTo(index, toast, saveCurrent)
                }
            }.start()
            return
        }
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
        warmNext()
    }

    /** The link of the next episode is asked for while this one plays, so the switch is not delayed. */
    private fun warmNext() {
        val next = queue.getOrNull(queueIndex + 1)?.takeIf { it.url.isEmpty() && it.watchKey.isNotEmpty() } ?: return
        Thread { LazyLinks.resolve(next.watchKey) }.start()
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
        setImageDrawable(PathDrawable(icon))
        // dark on the light cursor of a TV, white otherwise
        imageTintList = cursorColors(this@PlayerActivity, Color.WHITE)
        val pad = dp(if (big) 14 else 10)
        setPadding(pad, pad, pad, pad)
        isFocusable = true
        isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(cursorFill(this@PlayerActivity)); shape = GradientDrawable.OVAL })
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(cursorFill(this@PlayerActivity)); shape = GradientDrawable.OVAL })
            addState(intArrayOf(), GradientDrawable().apply { setColor(OVERLAY_SOFT); shape = GradientDrawable.OVAL })
        }
        setOnClickListener { resetHideTimer(); action() }
    }

    private fun updateSeekColors() {
        if (!::seek.isInitialized) return
        // the colours of the loading spinner (Loading.kt): white arc on a faint white track, in every state
        val progressColor = Color.WHITE
        // as the season buttons of the design system: the light plate at rest, the teal of the focus under the cursor
        // as the season buttons of the design system: the light plate at rest, the dark teal of the focus under the cursor
        // under the cursor the thumb is a white dot inside a whitish halo (the cursor colour is light and would vanish on the bar)
        val thumbColor = if (seek.isFocused) Color.WHITE else ACTIVE_PLATE
        seek.progressTintList = android.content.res.ColorStateList.valueOf(progressColor)
        // own thumb instead of the system one: a dark plate with a thin light rim round the dot under the cursor,
        // the same size at rest (the plate is clear) so the bar does not shift when the focus comes
        val halo = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            // under the cursor: the whitish halo round the thumb (the light plate at about 55%); at rest it is clear
            if (SEEK_HALO && seek.isFocused) {
                setColor(0x8CF1F5F9.toInt())
            } else setColor(Color.TRANSPARENT)
        }
        val dot = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(thumbColor)
        }
        seek.thumbTintList = null
        // the same size in both states, so the bar does not jump when the focus comes; the track is not split at the thumb,
        // so the line runs under the clear plate and no gap shows round the dot at rest
        seek.splitTrack = false
        // the system focus highlight would draw one more ring round the plate
        seek.defaultFocusHighlightEnabled = false
        seek.thumb = android.graphics.drawable.LayerDrawable(arrayOf(halo, dot)).apply {
            setLayerSize(0, dp(40), dp(40))
            setLayerSize(1, dp(18), dp(18))
            setLayerGravity(1, android.view.Gravity.CENTER)
        }
        seek.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x66FFFFFF)
        seek.secondaryProgressTintList = android.content.res.ColorStateList.valueOf(0x99FFFFFF.toInt())
    }

    private fun buildOverlay(name: String, meta: String): View {
        val overlay = FrameLayout(this).apply {
            isClickable = true
            isFocusable = false
            if (DeviceType.isTv(this@PlayerActivity)) setOnClickListener { setControlsVisible(false) }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(OVERLAY_DENSE, 0x00000000))
            setPadding(dp(24), dp(16), dp(48), dp(40))
            setOnClickListener { resetHideTimer() }
        }

        val backBtn = round(MenuIcons.BACK) { finish() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(16) }
        }
        playerBack = backBtn
        top.addView(backBtn)

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleView = label(queue.getOrNull(queueIndex)?.title ?: name, 22f, Color.WHITE, true)
        baseMeta = queue.getOrNull(queueIndex)?.meta ?: meta
        metaView = label(baseMeta, 14f, TEXT_SECONDARY).apply {
            setPadding(0, dp(4), 0, 0)
            visibility = if (baseMeta.isEmpty()) View.GONE else View.VISIBLE
        }
        if (titleView.text.isNotEmpty()) titles.addView(titleView)
        titles.addView(metaView)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        overlay.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(OVERLAY_DENSE, 0x00000000))
            setPadding(dp(48), dp(60), dp(48), dp(24))
            setOnClickListener { resetHideTimer() }
        }
        bottomContainer = bottom

        val seekRow = LinearLayout(this)
        seekRow.gravity = Gravity.CENTER_VERTICAL
        position = label("0:00", 14f, TEXT_SECONDARY)
        duration = label("0:00", 14f, TEXT_SECONDARY)
        seek = SeekBar(android.view.ContextThemeWrapper(this, R.style.PlayerSeekOverlay))
        seek.isFocusable = true
        updateSeekColors()
        seek.setOnFocusChangeListener { _, _ ->
            updateSeekColors()
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
            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
                updateSeekColors()
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                updateSeekColors()
                player?.seekTo(sb.progress * 1000L)
            }
        })
        seekRow.addView(position)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12); rightMargin = dp(12) })
        seekRow.addView(duration)
        bottom.addView(seekRow)

        val buttons = LinearLayout(this)
        buttons.gravity = Gravity.CENTER_VERTICAL
        buttons.setPadding(0, dp(16), 0, 0)
        buttons.addView(round(MenuIcons.REPLAY10) { seekByStep(-10_000L) },
            LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(16) })
        playPause = round(MenuIcons.PAUSE, true) { togglePlay() }
        buttons.addView(playPause, LinearLayout.LayoutParams(dp(68), dp(68)).apply { rightMargin = dp(16) })
        buttons.addView(round(MenuIcons.FORWARD10) { seekByStep(10_000L) },
            LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(32) })
        buttons.addView(round(MenuIcons.ASPECT) { cycleAspect() }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(12) })
        buttons.addView(round(MenuIcons.AUDIO_TRACK) { trackDialog(C.TRACK_TYPE_AUDIO, "Звуковая дорожка") },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(12) })
        buttons.addView(round(MenuIcons.SUBTITLES) { trackDialog(C.TRACK_TYPE_TEXT, "Субтитры") },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(12) })
        buttons.addView(round(MenuIcons.GEAR) { qualityAndSpeedDialog() },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(12) })
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
                val button = label(caption, 16f, TEXT_SECONDARY).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(16), 0, dp(16), 0)
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { switchTo(index, toast = false) }
                }
                episodeViews.add(button)
                episodeButtons.addView(button, LinearLayout.LayoutParams(-2, dp(46)).apply { rightMargin = dp(12) })
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
        val cached = Sources.cachedOnly(this, title, originalTitle, mediaYear, isTv)
        if (cached != null) {
            showSourcePicker(cached)
            return
        }
        Toast.makeText(this, "Поиск источников…", Toast.LENGTH_SHORT).show()
        Thread {
            val list = Sources.searchCached(applicationContext, title, originalTitle, mediaYear, isTv)
            handler.post {
                if (!isFinishing && !releasing) {
                    showSourcePicker(list)
                }
            }
        }.start()
    }

    private fun showSourcePicker(allVariants: List<Variant>, allYears: Boolean = false) {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        val currentItem = queue.getOrNull(queueIndex)
        val (variants, hidden) = Sources.forYear(allVariants, mediaYear, isTv, allYears, listOf(mediaTitle, originalTitle))

        if (allYears) {
            items.add("‹ Назад")
            actions.add { showSourcePicker(allVariants, false) }
        }
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

        if (hidden > 0) {
            items.add("Другие годы и части ($hidden)")
            actions.add { showSourcePicker(allVariants, true) }
        }
        // the torrents belong to the main list only, not to the expanded "other years" one
        if (!allYears) {
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
                        .putExtra("tmdbId", tmdbId)
                )
                finish()
            }
        }

        // showChoice marks the current item itself: the marks of the labels are dropped, so there is one tick, not two
        showChoice(this, "Смена источника", items.map { it.removePrefix("✓ ") }, items.indexOfFirst { it.startsWith("✓") }) { which ->
            actions.getOrNull(which)?.invoke()
        }
    }

    private fun switchSource(v: Variant) {
        val exo = player ?: return
        val pos = exo.currentPosition
        Toast.makeText(this, "Загрузка: ${v.source}…", Toast.LENGTH_SHORT).show()
        Thread {
            val episodes = runCatching { Sources.byId(v.sourceId)?.episodes(v) }.getOrNull()
            // the episode being played needs its link before the queue is replaced
            runCatching { episodes?.getOrNull(queueIndex)?.resolve() }
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
        val labels = stripLabels(episodes)
        episodes.forEachIndexed { position, ep ->
            val stream = streamFor(ep, wanted, null)
            newQueue.add(
                PlayItem(
                    url = stream.second,
                    title = "${v.title} • ${ep.title}",
                    meta = "${v.source} • ${stream.first}",
                    label = labels[position],
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

    /** Repaints the episode strip: the current episode is the light plate like the active season on the series page, the focused one is teal. */
    private fun updateEpisodeButtons() {
        episodeViews.forEachIndexed { index, view ->
            styleChoice(view, view, index == queueIndex, scale, plate = OVERLAY_SOFT, restText = TEXT_SECONDARY)
        }
    }

    /** The video fills the whole phone screen: the status and navigation bars are hidden (a swipe from the edge shows them for a moment). */
    private fun hideSystemBars() {
        if (DeviceType.isTv(this)) return
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        if (!barsWatched) {
            barsWatched = true
            // the shade of the device brings the bars back, and the first hide after it can be ignored by the system:
            // whenever the bars are seen again with the window in the focus, they are hidden once more a moment later
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
                if (insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars()) && !releasing) {
                    handler.removeCallbacks(rehideBars)
                    handler.postDelayed(rehideBars, 1200)
                }
                androidx.core.view.ViewCompat.onApplyWindowInsets(v, insets)
            }
        }
    }

    private var barsWatched = false
    private val rehideBars = Runnable { if (!isFinishing && hasWindowFocus()) hideSystemBars() }

    // the bars come back after a dialog, a notification shade or a swipe: hide them again when the window has the focus
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            // the system may still be closing the shade at this moment and ignore the first request: once more a bit later
            handler.removeCallbacks(rehideBars)
            handler.postDelayed(rehideBars, 400)
        }
    }

    override fun finish() {
        super.finish()
        // the page behind comes back at once, without the cross-fade
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /** Returns whether the video plays now (true) or is stopped (false). */
    private fun togglePlay(): Boolean {
        val exo = player ?: return false
        exo.playWhenReady = !exo.playWhenReady
        return exo.playWhenReady
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
        showChoice(this, title, entries, checkedIndex) { which ->
            val pick = picks[which]
            val builder = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
            if (pick != null) builder.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(pick.first, pick.second))
            else builder.setTrackTypeDisabled(type, true)
            exo.trackSelectionParameters = builder.build()
        }
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
        val options = listOf("Качество ($currentQuality)", speedLabel)
        showChoice(this, "Параметры воспроизведения", options, -1) { which ->
            if (which == 0) qualityDialog(itemStreams, videoGroups) else speedDialog()
        }
    }

    private fun qualityDialog(
        itemStreams: List<Pair<String, String>>,
        videoGroups: List<androidx.media3.common.Tracks.Group>,
    ) {
        val exo = player ?: return
        if (itemStreams.size > 1) {
            val labels = itemStreams.map { it.first }
            val checked = itemStreams.indexOfFirst { it.second == url }.coerceAtLeast(0)
            showChoice(this, "Качество потока", labels, checked) { which ->
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
            }
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
        showChoice(this, "Качество видео", entries, checked) { which ->
            val pick = picks[which]
            val builder = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            if (pick != null) builder.setOverrideForType(TrackSelectionOverride(pick.first, pick.second))
            exo.trackSelectionParameters = builder.build()
        }
    }

    private fun speedDialog() {
        val exo = player ?: return
        val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = listOf("0.75×", "1.0× (обычная)", "1.25×", "1.5×", "2.0×")
        val checked = speeds.indexOfFirst { it == playbackSpeed }.coerceAtLeast(1)
        showChoice(this, "Скорость воспроизведения", labels, checked) { which ->
            playbackSpeed = speeds[which]
            exo.setPlaybackSpeed(playbackSpeed)
            Toast.makeText(this, "Скорость: ${labels[which]}", Toast.LENGTH_SHORT).show()
        }
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

    private fun setControlsVisible(visible: Boolean, focusSeek: Boolean = false) {
        overlay.visibility = if (visible) View.VISIBLE else View.GONE
        updatePauseBadge()
        if (visible) {
            resetHideTimer()
            // a mouse click or touch puts the window in touch mode, where a view takes the focus only if it allows it:
            // without this the first request fails and the remote keys do nothing. Not on a phone: a tap must not leave a focus plate
            if (!isMobileUi()) { seek.isFocusableInTouchMode = true; playPause.isFocusableInTouchMode = true }
            if (focusSeek) seek.requestFocus() else playPause.requestFocus()
        } else handler.removeCallbacks(hider)
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
                    // a phone: two Back presses in a row leave the player whether or not the panel was open (the first hides it)
                    if (!DeviceType.isTv(this)) {
                        val now = System.currentTimeMillis()
                        if (now - lastBackAt < 2500L) {
                            finish()
                            return true
                        }
                        lastBackAt = now
                    }
                    setControlsVisible(false)
                    return true
                }
                // nothing is focused (the first request came before the window was ready): the key goes to the progress bar
                if (currentFocus == null && event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER) {
                    seek.requestFocus()
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        seekByStep(if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L)
                    } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) togglePlay()
                    return true
                }
                val focused = currentFocus
                // the Back button: down returns to the progress bar, the other arrows stay on it (OK leaves the player)
                if (focused === playerBack) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> { seek.requestFocus(); resetHideTimer(); return true }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { resetHideTimer(); return true }
                    }
                }
                // up from the progress bar goes to the Back button; down from the lowest row hides the panel
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && focused === seek) {
                    playerBack.requestFocus()
                    resetHideTimer()
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                    (if (episodeViews.isEmpty()) focused is ImageView else episodeViews.any { it === focused })
                ) {
                    setControlsVisible(false)
                    return true
                }
                resetHideTimer()
                // up from the episode strip lands exactly on the play/pause button, not on whatever is nearest
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && episodeViews.any { it === focused }) {
                    playPause.requestFocus()
                    return true
                }
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
                    // the same "-10 сек" / "+10 сек" bubble as after a double tap on a phone
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        seekByStep(-10_000L)
                        touchOverlay.showSeekFeedback(isForward = false)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        seekByStep(10_000L)
                        touchOverlay.showSeekFeedback(isForward = true)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> {
                        togglePlay()
                        showControls()
                        return true
                    }
                    // down opens the panel on the progress bar, up on the episode strip (or the buttons when there are none)
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        setControlsVisible(true, focusSeek = true)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        setControlsVisible(true)
                        (episodeViews.getOrNull(queueIndex) ?: episodeViews.firstOrNull())?.requestFocus()
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

    /** Everything needed to start this playback again: the stream, the episode list and the place in it (see ContinueWatching.resumeIntent). */
    private fun resumeData(): String {
        val list = org.json.JSONArray()
        queue.forEach { item ->
            val streams = org.json.JSONArray()
            item.streams.forEach { (q, u) -> streams.put(org.json.JSONObject().put("q", q).put("u", u)) }
            list.put(org.json.JSONObject().put("u", item.url).put("t", item.title).put("m", item.meta)
                .put("e", item.label).put("k", item.watchKey).put("s", streams))
        }
        return org.json.JSONObject()
            .put("url", url).put("name", intent.getStringExtra("name").orEmpty()).put("meta", intent.getStringExtra("meta").orEmpty())
            .put("playlist", if (queue.isEmpty()) "" else list.toString()).put("index", queueIndex)
            .put("mediaTitle", mediaTitle).put("originalTitle", originalTitle).put("year", mediaYear)
            .put("tv", isTv).put("poster", posterPath.orEmpty()).put("tmdbId", tmdbId).toString()
    }

    /**
     * What the season and episode screen needs to open on episode [index] of this series (see ContinueWatching.resumeIntent);
     * empty when there is no such episode or the queue does not come from a video service.
     */
    private fun seriesResume(index: Int): String {
        val item = queue.getOrNull(index) ?: return ""
        val parts = item.watchKey.split("|", limit = 3)
        if (parts.size < 3 || parts[0].isEmpty() || parts[1].isEmpty()) return ""
        return org.json.JSONObject().put("series", true).put("sourceId", parts[0]).put("ref", parts[1])
            .put("source", Sources.byId(parts[0])?.name.orEmpty()).put("title", item.title.substringBefore(" • "))
            .put("mediaTitle", mediaTitle).put("originalTitle", originalTitle).put("year", mediaYear)
            .put("poster", posterPath.orEmpty()).put("tmdbId", tmdbId).put("index", index).toString()
    }

    private fun save() {
        val exo = player ?: return
        if (Settings.bool(this, Settings.REMEMBER_POSITION)) {
            val pos = exo.currentPosition
            val dur = exo.duration
            val known = dur > 0 && dur != C.TIME_UNSET
            val appContext = applicationContext
            if (known && (pos >= dur - 90_000L || pos * 100 / dur >= 95)) {
                clearPosition()
                val following = seriesResume(queueIndex + 1)
                if (tmdbId > 0L && isTv && following.isNotEmpty()) {
                    // a series: the row of the home screen now offers the next episode
                    val title = Title(tmdbId, mediaTitle, "", posterPath, true, mediaYear)
                    Thread { ContinueWatching.update(appContext, title, 0L, dur, following, next = true) }.start()
                } else if (tmdbId > 0L) Thread { ContinueWatching.clear(appContext, isTv, tmdbId) }.start()
            } else if (pos >= 5_000L) {
                getSharedPreferences("positions", 0).edit().putLong(positionKey(), pos).apply()
                if (tmdbId > 0L && known) {
                    // the row of the home screen: the provider is written off the main thread
                    val title = Title(tmdbId, mediaTitle, "", posterPath, isTv, mediaYear)
                    val resume = if (isTv) seriesResume(queueIndex).ifEmpty { resumeData() } else resumeData()
                    Thread { ContinueWatching.update(appContext, title, pos, dur, resume) }.start()
                }
            }
        }
    }

    private fun showErrorDialog(error: PlaybackException) {
        errorDialog?.dismiss()
        val buttons = mutableListOf(
            DialogButton("Открыть во внешнем плеере") {
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*"))
                    finish()
                }.onFailure {
                    Toast.makeText(this, "Нет внешнего плеера", Toast.LENGTH_LONG).show()
                }
            },
        )
        val cause = error.cause ?: error
        if (Errors.isSsl(cause)) {
            buttons.add(DialogButton("Настройки даты") { Errors.openDateSettings(this) })
        } else if (intent.getBooleanExtra("resumed", false) && tmdbId > 0L) {
            // started from "continue watching": the saved stream may be gone (an old address, TorrServe off), the card offers other sources
            buttons.add(DialogButton("Открыть карточку") {
                startActivity(android.content.Intent(this, DetailsActivity::class.java)
                    .putExtras(Title(tmdbId, mediaTitle, "", posterPath, isTv, mediaYear).toExtras()))
                finish()
            })
        }
        buttons.add(DialogButton("Закрыть") { finish() })
        // the player is useless after an error: Back or a tap outside closes it like the "Закрыть" button
        errorDialog = showMessage(this, "Ошибка воспроизведения", message = Errors.explainPlayer(error), buttons = buttons, onCancel = { finish() })
    }

    override fun onStop() {
        super.onStop()
        errorDialog?.dismiss()
        errorDialog = null
        Connectivity.unlisten(onlineListener)
        save()
        releasing = true
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        finish()
    }
}

/** Whether the thumb of the player shows a plate round it under the cursor. */
private const val SEEK_HALO = true
