package ru.lazytv

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.leanback.widget.Presenter

/** A button above the rows of a films/series page: it opens the filters or the sort screen of that list. */
data class CatalogButton(val category: String, val filters: Boolean, val caption: String)

/** Flat chip with the caption; the focused one turns green like the other tiles of the app. */
class CatalogButtonPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val scale = parent.resources.displayMetrics.widthPixels / 1280f
        fun px(v: Int) = (v * scale).toInt()
        val plate = GradientDrawable().apply {
            setColor(0xFF39423D.toInt())
            cornerRadius = px(6).toFloat()
        }
        val chip = FrameLayout(parent.context)
        chip.layoutParams = ViewGroup.MarginLayoutParams(-2, px(52)).apply {
            rightMargin = px(16)
            topMargin = px(6)
            bottomMargin = px(6)
        }
        chip.background = plate
        chip.isFocusable = true
        chip.isFocusableInTouchMode = true
        chip.addView(TextView(parent.context).apply {
            tag = "caption"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f * scale)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(px(26), 0, px(26), 0)
        }, FrameLayout.LayoutParams(-2, -1))
        chip.setOnFocusChangeListener { _, focused -> plate.setColor(if (focused) 0xFF2F7D57.toInt() else 0xFF39423D.toInt()) }
        return ViewHolder(chip)
    }

    override fun onBindViewHolder(holder: ViewHolder, item: Any) {
        holder.view.findViewWithTag<TextView>("caption").text = (item as CatalogButton).caption
    }

    override fun onUnbindViewHolder(holder: ViewHolder) {}
}
