package ru.lazytv

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.Presenter

/** A button above the rows of a films/series page: it opens the filters or the sort screen of that list. */
data class CatalogButton(val category: String, val filters: Boolean, val caption: String)

/**
 * The button of the app: a flat chip with the caption and, if given, a small icon in front of it; the focused one turns green
 * like the other tiles. The caption is the child with the tag "caption". Filters, sort and the page header buttons are this one.
 */
fun chipButton(context: Context, scale: Float, caption: String, icon: String? = null): LinearLayout {
    fun px(v: Int) = (v * scale).toInt()
    return LinearLayout(context).apply {
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = focusShape(scale, 0xFF1F3228.toInt(), radius = 6f)
        layoutParams = ViewGroup.MarginLayoutParams(-2, px(52))
        setPadding(px(if (icon == null) 26 else 22), 0, px(26), 0)
        if (icon != null) addView(ImageView(context).apply {
            tag = "icon"
            setChipIcon(icon)
        }, LinearLayout.LayoutParams(px(22), px(22)).apply { rightMargin = px(10) })
        addView(TextView(context).apply {
            tag = "caption"
            text = caption
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f * scale)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
        }, LinearLayout.LayoutParams(-2, -1))
    }
}

private fun ImageView.setChipIcon(icon: String) =
    setImageDrawable(PathDrawable(icon).apply { setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) })

/** The caption and the icon of a chip can change after it is made (a bookmark that is saved, a watched mark). */
var View.chipCaption: CharSequence
    get() = findViewWithTag<TextView>("caption").text
    set(value) { findViewWithTag<TextView>("caption").text = value }

fun View.setChipIcon(icon: String) = findViewWithTag<ImageView>("icon")?.setChipIcon(icon)

/** The chip above the rows, made by [chipButton]. */
class CatalogButtonPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val scale = parent.resources.displayMetrics.widthPixels / 1280f
        val chip = chipButton(parent.context, scale, "")
        chip.layoutParams = ViewGroup.MarginLayoutParams(-2, (52 * scale).toInt()).apply {
            rightMargin = (16 * scale).toInt()
            topMargin = (6 * scale).toInt()
            bottomMargin = (6 * scale).toInt()
        }
        return ViewHolder(chip)
    }

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {
        holder.view.findViewWithTag<TextView>("caption").text = (item as CatalogButton).caption
    }

    override fun onUnbindViewHolder(holder: ViewHolder) {}
}
