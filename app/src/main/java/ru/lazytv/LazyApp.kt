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
        if (Settings.bool(this, Settings.AUTO_BYPASS)) {
            DpiBypass.appProxyAllowed = Settings.bool(this, Settings.APP_PROXY)
            DpiBypass.appProxyTorrentsAllowed = Settings.bool(this, Settings.APP_PROXY_TORRENTS)
            // development: "host:port" of a ByeDPI running on the PC, set with adb (emulator cannot run the bundled one)
            Settings.string(this, "dev_socks", "").takeIf { it.contains(':') }?.let {
                DpiBypass.externalSocksHost = it.substringBefore(':')
                DpiBypass.externalSocksPort = it.substringAfter(':').toInt()
            }
            DpiBypass.strategyFile = java.io.File(filesDir, "dpi_strategy")
            DpiBypass.start(applicationInfo.nativeLibraryDir)
        }
        Thread { ClockCheck.checkOnce() }.start()
        Thread { runCatching { Recommendations.refresh(this) } }.start()
        // the addresses file is renewed once a day in the background; without it the built-in ones stay
        Thread { RemoteConfig.refreshIfStale(this) }.start()
    }
}
