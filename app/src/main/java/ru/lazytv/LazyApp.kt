package ru.lazytv

import android.app.Application
import com.lmd.dpi.DpiBypass

/** Starts the automatic block bypass: direct, then the built-in ByeDPI, then the LazyMedia proxy. */
class LazyApp : Application() {
    override fun onCreate() {
        super.onCreate()
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
        Thread { runCatching { Recommendations.refresh(this) } }.start()
        // the addresses file is renewed once a day in the background; without it the built-in ones stay
        Thread { RemoteConfig.refreshIfStale(this) }.start()
    }
}
