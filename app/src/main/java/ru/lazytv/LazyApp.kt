package ru.lazytv

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import com.lmd.dpi.DpiBypass

/** Starts the automatic block bypass: direct, then the built-in ByeDPI, then the LazyMedia proxy. */
class LazyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // the screen behind the cursor is remembered to come back to it after an update (docs/workflow.md)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) { LastScreen.remember(activity) }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
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
        DpiBypass.apiProbePath = "/3/movie/popular?api_key=${BuildConfig.TMDB_KEY}"
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
    }
}
