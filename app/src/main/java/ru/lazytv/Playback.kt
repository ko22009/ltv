package ru.lazytv

import android.app.Activity
import android.content.Intent

/** A short caption for the player's episode strip: "1 серия SD/HD AniDub" becomes "1 серия". */
fun shortEpisode(title: String): String {
    fun clean(s: String) = s.replace(Regex("\\s*SD/HD.*$", RegexOption.IGNORE_CASE), "").trim()
    val parts = title.split(" • ")
    val first = parts[0].trim()
    // "1 сезон • 3 серия": the strip shows the episode only, so the buttons stay short (the player title keeps the season)
    if (first.contains("сезон", ignoreCase = true) && parts.size > 1) {
        val episode = clean(parts[1])
        if (episode.isNotEmpty()) return episode
    }
    return clean(first).ifEmpty { first }
}

/**
 * Captions of the player strip for a whole queue: "N сезон, M серия" when the series has several seasons (numbering
 * starts over in each), the episode alone when it has one.
 */
fun stripLabels(episodes: List<Episode>): List<String> {
    fun season(e: Episode) = e.title.substringBefore(" • ", "").takeIf { it.contains("сезон", ignoreCase = true) }
    val several = episodes.mapNotNull(::season).toSet().size >= 2
    return episodes.map { e ->
        val short = shortEpisode(e.title)
        val s = season(e)
        if (several && s != null) "$s, $short" else short
    }
}

/** The stream of an episode for a quality limit; [exact] (the user's pick) wins when that quality exists. */
fun streamFor(episode: Episode, wanted: String, exact: String? = null): Pair<String, String> =
    pickStream(episode.streams, wanted, exact)

/** Same for a bare list of streams; an episode whose links are not asked for yet gives an empty pair. */
fun pickStream(streams: List<Pair<String, String>>, wanted: String, exact: String? = null): Pair<String, String> {
    if (streams.isEmpty()) return "" to ""
    exact?.let { name -> streams.firstOrNull { it.first == name }?.let { return it } }
    val limit = wanted.toIntOrNull()
    return if (limit == null) streams.first()
    else streams.firstOrNull { (it.first.removeSuffix("p").toIntOrNull() ?: 0) <= limit } ?: streams.last()
}

/**
 * Plays one episode of a video-service release, with the whole list as the player's queue. The quality from the
 * settings is used without asking; "ask" (or [choose], the long press) shows the picker first. Shared by the
 * episode page and by the film card, which opens a one-video release at once.
 */
fun playEpisode(
    activity: Activity, variant: Variant, episodes: List<Episode>, index: Int, choose: Boolean = false,
    mediaTitle: String = "", originalTitle: String = "", year: String = "", tv: Boolean = false, poster: String? = null,
    tmdbId: Long = 0L,
) {
    val episode = episodes[index]
    if (!episode.isResolved) {
        // the link of this episode is asked for first (a network call, so off the main thread), then the same call goes on
        android.widget.Toast.makeText(activity, "Загрузка серии…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val found = runCatching { episode.resolve() }.getOrDefault(emptyList())
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (found.isEmpty()) android.widget.Toast.makeText(activity, "Не удалось получить ссылку на серию", android.widget.Toast.LENGTH_LONG).show()
                else playEpisode(activity, variant, episodes, index, choose, mediaTitle, originalTitle, year, tv, poster, tmdbId)
            }
        }.start()
        return
    }
    // opening an episode marks it watched even before the player starts; the button can remove the mark
    EpisodeState.setWatched(activity, EpisodeState.key(variant.sourceId, variant.ref, episode.title), true)
    val wanted = Settings.string(activity, Settings.QUALITY, "best")
    if ((choose || wanted == "ask") && episode.streams.size > 1) {
        showChoice(activity, "Качество • ${episode.title}", episode.streams.map { it.first }, -1) { which ->
            startPlayer(activity, variant, episodes, index, episode.streams[which].first, mediaTitle, originalTitle, year, tv, poster, tmdbId)
        }
        return
    }
    startPlayer(activity, variant, episodes, index, null, mediaTitle, originalTitle, year, tv, poster, tmdbId)
}

private fun startPlayer(
    activity: Activity, variant: Variant, episodes: List<Episode>, index: Int, exact: String?,
    mediaTitle: String = "", originalTitle: String = "", year: String = "", tv: Boolean = false, poster: String? = null,
    tmdbId: Long = 0L,
) {
    val wanted = Settings.string(activity, Settings.QUALITY, "best")
    val queue = org.json.JSONArray()
    val labels = stripLabels(episodes)
    episodes.forEachIndexed { position, episode ->
        LazyLinks.register(EpisodeState.key(variant.sourceId, variant.ref, episode.title), episode)
        val stream = streamFor(episode, wanted, exact)
        val streamsJson = org.json.JSONArray()
        episode.streams.forEach { (q, u) -> streamsJson.put(org.json.JSONObject().put("q", q).put("u", u)) }
        queue.put(
            org.json.JSONObject().put("u", stream.second).put("t", "${variant.title} • ${episode.title}")
                .put("m", "${variant.source} • ${stream.first}").put("e", labels[position])
                .put("k", EpisodeState.key(variant.sourceId, variant.ref, episode.title))
                .put("s", streamsJson)
        )
    }
    val current = streamFor(episodes[index], wanted, exact)
    val titleForMedia = mediaTitle.ifEmpty { variant.title }
    val titleForOriginal = originalTitle.ifEmpty { variant.alt }
    openPlayer(
        activity, current.second, "${variant.title} • ${episodes[index].title}", "${variant.source} • ${current.first}",
        queue.toString(), index, titleForMedia, titleForOriginal, year, tv, poster, tmdbId,
    )
}

/**
 * The other sources of the film the card has opened: when the stream of one does not start (a silent site, a white list),
 * the player moves on to the next one by itself. Only for the card's own start (matched by the TMDB id), not for
 * "continue watching" or other starts.
 */
object PlaybackFallback {
    private var tmdbId = 0L
    private var queue = ArrayDeque<Variant>()
    private var mediaTitle = ""
    private var originalTitle = ""
    private var year = ""
    private var poster: String? = null
    private var busy = false

    /** A series continued from the card: if its stream does not start, the list of episodes opens instead of the error window. */
    private var seriesList: Intent? = null
    private var seriesId = 0L

    fun armSeries(id: Long, list: Intent) {
        seriesId = id
        seriesList = list
    }

    fun arm(id: Long, rest: List<Variant>, mediaTitle: String, originalTitle: String, year: String, poster: String?) {
        seriesList = null
        tmdbId = id
        queue = ArrayDeque(rest)
        this.mediaTitle = mediaTitle; this.originalTitle = originalTitle; this.year = year; this.poster = poster
    }

    fun available(id: Long) = id > 0L && id == tmdbId && queue.isNotEmpty()

    /** Starts the next source from [activity] (the player that failed) and closes it; false when nothing is left. */
    fun next(activity: Activity, id: Long): Boolean {
        if (busy) return true
        seriesList?.takeIf { id > 0L && id == seriesId }?.let { list ->
            seriesList = null
            android.widget.Toast.makeText(activity, "Серия не запустилась, открыт список серий", android.widget.Toast.LENGTH_LONG).show()
            activity.startActivity(list)
            activity.finish()
            return true
        }
        if (!available(id)) return false
        busy = true
        val variant = queue.removeFirst()
        android.widget.Toast.makeText(activity, "Источник не открылся, пробуем ${variant.source}", android.widget.Toast.LENGTH_LONG).show()
        Thread {
            val answer = java.util.concurrent.atomic.AtomicReference<List<Episode>?>(null)
            val asking = Thread { answer.set(runCatching { Sources.episodesCached(variant) }.getOrNull()) }
            asking.start()
            asking.join(6000)
            val episodes = answer.get()
            activity.runOnUiThread {
                busy = false
                if (activity.isFinishing) return@runOnUiThread
                if (episodes.isNullOrEmpty()) {
                    if (!next(activity, id)) android.widget.Toast.makeText(activity, "Другие источники тоже не открылись", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    playEpisode(activity, variant, episodes, 0, choose = false, mediaTitle = mediaTitle, originalTitle = originalTitle,
                        year = year, tv = false, poster = poster, tmdbId = id)
                    activity.finish()
                }
            }
        }.start()
        return true
    }
}
