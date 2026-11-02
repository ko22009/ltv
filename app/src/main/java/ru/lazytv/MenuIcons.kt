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
    private const val DASHBOARD = "M3,13h8V3H3v10zM3,21h8v-6H3v6zM13,21h8V11h-8v10zM13,3v6h8V3h-8z"
    private const val MOVIE = "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z"
    private const val TV = "M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12z"
    private const val SMILE = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM8.5,10a1.5,1.5 0,1 1,0 3,1.5 1.5,0 0,1 0,-3zM15.5,10a1.5,1.5 0,1 1,0 3,1.5 1.5,0 0,1 0,-3zM12,18c-2.2,0 -4,-1.3 -4.6,-3h9.2c-0.6,1.7 -2.4,3 -4.6,3z"
    private const val WARNING = "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-5h2v5z"
    private const val LOCK = "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z"
    /** The bookmarks and history icons of LazyMedia (ic_menu_bookmarks, ic_menu_history). */
    private const val BOOKMARKS = "M2.53 19.65l1.34 0.56 0 -9.03 -2.43 5.86c-0.41 1.02 0.08 2.19 1.09 2.61zM22.03 15.95L17.07 3.98C16.76 3.23 16.03 2.77 15.26 2.75 15 2.75 14.73 2.79 14.47 2.9L7.1 5.95C6.35 6.26 5.89 6.98 5.87 7.75 5.86 8.02 5.91 8.29 6.02 8.55l4.96 11.97c0.31 0.76 1.05 1.22 1.83 1.23 0.26 0 0.52 -0.05 0.77 -0.15l7.36 -3.05c1.02 -0.42 1.51 -1.59 1.09 -2.6zM7.88 8.75c-0.55 0 -1 -0.45 -1 -1 0 -0.55 0.45 -1 1 -1 0.55 0 1 0.45 1 1 0 0.55 -0.45 1 -1 1zm-2 11c0 1.1 0.9 2 2 2l1.45 0 -3.45 -8.34 0 6.34z"
    const val HISTORY = "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z"
    private const val BOLT = "M11,21h-1l1,-7H7.5c-0.58,0 -0.57,-0.32 -0.38,-0.66 0.19,-0.34 0.05,-0.08 0.07,-0.12C8.48,10.94 10.42,7.54 13,3h1l-1,7h3.5c0.49,0 0.56,0.33 0.47,0.51l-0.07,0.15C12.96,17.55 11,21 11,21z"
    private const val SETTINGS = "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94 0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61l-2.01,-1.58zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6 3.6,1.62 3.6,3.6 -1.62,3.6 -3.6,3.6z"
    private const val LIST = "M3,5h18v2H3zM3,11h18v2H3zM3,17h18v2H3z"
    private const val HEART = "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"
    private const val EXPLORE = "M12,10.9c-0.61,0 -1.1,0.49 -1.1,1.1s0.49,1.1 1.1,1.1c0.61,0 1.1,-0.49 1.1,-1.1s-0.49,-1.1 -1.1,-1.1zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM14.19,14.19L6,18l3.81,-8.19L18,6l-3.81,8.19z"
    const val SEARCH = "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
    private const val ARTICLE = "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z"

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
        "ФАНТАСТИКА" to STAR,
        "ТРИЛЛЕРЫ" to WARNING,
        "МЕЛОДРАМЫ" to HEART,
        "ДЕТЕКТИВЫ" to SEARCH,
        "ПРИКЛЮЧЕНИЯ" to EXPLORE,
        "ФЭНТЕЗИ" to STAR,
        "ДОКУМЕНТАЛЬНЫЕ" to ARTICLE,
        "АНИМЕ" to SMILE,
        "ЗАКЛАДКИ" to BOOKMARKS,
        "ИСТОРИЯ" to HISTORY,
        "НАСТРОЙКИ" to SETTINGS,
    )

    fun forName(name: String): Drawable = PathDrawable(byName[name] ?: LIST)
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

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (title == null) title = findTitle(this)
            restyle()
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
