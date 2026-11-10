package ru.lazytv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
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
        val scale = activity.uiScale()
        fun px(value: Int) = (value * scale).toInt()
        // the text is laid out by hand: the stock dialog message centres itself, and the lines then start anywhere
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(0, 0, 0, 0)
            addView(TextView(activity).apply {
                text = "Браузера на этом устройстве нет. Откройте ссылку на телефоне или компьютере и поставьте TorrServe оттуда."
                setTextColor(0xFFCBD5E1.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f * scale)
                gravity = Gravity.START
                setLineSpacing(0f, 1.15f)
            })
            addView(TextView(activity).apply {
                text = url
                setTextColor(0xFFCBD5E1.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
                gravity = Gravity.START
                setTextIsSelectable(true)
                setPadding(0, px(14), 0, 0)
            })
        }
        showMessage(activity, "Ссылка для загрузки", content = box, buttons = listOf(DialogButton("Понятно")))
    }
}
