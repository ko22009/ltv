package ru.lazytv.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ru.lazytv.Cinema
import ru.lazytv.RatingBadge
import ru.lazytv.Title
import ru.lazytv.Watched
import ru.lazytv.databinding.ItemMobileTitleCardBinding
import ru.lazytv.showPoster

private const val PAYLOAD_WATCHED = "watched"

/** Plates shown while the first page loads: a few rows of three. */
private const val SKELETON_COUNT = 12

class MobileTitleAdapter(
    /** A fixed card width in px for a horizontal carousel; 0 keeps the width the grid gives. */
    private val cardWidth: Int = 0,
    private val onItemClick: (Title) -> Unit,
) : RecyclerView.Adapter<MobileTitleAdapter.TitleViewHolder>() {

    private val items = mutableListOf<Title>()

    /** Long press: removes the title from the list it stands in (bookmarks, history); null when the list has no such action. */
    var onItemLongClick: ((Title) -> Unit)? = null

    fun removeItem(title: Title) {
        val index = items.indexOfFirst { it.id == title.id && it.tv == title.tv }
        if (index >= 0) { items.removeAt(index); notifyItemRemoved(index) }
    }

    fun setItems(newItems: List<Title>) {
        items.clear()
        items.addAll(newItems)
        skeletons = false
        notifyDataSetChanged()
    }

    /** Empty plates instead of cards while the first page is coming (the list is empty then). */
    private var skeletons = false

    fun showSkeletons() {
        items.clear()
        skeletons = true
        notifyDataSetChanged()
    }

    /** The cards really held, without the skeleton plates. */
    val realCount get() = items.size

    private var recycler: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) { recycler = recyclerView }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) { recycler = null }

    fun addItems(newOnes: List<Title>) {
        // an empty page (only watched titles) leaves the plates: the next page follows at once
        val wasSkeletons = skeletons && newOnes.isNotEmpty()
        if (wasSkeletons) { skeletons = false; notifyDataSetChanged() }
        val start = items.size
        // TMDB can repeat a title on the next page (or twice in one): the same title is not shown twice
        val seen = items.mapTo(HashSet()) { it.id to it.tv }
        val moreItems = newOnes.filter { seen.add(it.id to it.tv) }
        items.addAll(moreItems)
        notifyItemRangeInserted(start, moreItems.size)
        // the first cards of a list fade in instead of popping up at once (same pace as the page slide)
        if (start == 0 && moreItems.isNotEmpty() && !wasSkeletons) {
            recycler?.apply { alpha = 0f; animate().alpha(1f).setDuration(300).start() }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TitleViewHolder {
        val binding = ItemMobileTitleCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        if (cardWidth > 0) binding.root.layoutParams = RecyclerView.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        return TitleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TitleViewHolder, position: Int) {
        if (skeletons) holder.bindSkeleton() else holder.bind(items[position])
    }

    /** Only the watched dimming is redrawn: a title was marked on its own screen, the posters stay as they are. */
    fun refreshWatched() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, PAYLOAD_WATCHED)
    }

    override fun onBindViewHolder(holder: TitleViewHolder, position: Int, payloads: MutableList<Any>) {
        if (skeletons) holder.bindSkeleton()
        else if (payloads.contains(PAYLOAD_WATCHED)) holder.bindWatched(items[position]) else super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount(): Int = if (skeletons) SKELETON_COUNT else items.size

    inner class TitleViewHolder(
        private val binding: ItemMobileTitleCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // the same plate as on the TV and on the poster of the title card
            val d = binding.root.resources.displayMetrics.density
            RatingBadge.style(binding.cardRating, 12f * d, (8 * d).toInt(), (4 * d).toInt())
            binding.cardSpinnerHolder.addView(ru.lazytv.Spinner(binding.root.context, (ru.lazytv.SPINNER_CARD * d).toInt()))
            // the plate of the rating (same size and padding) with the bookmark icon as tall as the rating text
            RatingBadge.style(binding.cardFavorite, 12f * d, (8 * d).toInt(), (4 * d).toInt())
            binding.cardFavorite.height = RatingBadge.plateHeight(binding.root.context, 12f * d, (8 * d).toInt(), (4 * d).toInt())
            binding.cardFavorite.gravity = android.view.Gravity.CENTER_VERTICAL
            binding.cardFavorite.setCompoundDrawables(ru.lazytv.PathDrawable(ru.lazytv.MenuIcons.BOOKMARK).apply {
                setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
                setBounds(0, 0, (12 * d).toInt(), (12 * d).toInt())
            }, null, null, null)
            binding.cardWatched.apply {
                setImageDrawable(ru.lazytv.PathDrawable(ru.lazytv.MenuIcons.CHECK).apply {
                    setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
                })
                setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
                imageAlpha = 153
            }
        }

        /** A title marked watched is dimmed, as on the TV cards; a saved one wears the bookmark in the corner. */
        fun bindWatched(title: Title) {
            val context = binding.root.context
            val watchedAlpha = if (Watched.has(context, title)) 0.45f else 1f
            binding.cardPoster.alpha = watchedAlpha
            binding.cardTitle.alpha = watchedAlpha
            binding.cardSubtitle.alpha = watchedAlpha
            binding.cardRating.alpha = watchedAlpha
            binding.cardFavorite.alpha = watchedAlpha
            binding.cardCinemaBadge.alpha = watchedAlpha
            binding.cardWatched.visibility = if (watchedAlpha < 1f) View.VISIBLE else View.GONE
            binding.cardFavorite.visibility = if (title.torrentKey.isNotEmpty() || ru.lazytv.Favorites.contains(context, title)) View.VISIBLE else View.GONE
        }

        /** An empty poster plate and two bars of the colour of the plates in place of the caption. */
        fun bindSkeleton() {
            com.bumptech.glide.Glide.with(binding.root).clear(binding.cardPoster)
            binding.cardPoster.setImageDrawable(null)
            binding.cardSpinnerHolder.visibility = View.GONE
            listOf(binding.cardPoster, binding.cardTitle, binding.cardSubtitle).forEach { it.alpha = 1f }
            listOf(binding.cardRating, binding.cardFavorite, binding.cardCinemaBadge, binding.cardWatched).forEach { it.visibility = View.GONE }
            fun bar(text: Int) = android.text.SpannableString(" ".repeat(text)).apply {
                setSpan(android.text.style.BackgroundColorSpan(ru.lazytv.PLATE_FILL), 0, length, 0)
            }
            binding.cardTitle.text = bar(22)
            binding.cardSubtitle.text = bar(14)
            binding.cardRipple.setOnClickListener(null)
            binding.cardRipple.setOnLongClickListener(null)
            binding.cardRipple.isClickable = false
        }

        fun bind(title: Title) {
            binding.cardRipple.isClickable = true
            binding.cardTitle.text = title.name
            val subtitle = listOfNotNull(
                title.year.ifEmpty { null },
                title.genre.ifEmpty { null }
            ).joinToString(" • ")
            binding.cardSubtitle.text = subtitle

            // Rating
            val ratingLabel = RatingBadge.label(title.rating)
            binding.cardRating.text = ratingLabel
            binding.cardRating.visibility = if (ratingLabel != null) View.VISIBLE else View.GONE

            // Cinema badge
            val cinemaCaption = Cinema.caption(title)
            if (cinemaCaption != null) {
                binding.cardCinemaBadge.text = cinemaCaption
                binding.cardCinemaBadge.visibility = View.VISIBLE
            } else {
                binding.cardCinemaBadge.visibility = View.GONE
            }

            // Poster
            binding.cardPoster.showPoster(title.poster, binding.cardSpinnerHolder)
            bindWatched(title)

            // Click listener
            binding.cardRipple.setOnLongClickListener(onItemLongClick?.let { handler -> View.OnLongClickListener { handler(title); true } })
            binding.cardRipple.setOnClickListener {
                onItemClick(title)
            }
        }
    }
}
