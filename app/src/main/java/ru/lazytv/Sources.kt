package ru.lazytv

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A title found on a video site: what the "ВИДЕО" row of the card shows. */
data class Variant(
    val source: String, val sourceId: String, val title: String, val note: String, val ref: String,
    /** The original (usually English) title: used for matching and shown as a second title line. */
    val alt: String = "",
    /** The site's own description, when it has one; shown apart from the titles. */
    val description: String = "",
    /** The chosen voice-over (translation) of a site that has several; empty means the site's default one. */
    val voice: String = "",
)

/** One playable item of a variant (an episode or the whole film) with streams per quality, best first. */
data class Episode(val title: String, val streams: List<Pair<String, String>>)

/** A video site. Each source answers a title search and lists the playable items of a found variant. */
interface VideoSource {
    val id: String
    val name: String
    fun search(title: String, original: String, year: String): List<Variant>
    fun episodes(variant: Variant): List<Episode>
    /** Voice-overs the site offers for the variant as (key, name); empty when there is no choice. */
    fun voices(variant: Variant): List<Pair<String, String>> = emptyList()
    /** False for a source that has no usable episode list (a series there is one long video). */
    val forSeries: Boolean get() = true
    /** False for a source that only has series: a film card must not offer it. */
    val forFilms: Boolean get() = true
    /** [search] knowing whether a series or a film is wanted; a source that mixes both drops the other kind. */
    fun search(title: String, original: String, year: String, tv: Boolean): List<Variant> = search(title, original, year)
}

object Sources {
    val all: List<VideoSource> = listOf(Anilibria, Seasonvar, Hdrezka, Kinovibe, Rutube)

    fun byId(id: String): VideoSource? = all.firstOrNull { it.id == id }

    /** Sources the user left switched on in the settings (a change applies at once). */
    fun enabled(context: Context, tv: Boolean = false) =
        all.filter { Settings.bool(context, Settings.service(it.id)) && (if (tv) it.forSeries else it.forFilms) }

    /** Searches every enabled source at once; a source that fails or finds nothing simply adds no tiles. */
    fun search(context: Context, title: String, original: String, year: String, tv: Boolean = false): List<Variant> {
        val list = enabled(context, tv)
        if (list.isEmpty()) return emptyList()
        val pool = java.util.concurrent.Executors.newCachedThreadPool()
        return try {
            val tasks = list.map { source ->
                java.util.concurrent.Callable { runCatching { source.search(title, original, year, tv) }.getOrDefault(emptyList()) }
            }
            // one slow site must not hold the card: whatever did not answer in 20 seconds is skipped
            val combined = pool.invokeAll(tasks, 20, java.util.concurrent.TimeUnit.SECONDS)
                .flatMap { future -> runCatching { future.get() }.getOrDefault(emptyList()) }
            // each source ranks its own answers; the combined list has to be ranked across sources again
            val ranked = relevant(combined, listOf(title, original), year)
            // a film of the wanted year comes first, then the ones whose year is not known, then the other parts and remakes:
            // nothing is hidden (the user may want just the title), but the card opens the right one
            if (tv) ranked else ranked.sortedBy { yearRank(it, year) }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * [search] with a six-hour cache of found variants (the setting can switch it off): opening the same card
     * again does not hit every site once more. The cache lives in its own preferences, so it is not a setting.
     */
    fun searchCached(context: Context, title: String, original: String, year: String, tv: Boolean = false): List<Variant> {
        if (!Settings.bool(context, Settings.CACHE_SOURCES)) return search(context, title, original, year, tv)
        val key = cacheKey(context, title, original, year, tv)
        val prefs = context.getSharedPreferences("cache", 0)
        val raw = prefs.getString(key, null)
        if (raw != null) {
            runCatching { variantFromJson(org.json.JSONObject(raw)) }.getOrNull()?.let { (time, list) ->
                if (System.currentTimeMillis() - time < 6 * 60 * 60 * 1000L) return list
            }
        }
        val found = search(context, title, original, year, tv)
        if (found.isNotEmpty()) {
            val json = org.json.JSONObject().put("t", System.currentTimeMillis()).put("v", org.json.JSONArray().apply {
                found.forEach { put(variantToJson(it)) }
            })
            prefs.edit().putString(key, json.toString()).apply()
            pruneVariantCache(prefs)
        }
        return found
    }

    /** Returns cached variants without network search, or null when the cache is empty or expired. */
    fun cachedOnly(context: Context, title: String, original: String, year: String, tv: Boolean = false): List<Variant>? {
        val key = cacheKey(context, title, original, year, tv)
        val prefs = context.getSharedPreferences("cache", 0)
        val raw = prefs.getString(key, null) ?: return null
        return runCatching { variantFromJson(org.json.JSONObject(raw)) }.getOrNull()?.let { (time, list) ->
            if (System.currentTimeMillis() - time < 6 * 60 * 60 * 1000L) list else null
        }
    }

    /**
     * The variant cache is a convenience, not an archive: drop entries of older cache versions, entries older
     * than a day and the oldest beyond 80 titles, so the preferences file cannot grow on the TV forever.
     */
    private fun pruneVariantCache(prefs: android.content.SharedPreferences) {
        val now = System.currentTimeMillis()
        val keys = prefs.all.keys.filter { it.startsWith("variants") }
        if (keys.isEmpty()) return
        fun timeOf(key: String): Long = runCatching {
            org.json.JSONObject(prefs.getString(key, "{}") ?: "{}").optLong("t")
        }.getOrDefault(0L)
        val edit = prefs.edit()
        keys.filter { !it.startsWith("variants10_") }.forEach { edit.remove(it) }
        val current = keys.filter { it.startsWith("variants10_") }.associateWith { timeOf(it) }
        current.filter { now - it.value > 24 * 60 * 60 * 1000L }.keys.forEach { edit.remove(it) }
        val alive = current.filter { now - it.value <= 24 * 60 * 60 * 1000L }
        if (alive.size > 80) {
            alive.entries.sortedBy { it.value }.take(alive.size - 80).forEach { edit.remove(it.key) }
        }
        edit.apply()
    }

    private fun cacheKey(context: Context, title: String, original: String, year: String, tv: Boolean): String {
        val raw = enabled(context, tv).joinToString(",") { it.id } + "|" + tv + "|" + title + "|" + original + "|" + year
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return "variants10_" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun variantToJson(v: Variant) = JSONObject().put("s", v.source).put("i", v.sourceId)
        .put("t", v.title).put("n", v.note).put("r", v.ref).put("a", v.alt).put("d", v.description)

    private fun variantFromJson(o: JSONObject) = o.getLong("t") to (0 until o.getJSONArray("v").length()).map { i ->
        val v = o.getJSONArray("v").getJSONObject(i)
        Variant(v.getString("s"), v.getString("i"), v.getString("t"), v.optString("n"), v.getString("r"),
            v.optString("a"), v.optString("d"))
    }

    /**
     * Keeps variants that contain at least 60% of the words of one wanted title (one shared word like "fall" is not
     * enough for a longer title), best match first (same year wins a tie).
     */
    fun relevant(found: List<Variant>, wanted: List<String>, year: String): List<Variant> {
        fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
        val titles = wanted.map { words(it) }.filter { it.isNotEmpty() }
        // a Russian query must not match only the English original of an unrelated Russian title:
        // "Интерстеллар" must not offer "Я злой лорд…" just because its English name carries "Interstellar"
        val cyrillicQuery = wanted.any { s -> s.any { it.code in 0x0400..0x04FF } }
        fun cyrillic(s: String) = s.any { it.code in 0x0400..0x04FF }
        return found.map { v ->
            val titleWords = words(v.title)
            val allWords = words(v.title + " " + v.alt)
            fun hits(where: Set<String>) = titles.maxOfOrNull { t ->
                val count = t.count { it in where }
                if (count * 5 >= t.size * 3) count else 0
            } ?: 0
            val total = hits(allWords)
            val inTitle = hits(titleWords)
            Triple(v, total, inTitle)
        }
            .filter { it.second > 0 && !(cyrillicQuery && it.third == 0 && cyrillic(it.first.title)) }
            // a hit in the shown title is worth more than a hit only in the original name: for "Интерстеллар"
            // the series "I am the Villainous Lord of the Interstellar Nation" must not come before the film
            .sortedWith(compareByDescending<Triple<Variant, Int, Int>> { it.second }
                .thenByDescending { it.third }
                .thenByDescending { it.first.note.startsWith(year) })
            .map { it.first }
    }

    /**
     * 0 when the variant's year is close to [year] (±1), 1 when either year is unknown, 2 when it is another year. The
     * year is the one the note starts with; a site that puts it in the title instead ("Обитель зла (2002)",
     * "…(2026,4K)") is read from there: any year of the title near the wanted one counts.
     */
    fun yearRank(variant: Variant, year: String): Int {
        val wanted = year.take(4).toIntOrNull() ?: return 1
        val fromNote = Regex("^(\\d{4})").find(variant.note)?.groupValues?.get(1)?.toIntOrNull()
        val years = if (fromNote != null) listOf(fromNote)
        else Regex("\\b(19|20)\\d{2}\\b").findAll(variant.title + " " + variant.alt).map { it.value.toInt() }.toList()
        if (years.isEmpty()) return 1
        return if (years.any { kotlin.math.abs(it - wanted) <= 1 }) 0 else 2
    }

    fun get(address: String, charset: java.nio.charset.Charset = Charsets.UTF_8): String =
        getBytes(address).toString(charset)

    /** POST with a JSON body, the same timeouts and UA as [get]; answers of the open APIs are JSON too. */
    fun postJson(address: String, body: String): String {
        val c = URL(address).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Safari/537.36")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return c.inputStream.use {
            ClockCheck.observe(c.date)
            it.readBytes().toString(Charsets.UTF_8)
        }
    }

    /** Binary answer (a .torrent file, for example) with the same timeouts and UA as [get]. */
    fun getBytes(address: String): ByteArray {
        val c = URL(address).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Safari/537.36")
        return c.inputStream.use {
            ClockCheck.observe(c.date)
            it.readBytes()
        }
    }

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * GET on a site through its addresses ([RemoteConfig]): the built-in one, the one from the addresses file or the
     * one typed by hand are tried in turn, and the answer carries the address that actually worked.
     */
    fun getAny(id: String, path: (String) -> String): Fetched<String> =
        RemoteConfig.attempt(id) { base -> get(path(base)) }
}

/** Anilibria through its public API: anime releases with HLS streams per episode. */
object Anilibria : VideoSource {
    override val id = "anilibria"
    override val name = "ANILIBRIA"

    /** The release type says whether it is a film (MOVIE) or a series (TV, ONA, OVA…): a card gets only its own kind. */
    override fun search(title: String, original: String, year: String, tv: Boolean): List<Variant> =
        search(title, original, year).filter { if (tv) !it.note.endsWith("Фильм") else it.note.endsWith("Фильм") }

    override fun search(title: String, original: String, year: String): List<Variant> {
        val seen = LinkedHashSet<Int>()
        val found = ArrayList<Variant>()
        for (query in listOf(title, original).filter { it.isNotBlank() }) {
            val answer = Sources.getAny(id) { base -> "$base/api/v1/app/search/releases?query=${Sources.encode(query)}" }
            val array = org.json.JSONArray(answer.value)
            for (i in 0 until array.length()) {
                val r = array.getJSONObject(i)
                if (!seen.add(r.getInt("id"))) continue
                val names = r.getJSONObject("name")
                found += Variant(
                    name, id, names.optString("main"),
                    listOf(r.optInt("year").toString().takeIf { it != "0" }.orEmpty(),
                        if (r.optJSONObject("type")?.optString("value") == "MOVIE") "Фильм" else "")
                        .filter { it.isNotEmpty() }.joinToString(" • "),
                    r.getInt("id").toString(), names.optString("english"),
                )
            }
        }
        return Sources.relevant(found, listOf(title, original), year)
    }

    override fun episodes(variant: Variant): List<Episode> {
        val release = JSONObject(Sources.getAny(id) { base -> "$base/api/v1/anime/releases/${variant.ref}" }.value)
        val array = release.getJSONArray("episodes")
        return (0 until array.length()).mapNotNull { i ->
            val e = array.getJSONObject(i)
            val streams = listOf("1080", "720", "480").mapNotNull { q ->
                e.optString("hls_$q").takeIf { it.isNotEmpty() && it != "null" }?.let { "${q}p" to it }
            }
            if (streams.isEmpty()) return@mapNotNull null
            val ordinal = e.optInt("ordinal", i + 1)
            val name = e.optString("name").takeIf { it.isNotEmpty() && it != "null" }
            ordinal to Episode("Серия $ordinal" + (name?.let { " • $it" } ?: ""), streams)
        }.sortedBy { it.first }.map { it.second }
    }
}

/** Makes text of an HTML fragment readable: drops tags, turns <br> into a space and decodes the usual entities. */
fun htmlText(html: String): String = html
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("<[^>]*>"), " ")
    .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
    .replace("&laquo;", "«").replace("&raquo;", "»").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace(Regex("\\s+"), " ").trim()

/**
 * Seasonvar through the open site: search by name, then the page's own playlist of direct MP4 links.
 * Seasons are flattened into one list; the standard translation is used, trailers are skipped.
 * The playlist stores each address Base64-encoded with a "//ololo=" marker in the middle (the site's own scheme).
 */
object Seasonvar : VideoSource {
    override val id = "seasonvar"
    override val name = "SEASONVAR"
    override val forFilms get() = false

    override fun search(title: String, original: String, year: String): List<Variant> {
        val seen = HashSet<String>()
        val found = ArrayList<Variant>()
        for (query in listOf(title, original).filter { it.isNotBlank() }) {
            val answer = runCatching { Sources.getAny(id) { base -> "$base/search?q=${Sources.encode(query)}" } }.getOrNull() ?: continue
            for ((variant, _) in parseList(answer.value, answer.base)) {
                if (seen.add(variant.ref)) found += variant
            }
        }
        return Sources.relevant(found, listOf(title, original), year)
    }

    /** Rows of a search/selection page: the variant and its poster (absolute or null). */
    private fun parseList(html: String, base: String): List<Pair<Variant, String?>> {
        val block = Regex("<div class=\"pgs-search-wrap\">(.*?)<div class=\"pgs-search-info\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
        return block.findAll(html).mapNotNull { m ->
            val href = Regex("<a href=\"([^\"]+)\"").find(m.groupValues[1])?.groupValues?.get(1) ?: return@mapNotNull null
            val info = m.groupValues[2]
            val links = Regex("<a href=\"[^\"]+\">(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(info)
                .map { htmlText(it.groupValues[1]) }.filter { it.isNotEmpty() }.toList()
            val name = links.firstOrNull() ?: return@mapNotNull null
            val originalName = links.getOrNull(1).orEmpty()
            val description = Regex("<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(info)?.groupValues?.get(1)
                ?.let { htmlText(it) }.orEmpty()
            val poster = Regex("<img src=\"([^\"]+)\"").find(m.groupValues[1])?.groupValues?.get(1)
                ?.let { if (it.startsWith("//")) "https:$it" else it }
            Variant(
                this.name, "seasonvar", name, originalName,
                if (href.startsWith("http")) href else base + href, originalName, description,
            ) to poster
        }.toList()
    }

    override fun episodes(variant: Variant): List<Episode> {
        // the pages found earlier carry absolute addresses: the site's own origin is the one that answered then
        val base = Regex("^(https?://[^/]+)").find(variant.ref)?.groupValues?.get(1) ?: RemoteConfig.base(id)
        val first = Sources.get(variant.ref)
        // a season page carries the season switch: follow every season, otherwise the page itself is the only one
        val seasonsBlock = Regex("<div class=\"pgs-seaslist\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(first)?.groupValues?.get(1)
        val seasons = seasonsBlock?.let { block ->
            Regex("<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(block).map {
                (if (it.groupValues[1].startsWith("http")) it.groupValues[1] else base + it.groupValues[1]) to
                    htmlText(it.groupValues[2]).ifEmpty { "Сезон" }
            }.toList()
        }.orEmpty().ifEmpty { listOf(variant.ref to "") }

        val result = ArrayList<Episode>()
        var blocked = false
        seasons.forEachIndexed { seasonIndex, (url, seasonName) ->
            val html = if (url == variant.ref) first else runCatching { Sources.get(url) }.getOrNull() ?: return@forEachIndexed
            // the site itself says the season is closed for this country; no attempts to go around it
            if (html.contains("заблокирован для вашей страны") || html.contains("По просьбе правообладателя")) {
                blocked = true
                return@forEachIndexed
            }
            val playlist = firstPlaylist(html, base, variant.voice) ?: return@forEachIndexed
            val episodes = runCatching { org.json.JSONArray(Sources.get(playlist)) }.getOrNull() ?: return@forEachIndexed
            val seasonLabel = Regex("\\d+\\s*сезон\\S*", RegexOption.IGNORE_CASE).find(seasonName)?.value
                ?: "Сезон ${seasonIndex + 1}"
            val prefix = if (seasons.size > 1) "$seasonLabel • " else ""
            for (i in 0 until episodes.length()) {
                val e = episodes.getJSONObject(i)
                val raw = e.optString("file")
                if (raw.isEmpty()) continue
                val address = runCatching { streamUrl(raw) }.getOrNull() ?: continue
                val caption = htmlText(e.optString("title")).ifEmpty { "Серия ${e.optInt("id", i + 1)}" }
                result += Episode(prefix + caption, listOf((if (address.contains(".m3u8")) "HLS" else "SD/HD") to address))
            }
        }
        if (result.isEmpty() && blocked) throw IllegalStateException(Errors.GEO_BLOCKED)
        return result
    }

    /** The voice-overs of a series page: `<li data-click="translate" data-translate="6">Кубик в кубе</li>`; trailers are not a voice-over. */
    override fun voices(variant: Variant): List<Pair<String, String>> {
        val html = Sources.get(variant.ref)
        val found = Regex("<li[^>]*data-click=\"translate\"[^>]*data-translate=\"(\\d+)\"[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { it.groupValues[1] to htmlText(it.groupValues[2]) }
            .filter { it.second.isNotEmpty() && !it.second.contains("трейлер", ignoreCase = true) }.distinctBy { it.first }.toList()
        return if (found.size > 1) found else emptyList()
    }

    /**
     * The playlist of a translation of the page: `var pl = {'0': "/playls2/…plist.txt"};` plus the optional `pl[n] = …` lines.
     * [preferred] is the key of the chosen voice-over; a season that does not have it falls back to the default one.
     */
    private fun firstPlaylist(html: String, base: String, preferred: String = ""): String? {
        val main = Regex("var pl = \\{(.*?)\\};", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1).orEmpty()
        val all = Regex("['\"]?(\\d+)['\"]?\\s*:\\s*\"([^\"]+)\"").findAll(main).mapNotNull { m -> m.groupValues[1].toIntOrNull()?.let { it to m.groupValues[2] } } +
            Regex("pl\\[(\\d+)]\\s*=\\s*\"([^\"]+)\"").findAll(html).mapNotNull { m -> m.groupValues[1].toIntOrNull()?.let { it to m.groupValues[2] } }
        val chosen = preferred.toIntOrNull()?.let { key -> all.firstOrNull { it.first == key }?.second }
            ?: all.firstOrNull { it.first == 0 }?.second ?: all.firstOrNull()?.second ?: return null
        return when {
            chosen.startsWith("http") -> chosen
            chosen.startsWith("//") -> "https:$chosen"
            else -> base + chosen
        }
    }

    /** `#2<base64 with a //ololo= marker>` -> the direct address. */
    private fun streamUrl(raw: String): String {
        var text = if (raw.startsWith("#2")) raw.substring(2) else raw
        Regex("//([^=/]+)=").find(text)?.let { text = text.replace("//${it.groupValues[1]}=", "") }
        val decoded = String(android.util.Base64.decode(text, android.util.Base64.DEFAULT))
        return if (decoded.startsWith("//")) "https:$decoded" else decoded
    }
}

/**
 * Rutube (the "Rumba" of HD AlfaBox) through its public API: the search gives long videos (full films),
 * the play options give an HLS address the API itself signs. Clips, paid videos and short trailers are skipped.
 */
object Rutube : VideoSource {
    override val id = "rutube"
    override val name = "RUTUBE"
    // its "series" are loose uploads of single videos: no seasons, no episode list
    override val forSeries get() = false

    override fun search(title: String, original: String, year: String): List<Variant> {
        val seen = HashSet<String>()
        // one movie is often uploaded several times: keep one card per title, year and length
        val unique = LinkedHashMap<String, Variant>()
        for (query in listOf(title, original).filter { it.isNotBlank() }) {
            val json = runCatching {
                JSONObject(Sources.getAny(id) { base -> "$base/api/search/video/?query=${Sources.encode(query)}" }.value)
            }.getOrNull() ?: continue
            val results = json.optJSONArray("results") ?: continue
            for (i in 0 until results.length()) {
                val o = results.getJSONObject(i)
                val id = o.optString("id")
                if (id.isEmpty() || o.optBoolean("is_paid") || !seen.add(id)) continue
                // a full film lasts about two hours; trailers and clips are not variants of the title
                val duration = o.optInt("duration")
                // 70 minutes: shorter uploads are trailers, clips and reviews, not the film
                if (duration < 4200) continue
                val category = o.optJSONObject("category")?.optString("name").orEmpty()
                if (category.isNotEmpty() && category != "Фильмы" && category != "Сериалы") continue
                val name = o.optString("title")
                if (name.isEmpty()) continue
                if (Regex("обзор|трейлер|разбор|рецензи|реакци|нарезк|рейтинг|топ ", RegexOption.IGNORE_CASE).containsMatchIn(name)) continue
                val minutes = duration / 60
                val author = o.optJSONObject("author")?.optString("name").orEmpty()
                val note = listOf("Фильм", "$minutes мин", author).filter { it.isNotEmpty() }.joinToString(" • ")
                val yearInName = Regex("\\b(19|20)\\d{2}\\b").find(name)?.value.orEmpty()
                val key = normalizedTitle(name) + "|" + yearInName + "|" + (duration / 600)
                if (!unique.containsKey(key)) unique[key] = Variant(this.name, "rutube", name, note, id)
            }
        }
        return Sources.relevant(unique.values.toList(), listOf(title, original), year)
    }

    private fun normalizedTitle(s: String): String = s.lowercase()
        .replace(Regex("\\b(4k|8k|uhd|hdr|fullhd|fhd|hd|1080p?|720p?|480p?|bluray|bdrip|webrip|web-dl|webdl|remux|hevc|x265|x264|aac|dts|дубляж|многоголосый|полный фильм)\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    override fun episodes(variant: Variant): List<Episode> {
        val json = JSONObject(Sources.getAny(id) { base -> "$base/api/play/options/${variant.ref}/?no_404=true" }.value)
        val hls = json.optJSONObject("video_balancer")?.optString("m3u8").orEmpty()
        if (!hls.startsWith("http")) return emptyList()
        return listOf(Episode("Фильм", listOf("HLS" to hls)))
    }
}

