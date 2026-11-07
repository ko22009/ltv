package ru.lazytv

import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Zona (zona.plus) through the open site: the search page lists titles, a title page carries the episodes (or one
 * player id for a film), and `/ajax/video/<id>` answers with a direct MP4 address. No login, no bot check.
 * A series page shows one season: every season link of the page is followed.
 */
object Zona : VideoSource {
    override val id = "zona"
    override val name = "ZONA"

    override fun search(title: String, original: String, year: String): List<Variant> {
        val seen = HashSet<String>()
        val found = ArrayList<Variant>()
        for (query in listOf(title, original).filter { it.isNotBlank() }) {
            val answer = runCatching { Sources.getAny(id) { base -> "$base/search/${Sources.encode(query)}" } }.getOrNull() ?: continue
            val item = Regex("<a class=\"results-item\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            for (m in item.findAll(answer.value)) {
                val href = m.groupValues[1]
                val body = m.groupValues[2]
                val name = Regex("results-item-title[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(body)
                    ?.let { htmlText(it.groupValues[1]) }.orEmpty()
                if (name.isEmpty()) continue
                val itemYear = Regex("results-item-year[^>]*>(.*?)</span>", RegexOption.DOT_MATCHES_ALL).find(body)
                    ?.let { htmlText(it.groupValues[1]) }.orEmpty()
                val kind = if (href.startsWith("/tvseries/")) "Сериал" else "Фильм"
                val ref = if (href.startsWith("http")) href else answer.base + href
                if (seen.add(ref)) found += Variant(this.name, id, name, listOf(itemYear, kind).filter { it.isNotEmpty() }.joinToString(" • "), ref)
            }
        }
        return Sources.relevant(found, listOf(title, original), year)
    }

    override fun episodes(variant: Variant): List<Episode> {
        val base = Regex("^(https?://[^/]+)").find(variant.ref)?.groupValues?.get(1) ?: RemoteConfig.base(id)
        val first = Sources.get(variant.ref)
        if (!first.contains("entity-episode-link")) {
            val player = Regex("class=\"entity-player\"[^>]*data-id=\"(\\d+)\"").find(first)?.groupValues?.get(1)
                ?: return emptyList()
            val streams = streams(base, player)
            return if (streams.isEmpty()) emptyList() else listOf(Episode("Фильм", streams))
        }
        val seasons = Regex("<a class=\"entity-season[^\"]*\"[^>]*href=\"([^\"]+)\"").findAll(first)
            .map { it.groupValues[1] }.distinct().toList()
        // the page itself is season 1 and has no season links only for a one-season series
        val pages = if (seasons.isEmpty()) listOf(first to 1) else seasons.mapIndexed { i, path ->
            val url = if (path.startsWith("http")) path else base + path
            (if (i == 0) first else runCatching { Sources.get(url) }.getOrNull().orEmpty()) to (i + 1)
        }
        // an episode needs its own request for the address: ask for them in parallel, the order is kept
        val rows = ArrayList<Triple<String, String, String>>()
        for ((html, season) in pages) {
            val prefix = if (pages.size > 1) "Сезон $season • " else ""
            val link = Regex("<span class=\"entity-episode-link\"[^>]*data-episode=\"(\\d+)\"[^>]*data-id=\"(\\d+)\"[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
            for (m in link.findAll(html)) {
                val caption = Regex("entity-episode-name\">(.*?)</span>", RegexOption.DOT_MATCHES_ALL).find(m.groupValues[3])
                    ?.let { htmlText(it.groupValues[1]) }.orEmpty().ifEmpty { "Серия ${m.groupValues[1]}" }
                rows += Triple(prefix + caption, m.groupValues[2], "")
            }
        }
        val pool = Executors.newFixedThreadPool(8)
        return try {
            pool.invokeAll(rows.map { row -> Callable { runCatching { streams(base, row.second) }.getOrDefault(emptyList()) } })
                .mapIndexedNotNull { i, future ->
                    val streams = runCatching { future.get() }.getOrDefault(emptyList())
                    if (streams.isEmpty()) null else Episode(rows[i].first, streams)
                }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Streams of one player id, best first; only the direct MP4 is used (the HLS addresses of the site often 404). */
    private fun streams(base: String, playerId: String): List<Pair<String, String>> {
        val json = JSONObject(Sources.get("$base/ajax/video/$playerId"))
        val url = json.optString("url")
        return if (url.startsWith("http")) listOf("MP4" to url) else emptyList()
    }
}
