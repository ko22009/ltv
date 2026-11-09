package ru.lazytv.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ru.lazytv.Cinema
import ru.lazytv.Title
import ru.lazytv.Watched
import ru.lazytv.databinding.ItemMobileTitleCardBinding
import ru.lazytv.showPoster

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

    override fun getItemCount(): Int = items.size

    inner class TitleViewHolder(
        private val binding: ItemMobileTitleCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(title: Title) {
            binding.cardTitle.text = title.name
            val subtitle = listOfNotNull(
                title.year.ifEmpty { null },
                title.genre.ifEmpty { null }
            ).joinToString(" • ")
            binding.cardSubtitle.text = subtitle

            // Rating
            if (title.rating.isNotEmpty() && title.rating != "0.0") {
                binding.cardRating.text = title.rating
                binding.cardRating.visibility = View.VISIBLE
            } else {
                binding.cardRating.visibility = View.GONE
            }

            // Cinema badge
            val cinemaCaption = Cinema.caption(title.id, title.tv)
            if (cinemaCaption != null) {
                binding.cardCinemaBadge.text = cinemaCaption
                binding.cardCinemaBadge.visibility = View.VISIBLE
            } else {
                binding.cardCinemaBadge.visibility = View.GONE
            }

            // Poster
            binding.cardPoster.showPoster(title.poster)
            // a title marked watched is dimmed, as on the TV cards
            binding.cardPoster.alpha = if (Watched.has(binding.root.context, title)) 0.45f else 1f

            // Click listener
            binding.cardRipple.setOnLongClickListener(onItemLongClick?.let { handler -> View.OnLongClickListener { handler(title); true } })
            binding.cardRipple.setOnClickListener {
                onItemClick(title)
            }
        }
    }
}
