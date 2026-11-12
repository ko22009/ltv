package ru.lazytv

import org.json.JSONArray
import org.json.JSONObject

/**
 * The part of the sync that does not touch Android: the document both devices exchange and the merge itself.
 * Kept apart from [Sync] so a plain JVM test can check the merge (app/src/test).
 *
 * The document is one JSON with lists of records; every record has a key (a title, a search term), so records of the
 * two devices are matched. Nothing here needs the network or the settings.
 */
object SyncMerge {
    /** One record: its key and the text that goes to the other device. */
    data class Item(val key: String, val json: String)

    class Doc(
        /** Bookmark folders: the whole title, the folder is inside. Order is newest first. */
        val favorites: List<Item> = emptyList(),
        /** Watched marks: "false:603" (film) or "true:1399" (series). */
        val watched: List<Item> = emptyList(),
        /** Opening history, newest first. */
        val history: List<Item> = emptyList(),
        /** Search terms, newest first. */
        val search: List<Item> = emptyList(),
        /** Setting key to its encoded value: `{"b":true}` or `{"s":"ru-RU"}`. */
        val settings: Map<String, String> = emptyMap(),
        /** Where a film was left: id, name, poster, year, position, duration, `at` (when). Key as titles. */
        val progress: List<Item> = emptyList(),
    )

    fun titleKey(o: JSONObject) = o.optBoolean("tv").toString() + ":" + o.optLong("id")

    fun titles(list: List<JSONObject>) = list.map { Item(titleKey(it), it.toString()) }

    fun terms(list: List<String>) = list.map { Item(it, it) }

    fun parse(text: String?): Doc {
        if (text.isNullOrBlank()) return Doc()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return Doc()
        return Doc(
            favorites = items(root.optJSONArray("favorites")),
            watched = plain(root.optJSONArray("watched")),
            history = items(root.optJSONArray("history")),
            search = plain(root.optJSONArray("search")),
            settings = settings(root.optJSONObject("settings")),
            progress = items(root.optJSONArray("progress")),
        )
    }

    fun json(doc: Doc, updated: Long = 0L): JSONObject {
        val root = JSONObject().put("format", 1)
        if (updated > 0L) root.put("updated", updated)
        root.put("favorites", objects(doc.favorites))
        root.put("watched", JSONArray().apply { doc.watched.forEach { put(it.key) } })
        root.put("history", objects(doc.history))
        // search terms are plain strings, not objects: the list is short and stays readable in the file
        root.put("search", JSONArray().apply { doc.search.forEach { put(it.key) } })
        val values = JSONObject()
        doc.settings.toSortedMap().forEach { (key, value) -> values.put(key, JSONObject(value)) }
        root.put("settings", values)
        root.put("progress", objects(doc.progress))
        return root
    }

    /**
     * Three-way merge against [base], the copy both devices last agreed on: a value changed on one device wins over the
     * unchanged value of the other. When both changed the same record in different ways, the device doing the sync wins
     * (`упрощено:` no per-record timestamps, which would mean stamping every bookmark, mark and search on every change).
     */
    fun merge(base: Doc, local: Doc, remote: Doc, historyLimit: Int = 100, searchLimit: Int = 20) = Doc(
        favorites = mergeList(base.favorites, local.favorites, remote.favorites),
        watched = mergeList(base.watched, local.watched, remote.watched),
        history = mergeList(base.history, local.history, remote.history).take(historyLimit),
        search = mergeList(base.search, local.search, remote.search).take(searchLimit),
        settings = mergeSettings(base.settings, local.settings, remote.settings),
        progress = mergeProgress(base.progress, local.progress, remote.progress),
    )

    /**
     * Like [mergeList], but when both devices moved the same film the later `at` wins: the place the person left last
     * is the one to continue from (a stale local record must not beat a newer one from the phone).
     */
    private fun mergeProgress(base: List<Item>, local: List<Item>, remote: List<Item>): List<Item> {
        val mine = local.associate { it.key to it.json }
        val theirs = remote.associate { it.key to it.json }
        fun at(json: String?) = json?.let { runCatching { JSONObject(it).optLong("at") }.getOrDefault(0L) } ?: 0L
        val settled = mergeList(base, local, remote)
        return settled.map { item ->
            val l = mine[item.key]
            val r = theirs[item.key]
            if (l != null && r != null && l != r) Item(item.key, if (at(r) > at(l)) r else l) else item
        }
    }

    /** Records merged by key; the local order first, then what only the other device has. Deletions on either side stick. */
    private fun mergeList(base: List<Item>, local: List<Item>, remote: List<Item>): List<Item> {
        val agreed = base.associate { it.key to it.json }
        val mine = LinkedHashMap<String, String>().apply { local.forEach { put(it.key, it.json) } }
        val theirs = LinkedHashMap<String, String>().apply { remote.forEach { put(it.key, it.json) } }
        val merged = LinkedHashMap<String, String>()
        for (key in LinkedHashSet(mine.keys + theirs.keys)) {
            val l = mine[key]
            val r = theirs[key]
            val b = agreed[key]
            val taken = when {
                l == r -> l
                l == b -> r
                r == b -> l
                else -> l
            }
            if (taken != null) merged[key] = taken
        }
        val out = ArrayList<Item>()
        for (key in mine.keys) merged.remove(key)?.let { out.add(Item(key, it)) }
        for (key in theirs.keys) merged.remove(key)?.let { out.add(Item(key, it)) }
        return out
    }

    private fun mergeSettings(base: Map<String, String>, local: Map<String, String>, remote: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (key in LinkedHashSet(local.keys + remote.keys)) {
            val l = local[key]
            val r = remote[key]
            val b = base[key]
            val taken = when {
                l == r -> l
                l == b -> r
                r == b -> l
                else -> l
            }
            if (taken != null) out[key] = taken
        }
        return out
    }

    private fun items(array: JSONArray?): List<Item> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { Item(titleKey(it), it.toString()) }
        }
    }

    private fun objects(list: List<Item>) = JSONArray().apply { list.forEach { put(JSONObject(it.json)) } }

    /** Plain-string lists (watched marks, search terms): the text itself is the key. */
    private fun plain(array: JSONArray?): List<Item> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optString(i).takeIf { it.isNotEmpty() }?.let { Item(it, it) }
        }
    }

    private fun settings(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { key -> o.optJSONObject(key)?.toString()?.let { out[key] = it } }
        return out
    }
}
