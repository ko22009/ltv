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
fun backButton(context: Context, scale: Float, normalColor: Int = OVERLAY_SOFT, onClick: () -> Unit): ImageView {
    val pad = (12 * scale).toInt()
    return ImageView(context).apply {
        setImageDrawable(PathDrawable(MenuIcons.BACK))
        // the icon sits on the view that takes the focus, so no need to pass the state down
        imageTintList = cursorColors(context, Color.WHITE)
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
val FOCUS_FILL: Int get() = LazyApp.res.getColor(R.color.focus_fill, null)

/** The plate of a pressed or focused button on a phone: a lighter grey, not the teal that belongs to the cursor of a TV remote. */
val TOUCH_FILL: Int get() = LazyApp.res.getColor(R.color.touch_fill, null)

/** The highlight under the cursor or the finger: [FOCUS_FILL] on a TV, the neutral lighter plate on a phone. */
fun cursorFill(context: android.content.Context): Int = if (DeviceType.isTv(context)) FOCUS_FILL else TOUCH_FILL

/** What is drawn on [FOCUS_FILL] (caption, icon). Changes together with it: a light plate needs a dark caption. */
val ON_FOCUS: Int get() = LazyApp.res.getColor(R.color.on_focus, null)

/** The caption colour on the plate of [cursorFill]: [ON_FOCUS] on a TV, white on the dark touch plate of a phone. */
fun cursorText(context: android.content.Context): Int = if (DeviceType.isTv(context)) ON_FOCUS else Color.WHITE

/** Text or icon colours: [rest] normally, the caption colour of the cursor while the view (or its parent, see [passParentState]) is focused. */
fun cursorColors(context: android.content.Context, rest: Int, state: Int = android.R.attr.state_focused) = android.content.res.ColorStateList(
    arrayOf(intArrayOf(state), intArrayOf()),
    intArrayOf(cursorText(context), rest),
)

/**
 * Makes a child follow the state (focus) of its parent. The parent starts to pass its state down only if the child had the flag
 * when it was added, so an already added child is added again.
 */
fun View.passParentState() {
    if (isDuplicateParentStateEnabled) return
    val parent = parent as? ViewGroup
    if (parent != null) {
        val at = parent.indexOfChild(this)
        val params = layoutParams
        parent.removeViewAt(at)
        isDuplicateParentStateEnabled = true
        parent.addView(this, at, params)
    } else isDuplicateParentStateEnabled = true
}

/** An icon of [PathDrawable] that is [rest] normally and the caption colour of the cursor while its parent is focused. */
fun ImageView.followCursor(rest: Int = Color.WHITE) {
    passParentState()
    imageTintList = cursorColors(context, rest)
}

/** A caption that is [rest] normally and the caption colour of the cursor while its parent is focused. */
fun TextView.followCursor(rest: Int = Color.WHITE) {
    passParentState()
    setTextColor(cursorColors(context, rest))
}

/** The plate of a text tile (settings section, saved episode, saved torrent): dark rounded card as on the phone, teal under focus. */
fun textTilePlate(scale: Float) = focusShape(scale, PLATE_FILL, radius = TITLE_CARD_RADIUS)

/** The one dark plate of cards and tiles: the text strip of a title card, the "show all" tile, the text tile. */
val PLATE_FILL: Int get() = LazyApp.res.getColor(R.color.plate_fill, null)

/** The darker plate of a page block (the banner, the bar of buttons, the cast cards, the left menu) and the colours of a watched episode. */
val BACKDROP_PLATE: Int get() = LazyApp.res.getColor(R.color.backdrop_plate, null)
val PLATE_LINE = 0xFF232836.toInt()
val PLATE_WATCHED = 0xFF121418.toInt()
val PLATE_WATCHED_LINE = 0xFF1B1F2A.toInt()

/** The plate of the chosen item among equals (the open season, the playing episode, the picked source, the current choice in a list). */
val ACTIVE_PLATE: Int get() = LazyApp.res.getColor(R.color.accent_light, null)
/** The caption on [ACTIVE_PLATE]: the colour of the page. */
val ACTIVE_TEXT: Int get() = PAGE_BG

/** The colour of the page behind everything (the same as the window background of the themes). */
val PAGE_BG: Int get() = LazyApp.res.getColor(R.color.page_bg, null)

/** Dark plates over a picture or a video (colors.xml): soft, light, dense; the cover is the page colour laid over the page. */
val OVERLAY_SOFT: Int get() = LazyApp.res.getColor(R.color.overlay_soft, null)
val OVERLAY_LIGHT: Int get() = LazyApp.res.getColor(R.color.overlay_light, null)
val OVERLAY_DENSE: Int get() = LazyApp.res.getColor(R.color.overlay_dense, null)
val PAGE_COVER: Int get() = LazyApp.res.getColor(R.color.page_cover, null)

/** The lighter grey of secondary text (a description, the rest of a name); one place, no number in the code. */
const val TEXT_SECONDARY = 0xFFCBD5E1.toInt()

/** Other named greys: the light text of a description, the dim text of a watched row, the bottom of the page gradient, the dark half of the watch button, the poster placeholder gradient. */
val TEXT_LIGHT: Int get() = ACTIVE_PLATE
const val TEXT_DIM = 0xFF64748B.toInt()
const val PAGE_BOTTOM = 0xFF0E1014.toInt()
const val WATCH_PLATE = 0xFF272C36.toInt()
const val PLACEHOLDER_FROM = 0xFF283048.toInt()
const val PLACEHOLDER_TO = 0xFF181D2A.toInt()

/** One-off roles: the neutral grey switch (no teal), the red of the stop-listening microphone, the gold mark. */
const val SWITCH_TRACK_ON = 0xFF6B7280.toInt()
const val SWITCH_TRACK_OFF = 0xFF3A3D42.toInt()
const val SWITCH_THUMB_OFF = 0xFFB4B7BC.toInt()
const val DANGER_FILL = 0xFFC62828.toInt()
const val DANGER_FOCUS = 0xFFE53935.toInt()
const val RATING_GOLD = 0xFFE0B94F.toInt()

/** The muted grey of captions and inactive icons. */
val TEXT_MUTED: Int get() = LazyApp.res.getColor(R.color.text_muted, null)

/**
 * The one look of "the chosen one among equals": resting it is the usual [plate] with the [restText] caption, the chosen
 * one is the light [ACTIVE_PLATE] with a dark caption (seen at once), under the cursor any of them is the teal [FOCUS_FILL]
 * with a white caption. [text] is the caption view (the item itself or its child); the chip takes the focus, the caption follows it.
 */
fun styleChoice(view: View, text: TextView?, active: Boolean, scale: Float, radius: Float = SMALL_RADIUS, plate: Int = PLATE_FILL, restText: Int = TEXT_MUTED) {
    val corner = radius * scale
    view.background = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply { setColor(cursorFill(view.context)); cornerRadius = corner })
        addState(intArrayOf(), android.graphics.drawable.GradientDrawable().apply { setColor(if (active) ACTIVE_PLATE else plate); cornerRadius = corner })
    }
    if (text == null) return
    if (text !== view) text.passParentState()
    text.setTextColor(cursorColors(view.context, if (active) ACTIVE_TEXT else restText))
}

fun chipButton(context: Context, scale: Float, caption: String, icon: String? = null): LinearLayout {
    fun px(v: Int) = (v * scale).toInt()
    return LinearLayout(context).apply {
        gravity = Gravity.CENTER
        isFocusable = !context.isMobileUi()
        isFocusableInTouchMode = !context.isMobileUi()
        isClickable = true
        background = focusShape(scale, PLATE_FILL, radius = SMALL_RADIUS)
        layoutParams = ViewGroup.MarginLayoutParams(-2, px(CHIP_HEIGHT))
        setPadding(px(if (icon == null) 22 else 18), 0, px(22), 0)
        if (icon != null) addView(ImageView(context).apply {
            tag = "icon"
            isDuplicateParentStateEnabled = true
            setChipIcon(icon)
        }, LinearLayout.LayoutParams(px(18), px(18)).apply { rightMargin = px(8) })
        addView(TextView(context).apply {
            tag = "caption"
            text = caption
            isDuplicateParentStateEnabled = true
            setTextColor(cursorColors(context, Color.WHITE))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * scale)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
        }, LinearLayout.LayoutParams(-2, -1))
    }
}

private fun ImageView.setChipIcon(icon: String) {
    setImageDrawable(PathDrawable(icon))
    imageTintList = cursorColors(context, Color.WHITE)
}

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
