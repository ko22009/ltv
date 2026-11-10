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
 * as online: it cannot be told from a working one without asking a site, the normal error messages cover it.
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
