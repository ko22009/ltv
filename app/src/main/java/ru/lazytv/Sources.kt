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
data class Episode(
    val title: String,
    private val first: List<Pair<String, String>>,
    /** A site that gives the links one by one (HDRezka series) lists the episodes at once and asks for a link when it is needed. */
    private val resolver: (() -> List<Pair<String, String>>)? = null,
) {
    @Volatile private var loaded: List<Pair<String, String>>? = null

    val streams: List<Pair<String, String>> get() = loaded ?: first

    /** False for an episode whose links are still to be asked for with [resolve] (network: not on the main thread). */
    val isResolved: Boolean get() = streams.isNotEmpty() || resolver == null

    fun resolve(): List<Pair<String, String>> {
        if (isResolved) return streams
        val found = resolver!!.invoke()
        if (found.isNotEmpty()) loaded = found
        return found
    }
}

/** Episodes handed to the player whose links are not known yet, by their watch key: the player asks for them when it gets there. */
object LazyLinks {
    private val episodes = java.util.concurrent.ConcurrentHashMap<String, Episode>()

    fun register(key: String, episode: Episode) {
        if (key.isEmpty() || episode.isResolved) return
        if (episodes.size > 600) episodes.clear()
        episodes[key] = episode
    }

    fun resolve(key: String): List<Pair<String, String>> = runCatching { episodes[key]?.resolve() }.getOrNull().orEmpty()
}

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

    /** Names of the enabled sources that do not answer now (shown to the user, so a missing source is not a mystery). */
    fun unavailable(context: Context, tv: Boolean = false): List<String> =
        enabled(context, tv).filter { RemoteConfig.isDown(it.id) }.map { it.name }

    /** Searches every enabled source at once; a source that fails or finds nothing simply adds no tiles. */
    fun search(
        context: Context, title: String, original: String, year: String, tv: Boolean = false,
        onPartial: ((List<Variant>) -> Unit)? = null,
    ): List<Variant> {
        val list = enabled(context, tv).filter { !RemoteConfig.isDown(it.id) }
        if (list.isEmpty()) return emptyList()
        val pool = java.util.concurrent.Executors.newCachedThreadPool()
        return try {
            val tasks = list.map { source ->
                java.util.concurrent.Callable {
                    val started = System.currentTimeMillis()
                    val found = runCatching { source.search(title, original, year, tv) }
                    android.util.Log.i("LazyTV", "source ${source.id}: ${System.currentTimeMillis() - started} ms, " +
                        (found.getOrNull()?.size?.toString() ?: ("failed: " + found.exceptionOrNull())))
                    found.getOrDefault(emptyList())
                }
            }
            fun rank(combined: List<Variant>): List<Variant> {
                // each source ranks its own answers; the combined list has to be ranked across sources again
                val ranked = relevant(combined, listOf(title, original), year)
                // a film of the wanted year comes first, then the ones whose year is not known, then the other parts and remakes:
                // nothing is hidden (the user may want just the title), but the card opens the right one
                if (tv) return ranked
                // the same film uploaded by several channels (same year, length to 5 minutes) is one "copy": the first of them
                // keeps its place, the repeats go to the end of their year group, still in the same list
                val seen = HashSet<String>()
                val repeat = ranked.associateWith { !seen.add(copyKey(it)) }
                return ranked.sortedWith(compareBy({ yearRank(it, year, listOf(title, original)) }, { repeat[it] == true }))
            }
            // the sources are collected as they answer: onPartial gets the ranked list of what is found so far, so the card
            // does not wait for the slowest site. One that did not answer in 12 seconds is skipped (the card waits up to 30)
            val done = java.util.concurrent.ExecutorCompletionService<List<Variant>>(pool)
            tasks.forEach { done.submit(it) }
            val combined = ArrayList<Variant>()
            val deadline = System.currentTimeMillis() + 12_000L
            repeat(tasks.size) {
                val left = deadline - System.currentTimeMillis()
                val future = (if (left > 0) done.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS) else null) ?: return@repeat
                val part = runCatching { future.get() }.getOrDefault(emptyList())
                if (part.isNotEmpty()) {
                    combined += part
                    onPartial?.invoke(rank(combined.toList()))
                }
            }
            rank(combined)
        } finally {
            pool.shutdownNow()
        }
    }

    private const val CACHE_MS = 6 * 60 * 60 * 1000L
    /** A title in the bookmarks keeps its found sources longer: it is opened again and again. */
    private const val CACHE_KEPT_MS = 3 * 24 * 60 * 60 * 1000L

    private fun cacheLife(o: JSONObject) = if (o.optBoolean("k")) CACHE_KEPT_MS else CACHE_MS

    /**
     * [search] with a six-hour cache of found variants (three days for a bookmarked title, [keep]); the setting can
     * switch it off. Opening the same card again does not hit every site once more. The cache lives in its own
     * preferences, so it is not a setting.
     */
    fun searchCached(
        context: Context, title: String, original: String, year: String, tv: Boolean = false, keep: Boolean = false,
        onPartial: ((List<Variant>) -> Unit)? = null,
    ): List<Variant> {
        if (!Settings.bool(context, Settings.CACHE_SOURCES)) return search(context, title, original, year, tv, onPartial)
        val key = cacheKey(context, title, original, year, tv)
        val prefs = context.getSharedPreferences("cache", 0)
        val raw = prefs.getString(key, null)
        if (raw != null) {
            runCatching {
                val o = org.json.JSONObject(raw)
                variantFromJson(o).let { (time, list) -> Triple(time, list, cacheLife(o)) }
            }.getOrNull()?.let { (time, list, life) ->
                if (System.currentTimeMillis() - time < life) return list
            }
        }
        val found = search(context, title, original, year, tv, onPartial)
        if (found.isNotEmpty()) {
            val json = org.json.JSONObject().put("t", System.currentTimeMillis()).put("k", keep).put("v", org.json.JSONArray().apply {
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
        return runCatching {
            val o = org.json.JSONObject(raw)
            variantFromJson(o).let { (time, list) -> Triple(time, list, cacheLife(o)) }
        }.getOrNull()?.let { (time, list, life) ->
            if (System.currentTimeMillis() - time < life) list else null
        }
    }

    /**
     * The variant cache is a convenience, not an archive: drop entries of older cache versions, entries older
     * than a day (three days for bookmarked titles) and the oldest beyond 80 titles (not bookmarked ones first),
     * so the preferences file cannot grow on the TV forever.
     */
    private fun pruneVariantCache(prefs: android.content.SharedPreferences) {
        val now = System.currentTimeMillis()
        val keys = prefs.all.keys.filter { it.startsWith("variants") }
        if (keys.isEmpty()) return
        fun entry(key: String): Pair<Long, Boolean> = runCatching {
            val o = org.json.JSONObject(prefs.getString(key, "{}") ?: "{}")
            o.optLong("t") to o.optBoolean("k")
        }.getOrDefault(0L to false)
        val edit = prefs.edit()
        keys.filter { !it.startsWith("variants16_") }.forEach { edit.remove(it) }
        val current = keys.filter { it.startsWith("variants16_") }.associateWith { entry(it) }
        val dayMs = 24 * 60 * 60 * 1000L
        fun expired(e: Pair<Long, Boolean>) = now - e.first > (if (e.second) 3 * dayMs else dayMs)
        current.filter { expired(it.value) }.keys.forEach { edit.remove(it) }
        val alive = current.filter { !expired(it.value) }
        if (alive.size > 80) {
            alive.entries.sortedWith(compareBy({ it.value.second }, { it.value.first }))
                .take(alive.size - 80).forEach { edit.remove(it.key) }
        }
        edit.apply()
    }

    private fun cacheKey(context: Context, title: String, original: String, year: String, tv: Boolean): String {
        val raw = enabled(context, tv).joinToString(",") { it.id } + "|" + tv + "|" + title + "|" + original + "|" + year
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return "variants16_" + digest.joinToString("") { "%02x".format(it) }
    }

    /** Removes a dead/stub variant from the cached search results so it is not offered again on reopening the card. */
    fun removeCachedVariant(context: Context, title: String, original: String, year: String, tv: Boolean, variant: Variant) {
        val key = cacheKey(context, title, original, year, tv)
        val prefs = context.getSharedPreferences("cache", 0)
        val raw = prefs.getString(key, null) ?: return
        runCatching {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("v")
            val newArr = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                if (item.optString("i") == variant.sourceId && item.optString("r") == variant.ref) continue
                newArr.put(item)
            }
            o.put("v", newArr)
            prefs.edit().putString(key, o.toString()).apply()
        }
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
        // articles and linking words are not part of a name: "the" alone must not tie "The Dark Knight" to "Thor: The Dark World"
        val stop = setOf("the", "and", "for", "with", "from", "part", "movie", "film", "что", "как", "это", "фильм", "часть")
        // "ё" and "е" are one letter for the sites: TMDB writes "Тёмный", HDRezka "Темный"
        fun words(s: String) = s.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in stop }.toSet()
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
     * The ±1 year is allowed only for a variant that carries the wanted name itself (sites date one film differently):
     * a longer title ("Зверь войны" for "Зверь") is another film and must have the exact year. Without [names] the
     * tolerance applies to every variant.
     */
    fun yearRank(variant: Variant, year: String, names: List<String> = emptyList(), exactYear: Boolean = false): Int {
        val wanted = year.take(4).toIntOrNull() ?: return 1
        val fromNote = Regex("^(\\d{4})").find(variant.note)?.groupValues?.get(1)?.toIntOrNull()
        val years = if (fromNote != null) listOf(fromNote)
        else Regex("(?<!\\d)(19|20)\\d{2}(?!\\d)").findAll(variant.title + " " + variant.alt).map { it.value.toInt() }.toList()
        if (years.isEmpty()) return 1
        // [exactYear] (a series): no +-1 year, the year must be the very one
        val tolerance = if (!exactYear && (names.isEmpty() || hasName(variant, names))) 1 else 0
        return if (years.any { kotlin.math.abs(it - wanted) <= tolerance }) 0 else 2
    }

    /** True when the title of the variant (or one of its parts split by "|" and "/", without brackets) is exactly one of [names]. */
    private fun hasName(variant: Variant, names: List<String>): Boolean {
        fun plain(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("\\([^)]*\\)"), " ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val wanted = names.map { plain(it) }.filter { it.isNotEmpty() }.toSet()
        val parts = (listOf(variant.title) + variant.title.split('|', '/') + variant.alt.split('|', '/')).map { plain(it) }
        return parts.any { it in wanted }
    }

    /**
     * What the source list of a film shows: the variants of its year and how many others (another year, or none known) are kept back
     * for "Другие годы"; when no variant fits the year, everything is shown. A series is cut the same way, except that a variant with no known year stays; [all] shows what was kept back.
     */
    fun forYear(variants: List<Variant>, year: String, tv: Boolean, all: Boolean, names: List<String> = emptyList()): Pair<List<Variant>, Int> {
        val main = mainList(variants, year, names, tv)
        // the expanded list holds only what the first one kept back: the film of its year is not shown twice
        if (all) return variants.filter { it !in main }.ifEmpty { variants } to 0
        return main to (variants.size - main.size)
    }

    /**
     * The variants of the film itself: of its year, and (when there are any) with exactly its name, so "Сердце зверя" or
     * "Зверь войны" do not stand among the copies of "Зверь". Nothing fits: all of them (the user may want just the title).
     */
    private fun mainList(variants: List<Variant>, year: String, names: List<String>, tv: Boolean): List<Variant> {
        // a series is judged by its year too (a film of another year is not the series), but a site that gives no year
        // for a series (a season list) stays in the main list: only a known different year is kept back
        val ofYear = variants.filter { if (tv) yearRank(it, year, names, exactYear = true) != 2 else yearRank(it, year, names) == 0 }
        if (ofYear.isEmpty()) return variants
        val exact = if (names.isEmpty()) emptyList() else ofYear.filter { hasName(it, names) }
        return exact.ifEmpty { ofYear }
    }

    /** The film itself: its year and exactly its name. The card starts without waiting for the slow sites once one is found. */
    fun isExact(variant: Variant, year: String, names: List<String>): Boolean =
        yearRank(variant, year, names) == 0 && hasName(variant, names)

    /** The variant a film opens by default: the first of [forYear]'s main list. */
    fun best(variants: List<Variant>, year: String, tv: Boolean, names: List<String>): Variant? =
        forYear(variants, year, tv, false, names).first.firstOrNull()

    /** Identity of an upload of a film: source, year and length (from the note, to 5 minutes); a variant without a length is its own copy. */
    private fun copyKey(v: Variant): String {
        val minutes = Regex("(\\d+) мин").find(v.note)?.groupValues?.get(1)?.toIntOrNull() ?: return v.sourceId + "|" + v.ref
        val year = Regex("(?<!\\d)(19|20)\\d{2}(?!\\d)").find(v.title + " " + v.alt + " " + v.note)?.value.orEmpty()
        return v.sourceId + "|" + year + "|" + Math.round(minutes / 5.0)
    }

    private val episodeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Episode>>>()

    /**
     * The video list of a variant, kept in memory for ten minutes: the card asks for it ahead of the "Смотреть" press and the
     * episode screen reuses the answer. Not kept longer, the links of some sites (HDRezka) expire within the hour.
     */
    fun episodesCached(variant: Variant): List<Episode> {
        val key = variant.sourceId + "|" + variant.ref + "|" + variant.voice
        episodeCache[key]?.let { (at, list) -> if (System.currentTimeMillis() - at < 10 * 60 * 1000L) return list }
        val source = byId(variant.sourceId) ?: error("Источник недоступен")
        val list = source.episodes(variant)
        if (list.isNotEmpty()) {
            if (episodeCache.size > 8) episodeCache.entries.minByOrNull { it.value.first }?.let { episodeCache.remove(it.key) }
            episodeCache[key] = System.currentTimeMillis() to list
        }
        return list
    }

    fun get(address: String, charset: java.nio.charset.Charset = Charsets.UTF_8, quick: Boolean = false): String =
        getBytes(address, quick).toString(charset)

    /** POST with a JSON body, the same timeouts and UA as [get]; answers of the open APIs are JSON too. */
    fun postJson(address: String, body: String): String {
        val c = Connectivity.open(address)
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
    fun getBytes(address: String, quick: Boolean = false): ByteArray {
        val c = Connectivity.open(address)
        // quick: a search must not wait for a dead mirror 15 seconds, the next mirror is tried instead
        c.connectTimeout = if (quick) 3000 else 15000
        c.readTimeout = if (quick) 6000 else 25000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Safari/537.36")
        return c.inputStream.use {
            ClockCheck.observe(c.date)
            it.readBytes()
        }
    }

    /**
     * The same request for several queries at once (the Russian and the original title): answers come back in the order of
     * the queries, a failed one as null. Two sequential round trips to a site become one.
     */
    fun <T> parallel(queries: List<String>, fetch: (String) -> T): List<Pair<String, T?>> {
        if (queries.size < 2) return queries.map { it to runCatching { fetch(it) }.getOrNull() }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(queries.size)
        try {
            val jobs = queries.map { q -> java.util.concurrent.Callable { runCatching { fetch(q) }.getOrNull() } }
            return queries.zip(pool.invokeAll(jobs).map { runCatching { it.get() }.getOrNull() })
        } finally {
            pool.shutdown()
        }
    }

    /** Logs an empty search answer that looks like a stub or a bot check (short page, challenge words): "nothing found" is told apart from "not served". */
    fun noteEmpty(id: String, html: String) {
        if (html.length < 4000 || Regex("captcha|anubis|Проверяем|Just a moment|cloudflare", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            android.util.Log.i("LazyTV", "source $id: empty answer looks like a stub or a check (${html.length} chars)")
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
        val answers = Sources.parallel(listOf(title, original).filter { it.isNotBlank() }) { query ->
            Sources.getAny(id) { base -> "$base/api/v1/app/search/releases?query=${Sources.encode(query)}" }
        }
        for ((_, answer) in answers) {
            if (answer == null) continue
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
        val answers = Sources.parallel(listOf(title, original).filter { it.isNotBlank() }) { query ->
            Sources.getAny(id) { base -> "$base/search?q=${Sources.encode(query)}" }
        }
        for ((_, answer) in answers) {
            if (answer == null) continue
            val rows = parseList(answer.value, answer.base)
            if (rows.isEmpty()) Sources.noteEmpty(id, answer.value)
            for ((variant, _) in rows) {
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
        val answers = Sources.parallel(listOf(title, original).filter { it.isNotBlank() }) { query ->
            JSONObject(Sources.getAny(id) { base -> "$base/api/search/video/?query=${Sources.encode(query)}" }.value)
        }
        for ((_, json) in answers) {
            if (json == null) continue
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
                if (Regex("обзор|трейлер|разбор|рецензи|реакци|нарезк|рейтинг|топ |смотрим с", RegexOption.IGNORE_CASE).containsMatchIn(name)) continue
                val minutes = duration / 60
                val author = o.optJSONObject("author")?.optString("name").orEmpty()
                val note = listOf("Фильм", "$minutes мин", author).filter { it.isNotEmpty() }.joinToString(" • ")
                val yearInName = Regex("(?<!\\d)(19|20)\\d{2}(?!\\d)").find(name)?.value.orEmpty()
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

