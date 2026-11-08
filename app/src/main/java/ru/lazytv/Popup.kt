package ru.lazytv

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The single choice list of the app (season, voice-over, quality): a dark panel in the style of the chips instead of
 * the system dialog. Rows are focusable for the remote; the current choice is marked and gets the focus first.
 */
fun showChoice(activity: Activity, title: String, items: List<String>, current: Int, onPick: (Int) -> Unit) {
    val scale = activity.resources.displayMetrics.widthPixels / 1280f
    fun px(v: Int) = (v * scale).toInt()
    val dialog = Dialog(activity)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(24), px(20), px(24), px(20))
        background = GradientDrawable().apply { setColor(0xFF12151C.toInt()); cornerRadius = px(10).toFloat() }
    }
    panel.addView(TextView(activity).apply {
        text = title
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f * scale)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(px(8), 0, px(8), px(12))
    })
    val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    var first: View? = null
    items.forEachIndexed { index, caption ->
        val row = TextView(activity).apply {
            text = (if (index == current) "✓  " else "") + caption
            setTextColor(if (index == current) 0xFFA5B4FC.toInt() else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
            typeface = if (index == current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            // long lines (a source with its note) wrap to two lines instead of being cut
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            minHeight = px(CHIP_HEIGHT)
            setPadding(px(18), px(8), px(18), px(8))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = focusShape(scale, 0xFF1C2029.toInt(), radius = 6f)
            setOnClickListener {
                dialog.dismiss()
                onPick(index)
            }
        }
        rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6) })
        if (index == current || (first == null && current !in items.indices)) first = row
    }
    panel.addView(ScrollView(activity).apply { addView(rows) }, LinearLayout.LayoutParams(-1, -2))
    dialog.setContentView(panel)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(px(640), -2)
        setDimAmount(0.6f)
    }
    dialog.setOnShowListener { first?.requestFocus() }
    dialog.show()
}
