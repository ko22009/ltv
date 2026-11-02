package ru.lazytv

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a source answered, and the address that answered it: links inside the answer are built from [base]. */
data class Fetched<T>(val value: T, val base: String)

/**
 * Addresses of the video sites and trackers, kept in a small JSON file on the network.
 *
 * Sites change domains and throw up mirrors; with the addresses compiled in, every such move needs a new build and a
 * manual install on the TV box. Here the file is downloaded once, kept in preferences and used before the built-in
 * addresses, so fixing a move is editing one line of JSON.
 *
 * The file (any HTTPS address that returns it, a gist included; schema and an example in `docs/remote-config.md`):
 * `{"addresses": {"anilibria": {"base": "https://anilibria.top", "alt": ["https://mirror.example"]}}}`
 * Entries are optional: an id that is not named keeps its built-in address. A manually set address in the settings
 * always wins, and the built-in one is tried when both the file and the settings are silent.
 */
object RemoteConfig {
    private const val PREFS = "remote"
    private const val JSON = "json"
    private const val AT = "at"

    /** The address that worked when the source was added, then the spare ones: tried in this order. */
    private val builtIn = linkedMapOf(
        "anilibria" to listOf("https://anilibria.top"),
        "seasonvar" to listOf("https://seasonvar.ru"),
        "rutube" to listOf("https://rutube.ru"),
        "rutor" to listOf("rutor.info", "rutor.is", "rutor.lib"),
        "octopus" to listOf("https://045.ultradox.click", "https://ultadox.space"),
        "torrentkino" to listOf("https://torrentkino.org"),
        "nnm" to listOf("https://nnmclub.to/forum/"),
        "videoplenka" to listOf("https://lafa.site"),
        "bitsearch" to listOf("https://bitsearch.to"),
        "yts" to listOf("https://yts.am"),
        "tpb" to listOf("https://apibay.org"),
        "nyaa" to listOf("https://nyaa.si"),
        "jacred" to listOf("https://jr.maxvol.pro", "https://jacred.freebie.tom.ru", "https://jac.stull.xyz"),
    )

    @Volatile
    private var app: Context? = null

    /** Called once from [LazyApp]: the addresses are asked for from screens that have no context of their own. */
    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    private fun prefs() = app?.let { prefs(it) }

    /** "site.example" -> "https://site.example"; an address that already carries a scheme is left alone. */
    private fun withScheme(address: String): String {
        val text = address.trim().trimEnd('/')
        if (text.isEmpty()) return text
        return if (text.contains("://")) text else "https://$text"
    }

    private fun document(): JSONObject? = prefs()?.getString(JSON, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Addresses of [id] from the downloaded file: the main one first, then the spares; null when it is not named there. */
    private fun fromRemote(id: String): List<String>? {
        val entry = document()?.optJSONObject("addresses")?.optJSONObject(id) ?: return null
        val alt = entry.optJSONArray("alt")?.let { array ->
            (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotEmpty() }
        }.orEmpty()
        val list = (listOf(entry.optString("base").trim()) + alt).filter { it.isNotEmpty() }.map(::withScheme)
        return list.ifEmpty { null }
    }

    /** The address the user typed for [id] in the settings; empty when there is none. */
    private fun manual(id: String): String = app?.let { Settings.string(it, Settings.baseKey(id), "").trim() }.orEmpty()

    /** Every address of [id], in the order they are tried: the setting, then the file, then the built-in list. */
    fun bases(id: String): List<String> {
        val manual = manual(id)
        if (manual.isNotEmpty()) return listOf(withScheme(manual))
        fromRemote(id)?.let { return it }
        return builtIn[id].orEmpty().map(::withScheme).filter { it.isNotEmpty() }
    }

    fun base(id: String): String = bases(id).firstOrNull().orEmpty()

    /** An address of a site: [id]'s base plus [path], with or without the leading slash. */
    fun url(id: String, path: String): String = base(id).trimEnd('/') + "/" + path.trimStart('/')

    /**
     * Runs [block] with the address of [id] and moves to the next one when it throws (a dead domain, a refused
     * connection, an empty body that is not valid JSON). The first answer that came through is returned with its
     * address, so links inside it point to the host that actually answered.
     */
    fun <T> attempt(id: String, block: (String) -> T): Fetched<T> {
        var last: Throwable? = null
        for (address in bases(id)) {
            try {
                return Fetched(block(address), address)
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("Нет адреса для источника «$id»")
    }

    fun configured(context: Context): String = Settings.string(context, Settings.REMOTE_URL, "").trim()

    /**
     * Downloads the file and keeps it for the next start; the previous file stays in place when the download fails.
     * Returns the line to show to the user.
     */
    fun refresh(context: Context): String {
        val address = configured(context)
        if (address.isEmpty()) return "Адрес конфига не задан: работают встроенные адреса"
        val text = Sources.get(address)
        val doc = JSONObject(text)
        val addresses = doc.optJSONObject("addresses") ?: JSONObject()
        val known = addresses.keys().asSequence().filter { addresses.optJSONObject(it) != null }.toList()
        if (known.isEmpty()) return "В конфиге нет ни одного адреса"
        prefs(context).edit().putString(JSON, text).putLong(AT, System.currentTimeMillis()).apply()
        return "Адреса обновлены: ${known.size} (${known.joinToString(", ")})"
    }

    /** On start: quietly renew a file older than a day; a failure keeps the previous one and is not shown. */
    fun refreshIfStale(context: Context) {
        if (configured(context).isEmpty()) return
        val at = prefs(context).getLong(AT, 0L)
        if (System.currentTimeMillis() - at < 24 * 60 * 60 * 1000L) return
        runCatching { refresh(context) }
    }

    fun loadedAt(context: Context): Long = prefs(context).getLong(AT, 0L)

    /** How many ids the downloaded file names. */
    fun remoteCount(): Int = document()?.optJSONObject("addresses")?.length() ?: 0

    /** What the settings screen says about the current addresses. */
    fun status(context: Context): String {
        val count = remoteCount()
        val at = loadedAt(context)
        if (count == 0) return "Встроенные адреса"
        return "Из конфига: $count, обновлён " + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(at))
    }

    /** Drops the addresses typed by hand: the file and the built-in list take over again. */
    fun resetManual(context: Context) {
        builtIn.keys.forEach { Settings.remove(context, Settings.baseKey(it)) }
    }
}
