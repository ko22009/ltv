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
/** [anchor]: a button the list drops from (on a phone it stands right under it instead of the middle of the screen). */
fun showChoice(activity: Activity, title: String, items: List<String>, current: Int, anchor: View? = null, onPick: (Int) -> Unit) {
    val scale = activity.uiScale()
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
            text = caption
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
            typeface = if (index == current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            // long lines (a source with its note) wrap to two lines instead of being cut
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            minHeight = px(CHIP_HEIGHT)
            setPadding(px(18), px(8), px(18), px(8))
            isFocusable = true
            isFocusableInTouchMode = !activity.isMobileUi()
            isClickable = true
            // the current choice is the outlined plate (like the active season), not a tick
            background = if (index == current) activeShape(scale, 0xFF1C2029.toInt(), radius = 6f) else focusShape(scale, 0xFF1C2029.toInt(), radius = 6f)
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
        setLayout(minOf(px(640), (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()), -2)
        setDimAmount(0.6f)
        if (anchor != null) {
            val at = IntArray(2)
            anchor.getLocationInWindow(at)
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = at[1] + anchor.height + px(6) }
        }
    }
    // a touch screen has no cursor: no row is focused (the outline already marks the current one)
    if (!activity.isMobileUi()) dialog.setOnShowListener { first?.requestFocus() }
    dialog.show()
}
