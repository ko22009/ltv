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
import ru.lazytv.TorrentsActivity
import ru.lazytv.databinding.FragmentMobileFavoritesBinding
import ru.lazytv.toExtras

/** One page like the TV bookmarks: the bookmarked titles, then the saved episodes and the starred torrents. A long press removes. */
class FavoritesFragment : Fragment() {
    private var _binding: FragmentMobileFavoritesBinding? = null
    private val binding get() = _binding!!
    private lateinit var titles: MobileTitleAdapter
    private val episodeRows = TextRows()
    private val torrentRows = TextRows()

    /** One line of the episode and torrent lists: a tap opens it, a long press takes it out of the bookmarks. */
    private class TextRow(val title: String, val info: String, val open: () -> Unit, val remove: () -> Unit)

    private inner class TextRows : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        val rows = mutableListOf<TextRow>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_mobile_torrent, parent, false)
        ) {}

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            holder.itemView.findViewById<TextView>(R.id.torrentTitle).text = row.title
            holder.itemView.findViewById<TextView>(R.id.torrentInfo).text = row.info
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
            startActivity(Intent(requireContext(), DetailsActivity::class.java).putExtras(title.toExtras()))
        }
        titles.onItemLongClick = { title ->
            Favorites.set(requireContext(), title, null)
            Toast.makeText(requireContext(), "Убрано из закладок", Toast.LENGTH_SHORT).show()
            refresh()
        }
        binding.titlesList.layoutManager = GridLayoutManager(requireContext(), mobileColumns(resources))
        binding.titlesList.adapter = titles
        binding.episodesList.layoutManager = LinearLayoutManager(requireContext())
        binding.episodesList.adapter = episodeRows
        binding.torrentsList.layoutManager = LinearLayoutManager(requireContext())
        binding.torrentsList.adapter = torrentRows
    }

    private fun refresh() {
        val ctx = requireContext()
        val saved = Favorites.all(ctx)
        titles.setItems(saved)
        // a bookmark without a poster asks TMDB for it once, then the list is drawn again
        if (saved.any { it.poster == null }) {
            val app = ctx.applicationContext
            Thread {
                val fixed = Favorites.withPosters(app, saved)
                activity?.runOnUiThread { if (isAdded && fixed != saved) titles.setItems(fixed) }
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

        torrentRows.rows.clear()
        FavoriteTorrents.all(ctx).forEach { torrent ->
            torrentRows.rows += TextRow(torrent.title, listOf(torrent.size, "▲ ${torrent.seeds}", torrent.source).filter { it.isNotEmpty() }.joinToString("  •  "), {
                // a saved torrent goes straight to the preload, no new search
                startActivity(
                    Intent(ctx, TorrentsActivity::class.java).putExtra("name", torrent.title)
                        .putExtra("magnet", torrent.magnet).putExtra("size", torrent.size).putExtra("seeds", torrent.seeds)
                        .putExtra("leeches", torrent.leeches).putExtra("source", torrent.source)
                )
            }, { FavoriteTorrents.toggle(ctx, torrent) })
        }
        torrentRows.notifyDataSetChanged()

        fun show(has: Boolean, vararg views: View) = views.forEach { it.visibility = if (has) View.VISIBLE else View.GONE }
        show(episodeRows.rows.isNotEmpty(), binding.episodesHeader, binding.episodesList)
        show(torrentRows.rows.isNotEmpty(), binding.torrentsHeader, binding.torrentsList)
        val empty = saved.isEmpty() && episodeRows.rows.isEmpty() && torrentRows.rows.isEmpty()
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
