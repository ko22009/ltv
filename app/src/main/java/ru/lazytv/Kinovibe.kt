package ru.lazytv

import org.json.JSONObject

/**
 * Kinovibe: every season of a series is its own page ("Теория большого взрыва 12 Сезон (2018)") whose player is a
 * plain JSON playlist of direct MP4 links. The search results of one series are grouped into one variant; its
 * [Variant.ref] keeps the page paths of all seasons (without the host, so any mirror works), separated by `;`.
 */
object Kinovibe : VideoSource {
    override val id = "kinovibe"
    override val name = "KINOVIBE"

    private val seasonNumber = Regex("(\\d+)\\s*сезон", RegexOption.IGNORE_CASE)

    private data class Page(val path: String, val title: String, val season: Int?, val year: String, val description: String)

    /** A page of a series carries "N сезон" in its name, a film does not: each card gets only its own kind. */
    override fun search(title: String, original: String, year: String, tv: Boolean): List<Variant> =
        search(title, original, year).filter { it.note.contains("сезон") == tv }

    override fun search(title: String, original: String, year: String): List<Variant> {
        val pages = LinkedHashMap<String, Page>()
        for (query in listOf(title, original).filter { it.isNotBlank() }) {
            val answer = runCatching {
                Sources.getAny(id) { base -> Sources.get("$base/?do=search&subaction=search&titleonly=3&story=${Sources.encode(query)}") }
            }.getOrNull() ?: continue
            for (page in parseSearch(answer.value)) pages.putIfAbsent(page.path, page)
        }
        // one variant per series: the seasons of a title are gathered under its name without "N сезон" and the year
        val groups = LinkedHashMap<String, MutableList<Page>>()
        for (page in pages.values) {
            val base = page.title.replace(seasonNumber, "").replace(Regex("\\(\\d{4}\\)"), "")
                .replace(Regex("\\s+"), " ").trim().lowercase()
            groups.getOrPut(base) { ArrayList() }.add(page)
        }
        val variants = groups.values.map { list ->
            // two pages of the same season (a re-upload) would double its episodes: the first one is kept
            val sorted = list.sortedBy { it.season ?: 0 }.distinctBy { it.season ?: it.path }
            val first = sorted.first()
            val shownTitle = first.title.replace(seasonNumber, "").replace(Regex("\\(\\d{4}\\)"), "").replace(Regex("\\s+"), " ").trim()
                .ifEmpty { first.title }
            val note = when {
                sorted.size > 1 -> "${sorted.size} сезонов"
                else -> listOf(first.year, first.season?.let { "$it сезон" }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" • ")
            }
            Variant(name, id, shownTitle, note, sorted.joinToString(";") { it.path }, "", first.description)
        }
        return Sources.relevant(variants, listOf(title, original), year)
    }

    /** Rows of the search page: `<a class="sres-wrap" href="…">…<h2>Title (Year)</h2><div class="sres-desc">…`. */
    private fun parseSearch(html: String): List<Page> =
        Regex("<a class=\"sres-wrap[^\"]*\" href=\"([^\"]+)\">(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).mapNotNull { m ->
            val body = m.groupValues[2]
            val heading = Regex("<h2>(.*?)</h2>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let { htmlText(it) }
                ?: return@mapNotNull null
            val path = "/" + m.groupValues[1].substringAfter("://").substringAfter('/', "")
            // news and articles ("8 сезон - первые новости", "Звёзды сериала…") have no year in brackets and no player
            val year = Regex("\\((\\d{4})\\)").find(heading)?.groupValues?.get(1) ?: return@mapNotNull null
            val description = Regex("sres-desc\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                ?.let { htmlText(it) }.orEmpty()
            Page(path, heading, seasonNumber.find(heading)?.groupValues?.get(1)?.toIntOrNull(), year, description)
        }.toList()

    override fun episodes(variant: Variant): List<Episode> {
        val paths = variant.ref.split(";").filter { it.isNotEmpty() }
        val many = paths.size > 1
        // a few pages at a time: a series with a dozen seasons is a dozen small requests
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val tasks = paths.map { path -> java.util.concurrent.Callable { runCatching { seasonEpisodes(path, many) }.getOrDefault(emptyList()) } }
            val result = pool.invokeAll(tasks, 60, java.util.concurrent.TimeUnit.SECONDS)
                .flatMap { runCatching { it.get() }.getOrDefault(emptyList()) }
            return result
        } finally {
            pool.shutdownNow()
        }
    }

    /** The episodes of one season page; [prefix] adds "Сезон N • " when the variant has several seasons. */
    private fun seasonEpisodes(path: String, prefix: Boolean): List<Episode> {
        val html = Sources.getAny(id) { base -> Sources.get(base + path) }.value
        val season = seasonNumber.find(Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1).orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val file = Regex("Playerjs\\(\\{[^}]*?file:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: return emptyList()
        val label = if (prefix && season != null) "Сезон $season • " else ""
        if (!file.endsWith(".txt")) {
            // a film: the player holds one file
            return listOf(Episode(label + "Фильм", listOf("SD/HD" to file)))
        }
        val text = Sources.get(file).trim().removePrefix("﻿")
        val items = JSONObject(text).getJSONArray("playlist")
        return (0 until items.length()).mapNotNull { i ->
            val item = items.getJSONObject(i)
            val link = item.optString("file")
            if (!link.startsWith("http")) return@mapNotNull null
            val caption = htmlText(item.optString("comment").substringBefore("<br>")).ifEmpty { "Серия ${i + 1}" }
            Episode(label + caption, listOf("SD/HD" to link))
        }
    }
}
