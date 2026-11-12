package ru.lazytv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

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
        if (browser == STUB) {
            showAddress(activity, url)
            return
        }
        // Android 11 hides other apps from resolveActivity unless they are listed in <queries>, so a missing answer
        // means nothing here: the attempt itself still reaches the browser. Only a real failure shows the address.
        val opened = runCatching { activity.startActivity(intent) }.isSuccess
        if (!opened) showAddress(activity, url)
    }

    fun openSupport(activity: Activity, url: String) {
        if (activity.isMobileUi()) {
            open(activity, url)
            return
        }

        val scale = activity.uiScale()
        fun px(value: Int) = (value * scale).toInt()
        val side = px(280)
        val bitmap = runCatching {
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, side, side)
            val pixels = IntArray(side * side) { index ->
                if (matrix[index % side, index / side]) Color.BLACK else Color.WHITE
            }
            Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        }.getOrElse {
            open(activity, url)
            return
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(activity).apply {
                text = "Отсканируйте код камерой телефона"
                setTextColor(TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * scale)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, px(12))
            })
            addView(ImageView(activity).apply {
                setImageBitmap(bitmap)
                setBackgroundColor(Color.WHITE)
                setPadding(px(8), px(8), px(8), px(8))
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "QR-код страницы поддержки на Boosty"
            }, LinearLayout.LayoutParams(side, side).apply {
                setMargins(0, 0, 0, px(12))
            })
            addView(TextView(activity).apply {
                text = url
                setTextColor(TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f * scale)
                setTextIsSelectable(true)
                autoLinkMask = android.text.util.Linkify.WEB_URLS
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
            })
        }
        showMessage(activity, "Поддержать проект", content = content, buttons = listOf(
            DialogButton("Открыть ссылку") { open(activity, url) },
            DialogButton("Закрыть"),
        ))
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
                text = "Открыть ссылку не удалось. Наберите её на телефоне или компьютере."
                setTextColor(TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f * scale)
                gravity = Gravity.START
                setLineSpacing(0f, 1.15f)
            })
            addView(TextView(activity).apply {
                text = url
                setTextColor(TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
                gravity = Gravity.START
                setTextIsSelectable(true)
                // the address itself opens the browser: typing it by hand on a TV remote is hopeless
                autoLinkMask = android.text.util.Linkify.WEB_URLS
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                setPadding(0, px(16), 0, 0)
            })
        }
        showMessage(activity, "Ссылка для загрузки", content = box, buttons = listOf(DialogButton("Понятно")))
    }
}
