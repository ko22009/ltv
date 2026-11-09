package ru.lazytv.mobile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import ru.lazytv.R

class TorrentsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val root = inflater.inflate(R.layout.fragment_mobile_placeholder, container, false)
        root.findViewById<TextView>(R.id.placeholderText)?.text = "Торренты"
        return root
    }
}
