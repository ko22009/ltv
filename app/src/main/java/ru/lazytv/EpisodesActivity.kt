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
    private var adapter: ArrayAdapter<Int>? = null
    private lateinit var list: ListView
    private lateinit var actions: LinearLayout
    private lateinit var actionWatched: View
    private lateinit var geoButton: View
    private var selectedIndex = 0
    private lateinit var actionVoice: View
    /** The row of season buttons above the list; hidden when the series has fewer than two seasons. */
    private lateinit var backButton: View
    private lateinit var seasonRow: LinearLayout
    private lateinit var seasonScroll: android.widget.HorizontalScrollView
    private var seasons: List<String> = emptyList()
    private var currentSeason: String? = null
    /** Indices (in [episodes]) of the episodes shown now: one season, or all of them when there is no season choice. */
    private var visible: List<Int> = emptyList()
    private var loadEpisodes: (() -> Unit)? = null
    private var voiceSwitched = false
    /** Marking mode: boxes next to the episodes; the choices wait in [pending] until "Применить". */
    private var editMode = false
    private val pending = HashMap<String, Boolean>()
    private lateinit var actionApply: View
    private lateinit var actionCancel: View
    private lateinit var actionAll: View

    /** [normal] colour, white while the row is selected (the focus plate behind it is bright). */
    private fun whiteWhenSelected(normal: Int) = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(Color.WHITE, normal),
    )

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
        // the voice-over chosen earlier for this series
        variant = variant.copy(voice = Settings.string(this, voiceKey(), ""))
        val root = FrameLayout(this)
        root.background = calmBackground()
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(px(64), px(40), px(64), px(20))
        val back = backButton(this, scale) { finish() }
        backButton = back
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(variant.title, 32f, Color.WHITE).apply {
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            // the original name is its own line, not glued to the description
            if (variant.alt.isNotEmpty() && !variant.alt.equals(variant.title, true)) {
                addView(label(variant.alt, 21f, 0xFF94A3B8.toInt()).apply {
                    setPadding(0, px(2), 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(px(56), px(56)).apply { rightMargin = px(20) })
            addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        }
        page.addView(header)
        page.addView(label("Удерживайте OK, чтобы отметить серию просмотренной или снять отметку", 14f, 0xFF94A3B8.toInt()).apply {
            setPadding(0, px(12), 0, px(12))
        })
        val status = label("", 15f, 0xFFCBD5E1.toInt())
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
        actionWatched = actionButton().apply { setOnClickListener { startEdit() } }
        actionApply = actionButton().apply { chipCaption = "Применить"; visibility = View.GONE; setOnClickListener { applyEdit() } }
        actionAll = actionButton().apply { chipCaption = "Весь сезон"; visibility = View.GONE; setOnClickListener { markAllShown() } }
        actionCancel = actionButton().apply { chipCaption = "Отмена"; visibility = View.GONE; setOnClickListener { endEdit() } }
        // shown only when the site offers several voice-overs (see load() below)
        actionVoice = actionButton().apply { visibility = View.GONE }
        actions.addView(actionVoice, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = px(12) })
        actions.addView(actionWatched, LinearLayout.LayoutParams(-1, -1).apply { width = -2; rightMargin = px(12) })
        actions.addView(actionApply, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = px(12) })
        actions.addView(actionAll, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = px(12) })
        actions.addView(actionCancel, LinearLayout.LayoutParams(-2, -1))
        seasonRow = LinearLayout(this)
        seasonScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(seasonRow)
        }
        page.addView(seasonScroll, LinearLayout.LayoutParams(-1, px(56)).apply { topMargin = px(6) })
        page.addView(actions, LinearLayout.LayoutParams(-1, px(56)).apply { topMargin = px(6) })
        list = ListView(this)
        list.divider = null
        list.selector = ColorDrawable(Color.TRANSPARENT)
        back.setOnKeyListener { _, code, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) false
            else when (code) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> when {
                    seasonScroll.visibility == View.VISIBLE -> activeSeasonChip().requestFocus()
                    actions.visibility == View.VISIBLE -> firstAction().requestFocus()
                    errorRow.visibility == View.VISIBLE -> geoButton.requestFocus()
                    episodes.isNotEmpty() -> list.requestFocus()
                    else -> true
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_DPAD_UP -> true
                else -> false
            }
        }
        list.setOnKeyListener { _, code, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && code == android.view.KeyEvent.KEYCODE_DPAD_UP && list.selectedItemPosition <= 0) {
                if (actions.visibility == View.VISIBLE) firstAction().requestFocus() else back.requestFocus()
                true
            } else false
        }
        fun bindRowKeys(container: LinearLayout, onDownToList: Boolean) {
            for (i in 0 until container.childCount) container.getChildAt(i).setOnKeyListener { view, code, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) false
                else if (code == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                    if (container === actions && seasonScroll.visibility == View.VISIBLE) activeSeasonChip().requestFocus()
                    else back.requestFocus()
                    true
                } else if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    val visible = (0 until container.childCount).map { container.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
                    val step = if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    val next = visible.getOrNull(visible.indexOf(view) + step)
                    if (next != null) next.requestFocus()
                    next != null || step > 0
                } else if (onDownToList && code == android.view.KeyEvent.KEYCODE_DPAD_DOWN && episodes.isNotEmpty()) {
                    list.requestFocus()
                    true
                } else false
            }
        }
        bindRowKeys(errorRow, onDownToList = false)
        bindRowKeys(actions, onDownToList = true)
        val loading = LoadingView(this, scale, "Загружаем список серий…")
        val body = FrameLayout(this)
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        body.addView(loading, FrameLayout.LayoutParams(-1, -1))
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        back.requestFocus()

        // loads the episode list of the variant with the chosen voice-over; also called again after another voice-over is picked
        fun load() {
        loading.visibility = View.VISIBLE
        status.visibility = View.GONE
        errorRow.visibility = View.GONE
        Thread {
            val result = runCatching {
                Sources.episodesCached(variant)
            }
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
                    list.setOnItemClickListener { _, _, position, _ ->
                        visible.getOrNull(position)?.let { if (editMode) togglePending(it) else play(episodes, it) }
                    }
                    // long OK: the "watched" mark of that episode on or off (the quality can still be asked for in the settings)
                    list.setOnItemLongClickListener { _, _, position, _ ->
                        visible.getOrNull(position)?.let {
                            selectedIndex = it
                            if (editMode) togglePending(it) else toggleWatched()
                        }
                        true
                    }
                    list.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                            visible.getOrNull(position)?.let { selectedIndex = it }
                            updateActions()
                        }
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    })
                    val focus = if (voiceSwitched) -1 else intent.getIntExtra("episodeIndex", -1)
                    // opened by hand: the first episode not yet watched (the first one when all are), so OK continues the series
                    selectedIndex = if (focus in episodes.indices) focus else episodes.indexOfFirst {
                        !EpisodeState.isWatched(this, EpisodeState.key(variant.sourceId, variant.ref, it.title))
                    }.coerceAtLeast(0)
                    actions.visibility = View.VISIBLE
                    setupSeasons()
                    showSeason(seasonOf(episodes[selectedIndex]), selectedIndex)
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
        loadEpisodes = ::load
        load()
        // the voice-overs are asked for in the background: the button appears when the site has a choice
        Thread {
            val voices = runCatching { Sources.all.firstOrNull { it.id == variant.sourceId }?.voices(variant) }.getOrNull().orEmpty()
            onUi { setVoices(voices) }
        }.start()
    }

    /** The season of an episode is the part of its title before " • " when that part names a season ("Сезон 2 • Серия 5"). */
    private fun seasonOf(episode: Episode): String? =
        episode.title.substringBefore(" • ", "").takeIf { it.contains("сезон", ignoreCase = true) }

    /** Season chips: one per season, the active one is lit; picking a chip shows only the episodes of that season. */
    private fun setupSeasons() {
        val names = LinkedHashSet<String>()
        episodes.forEach { seasonOf(it)?.let(names::add) }
        seasons = if (names.size >= 2) names.toList() else emptyList()
        seasonRow.removeAllViews()
        seasonScroll.visibility = if (seasons.isEmpty()) View.GONE else View.VISIBLE
        seasons.forEach { name ->
            val chip = actionButton().apply {
                chipCaption = name
                tag = name
                setOnClickListener { showSeason(name, -1); list.requestFocus() }
                setOnKeyListener { view, code, event ->
                    if (event.action != android.view.KeyEvent.ACTION_DOWN) false
                    else when (code) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val step = if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                            val next = seasonRow.getChildAt(seasonRow.indexOfChild(view) + step)
                            next?.requestFocus()
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> { back().requestFocus(); true }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { if (actions.visibility == View.VISIBLE) firstAction().requestFocus() else list.requestFocus(); true }
                        else -> false
                    }
                }
            }
            seasonRow.addView(chip, LinearLayout.LayoutParams(-2, px(CHIP_HEIGHT)).apply { rightMargin = px(10) })
        }
    }

    private fun back(): View = backButton
    private fun activeSeasonChip(): View =
        (0 until seasonRow.childCount).map { seasonRow.getChildAt(it) }.firstOrNull { it.tag == currentSeason }
            ?: seasonRow.getChildAt(0)

    /** Shows the episodes of [season] (all of them when the series has no seasons); [select] is the episode to focus, -1 for the first unwatched one. */
    private fun showSeason(season: String?, select: Int) {
        currentSeason = if (seasons.isEmpty()) null else season ?: seasons.first()
        visible = episodes.indices.filter { currentSeason == null || seasonOf(episodes[it]) == currentSeason }
        for (i in 0 until seasonRow.childCount) {
            val chip = seasonRow.getChildAt(i)
            chip.background = focusShape(scale, if (chip.tag == currentSeason) ACTIVE_FILL else 0xFF1C2029.toInt(), radius = 6f)
        }
        adapter = object : ArrayAdapter<Int>(this, 0, visible) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup) = row(getItem(position)!!, episodes[getItem(position)!!])
        }
        list.adapter = adapter
        val global = if (select in visible) select else visible.firstOrNull {
            !EpisodeState.isWatched(this, EpisodeState.key(variant.sourceId, variant.ref, episodes[it].title))
        } ?: visible.firstOrNull() ?: 0
        selectedIndex = global
        list.setSelection(visible.indexOf(global).coerceAtLeast(0))
        updateActions()
    }

    private fun voiceKey() = "voice_${variant.sourceId}_${variant.ref}"

    private fun setVoices(voices: List<Pair<String, String>>) {
        if (voices.isEmpty()) return
        fun caption() = "Озвучка: " + (voices.firstOrNull { it.first == variant.voice }?.second ?: voices.first().second)
        actionVoice.chipCaption = caption()
        actionVoice.visibility = View.VISIBLE
        actionVoice.setOnClickListener {
            val chosen = voices.indexOfFirst { it.first == variant.voice }.let { if (it < 0) 0 else it }
            showChoice(this, "Озвучка", voices.map { it.second }, chosen) { which ->
                val key = voices[which].first
                if (key == variant.voice) return@showChoice
                variant = variant.copy(voice = key)
                Settings.setString(this, voiceKey(), key)
                voiceSwitched = true
                actionVoice.chipCaption = caption()
                loadEpisodes?.invoke()
            }
        }
    }

    private fun row(position: Int, episode: Episode): View {
        // [position] is the index in the whole episode list (favourites remember it), not the place in the shown season
        val key = EpisodeState.key(variant.sourceId, variant.ref, episode.title)
        val watched = if (editMode) pending[key] ?: EpisodeState.isWatched(this, key) else EpisodeState.isWatched(this, key)
        val card = LinearLayout(this)
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(px(20), px(14), px(20), px(14))
        card.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply { setColor(0xFF6366F1.toInt()); cornerRadius = px(3).toFloat() })
            addState(intArrayOf(), GradientDrawable().apply {
                // a watched episode is visibly dimmer than an unseen one
                setColor(if (watched) 0x16000000 else 0x33000000)
                cornerRadius = px(3).toFloat()
            })
        }
        if (editMode) card.addView(label(if (watched) "☑" else "☐", 24f, 0)
            .apply { setPadding(0, 0, px(14), 0); setTextColor(whiteWhenSelected(if (watched) 0xFFA5B4FC.toInt() else 0xFF94A3B8.toInt())) })
        // the season is already chosen with the season buttons: the row names only the episode
        val shown = if (currentSeason != null) episode.title.substringAfter(" • ", episode.title) else episode.title
        // on the bright plate of the selected row every text is white: the dimmed colour of a watched episode was unreadable there
        card.addView(label(shown, 19f, 0, true).apply { setTextColor(whiteWhenSelected(if (watched) 0xFF64748B.toInt() else Color.WHITE)) },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (watched) card.addView(label("✓", 20f, 0).apply { setPadding(px(4), 0, px(6), 0); setTextColor(whiteWhenSelected(0xFFA5B4FC.toInt())) })
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
        actionWatched.chipCaption = "Отметить просмотренные"
    }

    /** The first button of the action row that is shown now (the series as a whole is bookmarked on its card, not here). */
    private fun firstAction(): View =
        (0 until actions.childCount).map { actions.getChildAt(it) }.first { it.visibility == View.VISIBLE }

    private fun keyOf(index: Int) = EpisodeState.key(variant.sourceId, variant.ref, episodes[index].title)

    private fun watchedNow(index: Int) = pending[keyOf(index)] ?: EpisodeState.isWatched(this, keyOf(index))

    private fun togglePending(index: Int) {
        pending[keyOf(index)] = !watchedNow(index)
        adapter?.notifyDataSetChanged()
    }

    /** Switches the mode on: the boxes show the current marks, the usual buttons give way to "Применить" / "Весь сезон" / "Отмена". */
    private fun startEdit() {
        editMode = true
        pending.clear()
        actionWatched.visibility = View.GONE
        actionApply.visibility = View.VISIBLE
        actionAll.visibility = View.VISIBLE
        actionCancel.visibility = View.VISIBLE
        adapter?.notifyDataSetChanged()
        list.requestFocus()
    }

    private fun endEdit() {
        editMode = false
        pending.clear()
        actionApply.visibility = View.GONE
        actionAll.visibility = View.GONE
        actionCancel.visibility = View.GONE
        actionWatched.visibility = View.VISIBLE
        adapter?.notifyDataSetChanged()
        updateActions()
        list.requestFocus()
    }

    /** Writes the marks chosen in the mode; only the episodes whose box differs from the saved mark are touched. */
    private fun applyEdit() {
        var changed = 0
        for (index in episodes.indices) {
            val key = keyOf(index)
            val want = pending[key] ?: continue
            if (want != EpisodeState.isWatched(this, key)) {
                EpisodeState.setWatched(this, key, want)
                changed++
            }
        }
        endEdit()
        android.widget.Toast.makeText(this, if (changed == 0) "Без изменений" else "Сохранено: $changed", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** "Весь сезон": every shown episode gets a mark, or loses it when all of them already have one. */
    private fun markAllShown() {
        val all = visible.all { watchedNow(it) }
        visible.forEach { pending[keyOf(it)] = !all }
        adapter?.notifyDataSetChanged()
    }

    override fun onBackPressed() {
        if (editMode) endEdit() else super.onBackPressed()
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
        val mediaTitle = intent.getStringExtra("mediaTitle").orEmpty().ifEmpty { variant.title }
        val originalTitle = intent.getStringExtra("originalTitle").orEmpty().ifEmpty { variant.alt }
        val year = intent.getStringExtra("year").orEmpty()
        val tv = intent.getBooleanExtra("tv", true)
        val poster = intent.getStringExtra("poster")
        playEpisode(this, variant, episodes, index, choose, mediaTitle, originalTitle, year, tv, poster, intent.getLongExtra("tmdbId", 0L))
        adapter?.notifyDataSetChanged()
    }
}

