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
    val texts = LinearLayout(context)
    texts.orientation = LinearLayout.VERTICAL
    texts.addView(label(main, if (mobile || compact) 16f else 19f, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    texts.addView(label(rest, 14f, TEXT_SECONDARY).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
    texts.addView(label(torrent.source, 13f, TEXT_MUTED))
    card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

    val numbers = LinearLayout(context)
    numbers.orientation = LinearLayout.VERTICAL
    numbers.gravity = Gravity.END
    numbers.addView(label(torrent.size, 14f, Color.WHITE, true))
    numbers.addView(label("▲ ${torrent.seeds}", 13f, SEEDS_COLOR))
    numbers.addView(label("▼ ${torrent.leeches}", 13f, PEERS_COLOR))
    card.addView(numbers, LinearLayout.LayoutParams(px(if (mobile || compact) 80 else 110), -2))

    val star = ImageView(context)
    star.tag = "star"
    star.setImageDrawable(PathDrawable(if (marked) MenuIcons.BOOKMARK else MenuIcons.BOOKMARK_BORDER).apply {
        setColorFilter(if (marked) Color.WHITE else 0x99FFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
    })
    card.addView(star, LinearLayout.LayoutParams(px(30), px(30)).apply { leftMargin = px(if (compact) 8 else 16) })
    return card
}
