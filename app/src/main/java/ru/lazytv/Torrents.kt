package ru.lazytv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Torrent(val title: String, val size: String, val seeds: Int, val leeches: Int, val magnet: String, val source: String = "Rutor")

/**
 * Identity used to collapse the same release found on several trackers: the BitTorrent hash from the magnet
 * when there is one, otherwise the page address and the title. The same hash with different tracker lists or
 * titles stays one row.
 */
fun Torrent.identity(): String {
    val hash = Regex("btih:([0-9A-Za-z]+)", RegexOption.IGNORE_CASE).find(magnet)?.groupValues?.get(1)?.lowercase()
    if (hash != null) return "btih:$hash"
    val cleanTitle = title.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    val mb = TorrentOrder.sizeBytes(size) shr 20
    return if (mb > 0) "title:$cleanTitle|$mb" else "url:${magnet.trim()}|$cleanTitle"
}

/** How the torrent list is ordered; the choice is kept in [Settings.TORRENT_SORT]. */
enum class TorrentSort(val key: String, val label: String) {
    SEEDS("seeds", "По сидам"),
    SIZE_DESC("size_desc", "По размеру: большие сверху"),
    SIZE_ASC("size_asc", "По размеру: малые сверху");

    fun next() = values()[(ordinal + 1) % values().size]

    companion object {
        fun of(key: String) = values().firstOrNull { it.key == key } ?: SEEDS
    }
}

/** Ordering and filtering of a torrent list: size sort, seedless releases last, exact title match. */
object TorrentOrder {
    private val sizePart = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*(TB|GB|MB|KB|ТБ|ГБ|МБ|КБ)", RegexOption.IGNORE_CASE)

    /** "6.96 GB" -> bytes; 0 when the size is missing or unreadable. */
    fun sizeBytes(size: String): Long {
        val match = sizePart.find(size.replace(' ', ' ')) ?: return 0
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0
        val unit = when (match.groupValues[2].uppercase()) {
            "TB", "ТБ" -> 1L shl 40
            "GB", "ГБ" -> 1L shl 30
            "MB", "МБ" -> 1L shl 20
            else -> 1L shl 10
        }
        return (value * unit).toLong()
    }

    private fun words(text: String) = " " + text.lowercase().replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() + " "

    /** The title (Russian and original parts alike) contains the whole query as a phrase of whole words. */
    fun matchesExactly(torrent: Torrent, query: String): Boolean {
        val needle = words(query)
        return needle.isNotBlank() && words(torrent.title).contains(needle)
    }

    /** Releases with no seeders always go to the very bottom, whatever the order. */
    fun arrange(list: List<Torrent>, sort: TorrentSort, exact: Boolean, query: String, minSeeds: Int = 0, voice: String = ""): List<Torrent> {
        val filtered = list.filter {
            it.seeds >= minSeeds && (!exact || matchesExactly(it, query)) && (voice.isEmpty() || voice in TorrentVoice.of(it))
        }
        val ordered = when (sort) {
            TorrentSort.SEEDS -> filtered.sortedByDescending { it.seeds }
            TorrentSort.SIZE_DESC -> filtered.sortedByDescending { sizeBytes(it.size) }
            TorrentSort.SIZE_ASC -> filtered.sortedBy { sizeBytes(it.size) }
        }
        val (alive, dead) = ordered.partition { it.seeds > 0 }
        return alive + dead
    }
}

/**
 * Voice-over (translation) of a release, read from its title: the studio ("LostFilm", "Кубик в кубе") or the kind
 * ("Дубляж", "Многоголосый"). The trackers have no field for it, so this is a list of known names; a release with none
 * of them simply has no voice-over label and shows under "any".
 */
object TorrentVoice {
    private val patterns = listOf(
        "LostFilm" to "lostfilm|лостфильм",
        "NewStudio" to "newstudio|ньюстудио",
        "Кубик в кубе" to "кубик\\s+в\\s+кубе",
        "Jaskier" to "jaskier|яскер",
        "AlexFilm" to "alexfilm|алексфильм",
        "Baibako" to "baibako|байбако",
        "Кураж-Бамбей" to "кураж.?бамбей",
        "ColdFilm" to "coldfilm|колдфильм",
        "HDRezka" to "hdrezka|резка",
        "Amedia" to "amedia|амедиа",
        "SDI Media" to "sdi\\s*media",
        "Пифагор" to "пифагор",
        "Novafilm" to "novafilm|новафильм",
        "Paramount Comedy" to "paramount\\s+comedy",
        "Дубляж" to "дубляж|дублирован|\\bdub\\b",
        "Многоголосый" to "многоголос|\\bmvo\\b",
        "Двухголосый" to "двухголос|\\bdvo\\b",
        "Одноголосый" to "одноголос|\\bavo\\b|\\bvo\\b",
        "Оригинал" to "оригинал|\\boriginal\\b",
        "Субтитры" to "субтитры|\\bsub\\b|\\brus\\s*sub\\b",
    ).map { (name, pattern) -> name to Regex(pattern, RegexOption.IGNORE_CASE) }

    /** The voice-overs named in the title, in the order of the list above. */
    fun of(torrent: Torrent): List<String> = patterns.filter { it.second.containsMatchIn(torrent.title) }.map { it.first }

    /** Voice-overs found in the list, the most frequent first. */
    fun available(list: List<Torrent>): List<String> =
        list.flatMap { of(it) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
}

/** One file of a torrent as TorrServe lists it. */
data class TorrentFile(val id: Int, val path: String, val length: Long)

/** Magnet links: the link text for a hash, and the hash of a .torrent file (SHA-1 of its bencoded "info"). */
object Magnet {
    private val trackers = listOf(
        "udp://tracker.opentrackr.org:1337/announce", "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce", "udp://exodus.desync.com:6969/announce",
    )

    fun link(hash: String, name: String): String =
        "magnet:?xt=urn:btih:$hash&dn=${URLEncoder.encode(name, "UTF-8")}" +
            trackers.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }

    /** Index of [value] at or after [from]; -1 when absent. */
    private fun indexOf(bytes: ByteArray, value: Byte, from: Int = 0): Int {
        for (i in from until bytes.size) if (bytes[i] == value) return i
        return -1
    }

    /** Index right after the bencoded value that starts at [start]; -1 when the data is not bencode. */
    private fun end(bytes: ByteArray, start: Int): Int {
        if (start >= bytes.size) return -1
        return when (bytes[start].toInt().toChar()) {
            'i' -> indexOf(bytes, 'e'.code.toByte(), start + 1).let { if (it < 0) -1 else it + 1 }
            'l', 'd' -> {
                var i = start + 1
                while (i < bytes.size && bytes[i] != 'e'.code.toByte()) {
                    i = end(bytes, i)
                    if (i < 0) return -1
                }
                if (i >= bytes.size) -1 else i + 1
            }
            in '0'..'9' -> {
                val colon = indexOf(bytes, ':'.code.toByte(), start)
                if (colon < 0) return -1
                val length = String(bytes, start, colon - start).toIntOrNull() ?: return -1
                (colon + 1 + length).takeIf { it <= bytes.size } ?: -1
            }
            else -> -1
        }
    }

    /** SHA-1 of the "info" dictionary of a .torrent file, hex; null when the file is not a torrent. */
    fun infoHash(bytes: ByteArray): String? {
        if (bytes.size < 2 || bytes[0] != 'd'.code.toByte()) return null
        var i = 1
        while (i < bytes.size && bytes[i] != 'e'.code.toByte()) {
            val keyEnd = end(bytes, i)
            if (keyEnd < 0) return null
            val colon = indexOf(bytes, ':'.code.toByte(), i)
            if (colon < 0 || colon >= keyEnd) return null
            val key = String(bytes, colon + 1, keyEnd - colon - 1)
            val valueEnd = end(bytes, keyEnd)
            if (valueEnd < 0) return null
            if (key == "info") {
                val digest = java.security.MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(keyEnd, valueEnd))
                return digest.joinToString("") { "%02x".format(it) }
            }
            i = valueEnd
        }
        return null
    }
}

/** Torrent search on rutor mirrors (the page lists magnet links directly); the first mirror that answers is used. */
object TorrentSearch {
    private val row = Regex("<tr class=\"(?:gai|tum)\">(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
    private val magnetLink = Regex("href=\"(magnet:[^\"]+)\"")
    private val titleLink = Regex("<a href=\"/torrent/\\d+[^\"]*\">(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val sizeCell = Regex("<td align=\"right\">([^<]*?)(?:&nbsp;|\\s)*(?:<img[^>]*>)?</td>\\s*<td align=\"center\"")
    private val seedsCell = Regex("<span class=\"green\">.*?&nbsp;(\\d+)</span>", RegexOption.DOT_MATCHES_ALL)
    private val leechCell = Regex("<span class=\"red\">&nbsp;(\\d+)</span>")

    /** Asks all mirrors at once and takes the first answer with results, so one blocked mirror does not delay the list. */
    fun search(query: String): List<Torrent> {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        // mirrors come from the addresses file (or from the settings), the built-in list is the last resort
        val mirrors = RemoteConfig.bases("rutor")
        val answers = java.util.concurrent.LinkedBlockingQueue<Result<List<Torrent>>>()
        mirrors.forEach { mirror ->
            val address = mirror.trimEnd('/') + "/search/0/0/000/0/$encoded"
            Thread { answers.put(runCatching { parse(download(address)) }) }.start()
        }
        var failure: Throwable? = null
        var answered = false
        for (i in mirrors.indices) {
            // a blocked mirror may never answer: wait a little for each and let the others through
            val answer = answers.poll(8, java.util.concurrent.TimeUnit.SECONDS) ?: break
            answer.onSuccess {
                if (it.isNotEmpty()) return it
                answered = true
            }
            answer.onFailure { failure = it }
        }
        // a mirror that answered with an empty list means "nothing found", not an error
        if (!answered) failure?.let { throw it }
        return emptyList()
    }

    fun parse(html: String): List<Torrent> = row.findAll(html).mapNotNull { match ->
        val block = match.groupValues[1]
        val magnet = magnetLink.find(block)?.groupValues?.get(1)?.replace("&amp;", "&") ?: return@mapNotNull null
        val title = titleLink.find(block)?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), "")?.let(::unescape)?.trim() ?: return@mapNotNull null
        val size = sizeCell.find(block)?.groupValues?.get(1)?.replace("&nbsp;", " ")?.trim().orEmpty()
        Torrent(title, size, seedsCell.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            leechCell.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 0, magnet)
    }.toList()

    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")

    private fun download(address: String): String {
        val connection = Connectivity.open(address)
        connection.connectTimeout = 15000
        connection.readTimeout = 25000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Safari/537.36")
        val bytes = connection.inputStream.use {
            ClockCheck.observe(connection.date)
            it.readBytes()
        }
        // the mirrors declare UTF-8 but some pages are windows-1251: use UTF-8 only when the bytes are valid UTF-8
        val strict = Charsets.UTF_8.newDecoder()
        return try {
            strict.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(bytes, charset("windows-1251"))
        }
    }
}

/** Live state of a torrent in TorrServe: what the preload dialog shows. */
data class TorrentStat(
    val status: String, val activePeers: Int, val totalPeers: Int, val seeders: Int,
    val speed: Double, val size: Long, val preloaded: Long, val preloadSize: Long,
)

/** Client of the TorrServe HTTP API: add a torrent, list its files, build the stream address. */
object TorrServe {
    /** Where a user gets TorrServe if it is not installed yet: the releases page of the original project. */
    const val DOWNLOAD = "https://github.com/YouROK/TorrServe/releases"

    private fun base(context: Context) = "http://" + Settings.string(context, Settings.TORRSERVE, "127.0.0.1:8090")

    /** Adds the magnet link and waits until TorrServe knows the file list. */
    fun files(context: Context, magnet: String, title: String): Pair<String, List<TorrentFile>> {
        var hash = ""
        var answer = JSONObject(post("${base(context)}/torrents",
            JSONObject().put("action", "add").put("link", magnet).put("title", title).put("save_to_db", false).toString()))
        repeat(30) {
            hash = answer.optString("hash", hash)
            val stats: JSONArray? = answer.optJSONArray("file_stats")
            if (stats != null && stats.length() > 0) {
                return hash to (0 until stats.length()).map {
                    val f = stats.getJSONObject(it)
                    TorrentFile(f.getInt("id"), f.getString("path"), f.optLong("length"))
                }
            }
            Thread.sleep(2000)
            // adding again on every step would restart the torrent; ask its current state instead
            answer = JSONObject(post("${base(context)}/torrents", JSONObject().put("action", "get").put("hash", hash).toString()))
        }
        throw IllegalStateException("TorrServe не получил список файлов за минуту")
    }

    /** Asks TorrServe to start buffering the file; the request ends when the buffer is filled. */
    fun preload(context: Context, hash: String, file: TorrentFile) {
        val connection = URL(streamUrl(context, hash, file).replace("&play", "&preload")).openConnection() as HttpURLConnection
        connection.readTimeout = 90000
        connection.inputStream.use { it.read() }
    }

    fun stat(context: Context, hash: String): TorrentStat {
        val a = JSONObject(post("${base(context)}/torrents", JSONObject().put("action", "get").put("hash", hash).toString()))
        return TorrentStat(
            a.optString("stat_string"), a.optInt("active_peers"), a.optInt("total_peers"), a.optInt("connected_seeders"),
            a.optDouble("download_speed", 0.0), a.optLong("torrent_size"), a.optLong("preloaded_bytes"), a.optLong("preload_size"),
        )
    }

    fun streamUrl(context: Context, hash: String, file: TorrentFile): String =
        "${base(context)}/stream/" + URLEncoder.encode(file.path.substringAfterLast('/'), "UTF-8").replace("+", "%20") +
            "?link=$hash&index=${file.id}&play"

    private fun post(address: String, body: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
        return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
