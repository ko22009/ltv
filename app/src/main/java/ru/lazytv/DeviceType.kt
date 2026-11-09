package ru.lazytv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** Detects whether the device is an Android TV / TV box or a mobile touch device. */
object DeviceType {
    fun isTv(context: Context): Boolean {
        val forced = Settings.string(context, "interface_mode", "auto")
        if (forced == "tv") return true
        if (forced == "mobile") return false

        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true

        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return true

        return false
    }
}
