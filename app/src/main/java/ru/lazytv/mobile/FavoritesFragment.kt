package ru.lazytv.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ru.lazytv.DetailsActivity
import ru.lazytv.EpisodeState
import ru.lazytv.EpisodesActivity
import ru.lazytv.FavoriteTorrents
import ru.lazytv.Favorites
import ru.lazytv.R
import ru.lazytv.TorrentsPopupActivity
import ru.lazytv.databinding.FragmentMobileFavoritesBinding
import ru.lazytv.toExtras

/** One page like the TV bookmarks: the bookmarked titles, then the saved episodes and the starred torrents. A long press removes. */
class FavoritesFragment : Fragment() {
    private var _binding: FragmentMobileFavoritesBinding? = null
    private val binding get() = _binding!!
    private lateinit var titles: MobileTitleAdapter
    private val episodeRows = TextRows()

    /** One line of the episode and torrent lists: a tap opens it, a long press takes it out of the bookmarks. */
    private class TextRow(val title: String, val info: String, val open: () -> Unit, val remove: () -> Unit, val torrent: ru.lazytv.Torrent? = null)

    private inner class TextRows : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        val rows = mutableListOf<TextRow>()

        // a torrent is drawn as in the list of torrents (a holder to put the card into), other lines with the simple two-line item
        override fun getItemViewType(position: Int) = if (rows[position].torrent != null) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
            if (viewType == 1) android.widget.FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(-1, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            } else LayoutInflater.from(parent.context).inflate(R.layout.item_mobile_torrent, parent, false)
        ) {}

        private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (row.torrent != null) {
                val holder1 = holder.itemView as android.widget.FrameLayout
                holder1.removeAllViews()
                val card = ru.lazytv.torrentRowCard(requireContext(), row.torrent, marked = true)
                // the filled bookmark takes the torrent out at once (a tap on the row would start TorrServe)
                card.findViewWithTag<View>("star")?.setOnClickListener {
                    row.remove()
                    Toast.makeText(requireContext(), "Убрано из закладок", Toast.LENGTH_SHORT).show()
                    refresh()
                }
                holder1.addView(card)
            } else {
                holder.itemView.findViewById<TextView>(R.id.torrentTitle).text = row.title
                holder.itemView.findViewById<TextView>(R.id.torrentInfo).text = row.info
            }
            holder.itemView.setOnClickListener { row.open() }
            holder.itemView.setOnLongClickListener {
                row.remove()
                Toast.makeText(requireContext(), "Убрано из закладок", Toast.LENGTH_SHORT).show()
                refresh()
                true
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMobileFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        titles = MobileTitleAdapter { title ->
            val torrent = torrentOf(title)
            if (torrent != null) openTorrent(torrent)
            else startActivity(Intent(requireContext(), DetailsActivity::class.java).putExtras(title.toExtras()))
        }
        titles.onItemLongClick = { title ->
            val torrent = torrentOf(title)
            if (torrent != null) FavoriteTorrents.toggle(requireContext(), torrent) else Favorites.set(requireContext(), title, null)
            Toast.makeText(requireContext(), "Убрано из закладок", Toast.LENGTH_SHORT).show()
            refresh()
        }
        binding.titlesList.layoutManager = GridLayoutManager(requireContext(), mobileColumns(resources))
        binding.titlesList.adapter = titles
        binding.episodesList.layoutManager = LinearLayoutManager(requireContext())
        binding.episodesList.adapter = episodeRows
    }

    /** The saved torrents drawn as film cards, by the key of the card. */
    private var cardTorrents = mapOf<String, ru.lazytv.Torrent>()

    private fun torrentOf(title: ru.lazytv.Title) = if (title.torrentKey.isEmpty()) null else cardTorrents[title.torrentKey]

    // a saved torrent goes straight to the preload, no new search
    private fun openTorrent(torrent: ru.lazytv.Torrent) {
        startActivity(
            Intent(requireContext(), TorrentsPopupActivity::class.java).putExtra("name", torrent.title)
                .putExtra("magnet", torrent.magnet).putExtra("size", torrent.size).putExtra("seeds", torrent.seeds)
                .putExtra("leeches", torrent.leeches).putExtra("source", torrent.source)
        )
    }

    private fun refresh() {
        val ctx = requireContext()
        val saved = Favorites.all(ctx)
        val cards = FavoriteTorrents.cards(ctx)
        cardTorrents = cards.associate { (torrent, film) -> film.torrentKey to torrent }
        titles.setItems(saved + cards.map { it.second })
        // a bookmark without a poster asks TMDB for it once, then the list is drawn again
        if (saved.any { it.poster == null || it.genre.isEmpty() }) {
            val app = ctx.applicationContext
            Thread {
                val fixed = Favorites.withPosters(app, saved)
                activity?.runOnUiThread { if (isAdded && fixed != saved) titles.setItems(fixed + cards.map { it.second }) }
            }.start()
        }

        episodeRows.rows.clear()
        EpisodeState.favorites(ctx).forEach { item ->
            episodeRows.rows += TextRow(item.seriesTitle, listOf(item.episodeTitle, item.source).filter { it.isNotEmpty() }.joinToString("  •  "), {
                startActivity(
                    Intent(ctx, EpisodesActivity::class.java)
                        .putExtra("source", item.source).putExtra("sourceId", item.sourceId)
                        .putExtra("title", item.seriesTitle).putExtra("note", item.note).putExtra("ref", item.ref)
                        .putExtra("alt", item.alt).putExtra("description", item.description)
                        .putExtra("episodeIndex", item.index)
                )
            }, { EpisodeState.toggleFavorite(ctx, item) })
        }
        episodeRows.notifyDataSetChanged()

        fun show(has: Boolean, vararg views: View) = views.forEach { it.visibility = if (has) View.VISIBLE else View.GONE }
        show(episodeRows.rows.isNotEmpty(), binding.episodesHeader, binding.episodesList)
        val empty = saved.isEmpty() && cards.isEmpty() && episodeRows.rows.isEmpty()
        binding.emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        binding.favoritesScroll.visibility = if (empty) View.GONE else View.VISIBLE
    }

    // the lists change on other screens (a card, the list of episodes or torrents): redraw when coming back
    override fun onResume() {
        super.onResume()
        if (_binding != null) refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
