package ru.lazytv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * HDRezka: search by name, then the site's own AJAX (the same calls its web player makes) for voice-overs, the
 * season/episode list and the direct MP4 links per quality. The address list has several mirrors (see [RemoteConfig]):
 * the site moves often, and a dead mirror just makes the next one be tried.
 *
 * The site sometimes answers a page with a "you are not a bot" check (Anubis). That check is not passed here:
 * the source reports it and the user can take the torrents instead.
 */
object Hdrezka : VideoSource {
    override val id = "hdrezka"
    override val name = "HDREZKA"

    private const val CHECK_MESSAGE = "HDRezka просит проверку «вы не бот», из приложения она не проходится. Попробуйте другой источник или торренты"

    /** A page with the bot check instead of the site (or an empty answer): reported, never worked around. */
    private fun requireSite(html: String): String {
        if (html.contains("anubis_challenge") || html.contains("Проверяем, что вы не бот")) throw IllegalStateException(CHECK_MESSAGE)
        return html
    }

    /** The kind comes with each result (the address says "series" or "films"): the other kind is dropped. */
    override fun search(title: String, original: String, year: String, tv: Boolean): List<Variant> =
        search(title, original, year).filter { v ->
            if (tv) !v.note.endsWith("Фильм") else !v.note.endsWith("Сериал")
        }

    override fun search(title: String, original: String, year: String): List<Variant> {
        val seen = HashSet<String>()
        val found = ArrayList<Variant>()
        for (query in listOf(title, original).filter { it.isNotBlank() }) {
            val answer = runCatching {
                Sources.getAny(id) { base -> requireSite(Sources.get("$base/search/?do=search&subaction=search&q=${Sources.encode(query)}", quick = true)) }
            }.getOrNull() ?: continue
            for (variant in parseSearch(answer.value)) if (seen.add(variant.ref)) found += variant
        }
        return Sources.relevant(found, listOf(title, original), year)
    }

    /** Rows of the search page: `data-url`, the title link and the line "2007-2019, США, Комедии". */
    private fun parseSearch(html: String): List<Variant> =
        html.split("b-content__inline_item\"").drop(1).mapNotNull { chunk ->
            val url = Regex("data-url=\"([^\"]+)\"").find(chunk)?.groupValues?.get(1) ?: return@mapNotNull null
            val link = Regex("b-content__inline_item-link\">\\s*<a href=\"[^\"]*\">(.*?)</a>\\s*<div>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
                .find(chunk) ?: return@mapNotNull null
            val title = htmlText(link.groupValues[1])
            val info = htmlText(link.groupValues[2])
            // a title may be shown as "Название / Original" on some mirrors
            val years = Regex("\\d{4}").find(info)?.value.orEmpty()
            val kind = when {
                url.contains("/series/") -> "Сериал"
                url.contains("/cartoons/") || url.contains("/animation/") -> "Мультфильм"
                url.contains("/films/") -> "Фильм"
                else -> ""
            }
            val note = listOf(years, kind).filter { it.isNotEmpty() }.joinToString(" • ")
            // only the path is kept: it works on every mirror
            val path = url.substringAfter("://").substringAfter('/', "").let { "/$it" }
            Variant(name, id, title.substringBefore(" / "), note, path, title.substringAfter(" / ", ""), info)
        }

    override fun voices(variant: Variant): List<Pair<String, String>> {
        val html = page(variant)
        val found = Regex("<li[^>]*data-translator_id=\"(\\d+)\"[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { it.groupValues[1] to htmlText(it.groupValues[2]) }
            .filter { it.second.isNotEmpty() }.distinctBy { it.first }.toList()
        return if (found.size > 1) found else emptyList()
    }

    private fun page(variant: Variant): String =
        Sources.getAny(id) { base -> requireSite(Sources.get(base + variant.ref)) }.value

    override fun episodes(variant: Variant): List<Episode> {
        val fetched = RemoteConfig.attempt(id) { base -> requireSite(Sources.get(base + variant.ref)) to base }
        val html = fetched.value.first
        val base = fetched.value.second
        val filmId = Regex("/(\\d+)-[^/]*\\.html").find(variant.ref)?.groupValues?.get(1) ?: error("Не найден номер фильма на странице")
        val init = Regex("initCDN(Series|Movies)Events\\(\\s*(\\d+)\\s*,\\s*(\\d+)").find(html)
        val isSeries = (init?.groupValues?.get(1) ?: if (variant.ref.contains("/series/")) "Series" else "Movies") == "Series"
        val translator = variant.voice.ifEmpty { init?.groupValues?.get(3).orEmpty() }.ifEmpty { "0" }

        if (!isSeries) {
            val streams = stream(base, variant.ref, filmId, translator, "get_movie", null, null)
            return if (streams.isEmpty()) emptyList() else listOf(Episode("Фильм", streams))
        }

        // the list of seasons and episodes for the chosen voice-over
        val list = JSONObject(post(base, variant.ref, "get_episodes", mapOf("id" to filmId, "translator_id" to translator)))
        val markup = list.optString("episodes").ifEmpty { html }
        val pairs = Regex("data-season_id=\"(\\d+)\"[^>]*data-episode_id=\"(\\d+)\"").findAll(markup)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.distinct().toList()
            .sortedWith(compareBy({ it.first }, { it.second }))
        if (pairs.isEmpty()) return emptyList()

        // one request per episode (the site gives links only one by one); a few at a time so the site is not hammered
        // упрощено: for a series of hundreds of episodes the first open takes longer; lazy links per episode when it grows
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            val tasks = pairs.map { (season, episode) ->
                java.util.concurrent.Callable {
                    val streams = runCatching {
                        stream(base, variant.ref, filmId, translator, "get_stream", season, episode)
                    }.getOrDefault(emptyList())
                    if (streams.isEmpty()) null else Episode("Сезон $season • Серия $episode", streams)
                }
            }
            return pool.invokeAll(tasks, 90, java.util.concurrent.TimeUnit.SECONDS)
                .mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun stream(base: String, ref: String, filmId: String, translator: String, action: String, season: Int?, episode: Int?): List<Pair<String, String>> {
        val fields = linkedMapOf("id" to filmId, "translator_id" to translator, "action" to action)
        if (season != null && episode != null) {
            fields["season"] = season.toString()
            fields["episode"] = episode.toString()
        }
        val json = JSONObject(post(base, ref, if (season == null) "get_movie" else "get_stream", fields))
        if (!json.optBoolean("success", true)) return emptyList()
        val raw = json.optString("url")
        if (raw.isEmpty() || raw == "false") return emptyList()
        return parseStreams(decode(raw))
    }

    /** `[360p]a.mp4 or b.m3u8,[720p]c.mp4 or d.m3u8` -> best quality first, a plain MP4 preferred over the playlist. */
    private fun parseStreams(text: String): List<Pair<String, String>> =
        Regex("\\[([^\\]]+)]([^,\\[]+)").findAll(text).mapNotNull { m ->
            val urls = m.groupValues[2].split(" or ").map { it.trim() }.filter { it.startsWith("http") }
            val url = urls.firstOrNull { it.contains(".mp4") && !it.contains(".m3u8") } ?: urls.firstOrNull() ?: return@mapNotNull null
            m.groupValues[1].trim() to url
        }.distinctBy { it.first }.sortedByDescending { height(it.first) }.toList()

    private fun height(label: String): Int = when {
        label.startsWith("4K") -> 2160
        label.startsWith("2K") -> 1440
        else -> Regex("\\d+").find(label)?.value?.toIntOrNull() ?: 0
    }

    /** The site hides links as `#h` + Base64 with junk groups (`//_//` + Base64 of a few of @#!^$) in between. */
    private fun decode(raw: String): String {
        if (!raw.startsWith("#h")) return raw
        var text = raw.substring(2)
        val marks = "@#!^$"
        val junk = ArrayList<String>()
        for (a in marks) for (b in marks) {
            junk += "$a$b"
            for (c in marks) junk += "$a$b$c"
        }
        // longest groups first, so a three-mark group is not left half-cut by a two-mark one
        junk.sortedByDescending { it.length }.forEach { j ->
            val encoded = android.util.Base64.encodeToString(j.toByteArray(), android.util.Base64.NO_WRAP)
            text = text.replace("//_//$encoded", "")
        }
        text = text.replace("//_//", "")
        return String(android.util.Base64.decode(text, android.util.Base64.DEFAULT))
    }

    private fun post(base: String, ref: String, kind: String, fields: Map<String, String>): String {
        val c = URL("$base/ajax/get_cdn_series/?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Safari/537.36")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        c.setRequestProperty("Referer", base + ref)
        val body = (fields + ("action" to (fields["action"] ?: kind))).entries
            .joinToString("&") { "${Sources.encode(it.key)}=${Sources.encode(it.value)}" }
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val text = c.inputStream.use {
            ClockCheck.observe(c.date)
            it.readBytes().toString(Charsets.UTF_8)
        }
        return requireSite(text)
    }
}
