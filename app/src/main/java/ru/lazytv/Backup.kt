package ru.lazytv

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A copy of everything the user would hate to lose: bookmark folders, history, watched marks, favourite episodes,
 * search history and the settings themselves (parental code and hand-typed addresses included). All of it lives in
 * the app's preferences only, so a reinstall or "clear data" wipes it; the file can be kept somewhere else.
 *
 * The settings cache ("cache", six-hour list of found sources) is not copied: it is a cache, not data.
 */
object Backup {
    private const val FORMAT = 1
    private const val APP = "LazyTV"
    private const val LAST_AT = "last_backup_at"
    private const val MONTH_MS = 30L * 24 * 60 * 60 * 1000L

    /** Preferences that hold data; the same names are read back on restore. */
    private val files = listOf("lazytv", "episodes", "remote", "positions", "catalog_filters")

    fun backupName(): String =
        "LazyTV-backup-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".json"

    private fun rememberBackup(context: Context) {
        context.getSharedPreferences("lazytv", 0).edit().putLong(LAST_AT, System.currentTimeMillis()).apply()
    }

    /** True when a copy was made at least once: by the app or found in the default folder. */
    fun exists(context: Context): Boolean =
        context.getSharedPreferences("lazytv", 0).getLong(LAST_AT, 0L) > 0L || latestDefault(context) != null

    /** Status line for the settings screen, with a gentle reminder when no copy was made for over a month. */
    fun status(context: Context): String {
        val at = context.getSharedPreferences("lazytv", 0).getLong(LAST_AT, 0L)
            .takeIf { it > 0L } ?: latestDefault(context)?.lastModified() ?: 0L
        if (at <= 0L) return "Ещё не создавалась. Сохраните копию на случай переустановки"
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(at))
        return if (System.currentTimeMillis() - at > MONTH_MS) {
            "Давно не обновлялась ($date). Сохраните свежую копию"
        } else {
            "Последняя копия: $date"
        }
    }

    /** Everything as one JSON text: `{"app": "LazyTV", "prefs": {"lazytv": {ключ: {"s": "значение"}}}}`. */
    fun toJson(context: Context): String {
        val prefs = JSONObject()
        for (name in files) {
            val one = JSONObject()
            context.getSharedPreferences(name, 0).all.forEach { (key, value) ->
                val encoded = when (value) {
                    is Boolean -> JSONObject().put("b", value)
                    is Int -> JSONObject().put("i", value)
                    is Long -> JSONObject().put("l", value)
                    is Float -> JSONObject().put("f", value.toDouble())
                    is String -> JSONObject().put("s", value)
                    is Set<*> -> JSONObject().put("a", JSONArray(value.map { it.toString() }))
                    else -> null
                }
                if (encoded != null) one.put(key, encoded)
            }
            prefs.put(name, one)
        }
        return JSONObject()
            .put("app", APP).put("format", FORMAT).put("version", BuildConfig.VERSION_NAME)
            .put("created", System.currentTimeMillis()).put("prefs", prefs)
            .toString(2)
    }

    /** Replaces the data with the file's content; returns how many values were written. */
    fun restore(context: Context, json: String): Int {
        val root = JSONObject(json)
        require(root.optString("app") == APP) { "Это не файл LazyTV" }
        require(root.optInt("format", 0) <= FORMAT) { "Файл от более новой версии приложения" }
        val prefs = root.optJSONObject("prefs") ?: return 0
        var count = 0
        for (name in files) {
            val one = prefs.optJSONObject(name) ?: continue
            val editor = context.getSharedPreferences(name, 0).edit().clear()
            one.keys().forEach { key ->
                val value = one.optJSONObject(key) ?: return@forEach
                when {
                    value.has("b") -> editor.putBoolean(key, value.optBoolean("b"))
                    value.has("i") -> editor.putInt(key, value.optInt("i"))
                    value.has("l") -> editor.putLong(key, value.optLong("l"))
                    value.has("f") -> editor.putFloat(key, value.optDouble("f").toFloat())
                    value.has("s") -> editor.putString(key, value.optString("s"))
                    value.has("a") -> {
                        val array = value.optJSONArray("a") ?: JSONArray()
                        editor.putStringSet(key, (0 until array.length()).map { array.optString(it) }.toSet())
                    }
                    else -> return@forEach
                }
                count++
            }
            editor.apply()
        }
        return count
    }

    fun write(context: Context, uri: Uri, json: String) {
        val stream = context.contentResolver.openOutputStream(uri) ?: error("Файл недоступен для записи")
        stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        rememberBackup(context)
    }

    fun read(context: Context, uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri) ?: error("Файл недоступен для чтения")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /**
     * Where the copy goes when the device has no file picker (some TV boxes have none): the app's own folder.
     * It is readable over adb, but disappears with the app on uninstall — the picker is the better way.
     */
    fun writeDefault(context: Context, json: String): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, backupName()).apply { writeText(json) }.also { rememberBackup(context) }
    }

    /** The newest copy made by [writeDefault], if there is one. */
    fun latestDefault(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return dir.listFiles { file -> file.isFile && file.name.startsWith("LazyTV-backup-") && file.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() }
    }
}
