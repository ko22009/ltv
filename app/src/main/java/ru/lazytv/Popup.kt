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
 * The dialogs of the app (a choice list, a message with buttons) share one look: a dark panel in the style of the chips
 * instead of the system dialog. Rows and buttons are focusable for the remote. The system AlertDialog is not used anywhere.
 */

/** A button of [showMessage]: [closes] false keeps the window open after the press (the clock settings, for one). */
class DialogButton(val label: String, val closes: Boolean = true, val onClick: () -> Unit = {})

private fun dialogPanel(activity: Activity, scale: Float, title: String): LinearLayout {
    fun px(v: Int) = (v * scale).toInt()
    val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        if (activity.isMobileUi()) setPadding(px(12), px(12), px(12), px(12)) else setPadding(px(24), px(16), px(24), px(16))
        background = GradientDrawable().apply { setColor(PLATE_FILL); cornerRadius = px(TITLE_CARD_RADIUS.toInt()).toFloat() }
    }
    panel.addView(TextView(activity).apply {
        text = title
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f * scale)
        typeface = Typeface.DEFAULT_BOLD
        if (activity.isMobileUi()) setPadding(px(8), 0, px(8), px(8)) else setPadding(px(8), 0, px(8), px(12))
    })
    return panel
}

/** One row of a dialog: a choice of a list or a button; the chosen one is the light plate, the cursor is the teal one. */
private fun dialogRow(activity: Activity, scale: Float, caption: String, active: Boolean, bold: Boolean): TextView {
    fun px(v: Int) = (v * scale).toInt()
    return TextView(activity).apply {
        text = caption
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * scale)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        // long lines (a source with its note) wrap to two lines instead of being cut
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        minHeight = px(CHIP_HEIGHT)
        if (activity.isMobileUi()) setPadding(px(12), px(4), px(12), px(4)) else setPadding(px(16), px(4), px(16), px(4))
        isFocusable = true
        isFocusableInTouchMode = !activity.isMobileUi()
        isClickable = true
        styleChoice(this, this, active, scale, restText = Color.WHITE)
    }
}

private fun showPanel(activity: Activity, scale: Float, dialog: Dialog, panel: View, first: View?, anchor: View? = null, focusOnTouch: Boolean = false) {
    fun px(v: Int) = (v * scale).toInt()
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
    // (an input field is the exception: the keyboard comes up with it)
    if (!activity.isMobileUi() || focusOnTouch) dialog.setOnShowListener { first?.requestFocus() }
    dialog.show()
}

/** [anchor]: a button the list drops from (on a phone it stands right under it instead of the middle of the screen). */
fun showChoice(activity: Activity, title: String, items: List<String>, current: Int, anchor: View? = null, onPick: (Int) -> Unit): Dialog {
    val scale = activity.uiScale()
    fun px(v: Int) = (v * scale).toInt()
    val dialog = Dialog(activity)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val panel = dialogPanel(activity, scale, title)
    val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    var first: View? = null
    items.forEachIndexed { index, caption ->
        val row = dialogRow(activity, scale, caption, index == current, bold = index == current).apply {
            setOnClickListener {
                dialog.dismiss()
                onPick(index)
            }
        }
        rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(if (activity.isMobileUi()) 2 else 3) })
        if (index == current || (first == null && current !in items.indices)) first = row
    }
    panel.addView(ScrollView(activity).apply { addView(rows) }, LinearLayout.LayoutParams(-1, -2))
    showPanel(activity, scale, dialog, panel, first, anchor)
    return dialog
}

/**
 * A message (or any [content] view) with buttons under it, stacked as rows; the first button gets the focus.
 * Back and a tap outside close it, [onCancel] runs then.
 */
fun showMessage(
    activity: Activity,
    title: String,
    message: CharSequence? = null,
    content: View? = null,
    buttons: List<DialogButton>,
    onCancel: (() -> Unit)? = null,
    initialFocus: View? = null,
): Dialog {
    val scale = activity.uiScale()
    fun px(v: Int) = (v * scale).toInt()
    val dialog = Dialog(activity)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val panel = dialogPanel(activity, scale, title)
    val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(8), 0, px(8), px(8))
    }
    if (message != null) body.addView(TextView(activity).apply {
        text = message
        setTextColor(TEXT_SECONDARY)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f * scale)
        gravity = Gravity.START
        setLineSpacing(0f, 1.15f)
    })
    if (content != null) body.addView(content)
    // a long text scrolls inside its own area, the buttons stay on the screen
    val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
    panel.addView(object : ScrollView(activity) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        }
    }.apply { addView(body) }, LinearLayout.LayoutParams(-1, -2))
    var first: View? = null
    buttons.forEachIndexed { index, button ->
        val row = dialogRow(activity, scale, button.label, active = false, bold = true).apply {
            setOnClickListener {
                if (button.closes) dialog.dismiss()
                button.onClick()
            }
        }
        panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(if (index == 0) 8 else 3) })
        if (first == null) first = row
    }
    if (onCancel != null) dialog.setOnCancelListener { onCancel() }
    // the panel stays above the keyboard when the content has an input field
    dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    showPanel(activity, scale, dialog, panel, initialFocus ?: first, focusOnTouch = initialFocus != null)
    return dialog
}

/** A small preload-style window over [content] with no buttons: Back closes it, [onCancel] runs then. */
fun showBare(activity: Activity, content: View, onCancel: () -> Unit): Dialog {
    val dialog = Dialog(activity)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(content)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.setOnCancelListener { onCancel() }
    dialog.show()
    return dialog
}

/** The one "close" button of a plain text window (a full description, a biography). */
fun showText(activity: Activity, title: String, text: CharSequence): Dialog =
    showMessage(activity, title, message = text, buttons = listOf(DialogButton("Закрыть")))
