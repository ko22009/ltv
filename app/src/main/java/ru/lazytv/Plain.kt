package ru.lazytv

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/*
 * The small plain replacements of what the app used from Leanback: a presenter that makes and fills a view, a list of items
 * for a RecyclerView and the grid of cards with the TV focus. No library below this file moves the screen by itself.
 */

/** Makes the view of an item and fills it; one presenter for one kind of item. */
abstract class Presenter {
    class ViewHolder(@JvmField val view: View)

    abstract fun onCreateViewHolder(parent: ViewGroup): ViewHolder
    abstract fun onBindViewHolder(holder: ViewHolder, item: Any)
    open fun onUnbindViewHolder(holder: ViewHolder) {}
}

/** Chooses the presenter by the class of the item. */
class ClassPresenterSelector {
    private val map = LinkedHashMap<Class<*>, Presenter>()
    val presenters: List<Presenter> get() = map.values.distinct()

    fun addClassPresenter(cls: Class<*>, presenter: Presenter): ClassPresenterSelector {
        map[cls] = presenter
        return this
    }

    fun presenterFor(item: Any): Presenter =
        map[item.javaClass] ?: map.entries.first { it.key.isAssignableFrom(item.javaClass) }.value
}

/** How much the card under the cursor grows, the same for the grids and the pages of cards. */
const val FOCUS_ZOOM = 1.14f

/** The card or tile under the cursor grows and comes over its neighbours: the only focus mark of the cards. */
fun zoomOnFocus(view: View) {
    view.setOnFocusChangeListener { v, focused ->
        val to = if (focused) FOCUS_ZOOM else 1f
        v.animate().scaleX(to).scaleY(to).translationZ(if (focused) 8f else 0f).setDuration(150).start()
    }
}

/** The items of a list or a grid, shown by the presenters; the methods are the ones the pages used. */
class ItemList(private val selector: ClassPresenterSelector) : RecyclerView.Adapter<ItemList.Holder>() {
    constructor(presenter: Presenter) : this(ClassPresenterSelector().addClassPresenter(Any::class.java, presenter))

    class Holder(val inner: Presenter.ViewHolder, val presenter: Presenter) : RecyclerView.ViewHolder(inner.view) {
        var item: Any? = null
    }

    /** The pages hook their click and focus handlers here, once for a made view and again on every fill. */
    open class Listener {
        open fun onCreate(holder: Holder) {}
        open fun onBind(holder: Holder) {}
        open fun onAttachedToWindow(holder: Holder) {}
    }

    var listener: Listener? = null
    /** Cards grow under the focus on a TV (a touch screen has no cursor). */
    var zoom = false

    private val items = mutableListOf<Any>()

    fun size() = items.size
    fun get(index: Int): Any = items[index]

    fun add(item: Any) {
        items.add(item)
        notifyItemInserted(items.lastIndex)
    }

    fun addAll(index: Int, more: List<Any>) {
        items.addAll(index, more)
        notifyItemRangeInserted(index, more.size)
    }

    fun clear() {
        val count = items.size
        items.clear()
        notifyItemRangeRemoved(0, count)
    }

    fun replace(index: Int, item: Any) {
        items[index] = item
        notifyItemChanged(index)
    }

    fun notifyArrayItemRangeChanged(start: Int, count: Int) = notifyItemRangeChanged(start, count)

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int): Int = selector.presenters.indexOf(selector.presenterFor(items[position]))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val presenter = selector.presenters[viewType]
        val holder = Holder(presenter.onCreateViewHolder(parent), presenter)
        if (zoom) zoomOnFocus(holder.itemView)
        listener?.onCreate(holder)
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.item = items[position]
        holder.presenter.onBindViewHolder(holder.inner, items[position])
        listener?.onBind(holder)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.presenter.onUnbindViewHolder(holder.inner)
        super.onViewRecycled(holder)
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        listener?.onAttachedToWindow(holder)
    }
}

/**
 * A vertical grid of cards: the focus walks through the views by the arrows, the grid scrolls only as far as the focused card needs.
 * [onSelected] tells which position took the focus, [keyIntercept] sees the keys first (a page leads the arrows out of the grid by hand).
 */
open class CardGridView(context: Context, private val columns: Int, private val gap: Int) : RecyclerView(context) {
    var onSelected: ((Int) -> Unit)? = null
    var keyIntercept: ((KeyEvent) -> Boolean)? = null
    private var chosen = 0

    var selectedPosition: Int
        get() = focusedChild?.let { getChildAdapterPosition(it) }?.takeIf { it >= 0 } ?: chosen
        set(value) {
            chosen = value
            scrollToPosition(value)
        }

    init {
        layoutManager = GridLayoutManager(context, columns)
        itemAnimator = null
        isFocusable = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addItemDecoration(object : ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: State) {
                outRect.set(gap / 2, gap / 2, gap / 2, gap / 2)
            }
        })
    }

    override fun requestChildFocus(child: View, focused: View?) {
        super.requestChildFocus(child, focused)
        val position = getChildAdapterPosition(child)
        if (position >= 0) {
            chosen = position
            onSelected?.invoke(position)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyIntercept?.invoke(event) == true) return true
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focusedChild != null) {
            // the next row is not made yet while it is off screen, and the platform search then gives up or jumps away:
            // the grid walks down by itself, and from the last row the focus stays
            val count = adapter?.itemCount ?: 0
            val from = selectedPosition
            val row = from / columns
            if (row < (count - 1) / columns) moveTo(minOf(from + columns, count - 1))
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Puts the focus on the card at [position], making it first when it is off screen. */
    private fun moveTo(position: Int) {
        val ready = findViewHolderForAdapterPosition(position)
        if (ready != null) {
            ready.itemView.requestFocus()
            return
        }
        scrollToPosition(position)
        addOnLayoutChangeListener(object : OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                removeOnLayoutChangeListener(this)
                findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        })
    }
}
