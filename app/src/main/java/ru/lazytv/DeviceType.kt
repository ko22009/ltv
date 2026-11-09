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

/** True on a phone or tablet: screens use the touch layout instead of the remote-control one. */
fun Context.isMobileUi(): Boolean = !DeviceType.isTv(this)

/**
 * Size of one layout unit in pixels. TV screens are drawn for a 1280-unit width; on a phone the virtual width is 480
 * units of the short side, so texts and touch targets stay readable and the layout is the same in any rotation.
 */
fun Context.uiScale(): Float {
    val m = resources.displayMetrics
    return if (isMobileUi()) minOf(m.widthPixels, m.heightPixels) / 480f else m.widthPixels / 1280f
}
