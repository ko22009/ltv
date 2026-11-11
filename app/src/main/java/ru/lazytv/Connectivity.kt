package ru.lazytv

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Whether the device has any network with internet. A connection that only lets some sites through (white lists) still counts
 * as online here; such a case is caught separately from the results of the app's own requests (`report`, `reachable`) and
 * shown as a strip over the screen.
 * Listeners are called on the main thread.
 */
object Connectivity {
    @Volatile var online = true
        private set
    private val main = Handler(Looper.getMainLooper())
    private val networks = HashSet<Network>()
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    fun init(app: Application) {
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        online = manager.allNetworks.any { hasInternet(manager, it) }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        runCatching {
            manager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = change { networks.add(network) }
                override fun onLost(network: Network) = change { networks.remove(network) }
            })
        }
    }

    private fun hasInternet(manager: ConnectivityManager, network: Network) =
        manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun change(edit: () -> Unit) {
        main.post {
            edit()
            val now = networks.isNotEmpty()
            if (now != online) {
                online = now
                // a new network starts with a clean slate: the old failures say nothing about it
                if (now) { failStreak = 0; if (!reachable) { reachable = true; reachListeners.toList().forEach { it(true) } } }
                listeners.toList().forEach { it(now) }
            }
        }
    }

    /**
     * False while the catalogue server does not answer although the system says "online" (a white list, a blocked site).
     * Asked by a real request: any HTTP answer, even an error one, means the site is reachable.
     */
    @Volatile var reachable = true
        private set
    private val reachListeners = mutableListOf<(Boolean) -> Unit>()
    private var failStreak = 0

    /**
     * The result of a real request of the app (no probes of its own): [ok] after an answer, false after a timeout, a refused
     * connection or an unknown address. Three failures in a row mean the server is shut off; one answer clears it.
     */
    fun report(ok: Boolean) {
        main.post {
            failStreak = if (ok) 0 else failStreak + 1
            val now = failStreak < FAILS_TO_WARN
            if (now != reachable) {
                reachable = now
                reachListeners.toList().forEach { it(now) }
                if (!now) probeUntilAnswer()
            }
        }
    }

    private var probing = false
    @Volatile private var lastCheck = 0L

    /** One real request to the server, never from the cache: true after any HTTP answer, even an error one. */
    private fun ping(): Boolean = try {
        val c = java.net.URL("https://api.themoviedb.org/3/configuration?api_key=${BuildConfig.TMDB_KEY}")
            .openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.responseCode
        c.disconnect()
        true
    } catch (e: Exception) {
        !Errors.isNetwork(e)
    }

    /**
     * A real request on every screen opening and every cache hit (answers kept on the device say nothing about the network).
     * At most one in 15 s. One silent answer is enough here: the plate goes up at once.
     */
    fun check() {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 15000 || !online) return
        lastCheck = now
        Thread {
            val ok = ping()
            main.post {
                failStreak = if (ok) 0 else FAILS_TO_WARN
                val reach = ok
                if (reach != reachable) {
                    reachable = reach
                    reachListeners.toList().forEach { it(reach) }
                    if (!reach) probeUntilAnswer()
                }
            }
        }.start()
    }

    /** The plate blocks the app, so nothing else would ever ask again: knock on the server until any answer comes. */
    private fun probeUntilAnswer() {
        if (probing) return
        probing = true
        Thread {
            while (!reachable) {
                try {
                    Thread.sleep(8000)
                    if (ping()) report(true)
                } catch (e: Exception) { /* still silent */ }
            }
            probing = false
        }.start()
    }

    private const val FAILS_TO_WARN = 3

    fun listenReach(listener: (Boolean) -> Unit) { reachListeners.add(listener) }

    fun listen(listener: (Boolean) -> Unit) { listeners.add(listener) }
    fun unlisten(listener: (Boolean) -> Unit) { listeners.remove(listener) }
}

/**
 * Phone screens other than the player: while there is no network a plate covers the screen (nothing works without it anyway,
 * and touching a half-loaded screen only confuses); when the network is back the screen is built again, so it loads by itself.
 * The player is not covered: it goes on from the same place (see PlayerActivity).
 */
object OfflineCover {
    private val covers = WeakHashMap<Activity, FrameLayout>()
    private var top: Activity? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        Connectivity.listenReach { ok ->
            covers.forEach { (_, cover) -> show(cover, !Connectivity.online || !ok) }
            if (ok) top?.takeIf { !it.isFinishing && covers.containsKey(it) }?.recreate()
        }
        Connectivity.listen { online ->
            covers.forEach { (_, cover) -> show(cover, !online || !Connectivity.reachable) }
            // the screen in front is built again: whatever failed to load while the network was gone loads now
            if (online) top?.takeIf { !it.isFinishing && covers.containsKey(it) }?.recreate()
        }
    }

    fun resumed(activity: Activity) {
        top = activity
        if (activity is PlayerActivity) return
        Connectivity.check()
        val cover = covers.getOrPut(activity) { build(activity) }
        show(cover, !Connectivity.online || !Connectivity.reachable)
    }

    private fun show(cover: FrameLayout, offline: Boolean) {
        cover.visibility = if (offline) android.view.View.VISIBLE else android.view.View.GONE
        // on a TV the cursor must not wander under the plate: the plate holds the focus
        if (offline) cover.requestFocus()
    }

    fun paused(activity: Activity) {
        if (top === activity) top = null
    }

    private fun build(activity: Activity): FrameLayout {
        val density = activity.resources.displayMetrics.density
        val cover = FrameLayout(activity).apply {
            setBackgroundColor(PAGE_COVER)
            // takes every touch: the screen under the plate is not used
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            visibility = android.view.View.GONE
            // the remote keys are taken too, only Back goes through (the app can still be left)
            setOnKeyListener { _, keyCode, _ -> keyCode != android.view.KeyEvent.KEYCODE_BACK }
        }
        val tv = DeviceType.isTv(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((32 * density).toInt(), 0, (32 * density).toInt(), 0)
        }
        column.addView(TextView(activity).apply {
            text = "Доступ к интернету потерян"
            setTextColor(Color.WHITE)
            textSize = if (tv) 30f else 20f
            gravity = Gravity.CENTER
        })
        column.addView(TextView(activity).apply {
            text = "Включите интернет.\nПриложение продолжит работу, как только связь появится."
            setTextColor(TEXT_MUTED)
            textSize = if (tv) 22f else 15f
            gravity = Gravity.CENTER
            setPadding(0, (8 * density).toInt(), 0, (20 * density).toInt())
        })
        column.addView(ProgressBar(activity).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) })
        cover.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        (activity.window.decorView as ViewGroup).addView(cover, ViewGroup.LayoutParams(-1, -1))
        return cover
    }
}
