package ru.lazytv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Colours of the seeds and the peers: green and red, the same in every list of torrents. */
const val SEEDS_COLOR = 0xFF4ADE80.toInt()
const val PEERS_COLOR = 0xFFF87171.toInt()
/** The same two on the light plate of the TV cursor (the selected row): darker, so they stay readable. */
const val SEEDS_COLOR_ON_LIGHT = 0xFF15803D.toInt()
const val PEERS_COLOR_ON_LIGHT = 0xFFB91C1C.toInt()

/**
 * One torrent as a card: the Russian title (bold), the rest of the name, the source; on the right the size, the seeds
 * (green) and the peers (red), then the bookmark (filled white when the torrent is in the favorites).
 * The one look for the list of torrents and for the favorite torrents (TV and phone); the bookmark carries the tag "star".
 * [compact] is for a narrow tile (smaller padding, a narrower column of numbers).
 */
fun torrentRowCard(context: Context, torrent: Torrent, marked: Boolean, compact: Boolean = false): LinearLayout {
    val scale = context.uiScale()
    val mobile = context.isMobileUi()
    fun px(v: Int) = (v * scale).toInt()
    fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    val parts = torrent.title.split(" / ", limit = 2)
    val main = if (parts.size == 2) parts[0] else torrent.title.substringBefore(" (")
    val rest = if (parts.size == 2) parts[1] else torrent.title

    val card = LinearLayout(context)
    card.gravity = Gravity.CENTER_VERTICAL
    if (mobile || compact) card.setPadding(px(12), px(12), px(12), px(12)) else card.setPadding(px(16), px(12), px(16), px(12))
    card.background = outlinedCardBackground(scale, android.R.attr.state_selected, 12)
    // the selected row sits on the cursor plate: its texts take the caption colour of the cursor (the state goes down through the blocks)
    val selected = android.R.attr.state_selected
    fun TextView.onSelected(rest: Int): TextView = apply {
        isDuplicateParentStateEnabled = true
        setTextColor(cursorColors(context, rest, selected))
    }
    val texts = LinearLayout(context)
    texts.orientation = LinearLayout.VERTICAL
    texts.isDuplicateParentStateEnabled = true
    texts.addView(label(main, if (mobile || compact) 16f else 19f, Color.WHITE, true).onSelected(Color.WHITE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    texts.addView(label(rest, 14f, TEXT_SECONDARY).onSelected(TEXT_SECONDARY).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
    texts.addView(label(torrent.source, 13f, TEXT_MUTED).onSelected(TEXT_MUTED))
    card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

    val numbers = LinearLayout(context)
    numbers.orientation = LinearLayout.VERTICAL
    numbers.gravity = Gravity.END
    numbers.isDuplicateParentStateEnabled = true
    numbers.addView(label(torrent.size, 14f, Color.WHITE, true).onSelected(Color.WHITE))
    // on the light plate of the TV cursor the bright green and red wash out: the selected row gets their darker twins
    fun TextView.signalOnSelected(rest: Int, onLight: Int): TextView = apply {
        isDuplicateParentStateEnabled = true
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(selected), intArrayOf()),
            intArrayOf(if (DeviceType.isTv(context)) onLight else rest, rest)))
    }
    numbers.addView(label("▲ ${torrent.seeds}", 13f, SEEDS_COLOR).signalOnSelected(SEEDS_COLOR, SEEDS_COLOR_ON_LIGHT))
    numbers.addView(label("▼ ${torrent.leeches}", 13f, PEERS_COLOR).signalOnSelected(PEERS_COLOR, PEERS_COLOR_ON_LIGHT))
    card.addView(numbers, LinearLayout.LayoutParams(px(if (mobile || compact) 80 else 110), -2))

    val star = ImageView(context)
    star.tag = "star"
    star.isDuplicateParentStateEnabled = true
    star.setImageDrawable(PathDrawable(if (marked) MenuIcons.BOOKMARK else MenuIcons.BOOKMARK_BORDER))
    star.imageTintList = cursorColors(context, if (marked) Color.WHITE else 0x99FFFFFF.toInt(), selected)
    card.addView(star, LinearLayout.LayoutParams(px(30), px(30)).apply { leftMargin = px(if (compact) 8 else 16) })
    return card
}
