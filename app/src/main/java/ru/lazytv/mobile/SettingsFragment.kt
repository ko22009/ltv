package ru.lazytv.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ru.lazytv.R
import ru.lazytv.SETTINGS_SECTIONS
import ru.lazytv.SettingsActivity
import ru.lazytv.PathDrawable
import ru.lazytv.Links
import ru.lazytv.SUPPORT_URL
import ru.lazytv.databinding.FragmentMobileGridBinding

/** The sections of the settings as a list; a section opens on its own screen (the same one the TV uses). */
class SettingsFragment : Fragment() {
    private var _binding: FragmentMobileGridBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMobileGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_mobile_torrent, parent, false)
            ) {}

            override fun getItemCount() = SETTINGS_SECTIONS.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val section = SETTINGS_SECTIONS[position]
                holder.itemView.findViewById<TextView>(R.id.torrentTitle).apply {
                    text = section.title
                    val icon = section.icon?.let {
                        PathDrawable(it).apply {
                            val size = (20 * resources.displayMetrics.density).toInt()
                            setBounds(0, 0, size, size)
                        }
                    }
                    setCompoundDrawables(icon, null, null, null)
                    compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
                }
                holder.itemView.findViewById<TextView>(R.id.torrentInfo).text = section.description
                holder.itemView.setOnClickListener {
                    if (section.title == "Поддержать проект") {
                        Links.openSupport(requireActivity(), SUPPORT_URL)
                        return@setOnClickListener
                    }
                    startActivity(Intent(requireContext(), SettingsActivity::class.java).putExtra("section", section.title))
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
