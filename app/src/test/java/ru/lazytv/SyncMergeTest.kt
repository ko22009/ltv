package ru.lazytv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The merge decides what one device keeps and what it takes from the other: a mistake here loses bookmarks silently. */
class SyncMergeTest {
    private fun title(id: Long, tv: Boolean = false, folder: String = "favorites", name: String = "Фильм $id") =
        JSONObject().put("id", id).put("tv", tv).put("name", name).put("folder", folder)

    private fun doc(
        favorites: List<JSONObject> = emptyList(),
        watched: List<String> = emptyList(),
        history: List<JSONObject> = emptyList(),
        search: List<String> = emptyList(),
        settings: Map<String, String> = emptyMap(),
    ) = SyncMerge.Doc(SyncMerge.titles(favorites), SyncMerge.terms(watched), SyncMerge.titles(history),
        SyncMerge.terms(search), settings)

    private fun keys(list: List<SyncMerge.Item>) = list.map { it.key }

    @Test
    fun `first link keeps both devices`() {
        val merged = SyncMerge.merge(SyncMerge.Doc(), doc(favorites = listOf(title(1))), doc(favorites = listOf(title(2))))
        assertEquals(listOf("false:1", "false:2"), keys(merged.favorites))
    }

    @Test
    fun `deletion on one device sticks although the other device still has it`() {
        val agreed = doc(favorites = listOf(title(1), title(2)))
        val mine = doc(favorites = listOf(title(2)))
        val merged = SyncMerge.merge(agreed, mine, agreed)
        assertEquals(listOf("false:2"), keys(merged.favorites))
    }

    @Test
    fun `a new record of the other device comes in`() {
        val agreed = doc(favorites = listOf(title(1)))
        val theirs = doc(favorites = listOf(title(1), title(3, tv = true)))
        val merged = SyncMerge.merge(agreed, agreed, theirs)
        assertEquals(listOf("false:1", "true:3"), keys(merged.favorites))
    }

    @Test
    fun `changing the folder of a title moves it`() {
        val agreed = doc(favorites = listOf(title(1)))
        val mine = doc(favorites = listOf(title(1, folder = "done")))
        val merged = SyncMerge.merge(agreed, mine, agreed)
        assertEquals("done", JSONObject(merged.favorites.single().json).optString("folder"))
    }

    @Test
    fun `the device doing the sync wins a real conflict`() {
        val agreed = doc(favorites = listOf(title(1)))
        val mine = doc(favorites = listOf(title(1, folder = "later")))
        val theirs = doc(favorites = listOf(title(1, folder = "done")))
        val merged = SyncMerge.merge(agreed, mine, theirs)
        assertEquals("later", JSONObject(merged.favorites.single().json).optString("folder"))
    }

    @Test
    fun `an unmarked watched title stays unmarked`() {
        val agreed = doc(watched = listOf("false:1", "false:2"))
        val mine = doc(watched = listOf("false:2"))
        val merged = SyncMerge.merge(agreed, mine, agreed)
        assertEquals(listOf("false:2"), merged.watched.map { it.key })
    }

    @Test
    fun `the order of history is the local one first`() {
        val agreed = doc(history = listOf(title(1)))
        val mine = doc(history = listOf(title(2), title(1)))
        val merged = SyncMerge.merge(agreed, mine, doc(history = listOf(title(3), title(1))))
        assertEquals(listOf("false:2", "false:1", "false:3"), keys(merged.history))
    }

    @Test
    fun `history does not grow past its limit`() {
        val many = (1L..130L).map { title(it) }
        val merged = SyncMerge.merge(SyncMerge.Doc(), doc(history = many), doc(history = many), historyLimit = 100)
        assertEquals(100, merged.history.size)
    }

    @Test
    fun `settings take the changed side only`() {
        val agreed = mapOf("quality" to "{\"s\":\"best\"}", "language" to "{\"s\":\"ru-RU\"}")
        val mine = mapOf("quality" to "{\"s\":\"720\"}", "language" to "{\"s\":\"ru-RU\"}")
        val theirs = mapOf("quality" to "{\"s\":\"best\"}", "language" to "{\"s\":\"en-US\"}")
        val merged = SyncMerge.merge(doc(settings = agreed), doc(settings = mine), doc(settings = theirs))
        assertEquals("720", JSONObject(merged.settings.getValue("quality")).optString("s"))
        assertEquals("en-US", JSONObject(merged.settings.getValue("language")).optString("s"))
    }

    @Test
    fun `the document survives a round trip`() {
        val original = doc(
            favorites = listOf(title(1, folder = "later")),
            watched = listOf("true:1399"),
            history = listOf(title(2, tv = true)),
            search = listOf("матрица"),
            settings = mapOf("show_adult" to "{\"b\":false}"),
        )
        val parsed = SyncMerge.parse(SyncMerge.json(original, System.currentTimeMillis()).toString())
        assertEquals(keys(original.favorites), keys(parsed.favorites))
        assertEquals("later", JSONObject(parsed.favorites.single().json).optString("folder"))
        assertEquals(listOf("true:1399"), parsed.watched.map { it.key })
        assertEquals(listOf("матрица"), parsed.search.map { it.key })
        assertEquals("{\"b\":false}", parsed.settings.getValue("show_adult"))
        assertFalse(parsed.settings.getValue("show_adult").contains("\"s\""))
    }

    @Test
    fun `an empty or broken document is not a reason to fail`() {
        assertTrue(SyncMerge.parse(null).favorites.isEmpty())
        assertTrue(SyncMerge.parse("").history.isEmpty())
        assertTrue(SyncMerge.parse("{это не json").search.isEmpty())
    }

    private fun progress(pos: Long, at: Long) = SyncMerge.Doc(
        progress = SyncMerge.titles(listOf(JSONObject().put("id", 7L).put("tv", false).put("pos", pos).put("at", at))))

    @Test
    fun `the film left last wins, not the device doing the sync`() {
        val agreed = progress(1000, 1)
        // the box has an older place, the phone a newer one: after the sync the box continues from the phone's minute
        val merged = SyncMerge.merge(agreed, progress(2000, 5), progress(9000, 8))
        assertEquals(9000L, JSONObject(merged.progress.single().json).getLong("pos"))
        // and the other way round: the local one is newer
        val mine = SyncMerge.merge(agreed, progress(9000, 8), progress(2000, 5))
        assertEquals(9000L, JSONObject(mine.progress.single().json).getLong("pos"))
    }

    @Test
    fun `a film finished on one device leaves the other one`() {
        val agreed = progress(1000, 1)
        val merged = SyncMerge.merge(agreed, SyncMerge.Doc(), agreed)
        assertTrue(merged.progress.isEmpty())
    }
}
