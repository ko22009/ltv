package ru.lazytv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Torrent sources besides rutor. They index original (mostly English) titles, so they are asked with the original name. */
object ExtraTorrents {
    /** The public JacRed servers that worked when the source was added; used until the user sets their own list. */
    const val DEFAULT_JAC_RED = "https://jr.maxvol.pro,https://jacred.freebie.tom.ru,https://jac.stull.xyz"

    private fun magnet(hash: String, name: String) = Magnet.link(hash, name)

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1_048_576.0)
        else -> "%d KB".format(bytes / 1024)
    }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }

    /**
     * Asks every extra source at once; one that fails or finds nothing adds nothing to the list.
     * The English indexes get the original title, the Russian tracker (Octopus) gets the Russian one.
     */
    fun search(context: Context, name: String, original: String, onSource: ((String, List<Torrent>) -> Unit)? = null): List<Torrent> {
        val query = words(original).joinToString(" ")
        val wanted = words(original).filter { it.length >= 3 }.toSet()
        // the public indexes return loose matches: keep releases whose title carries most of the wanted words
        fun related(list: List<Torrent>) = list.filter { t ->
            val have = words(t.title).toSet()
            wanted.isEmpty() || wanted.count { it in have } * 2 >= wanted.size
        }
        val jobs = mutableListOf<Pair<String, () -> List<Torrent>>>()
        if (query.isNotBlank()) {
            jobs.add("TPB" to { related(pirateBay(query)) })
            jobs.add("Nyaa" to { related(nyaa(query)) })
            jobs.add("YTS" to { related(yts(query)) })
        }
        if (name.isNotBlank()) jobs.add("Octopus" to { octopus(name, original) })
        if (name.isNotBlank()) jobs.add("Videoplenka" to { videoplenka(name) })
        if (name.isNotBlank()) jobs.add("JacRed" to { jacRed(context, name) })
        if (name.isNotBlank()) jobs.add("TorrentKino" to { torrentKino(name) })
        if (name.isNotBlank() || original.isNotBlank()) jobs.add("Jackett" to { jackett(context, name.ifBlank { original }, original) })
        if (query.isNotBlank()) jobs.add("Bitsearch" to { related(bitsearch(query)) })
        if (query.isNotBlank() && Settings.bool(context, Settings.SEARCHTOR)) jobs.add("Searchtor" to { related(searchtor(query)) })
        // NNM-Club titles carry both names; a Japanese or Chinese original cannot be searched, so the Russian name goes then
        val latin = Regex("[A-Za-z\\p{IsCyrillic}]").containsMatchIn(original)
        val phrase = if (latin) original else name
        if (phrase.isNotBlank()) jobs.add("NNM" to { if (latin) related(nnmClub(phrase)) else nnmClub(phrase) })
        val pool = java.util.concurrent.Executors.newCachedThreadPool()
        val completion = java.util.concurrent.ExecutorCompletionService<Pair<String, List<Torrent>>>(pool)
        val tasks = jobs.map { (source, job) ->
            // each source reports its rows as soon as it answers, so the screen is not empty for a minute
            completion.submit(java.util.concurrent.Callable {
                source to runCatching { job() }
                    .onFailure { android.util.Log.w("LazyTV", "torrent source $source failed: $it") }
                    .getOrDefault(emptyList())
            })
        }
        val result = ArrayList<Torrent>()
        val deadline = System.currentTimeMillis() + 50_000
        try {
            repeat(tasks.size) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return@repeat
                val future = completion.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return@repeat
                val pair = runCatching { future.get() }.getOrNull() ?: return@repeat
                android.util.Log.i("LazyTV", "torrent source ${pair.first}: ${pair.second.size}")
                runCatching { onSource?.invoke(pair.first, pair.second) }
                result += pair.second
            }
        } finally {
            pool.shutdownNow()
        }
        return result
    }

    /** Own Jackett, when the address and key are set in the settings; other sources keep working without it. */
    private fun jackett(context: Context, phrase: String, original: String): List<Torrent> {
        val url = Settings.string(context, Settings.JACKETT_URL, "").trimEnd('/')
        val key = Settings.string(context, Settings.JACKETT_KEY, "")
        if (url.isEmpty() || key.isEmpty()) return emptyList()
        // Russian trackers answer the Russian title, foreign ones the original; try the second phrase only when the first is empty
        val phrases = listOf(phrase, original).filter { it.isNotBlank() }.distinct()
        for (p in phrases) {
            val found = runCatching { jackettParse(Sources.get("$url/api/v2.0/indexers/all/results?apikey=${Sources.encode(key)}&query=${Sources.encode(p)}")) }
                .getOrElse { if (p == phrases.last()) throw it else emptyList() }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /** Number of releases Jackett finds for a probe phrase: the settings button uses it as a connection test. */
    fun jackettCount(context: Context, phrase: String): Int = jackett(context, phrase, "").size

    private fun jackettParse(json: String): List<Torrent> {
        val root = JSONObject(json)
        val array = root.optJSONArray("Results") ?: root.optJSONArray("results")
            ?: if (json.trimStart().startsWith("[")) JSONArray(json) else return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val title = o.optString("Title").ifEmpty { o.optString("title") }
            val magnet = o.optString("MagnetUri").ifEmpty { o.optString("magnetUri") }
                .ifEmpty { o.optString("Link").ifEmpty { o.optString("link") } }
            if (title.isEmpty() || magnet.isEmpty()) return@mapNotNull null
            val tracker = o.optString("Tracker").ifEmpty { o.optString("TrackerId").ifEmpty { o.optString("tracker") } }
            Torrent(
                title, size(o.optLong("Size").takeIf { it > 0 } ?: o.optLong("size")),
                o.optInt("Seeders").takeIf { it > 0 } ?: o.optInt("seeders"),
                o.optInt("Peers").takeIf { it > 0 } ?: o.optInt("peers"), magnet,
                if (tracker.isEmpty()) "Jackett" else tracker.replaceFirstChar { it.uppercase() },
            )
        }
    }

    /** Servers from the settings (a comma-separated list), else the addresses file, else the built-in ones. */
    private fun jacRedServers(context: Context): List<String> {
        val own = Settings.string(context, Settings.JACRED_SERVERS, "")
            .split(',', ';', ' ')
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("http") }
        return own.ifEmpty { RemoteConfig.bases("jacred") }
    }

    // трекеры, которые у нас уже есть отдельными источниками: чтобы раздачи не повторялись
    private val jacRedSkip = setOf("rutor", "nnmclub", "ultradox")

    private val jacRedNames = mapOf("rutracker" to "RuTracker", "kinozal" to "Kinozal", "bitru" to "Bitru",
        "megapeer" to "Megapeer", "torrentby" to "Torrent.by", "toloka" to "Toloka", "selezen" to "Selezen")

    /**
     * JacRed: one request to a public index returns releases of many Russian trackers (RuTracker, Kinozal and others)
     * with the magnet link, size and seeders, no account needed. Only releases that carry the wanted words are kept.
     */
    private fun jacRed(context: Context, name: String): List<Torrent> {
        val wanted = words(name).filter { it.length >= 3 }.toSet()
        val servers = jacRedServers(context)
        val answers = java.util.concurrent.LinkedBlockingQueue<List<Torrent>>()
        servers.forEach { server ->
            Thread {
                answers.put(runCatching { jacRedParse(Sources.get("$server/api/v1.0/torrents?search=${Sources.encode(name)}&apikey=")) }
                    .getOrDefault(emptyList()))
            }.start()
        }
        var found = emptyList<Torrent>()
        for (i in servers.indices) {
            found = answers.take()
            if (found.isNotEmpty()) break
        }
        return found.filter { t ->
            val have = words(t.title).toSet()
            wanted.isEmpty() || wanted.count { it in have } * 2 >= wanted.size
        }.sortedByDescending { it.seeds }.take(200)
    }

    private fun jacRedParse(json: String): List<Torrent> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val tracker = o.optString("tracker").lowercase()
            val magnet = o.optString("magnet")
            if (tracker in jacRedSkip || !magnet.startsWith("magnet:") || o.optString("title").isEmpty()) return@mapNotNull null
            Torrent(o.optString("title"), size(o.optLong("size")), o.optInt("sid"), o.optInt("pir"), magnet,
                jacRedNames[tracker] ?: tracker.replaceFirstChar { it.uppercase() })
        }
    }

    private fun tk() = RemoteConfig.base("torrentkino").trimEnd('/')

    /**
     * TorrentKino: the title search gives the film page, the page carries every release with quality, dub, size and
     * seeders as JSON. The magnet link is a separate small request, made when the row is picked (see [magnetOf]).
     * упрощено: берётся первый фильм с точно таким же названием (у ремейков с одним названием может выйти не тот год).
     */
    private fun torrentKino(name: String): List<Torrent> {
        val found = JSONObject(Sources.get("${tk()}/api/search?q=${Sources.encode(name)}&limit=5&mode=suggestions")).getJSONArray("results")
        val slug = (0 until found.length()).map { found.getJSONObject(it) }
            .firstOrNull { it.optString("title").equals(name, true) }?.optString("slug")?.ifEmpty { null } ?: return emptyList()
        val page = Sources.get("${tk()}/films/$slug")
        // the releases sit in the page as escaped JSON: \"sources\":[...],\"filmTitle
        val json = page.substringAfter("\\\"sources\\\":", "").substringBefore(",\\\"filmTitle", "")
        if (json.isEmpty()) return emptyList()
        val array = JSONArray(json.replace("\\\"", "\""))
        return (0 until array.length()).map { array.getJSONObject(it) }.map { o ->
            val label = listOf(o.optString("qualityLabel"), o.optString("translation")).filter { it.isNotEmpty() }.joinToString(", ")
            Torrent("$name • $label", o.optString("size").replace("ГБ", "GB").replace("МБ", "MB").replace(',', '.'),
                o.optInt("seeds"), o.optInt("peers"), "${tk()}/api/sources/${o.optLong("id")}", "TorrentKino")
        }
    }

    private fun octopusMirrors() = RemoteConfig.bases("octopus")

    /**
     * Octopus (UltraDox), an open Russian tracker: the site search lists the title pages, every page carries the magnet
     * links in plain HTML. The page does not show seeders, so they stay 0.
     */
    private fun octopus(name: String, original: String): List<Torrent> {
        val wanted = words(name).filter { it.length >= 3 }.toSet()
        val item = Regex("<div class=\"top__slider_div__item\"[^>]*>\\s*<a href=\"([^\"]+)\">.*?<span>([^<]+)</span>", RegexOption.DOT_MATCHES_ALL)
        mirrors@ for (base in octopusMirrors()) {
            // the site matches the phrase as typed ("Матрица: Воскрешение" finds it, "матрица воскрешение" does not);
            // the original title is the second try, its pages carry Russian titles, so they cannot be checked by words
            for ((phrase, check) in listOf(name.trim() to true, original.trim() to false)) {
                if (phrase.isEmpty()) continue
                val html = runCatching { Sources.get("$base/index.php?do=search&subaction=search&story=${Sources.encode(phrase)}") }.getOrNull()
                    ?: continue@mirrors
                val pages = item.findAll(html).map { it.groupValues[1] to it.groupValues[2] }.filter { (_, title) ->
                    val have = words(title).toSet()
                    !check || wanted.isEmpty() || wanted.count { it in have } * 5 >= wanted.size * 3
                }.take(3).toList()
                if (pages.isNotEmpty()) {
                    return pages.flatMap { (href, _) -> octopusPage(Sources.get(if (href.startsWith("http")) href else base + href)) }
                }
            }
            return emptyList()
        }
        return emptyList()
    }

    private val windows1251 = java.nio.charset.Charset.forName("windows-1251")
    private fun nnm() = RemoteConfig.base("nnm").trimEnd('/') + "/"
    private val nnmNonVideo = listOf(
        "Дополнения", "Программы", "Софт", "Игры", "Книги", "Журналы", "Музыка", "Обои",
        "Мобильные", "Скрипты", "Шаблоны", "Дизайн", "Фото", "Уроки", "Информация",
    )

    /**
     * NNM-Club: the search works without an account and every row carries size and seeders. The magnet link is only
     * on the topic page, so the row keeps the page address and [magnetOf] fetches the link when the row is picked.
     */
    private fun nnmClub(phrase: String): List<Torrent> {
        val html = Sources.get(nnm() + "tracker.php?nm=" + URLEncoder.encode(phrase, "windows-1251"), windows1251)
        fun text(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").trim()
        return html.split("<tr class=\"prow").drop(1).take(40).mapNotNull { row ->
            val forum = Regex("class=\"gen\" href=\"tracker\\.php\\?f=\\d+[^\"]*\">([^<]+)</a>").find(row)?.groupValues?.get(1).orEmpty()
            if (nnmNonVideo.any { forum.contains(it, true) }) return@mapNotNull null
            val topic = Regex("class=\"genmed topictitle\" href=\"viewtopic\\.php\\?t=(\\d+)\">(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(row)
                ?: return@mapNotNull null
            val bytes = Regex("<u>(\\d+)</u>\\s*[^<]*?</td>").find(row)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            fun count(cls: String) = Regex("class=\"$cls\"><b>(\\d+)</b>").find(row)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Torrent(text(topic.groupValues[2]), size(bytes), count("seedmed"), count("leechmed"), nnm() + "viewtopic.php?t=" + topic.groupValues[1], "NNM-Club")
        }
    }

    private fun vp() = RemoteConfig.base("videoplenka").trimEnd('/')

    /**
     * Videoplenka (lafa.site): the search lists film pages, a film page carries all its releases in one table —
     * the magnet hash sits in the mylinks link, so no extra request is needed. Windows-1251 site.
     */
    private fun videoplenka(name: String): List<Torrent> {
        val wanted = words(name).filter { it.length >= 3 }.toSet()
        val search = Sources.get("${vp()}/torrentz/search/${Sources.encode(name)}/", windows1251)
        val films = Regex("<div\\s+class=\"c_title\"><a href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            .findAll(search).mapNotNull { m ->
                val href = m.groupValues[1]
                val title = htmlText(m.groupValues[2]).ifEmpty { return@mapNotNull null }
                val have = words(title).toSet()
                if (wanted.isNotEmpty() && wanted.count { it in have } * 2 < wanted.size) null
                else (if (href.startsWith("http")) href else vp() + href) to title
            }.distinctBy { it.first }.take(3).toList()
        return films.flatMap { (url, title) ->
            runCatching { videoplenkaPage(Sources.get(url, windows1251), title) }.getOrDefault(emptyList())
        }
    }

    private fun videoplenkaPage(html: String, filmTitle: String): List<Torrent> {
        return Regex("<tr(?![^>]*expand-child)[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(html).mapNotNull { m ->
            val row = m.groupValues[1]
            val hash = Regex("mylinks\\.ws/m/125/([0-9A-Fa-f]{40})").find(row)?.groupValues?.get(1) ?: return@mapNotNull null
            val id = Regex("data-id=\"(\\d+)\"").find(row)?.groupValues?.get(1) ?: return@mapNotNull null
            val translation = Regex("class=\"dl_trad\">([^<]*)<").find(row)?.groupValues?.get(1)?.trim().orEmpty()
            val format = Regex("<td width=\"10%\" align=\"center\">([A-Za-z0-9]{2,5})</td>").find(row)?.groupValues?.get(1).orEmpty()
            val bytes = Regex("data-sort-value=\"(\\d+)\"").find(row)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val seeds = Regex("seeders_$id\">(\\d+)<").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val leeches = Regex("leechers_$id\">(\\d+)<").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val note = listOf(translation, format).filter { it.isNotEmpty() }.joinToString(", ")
            Torrent("$filmTitle • $note", if (bytes > 0) size(bytes) else "", seeds, leeches, magnet(hash, "$filmTitle $note"), "Videoplenka")
        }.toList()
    }

    /**
     * Bitsearch / SolidTorrents: a public index with magnet links in the HTML. Movies and TV are asked
     * separately (categories 2 and 3), so software and games that share a word with the title stay out.
     */
    private fun bitsearch(query: String): List<Torrent> =
        listOf(2, 3).flatMap { category -> bitsearchCategory(query, category) }
            .distinctBy { it.magnet }

    private fun bitsearchCategory(query: String, category: Int): List<Torrent> {
        val html = Sources.get(RemoteConfig.url("bitsearch", "/search?q=${Sources.encode(query)}&category=$category"))
        return html.split("<div class=\"torrent-result-card").drop(1).take(30).mapNotNull { block ->
            val title = Regex("<h3[^>]*>\\s*<a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)
                ?.let { htmlText(it) }.orEmpty()
            val raw = Regex("href=\"(magnet:[^\"]+)\"").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val magnetLink = raw.replace("&#x3D;", "=").replace("&#61;", "=").replace("&amp;", "&")
            if (title.isEmpty() || !magnetLink.contains("urn:btih:")) return@mapNotNull null
            val body = block.substringBefore("Mobile Download Links")
            val sizeText = Regex("fa-download[^>]*>\\s*</i>\\s*<span>([^<]+)</span>").find(body)?.groupValues?.get(1).orEmpty()
            val seeds = Regex("text-green-600[^>]*>.*?font-medium\">(\\d+)</span>", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val leeches = Regex("text-red-600[^>]*>.*?font-medium\">(\\d+)</span>", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Torrent(title, sizeText, seeds, leeches, magnetLink, "Bitsearch")
        }
    }

    /**
     * Experimental Searchtor: a plain GET of /r/<phrase>, at most a few redirects, no challenge solving and no
     * special headers. If Cloudflare answers with a page, the usual magnet links are taken from it; if it answers
     * with a redirect loop or a challenge, the source simply adds nothing.
     */
    private fun searchtor(query: String): List<Torrent> {
        var current = "https://searchtor.to/r/" + Sources.encode(query)
        var html: String? = null
        for (i in 0 until 4) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            val code = runCatching { connection.responseCode }.getOrElse { connection.disconnect(); return emptyList() }
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: run { connection.disconnect(); return emptyList() }
                val next = URL(URL(current), location).toString()
                connection.disconnect()
                if (next == current) return emptyList()
                current = next
                continue
            }
            if (code in 200..299) {
                html = runCatching { connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }
                    .getOrElse { connection.disconnect(); return emptyList() }
                connection.disconnect()
                break
            }
            connection.disconnect()
            return emptyList()
        }
        val page = html ?: return emptyList()
        val magnetLink = Regex("magnet:\\?xt=urn:btih:([0-9A-Za-z]{32,40})[^\"'<>\\s]*")
        return magnetLink.findAll(page).mapNotNull { match ->
            val before = page.substring(maxOf(0, match.range.first - 800), match.range.first)
            val title = Regex("<a[^>]*>([^<]{4,})</a>").findAll(before).lastOrNull()
                ?.groupValues?.get(1)?.let { htmlText(it) }.orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val size = Regex("([0-9]+(?:[.,][0-9]+)?\\s*(?:TB|GB|MB|KB|ТБ|ГБ|МБ|КБ))", RegexOption.IGNORE_CASE)
                .find(before)?.value.orEmpty()
            val seeds = Regex("(?:seeders?|сид[а-я]*)[^0-9]{0,20}(\\d+)", RegexOption.IGNORE_CASE)
                .find(before)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            Torrent(title, size, seeds, 0, match.value.replace("&amp;", "&"), "Searchtor")
        }.distinctBy { it.magnet }.toList()
    }

    /** The magnet link of a row: ready for most sources, taken from the topic page for NNM-Club, from a .torrent file for Jackett. */
    fun magnetOf(torrent: Torrent): String {
        if (torrent.magnet.startsWith("magnet:")) return torrent.magnet
        if (torrent.magnet.contains("/api/sources/")) return JSONObject(Sources.get(torrent.magnet)).getString("magnetUrl")
        val bytes = Sources.getBytes(torrent.magnet)
        Magnet.infoHash(bytes)?.let { return magnet(it, torrent.title) }
        val page = bytes.toString(windows1251)
        val hash = Regex("magnet:\\?xt=urn:btih:([0-9A-Za-z]+)").find(page)?.groupValues?.get(1)
            ?: error("на странице раздачи нет magnet-ссылки")
        return magnet(hash, torrent.title)
    }

    private fun octopusPage(html: String): List<Torrent> {
        fun cell(block: String, cls: String) =
            Regex("<div class=\"$cls\">([^<]*)</div>").find(block)?.groupValues?.get(1)?.trim().orEmpty()
        return html.split("class=\"torrent\"").drop(1).mapNotNull { block ->
            val magnet = Regex("href=\"(magnet:[^\"]+)\"").find(block)?.groupValues?.get(1)?.replace("&amp;", "&") ?: return@mapNotNull null
            val title = cell(block, "info_d1").removeSuffix(".torrent")
            if (title.isEmpty()) return@mapNotNull null
            Torrent(title, cell(block, "info_d-size").replace("Gb", "GB").replace("Mb", "MB"), 0, 0, magnet, "Octopus")
        }
    }

    /** YTS through its open JSON API: a movie carries one entry per quality, the magnet is built from the hash. */
    private fun yts(query: String): List<Torrent> {
        val obj = JSONObject(Sources.get(RemoteConfig.url("yts", "/api/v2/list_movies.json?query_term=${Sources.encode(query)}&limit=20")))
        val movies = obj.optJSONObject("data")?.optJSONArray("movies") ?: return emptyList()
        val result = ArrayList<Torrent>()
        for (i in 0 until movies.length()) {
            val movie = movies.getJSONObject(i)
            val name = movie.optString("title_long").ifEmpty { movie.optString("title") }
            val torrents = movie.optJSONArray("torrents") ?: continue
            for (j in 0 until torrents.length()) {
                val t = torrents.getJSONObject(j)
                val hash = t.optString("hash")
                if (hash.isEmpty()) continue
                val quality = t.optString("quality")
                val title = if (quality.isEmpty()) name else "$name [$quality]"
                val bytes = t.optLong("size_bytes")
                result += Torrent(
                    title, if (bytes > 0) size(bytes) else t.optString("size"),
                    t.optInt("seeds"), t.optInt("peers"), magnet(hash, title), "YTS",
                )
            }
        }
        return result
    }

    /** The Pirate Bay through its public JSON interface. */
    private fun pirateBay(query: String): List<Torrent> {
        val array = JSONArray(Sources.get(RemoteConfig.url("tpb", "/q.php?q=${Sources.encode(query)}&cat=200")))
        return (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val hash = o.optString("info_hash")
            if (o.optString("id") == "0" || hash.length < 32 || hash.all { it == '0' }) return@mapNotNull null
            val name = o.optString("name")
            Torrent(name, size(o.optLong("size")), o.optInt("seeders"), o.optInt("leechers"), magnet(hash, name), "TPB")
        }
    }

    /** Nyaa (anime) through its RSS feed. */
    private fun nyaa(query: String): List<Torrent> {
        val rss = Sources.get(RemoteConfig.url("nyaa", "/?page=rss&q=${Sources.encode(query)}&s=seeders&o=desc"))
        val item = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
        fun tag(block: String, name: String) =
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim().orEmpty()
        return item.findAll(rss).take(30).mapNotNull { m ->
            val block = m.groupValues[1]
            val hash = tag(block, "nyaa:infoHash")
            if (hash.isEmpty()) return@mapNotNull null
            val name = tag(block, "title").replace("<![CDATA[", "").replace("]]>", "")
                .replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
            Torrent(name, tag(block, "nyaa:size").replace("MiB", "MB").replace("GiB", "GB").replace("KiB", "KB"),
                tag(block, "nyaa:seeders").toIntOrNull() ?: 0, tag(block, "nyaa:leechers").toIntOrNull() ?: 0, magnet(hash, name), "Nyaa")
        }.toList()
    }
}
