package ru.lazytv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Playable items (episodes) of a variant found on a video site; picking one starts the player. */
class EpisodesActivity : Activity() {
    private val scale get() = resources.displayMetrics.widthPixels / 1280f
    private fun px(value: Int) = (value * scale).toInt()

    private lateinit var variant: Variant
    private var episodes: List<Episode> = emptyList()
    private var adapter: ArrayAdapter<Episode>? = null
    private lateinit var list: ListView
    private lateinit var actions: LinearLayout
    private lateinit var actionFavorite: View
    private lateinit var actionWatched: View
    private lateinit var geoButton: View
    private var selectedIndex = 0

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** One of the action buttons above the list: the button of the design system ([chipButton]); works on the selected episode. */
    private fun actionButton() = chipButton(this, scale, "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        variant = Variant(
            intent.getStringExtra("source").orEmpty(), intent.getStringExtra("sourceId").orEmpty(),
            intent.getStringExtra("title").orEmpty(), intent.getStringExtra("note").orEmpty(),
            intent.getStringExtra("ref").orEmpty(), intent.getStringExtra("alt").orEmpty(),
            intent.getStringExtra("description").orEmpty(),
        )
        val root = FrameLayout(this)
        root.background = calmBackground()
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(px(64), px(40), px(64), px(20))
        page.addView(label(variant.title, 32f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        // the original name is its own line, not glued to the description
        if (variant.alt.isNotEmpty() && !variant.alt.equals(variant.title, true)) {
            page.addView(label(variant.alt, 21f, 0xFFAFC4B6.toInt()).apply {
                setPadding(0, px(2), 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        page.addView(label("Удерживайте OK, чтобы выбрать качество", 14f, 0xFF8FB39D.toInt()).apply {
            setPadding(0, px(12), 0, px(12))
        })
        val status = label("", 15f, 0xFFDCE8E0.toInt())
        status.visibility = View.GONE
        page.addView(status)
        val errorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        geoButton = actionButton().apply {
            chipCaption = "Искать торренты"
            setOnClickListener { openTorrents() }
        }
        val fixButton = actionButton().apply { visibility = View.GONE }
        errorRow.addView(geoButton, LinearLayout.LayoutParams(-2, px(CHIP_HEIGHT)).apply { rightMargin = px(12) })
        errorRow.addView(fixButton, LinearLayout.LayoutParams(-2, px(CHIP_HEIGHT)))
        page.addView(errorRow, LinearLayout.LayoutParams(-2, px(56)).apply { topMargin = px(6) })
        actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.visibility = View.GONE
        actionFavorite = actionButton().apply { setOnClickListener { toggleFavorite() } }
        actionWatched = actionButton().apply { setOnClickListener { toggleWatched() } }
        actions.addView(actionFavorite, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = px(12) })
        actions.addView(actionWatched, LinearLayout.LayoutParams(-1, -1).apply { width = -2 })
        page.addView(actions, LinearLayout.LayoutParams(-1, px(56)).apply { topMargin = px(6) })
        list = ListView(this)
        list.divider = null
        list.selector = ColorDrawable(Color.TRANSPARENT)
        val loading = LoadingView(this, scale, "Загружаем список серий…")
        val body = FrameLayout(this)
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        body.addView(loading, FrameLayout.LayoutParams(-1, -1))
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        Thread {
            val result = runCatching { Sources.all.first { it.id == variant.sourceId }.episodes(variant) }
            onUi {
                loading.visibility = View.GONE
                result.onSuccess { loaded ->
                    episodes = loaded
                    if (episodes.isEmpty()) {
                        status.text = "Нет доступных серий. Попробуйте торренты"
                        status.visibility = View.VISIBLE
                        errorRow.visibility = View.VISIBLE
                        geoButton.requestFocus()
                        return@onSuccess
                    }
                    adapter = object : ArrayAdapter<Episode>(this, 0, episodes) {
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup) = row(position, getItem(position)!!)
                    }
                    list.adapter = adapter
                    list.setOnItemClickListener { _, _, position, _ -> play(episodes, position) }
                    list.setOnItemLongClickListener { _, _, position, _ -> play(episodes, position, true); true }
                    list.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                            selectedIndex = position
                            updateActions()
                        }
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    })
                    val focus = intent.getIntExtra("episodeIndex", -1)
                    selectedIndex = if (focus in episodes.indices) focus else 0
                    list.setSelection(selectedIndex)
                    actions.visibility = View.VISIBLE
                    updateActions()
                    list.requestFocus()
                }.onFailure { err ->
                    status.text = Errors.explain(err)
                    status.visibility = View.VISIBLE
                    Errors.actionOf(err)?.let { action ->
                        fixButton.chipCaption = action.caption
                        fixButton.visibility = View.VISIBLE
                        fixButton.setOnClickListener { action.run(this) }
                    }
                    errorRow.visibility = View.VISIBLE
                    geoButton.requestFocus()
                }
            }
        }.start()
    }

    private fun row(position: Int, episode: Episode): View {
        val key = EpisodeState.key(variant.sourceId, variant.ref, episode.title)
        val watched = EpisodeState.isWatched(this, key)
        val favorite = EpisodeState.isFavorite(this, variant.sourceId, variant.ref, position, episode.title)
        val card = LinearLayout(this)
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(px(20), px(14), px(20), px(14))
        card.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply { setColor(0xFF2F7D57.toInt()); cornerRadius = px(3).toFloat() })
            addState(intArrayOf(), GradientDrawable().apply {
                // a watched episode is visibly dimmer than an unseen one
                setColor(if (watched) 0x16000000 else 0x33000000)
                cornerRadius = px(3).toFloat()
            })
        }
        card.addView(label(episode.title, 19f, if (watched) 0xFF6F8478.toInt() else Color.WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        if (favorite) card.addView(label("★", 20f, 0xFFE0B94F.toInt()).apply { setPadding(px(8), 0, px(4), 0) })
        if (watched) card.addView(label("✓", 20f, 0xFF6FA487.toInt()).apply { setPadding(px(4), 0, px(6), 0) })
        card.addView(label(episode.streams.joinToString("  ") { it.first }, 14f,
            if (watched) 0xFF5E7066.toInt() else 0xFFAFC4B6.toInt()).apply {
            setPadding(px(8), 0, px(12), 0)
        })
        val holder = object : FrameLayout(this) {
            override fun setSelected(selected: Boolean) { super.setSelected(selected); card.isSelected = selected }
        }
        holder.setPadding(0, px(3), 0, px(3))
        holder.addView(card)
        return holder
    }

    /** The two buttons work on the episode currently selected in the list. */
    private fun updateActions() {
        if (episodes.isEmpty()) return
        val position = selectedIndex.coerceIn(0, episodes.lastIndex)
        val episode = episodes[position]
        val watched = EpisodeState.isWatched(this, EpisodeState.key(variant.sourceId, variant.ref, episode.title))
        val favorite = EpisodeState.isFavorite(this, variant.sourceId, variant.ref, position, episode.title)
        actionFavorite.chipCaption = if (favorite) "Убрать из избранного" else "Добавить в избранное"
        actionWatched.chipCaption = if (watched) "Снять отметку «просмотрено»" else "Пометить просмотренным"
    }

    private fun toggleFavorite() {
        val position = selectedIndex.coerceIn(0, episodes.lastIndex)
        val episode = episodes[position]
        val added = EpisodeState.toggleFavorite(this, FavoriteEpisode(
            variant.sourceId, variant.source, variant.ref, variant.title, variant.note,
            variant.alt, variant.description, episode.title, position,
        ))
        adapter?.notifyDataSetChanged()
        updateActions()
        android.widget.Toast.makeText(this, if (added) "Серия в избранном" else "Серия убрана из избранного",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun toggleWatched() {
        val position = selectedIndex.coerceIn(0, episodes.lastIndex)
        val episode = episodes[position]
        val key = EpisodeState.key(variant.sourceId, variant.ref, episode.title)
        EpisodeState.setWatched(this, key, !EpisodeState.isWatched(this, key))
        adapter?.notifyDataSetChanged()
        updateActions()
    }

    /** Alternative for a blocked or empty site: the same title in the torrent search. */
    private fun openTorrents() {
        startActivity(android.content.Intent(this, TorrentsActivity::class.java)
            .putExtra("name", variant.title).putExtra("original", variant.alt))
    }

    override fun onResume() {
        super.onResume()
        adapter?.notifyDataSetChanged()
        updateActions()
    }

    private fun play(episodes: List<Episode>, index: Int, choose: Boolean = false) {
        playEpisode(this, variant, episodes, index, choose)
        adapter?.notifyDataSetChanged()
    }
}

