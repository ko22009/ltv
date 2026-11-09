package ru.lazytv.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import ru.lazytv.DetailsActivity
import ru.lazytv.FavoriteTorrents
import ru.lazytv.Favorites
import ru.lazytv.History
import ru.lazytv.R
import ru.lazytv.Torrent
import ru.lazytv.TorrentsActivity
import ru.lazytv.databinding.FragmentMobileGridBinding
import ru.lazytv.toExtras

/** Bookmarks of the device, the history of opened titles and the torrents marked with the star. */
class FavoritesFragment : Fragment() {
    private enum class Mode(val caption: String) { BOOKMARKS("Закладки"), HISTORY("История"), TORRENTS("Раздачи") }

    private var _binding: FragmentMobileGridBinding? = null
    private val binding get() = _binding!!
    private lateinit var titles: MobileTitleAdapter
    private var mode = Mode.BOOKMARKS
    private val torrents = mutableListOf<Torrent>()

    private val torrentAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_mobile_torrent, parent, false)
        ) {}

        override fun getItemCount() = torrents.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val torrent = torrents[position]
            holder.itemView.findViewById<TextView>(R.id.torrentTitle).text = torrent.title
            holder.itemView.findViewById<TextView>(R.id.torrentInfo).text =
                listOf(torrent.size, "▲ ${torrent.seeds}", torrent.source).filter { it.isNotEmpty() }.joinToString("  •  ")
            holder.itemView.setOnClickListener {
                // a saved torrent goes straight to the preload, no new search
                startActivity(
                    Intent(requireContext(), TorrentsActivity::class.java).putExtra("name", torrent.title)
                        .putExtra("magnet", torrent.magnet).putExtra("size", torrent.size).putExtra("seeds", torrent.seeds)
                        .putExtra("leeches", torrent.leeches).putExtra("source", torrent.source)
                )
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMobileGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        titles = MobileTitleAdapter { title ->
            startActivity(Intent(requireContext(), DetailsActivity::class.java).putExtras(title.toExtras()))
        }
        binding.chipScroll.visibility = View.VISIBLE
        Mode.values().forEach { m ->
            val chip = layoutInflater.inflate(R.layout.chip_mobile_filter, binding.chipGroup, false) as Chip
            chip.text = m.caption
            chip.id = View.generateViewId()
            chip.isChecked = m == mode
            chip.setOnClickListener { mode = m; refresh() }
            binding.chipGroup.addView(chip)
        }
    }

    private fun refresh() {
        val ctx = requireContext()
        val count: Int
        if (mode == Mode.TORRENTS) {
            torrents.clear()
            torrents.addAll(FavoriteTorrents.all(ctx))
            binding.recycler.layoutManager = LinearLayoutManager(ctx)
            binding.recycler.adapter = torrentAdapter
            torrentAdapter.notifyDataSetChanged()
            count = torrents.size
            binding.emptyText.text = "Раздач пока нет.\nОтметьте раздачу звёздочкой в списке торрентов"
        } else {
            val list = if (mode == Mode.HISTORY) History.all(ctx) else Favorites.all(ctx)
            binding.recycler.layoutManager = GridLayoutManager(ctx, mobileColumns(resources))
            binding.recycler.adapter = titles
            titles.setItems(list)
            count = list.size
            binding.emptyText.text = if (mode == Mode.HISTORY) "История пуста.\nЗдесь появятся открытые фильмы и сериалы"
            else "Закладок пока нет.\nДобавьте фильм кнопкой «Закладки» на его странице"
        }
        binding.emptyText.visibility = if (count == 0) View.VISIBLE else View.GONE
    }

    // the lists change on other screens (a card, the torrent list): redraw when coming back
    override fun onResume() {
        super.onResume()
        if (_binding != null) refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
