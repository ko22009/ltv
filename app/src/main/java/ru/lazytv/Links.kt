package ru.lazytv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.widget.TextView

/**
 * Opens a web page for the user. Many TV boxes have no browser at all, and the system one is only a stub that
 * does nothing, so the address is shown on screen instead: it is easy to retype on a phone and install from there.
 */
object Links {
    /** The system "browser" of Android TV: an activity that answers every link and opens nothing. */
    private const val STUB = "com.android.tv.frameworkpackagestubs"

    fun open(activity: Activity, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val target = activity.packageManager.resolveActivity(intent, 0)
        val browser = target?.activityInfo?.packageName
        if (browser == null || browser == STUB) {
            showAddress(activity, url)
            return
        }
        runCatching { activity.startActivity(intent) }.onFailure { showAddress(activity, url) }
    }

    private fun showAddress(activity: Activity, url: String) {
        val scale = activity.resources.displayMetrics.widthPixels / 1280f
        fun px(value: Int) = (value * scale).toInt()
        val link = TextView(activity).apply {
            text = url
            setTextColor(0xFFDCE8E0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
            setTextIsSelectable(true)
            setPadding(px(24), px(12), px(24), 0)
        }
        AlertDialog.Builder(activity)
            .setTitle("Ссылка для загрузки")
            .setMessage("Браузера на этом устройстве нет. Откройте ссылку на телефоне или компьютере и поставьте TorrServe оттуда.")
            .setView(link)
            .setPositiveButton("Понятно", null)
            .show()
    }
}
