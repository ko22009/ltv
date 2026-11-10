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

class MobileTitleAdapter(
    private val onItemClick: (Title) -> Unit
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
        notifyDataSetChanged()
    }

    fun addItems(moreItems: List<Title>) {
        val start = items.size
        items.addAll(moreItems)
        notifyItemRangeInserted(start, moreItems.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TitleViewHolder {
        val binding = ItemMobileTitleCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TitleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TitleViewHolder, position: Int) {
        holder.bind(items[position])
    }

    /** Only the watched dimming is redrawn: a title was marked on its own screen, the posters stay as they are. */
    fun refreshWatched() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, PAYLOAD_WATCHED)
    }

    override fun onBindViewHolder(holder: TitleViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_WATCHED)) holder.bindWatched(items[position]) else super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount(): Int = items.size

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
        }

        /** A title marked watched is dimmed, as on the TV cards; a saved one wears the bookmark in the corner. */
        fun bindWatched(title: Title) {
            val context = binding.root.context
            binding.cardPoster.alpha = if (Watched.has(context, title)) 0.45f else 1f
            binding.cardFavorite.visibility = if (ru.lazytv.Favorites.contains(context, title)) View.VISIBLE else View.GONE
        }

        fun bind(title: Title) {
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
            val cinemaCaption = Cinema.caption(title.id, title.tv)
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
