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
 * as online here; such a case is caught separately by the probe below (`reachable`) and shown as a strip over the screen.
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
    private var probing = false

    /** Starts the periodic probe (once): every minute while all is well, every 10 seconds while the server is silent. */
    fun startProbe() {
        if (probing) return
        probing = true
        Thread {
            while (true) {
                if (online) {
                    val ok = runCatching {
                        val c = java.net.URL("https://api.themoviedb.org/").openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 6000
                        c.readTimeout = 6000
                        c.requestMethod = "HEAD"
                        c.responseCode
                        c.disconnect()
                    }.isSuccess
                    if (ok != reachable) main.post { reachable = ok; reachListeners.toList().forEach { it(ok) } }
                }
                Thread.sleep(if (reachable) 60_000L else 10_000L)
            }
        }.apply { isDaemon = true }.start()
    }

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
    private val bars = WeakHashMap<Activity, TextView>()
    private var top: Activity? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        Connectivity.startProbe()
        Connectivity.listenReach { ok -> bars.forEach { (_, bar) -> bar.visibility = if (ok) android.view.View.GONE else android.view.View.VISIBLE } }
        Connectivity.listen { online ->
            covers.forEach { (_, cover) -> show(cover, !online) }
            // the screen in front is built again: whatever failed to load while the network was gone loads now
            if (online) top?.takeIf { !it.isFinishing && covers.containsKey(it) }?.recreate()
        }
    }

    fun resumed(activity: Activity) {
        top = activity
        if (activity is PlayerActivity) return
        val cover = covers.getOrPut(activity) { build(activity) }
        show(cover, !Connectivity.online)
        bars.getOrPut(activity) { buildBar(activity) }.visibility =
            if (Connectivity.reachable) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun show(cover: FrameLayout, offline: Boolean) {
        cover.visibility = if (offline) android.view.View.VISIBLE else android.view.View.GONE
        // on a TV the cursor must not wander under the plate: the plate holds the focus
        if (offline) cover.requestFocus()
    }

    fun paused(activity: Activity) {
        if (top === activity) top = null
    }

    /** A thin strip over the top of the screen: the network is there but the catalogue server is not answering. Takes no focus or touch. */
    private fun buildBar(activity: Activity): TextView {
        val density = activity.resources.displayMetrics.density
        val tv = DeviceType.isTv(activity)
        val bar = TextView(activity).apply {
            text = "Часть сайтов недоступна (возможно, белый список). Включите обход блокировок в настройках"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xE6B45309.toInt())
            textSize = if (tv) 16f else 12f
            gravity = Gravity.CENTER
            setPadding((16 * density).toInt(), (6 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
            isFocusable = false
            visibility = android.view.View.GONE
        }
        (activity.window.decorView as ViewGroup).addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        return bar
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
            text = "Нет интернета"
            setTextColor(Color.WHITE)
            textSize = if (tv) 30f else 20f
            gravity = Gravity.CENTER
        })
        column.addView(TextView(activity).apply {
            text = "Без сети приложение не работает.\nОжидание включения интернета..."
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
