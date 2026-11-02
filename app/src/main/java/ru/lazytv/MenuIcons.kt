package ru.lazytv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PathParser
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowHeaderPresenter

/** Icons of the left menu as 24x24 outline paths (Material icon shapes), drawn white. */
object MenuIcons {
    /** The history icon of LazyMedia (ic_menu_history), used by the search phrases. */
    const val HISTORY = "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z"

    /** The sort arrows of LazyMedia (ic_searchhistory_order), reused for the sort chip of the torrent list. */
    const val MIC = "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"
    /** Buttons of the title card. */
    const val PLAY = "M8,5v14l11,-7z"
    const val BOOKMARK = "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z"
    const val DOWNLOAD = "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z"
    const val TRAILER = "M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12zM16,11l-5,3V8z"
    /** Icons of the player's own control row. */
    const val PAUSE = "M6,19h4V5H6v14zM14,5v14h4V5h-4z"
    const val REPLAY10 = "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8zM10.9,16v-3.84L9.8,12.49v-0.69l1.87,-0.67h0.76V16h-1.53z"
    const val FORWARD10 = "M12,5V1l5,5 -5,5V7c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6h2c0,4.42 -3.58,8 -8,8s-8,-3.58 -8,-8 3.58,-8 8,-8zM10.9,16v-3.84L9.8,12.49v-0.69l1.87,-0.67h0.76V16h-1.53z"
    const val ASPECT = "M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM19,19H5V5h14v14zM8,15h8v-2H8v2zM8,11h8V9H8v2z"
    const val AUDIO_TRACK = "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"
    const val SUBTITLES = "M20,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM20,18H4V6h16v12zM6,10h2v2H6zM6,13h8v2H6zM16,10h2v2h-2zM11,10h2v2h-2z"
    const val STAR ="M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z"
    const val STAR_BORDER = "M22,9.24l-7.19,-0.62L12,2 9.19,8.62 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24zM12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z"
    const val SORT ="M16.372881 17.689322l0 -7.9605091 -2.271186 0 0 7.9605091 -3.40678 0 4.542373 4.531017 4.542373 -4.531017 -3.40678 0zM8.4237288 1.779661L3.8813559 6.3106779l3.4067797 0 0 7.9605081 2.2711864 0 0 -7.9605081 3.40678 0L8.4237288 1.779661Z"
    const val DELETE = "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"

    // Menu icons: one outline style (2 px line, round ends) on a 24x24 grid, so they stay even at the small menu size.
    private const val DASHBOARD = "M4,3h5a1,1 0 0 1 1,1v5a1,1 0 0 1 -1,1H4a1,1 0 0 1 -1,-1V4a1,1 0 0 1 1,-1z M15,3h5a1,1 0 0 1 1,1v5a1,1 0 0 1 -1,1h-5a1,1 0 0 1 -1,-1V4a1,1 0 0 1 1,-1z M15,14h5a1,1 0 0 1 1,1v5a1,1 0 0 1 -1,1h-5a1,1 0 0 1 -1,-1v-5a1,1 0 0 1 1,-1z M4,14h5a1,1 0 0 1 1,1v5a1,1 0 0 1 -1,1H4a1,1 0 0 1 -1,-1v-5a1,1 0 0 1 1,-1z"
    private const val MOVIE = "M5,3h14a2,2 0 0 1 2,2v14a2,2 0 0 1 -2,2H5a2,2 0 0 1 -2,-2V5a2,2 0 0 1 2,-2z M7,3v18 M17,3v18 M3,12h18 M3,7.5h4 M3,16.5h4 M17,7.5h4 M17,16.5h4"
    private const val TV = "M4,7h16a2,2 0 0 1 2,2v11a2,2 0 0 1 -2,2H4a2,2 0 0 1 -2,-2V9a2,2 0 0 1 2,-2z M17,2l-5,5 -5,-5"
    private const val SMILE = "M12,2a10,10 0 1 0 0,20 10,10 0 1 0 0,-20z M8,14s1.5,2 4,2 4,-2 4,-2 M9,9.5h.01 M15,9.5h.01"
    private const val WARNING = "M21.73,18l-8,-14a2,2 0 0 0 -3.48,0l-8,14A2,2 0 0 0 4,21h16a2,2 0 0 0 1.73,-3z M12,9v4 M12,17h.01"
    private const val LOCK = "M5,11h14a2,2 0 0 1 2,2v7a2,2 0 0 1 -2,2H5a2,2 0 0 1 -2,-2v-7a2,2 0 0 1 2,-2z M7,11V7a5,5 0 0 1 10,0v4"
    private const val BOLT = "M13,2L3,14h9l-1,8 10,-12h-9l1,-8z"
    private const val HEART = "M19,14c1.49,-1.46 3,-3.21 3,-5.5A5.5,5.5 0 0 0 16.5,3c-1.76,0 -3,0.5 -4.5,2 -1.5,-1.5 -2.74,-2 -4.5,-2A5.5,5.5 0 0 0 2,8.5c0,2.3 1.5,4.05 3,5.5l7,7z"
    private const val COMPASS = "M12,2a10,10 0 1 0 0,20 10,10 0 1 0 0,-20z M16.24,7.76l-2.12,6.36 -6.36,2.12 2.12,-6.36z"
    private const val SEARCH_ICON = "M11,3a8,8 0 1 0 0,16 8,8 0 1 0 0,-16z M21,21l-4.35,-4.35"
    private const val DOCUMENT = "M14,2H6a2,2 0 0 0 -2,2v16a2,2 0 0 0 2,2h12a2,2 0 0 0 2,-2V8z M14,2v6h6 M16,13H8 M16,17H8 M10,9H8"
    private const val STAR_ICON = "M12,2l3.09,6.26L22,9.27l-5,4.87 1.18,6.88L12,17.77l-6.18,3.25L7,14.14 2,9.27l6.91,-1.01z"
    private const val LIST = "M8,6h13 M8,12h13 M8,18h13 M3,6h.01 M3,12h.01 M3,18h.01"
    private const val BOOKMARKS = "M6,3h12a1,1 0 0 1 1,1v17.5l-7,-4.5 -7,4.5V4a1,1 0 0 1 1,-1z M9.5,9h5"
    private const val CLOCK = "M3,12a9,9 0 1 0 9,-9 9.75,9.75 0 0 0 -6.74,2.74L3,8 M3,3v5h5 M12,7v5l4,2"
    private const val GEAR = "M12.22,2h-0.44a2,2 0 0 0 -2,2v0.18a2,2 0 0 1 -1,1.73l-0.43,0.25a2,2 0 0 1 -2,0l-0.15,-0.08a2,2 0 0 0 -2.73,0.73l-0.22,0.38a2,2 0 0 0 0.73,2.73l0.15,0.1a2,2 0 0 1 1,1.72v0.51a2,2 0 0 1 -1,1.74l-0.15,0.09a2,2 0 0 0 -0.73,2.73l0.22,0.38a2,2 0 0 0 2.73,0.73l0.15,-0.08a2,2 0 0 1 2,0l0.43,0.25a2,2 0 0 1 1,1.73V20a2,2 0 0 0 2,2h0.44a2,2 0 0 0 2,-2v-0.18a2,2 0 0 1 1,-1.73l0.43,-0.25a2,2 0 0 1 2,0l0.15,0.08a2,2 0 0 0 2.73,-0.73l0.22,-0.39a2,2 0 0 0 -0.73,-2.73l-0.15,-0.08a2,2 0 0 1 -1,-1.74v-0.5a2,2 0 0 1 1,-1.74l0.15,-0.09a2,2 0 0 0 0.73,-2.73l-0.22,-0.38a2,2 0 0 0 -2.73,-0.73l-0.15,0.08a2,2 0 0 1 -2,0l-0.43,-0.25a2,2 0 0 1 -1,-1.73V4a2,2 0 0 0 -2,-2z M12,15a3,3 0 1 0 0,-6 3,3 0 0 0 0,6z"

    private val byName = mapOf(
        "ОБЗОР" to DASHBOARD,
        "ФИЛЬМЫ" to MOVIE,
        "СЕРИАЛЫ" to TV,
        "МУЛЬТФИЛЬМЫ" to SMILE,
        "УЖАСТИКИ" to WARNING,
        "18+" to LOCK,
        "БОЕВИКИ" to BOLT,
        "КОМЕДИИ" to SMILE,
        "ДРАМЫ" to MOVIE,
        "ФАНТАСТИКА" to STAR_ICON,
        "ТРИЛЛЕРЫ" to WARNING,
        "МЕЛОДРАМЫ" to HEART,
        "ДЕТЕКТИВЫ" to SEARCH_ICON,
        "ПРИКЛЮЧЕНИЯ" to COMPASS,
        "ФЭНТЕЗИ" to STAR_ICON,
        "ДОКУМЕНТАЛЬНЫЕ" to DOCUMENT,
        "АНИМЕ" to SMILE,
        "ЗАКЛАДКИ" to BOOKMARKS,
        "ИСТОРИЯ" to CLOCK,
        "НАСТРОЙКИ" to GEAR,
    )

    fun forName(name: String): Drawable = StrokeIcon(byName[name] ?: LIST)
}

/** Draws outline paths of a 24x24 grid with round line ends, scaled to its bounds. */
class StrokeIcon(data: String) : Drawable() {
    private val path: Path = PathParser.createPathFromPathData(data)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val scale = bounds.width() / 24f
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(scale, scale)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Draws a 24x24 path scaled to its bounds. */
class PathDrawable(data: String) : Drawable() {
    private val path: Path = PathParser.createPathFromPathData(data)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    override fun draw(canvas: Canvas) {
        val scale = bounds.width() / 24f
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(scale, scale)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Left menu entry: an icon and the title. The row the user is on gets a green plate behind it and a white title,
 * so it reads clearly against the muted rest of the menu — both when it is the selected page and when the D-pad
 * just lands on it.
 */
class IconHeaderPresenter : RowHeaderPresenter() {
    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val base = super.onCreateViewHolder(parent) as ViewHolder
        val scale = parent.resources.displayMetrics.widthPixels / 1280f
        val size = (scale * 28).toInt()
        val icon = ImageView(parent.context)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        val container = HeaderRow(parent.context, scale)
        container.orientation = LinearLayout.HORIZONTAL
        container.gravity = Gravity.CENTER_VERTICAL
        container.addView(icon, LinearLayout.LayoutParams(size, size).apply { rightMargin = size / 2 })
        container.addView(base.view)
        container.title = findTitle(container)
        container.restyle()
        return ViewHolder(container)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        super.onBindViewHolder(viewHolder, item)
        val name = (item as? Row)?.headerItem?.name ?: return
        val container = viewHolder.view as HeaderRow
        (container.getChildAt(0) as ImageView).setImageDrawable(MenuIcons.forName(name))
        container.title = findTitle(container)
        container.restyle()
    }

    private fun findTitle(view: ViewGroup): TextView? {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child is TextView) return child
            if (child is ViewGroup) findTitle(child)?.let { return it }
        }
        return null
    }

    /** The row repaints itself as soon as the list selects it or the focus lands anywhere inside. */
    private class HeaderRow(context: Context, private val scale: Float) : LinearLayout(context) {
        var title: TextView? = null

        init {
            background = plate()
            setPadding((6 * scale).toInt(), (4 * scale).toInt(), (18 * scale).toInt(), (4 * scale).toInt())
        }

        /** A bright plate under the focus and a calmer one under the selected page; nothing otherwise. */
        private fun plate() = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(0xFF2F7D57.toInt()))
            addState(intArrayOf(android.R.attr.state_selected), shape(0xFF2A5540.toInt()))
            addState(intArrayOf(), shape(Color.TRANSPARENT))
        }

        private fun shape(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 10f * scale
        }

        override fun setSelected(selected: Boolean) {
            super.setSelected(selected)
            restyle()
        }

        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            restyle()
        }

        // the base presenter dims headers through alpha; the look is set by colours here, otherwise the chosen
        // item never turns really white
        override fun setAlpha(alpha: Float) {
            super.setAlpha(1f)
        }

        // the same for the zoom of a focused header: the plate and the colour are enough
        override fun setScaleX(scaleX: Float) {
            super.setScaleX(1f)
        }

        override fun setScaleY(scaleY: Float) {
            super.setScaleY(1f)
        }

        // the focus zoom of the headers is applied to the wrapper that leanback puts around the row, where the
        // overrides above do not reach: it is set back to 1 before every frame
        private val noZoom = android.view.ViewTreeObserver.OnPreDrawListener {
            var v: android.view.View? = this
            repeat(2) {
                if (v != null && (v!!.scaleX != 1f || v!!.scaleY != 1f)) { v!!.scaleX = 1f; v!!.scaleY = 1f }
                v = v?.parent as? android.view.View
            }
            true
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (title == null) title = findTitle(this)
            viewTreeObserver.addOnPreDrawListener(noZoom)
            restyle()
        }

        override fun onDetachedFromWindow() {
            viewTreeObserver.removeOnPreDrawListener(noZoom)
            super.onDetachedFromWindow()
        }

        fun restyle() {
            val active = isSelected || isFocused || hasFocus()
            title?.setTextColor(if (active) Color.WHITE else 0xFF94AFA1.toInt())
            title?.alpha = 1f
            // the leanback header dims its own title view; the plate already tells the state
            getChildAt(1)?.alpha = 1f
        }

        private fun findTitle(view: ViewGroup): TextView? {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is TextView) return child
                if (child is ViewGroup) findTitle(child)?.let { return it }
            }
            return null
        }
    }
}
