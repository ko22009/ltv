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

/** Height of the one button of the app (dp of the screen), and of the rows that hold such buttons. */
const val CHIP_HEIGHT = 44

/** Round 56dp Back button for secondary screens so mouse and remotes without a Back key can always leave the page. */
fun backButton(context: Context, scale: Float, normalColor: Int = 0x66000000, onClick: () -> Unit): ImageView {
    val pad = (12 * scale).toInt()
    return ImageView(context).apply {
        setImageDrawable(PathDrawable(MenuIcons.BACK).apply { setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) })
        setPadding(pad, pad, pad, pad)
        background = focusShape(scale, normalColor, circle = true)
        // a touch screen has no cursor: the teal focus plate would stay on the button after the first tap
        isFocusable = !context.isMobileUi()
        isClickable = true
        id = View.generateViewId()
        contentDescription = "Назад"
        setOnClickListener { onClick() }
    }
}

/** A button above the rows of a films/series page: it opens the filters or the sort screen of that list. */
data class CatalogButton(val category: String, val filters: Boolean, val caption: String)

/**
 * The button of the app: a flat chip with the caption and, if given, a small icon in front of it; the focused one turns green
 * like the other tiles. The caption is the child with the tag "caption". Filters, sort and the page header buttons are this one.
 */
/** The one colour of every focus and selection highlight in the app (menu, tiles, buttons, rows, chips). */
val FOCUS_FILL = 0xFF0F766E.toInt()

/** The plate of a text tile (settings section, saved episode, saved torrent): dark rounded card as on the phone, teal under focus. */
fun textTilePlate(scale: Float) = focusShape(scale, PLATE_FILL, radius = TITLE_CARD_RADIUS)

/** The one dark plate of cards and tiles: the text strip of a title card, the "show all" tile, the text tile. */
val PLATE_FILL = 0xFF1C2029.toInt()

/** The plate of the chosen item among equals (the open season, the playing episode, the picked source, the current choice in a list). */
const val ACTIVE_PLATE = 0xFFE2E8F0.toInt()
/** The caption on [ACTIVE_PLATE]: the colour of the page. */
const val ACTIVE_TEXT = 0xFF101216.toInt()

/**
 * The one look of "the chosen one among equals": resting it is the usual [plate] with the [restText] caption, the chosen
 * one is the light [ACTIVE_PLATE] with a dark caption (seen at once), under the cursor any of them is the teal [FOCUS_FILL]
 * with a white caption. [text] is the caption view (the item itself or its child); the chip takes the focus, the caption follows it.
 */
fun styleChoice(view: View, text: TextView?, active: Boolean, scale: Float, radius: Float = 10f, plate: Int = 0xFF1C2029.toInt(), restText: Int = 0xFF94A3B8.toInt()) {
    val corner = radius * scale
    view.background = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply { setColor(FOCUS_FILL); cornerRadius = corner })
        addState(intArrayOf(), android.graphics.drawable.GradientDrawable().apply { setColor(if (active) ACTIVE_PLATE else plate); cornerRadius = corner })
    }
    if (text == null) return
    if (text !== view && !text.isDuplicateParentStateEnabled) {
        // the parent starts to pass its state down to a child only if the child had the flag when it was added: add it again
        val parent = text.parent as? ViewGroup
        if (parent != null) {
            val at = parent.indexOfChild(text)
            val params = text.layoutParams
            parent.removeViewAt(at)
            text.isDuplicateParentStateEnabled = true
            parent.addView(text, at, params)
        } else text.isDuplicateParentStateEnabled = true
    }
    text.setTextColor(android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
        intArrayOf(Color.WHITE, if (active) ACTIVE_TEXT else restText),
    ))
}

fun chipButton(context: Context, scale: Float, caption: String, icon: String? = null): LinearLayout {
    fun px(v: Int) = (v * scale).toInt()
    return LinearLayout(context).apply {
        gravity = Gravity.CENTER
        isFocusable = !context.isMobileUi()
        isFocusableInTouchMode = !context.isMobileUi()
        isClickable = true
        background = focusShape(scale, 0xFF1C2029.toInt(), radius = 6f)
        layoutParams = ViewGroup.MarginLayoutParams(-2, px(CHIP_HEIGHT))
        setPadding(px(if (icon == null) 22 else 18), 0, px(22), 0)
        if (icon != null) addView(ImageView(context).apply {
            tag = "icon"
            setChipIcon(icon)
        }, LinearLayout.LayoutParams(px(18), px(18)).apply { rightMargin = px(8) })
        addView(TextView(context).apply {
            tag = "caption"
            text = caption
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * scale)
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
        val scale = parent.context.uiScale()
        val chip = chipButton(parent.context, scale, "")
        chip.layoutParams = ViewGroup.MarginLayoutParams(-2, (CHIP_HEIGHT * scale).toInt()).apply {
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
