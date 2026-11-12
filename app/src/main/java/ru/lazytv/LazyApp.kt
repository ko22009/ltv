package ru.lazytv

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import com.lmd.dpi.DpiBypass

/** Starts the automatic block bypass: direct, then the built-in ByeDPI, then the LazyMedia proxy. */
class LazyApp : Application() {
    companion object {
        /** Resources of the app for the colours that the XML styles share with the code (see colors.xml). */
        lateinit var res: android.content.res.Resources
        lateinit var app: Application
    }

    override fun onCreate() {
        super.onCreate()
        res = resources
        app = this
        // the screen behind the cursor is remembered to come back to it after an update (docs/workflow.md)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // phone screens are drawn for portrait; only the player and the gallery of covers turn sideways
                if (activity !is PlayerActivity && activity !is GalleryActivity && activity.isMobileUi()) {
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                // a phone page comes in from the right and leaves to the right, like a turned page (not the system slide up and down)
                if (slides(activity) && savedInstanceState == null && !activity.isTaskRoot) {
                    activity.overridePendingTransition(R.anim.page_in_right, R.anim.page_out_left)
                }
            }
            private fun slides(activity: Activity) = activity !is PlayerActivity && activity.isMobileUi()
            private var started = 0
            private var firstStart = true
            override fun onActivityStarted(activity: Activity) {
                // the app came back from the background (the cold start is covered by startCheck): look at Drive
                if (started++ == 0 && !firstStart) Sync.checkRemote(activity)
                firstStart = false
            }
            override fun onActivityResumed(activity: Activity) { LastScreen.remember(activity); OfflineCover.resumed(activity) }
            override fun onActivityPaused(activity: Activity) {
                OfflineCover.paused(activity)
                if (slides(activity) && activity.isFinishing) activity.overridePendingTransition(R.anim.page_in_left, R.anim.page_out_right)
            }
            override fun onActivityStopped(activity: Activity) { started-- }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        Connectivity.init(this)
        OfflineCover.start()
        RemoteConfig.init(this)
        Cinema.init(this)
        DpiBypass.enabled = Settings.bool(this, Settings.AUTO_BYPASS)
        DpiBypass.appProxyAllowed = Settings.bool(this, Settings.APP_PROXY)
        DpiBypass.appProxyTorrentsAllowed = Settings.bool(this, Settings.APP_PROXY_TORRENTS)
        // development: "host:port" of a ByeDPI running on the PC, set with adb (emulator cannot run the bundled one)
        Settings.string(this, "dev_socks", "").takeIf { it.contains(':') }?.let { raw ->
            val port = raw.substringAfter(':').trim().toIntOrNull()
            if (port != null && port in 1..65535) {
                DpiBypass.externalSocksHost = raw.substringBefore(':').trim()
                DpiBypass.externalSocksPort = port
            }
        }
        DpiBypass.apiProbePath = "/3/movie/popular?api_key=${BuildConfig.TMDB_KEY}&language=ru-RU"
        DpiBypass.strategyFile =java.io.File(filesDir, "dpi_strategy")
        if (DpiBypass.enabled) {
            DpiBypass.start(applicationInfo.nativeLibraryDir)
            // five seconds after the start, one site at a time: the first card then does not wait for the probe of its sites
            val sites = (Sources.all.map { it.id } + "rutor").map { RemoteConfig.base(it) }.filter { it.startsWith("https://") }
                .map { it.substringAfter("://").substringBefore('/') }
            DpiBypass.warmLater((listOf("image.tmdb.org", "api.themoviedb.org") + sites).distinct().toTypedArray(), 5000L)
        }
        Thread { ClockCheck.checkOnce() }.start()
        Thread { runCatching { Recommendations.refresh(this) } }.start()
        // the addresses file is renewed once a day in the background; without it the built-in ones stay
        Thread { RemoteConfig.refreshIfStale(this) }.start()
        // bookmarks and history from the other device: quietly, only when the link is set and the last pass is old
        Sync.startCheck(this)
    }
}
