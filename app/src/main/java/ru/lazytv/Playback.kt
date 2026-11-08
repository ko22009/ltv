package ru.lazytv

import android.app.Activity

/** A short caption for the player's episode strip: "1 серия SD/HD AniDub" becomes "1 серия". */
fun shortEpisode(title: String): String {
    val first = title.substringBefore(" • ").trim()
    return first.replace(Regex("\\s*SD/HD.*$", RegexOption.IGNORE_CASE), "").trim().ifEmpty { first }
}

/** The stream of an episode for a quality limit; [exact] (the user's pick) wins when that quality exists. */
fun streamFor(episode: Episode, wanted: String, exact: String? = null): Pair<String, String> {
    exact?.let { name -> episode.streams.firstOrNull { it.first == name }?.let { return it } }
    val limit = wanted.toIntOrNull()
    return if (limit == null) episode.streams.first()
    else episode.streams.firstOrNull { (it.first.removeSuffix("p").toIntOrNull() ?: 0) <= limit } ?: episode.streams.last()
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
    // opening an episode marks it watched even before the player starts; the button can remove the mark
    EpisodeState.setWatched(activity, EpisodeState.key(variant.sourceId, variant.ref, episodes[index].title), true)
    val episode = episodes[index]
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
    episodes.forEach { episode ->
        val stream = streamFor(episode, wanted, exact)
        val streamsJson = org.json.JSONArray()
        episode.streams.forEach { (q, u) -> streamsJson.put(org.json.JSONObject().put("q", q).put("u", u)) }
        queue.put(
            org.json.JSONObject().put("u", stream.second).put("t", "${variant.title} • ${episode.title}")
                .put("m", "${variant.source} • ${stream.first}").put("e", shortEpisode(episode.title))
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
