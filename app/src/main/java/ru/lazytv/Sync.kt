package ru.lazytv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bookmarks, watch history, watched marks, search history and some settings shared between the person's devices through
 * a folder of their own Google Drive. Nothing passes through the app: the account, the permission and the file belong to
 * the user.
 *
 * The sign-in is Google's code flow for TVs: the app shows a short code, the person types it on a phone, the app polls
 * for the token. A TV box needs no Play Services and no keyboard; the same code works on the phone.
 *
 * The flow accepts a narrow set of scopes: "drive.appdata" is refused with invalid_scope, so the shared file lives in a
 * visible "LazyTV" folder and the scope is "drive.file" (files the app itself made). See docs/sync.md.
 *
 * The client id comes from `local.properties` (see docs/sync.md): Google issues it for this app only, and without it the
 * section is switched off.
 */
object Sync {
    private const val PREFS = "sync"
    private const val BASE = "base"
    private const val REFRESH = "refresh_token"
    private const val ACCESS = "access_token"
    private const val EXPIRES = "access_expires"
    private const val LAST_AT = "last_at"
    private const val FAILED_AT = "failed_at"
    private const val FAILED_TEXT = "failed_text"

    /** Settings key: look for changes quietly on start (on by default after linking). */
    const val AUTO = "sync_auto"

    private const val DEVICE_URL = "https://oauth2.googleapis.com/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val FILES = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    private const val FORM = "application/x-www-form-urlencoded; charset=utf-8"
    private const val FILE_NAME = "lazytv.json"
    /** Visible folder on the person's Drive: the hidden app folder needs a scope the TV sign-in refuses. */
    private const val FOLDER_NAME = "LazyTV"
    private const val FOLDER_TYPE = "application/vnd.google-apps.folder"
    private const val HISTORY_LIMIT = 100
    private const val SEARCH_LIMIT = 20

    /** A start check is skipped when the last one is fresher than this: opening screens back and forth must not mean requests. */
    private const val MIN_GAP_MS = 10 * 60 * 1000L

    private const val PREFS_FAVORITES = "favorites"
    private const val PREFS_HISTORY = "history"
    private const val PREFS_WATCHED = "watched_titles"

    /**
     * Settings that mean the same on every device. Addresses (TorrServe, JacRed, Jackett), the app proxy, the card size
     * and the parental code stay on their device: they describe the device, not the person.
     */
    private val SYNCED_KEYS = setOf(
        Settings.SHOW_ADULT, Settings.SHOW_HORROR, Settings.LANGUAGE, Settings.QUALITY, Settings.VOICE,
        Settings.REMEMBER_POSITION, Settings.AUTO_NEXT, Settings.SOURCE_MODE, Settings.TORRENT_SORT,
        Settings.TORRENT_EXACT, Settings.TORRENT_MIN_SEEDS,
    )

    private fun synced(key: String) = key in SYNCED_KEYS || key.startsWith("service_")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    /** False when the build has no Google client id: the settings section then explains where it comes from. */
    fun configured(context: Context): Boolean = clientId().isNotEmpty()

    fun linked(context: Context): Boolean = prefs(context).getString(REFRESH, null) != null

    private fun clientId() = BuildConfig.DRIVE_CLIENT_ID
    private fun clientSecret() = BuildConfig.DRIVE_CLIENT_SECRET

    /** One line for the settings screen: linked or not, and how the last sync went. */
    fun status(context: Context): String {
        if (!configured(context)) return "Не настроено в сборке: нет ключа Google"
        if (!linked(context)) return "Не подключено. Закладки, история, поиск и настройки остаются на этом устройстве"
        val p = prefs(context)
        val at = p.getLong(LAST_AT, 0L)
        val failed = p.getString(FAILED_TEXT, null)
        val when_ = if (at <= 0L) "Синхронизации ещё не было"
        else "Последняя синхронизация: " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(at))
        return if (failed != null) "Подключено. $when_. Не вышло: $failed" else "Подключено. $when_"
    }

    fun lastSync(context: Context): Long = prefs(context).getLong(LAST_AT, 0L)

    // ---- sign-in -----------------------------------------------------------------------------------------------

    @Volatile private var canceled = false

    /** Stops the code polling of a sign-in the person closed (the code itself loses its power after a while anyway). */
    fun cancel() {
        canceled = true
    }

    /**
     * Starts the code sign-in and polls in the background. [onCode] gets the code to show; [onDone] gets null after the
     * link worked or a short explanation of what went wrong. Both are called on the main thread.
     */
    fun link(context: Context, onCode: (String) -> Unit, onDone: (String?) -> Unit) {
        val app = context.applicationContext
        canceled = false
        Thread {
            val failure = runCatching { poll(app, onCode) }.exceptionOrNull()?.message
            if (!canceled) main { onDone(failure) }
        }.start()
    }

    /** Asks Google for a code, shows it and waits for the person to type it on a phone; stores the tokens on success. */
    private fun poll(context: Context, onCode: (String) -> Unit) {
        val reply = send(DEVICE_URL, "POST", form(mapOf("client_id" to clientId(), "scope" to SCOPE)), FORM, null)
        val body = JSONObject(reply.text.ifEmpty { "{}" })
        if (reply.code !in 200..299) throw IllegalStateException(problem(body, reply.code))
        val device = body.getString("device_code")
        var interval = body.optInt("interval", 5).coerceAtLeast(1)
        val until = System.currentTimeMillis() + body.optLong("expires_in", 1800L) * 1000L
        main { onCode(body.getString("user_code")) }
        while (System.currentTimeMillis() < until) {
            if (canceled) return
            Thread.sleep(interval * 1000L)
            val fields = mutableMapOf(
                "client_id" to clientId(),
                "device_code" to device,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            )
            if (clientSecret().isNotEmpty()) fields["client_secret"] = clientSecret()
            val answer = send(TOKEN_URL, "POST", form(fields), FORM, null)
            val tokens = JSONObject(answer.text.ifEmpty { "{}" })
            when (val error = tokens.optString("error")) {
                "" -> {
                    store(context, tokens)
                    return
                }
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "access_denied" -> throw IllegalStateException("Вход отменён на телефоне")
                "expired_token" -> throw IllegalStateException("Код устарел, начните подключение заново")
                else -> throw IllegalStateException(problem(tokens, answer.code).ifEmpty { error })
            }
        }
        throw IllegalStateException("Код не введён за отведённое время, начните заново")
    }

    private fun store(context: Context, tokens: JSONObject) {
        val edit = prefs(context).edit()
            .putString(ACCESS, tokens.optString("access_token"))
            .putLong(EXPIRES, System.currentTimeMillis() + tokens.optLong("expires_in", 3600L) * 1000)
        tokens.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { edit.putString(REFRESH, it) }
        edit.apply()
    }

    fun unlink(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** A short reason out of a Google answer, without codes and technical names. */
    private fun problem(body: JSONObject, code: Int): String = when (body.optString("error")) {
        "invalid_client" -> "Ключ Google не подходит, проверьте client id и secret"
        "invalid_grant" -> "Вход в Google истёк, подключите заново"
        "invalid_scope" -> "Google не разрешил доступ к Диску: в сборке запрошен неподдерживаемый набор прав"
        else -> body.optString("error_description").ifEmpty { "Google ответил отказом ($code)" }
    }

    // ---- sync --------------------------------------------------------------------------------------------------

    /**
     * One full pass: read the copy on Drive, merge it with this device, write the result back to both. Blocking, so it
     * belongs on a background thread. Returns the line for the user.
     */
    @Synchronized
    fun syncNow(context: Context): String {
        check(configured(context)) { "В сборке нет ключа Google" }
        check(linked(context)) { "Google не подключён" }
        try {
            val base = prefs(context).getString(BASE, null)
            val folder = folderId(context)
            val remote = download(context, folder)
            val merged = SyncMerge.merge(
                SyncMerge.parse(base), local(context), SyncMerge.parse(remote),
                historyLimit = HISTORY_LIMIT, searchLimit = SEARCH_LIMIT,
            )
            apply(context, merged)
            val text = SyncMerge.json(merged, System.currentTimeMillis()).toString()
            upload(context, text, folder)
            prefs(context).edit().putString(BASE, text).putLong(LAST_AT, System.currentTimeMillis())
                .remove(FAILED_TEXT).apply()
            return "Синхронизировано: закладки ${merged.favorites.size}, история ${merged.history.size}, поиск ${merged.search.size}"
        } catch (e: Throwable) {
            prefs(context).edit().putLong(FAILED_AT, System.currentTimeMillis())
                .putString(FAILED_TEXT, e.message ?: "нет связи с Google").apply()
            throw e
        }
    }

    /** Quiet check when the app starts: only when linked, switched on and the last pass is not fresh. */
    fun startCheck(context: Context) {
        if (!configured(context) || !linked(context)) return
        if (!Settings.bool(context, AUTO)) return
        if (System.currentTimeMillis() - lastSync(context) < MIN_GAP_MS) return
        val app = context.applicationContext
        Thread { runCatching { syncNow(app) } }.start()
    }

    /** This device's data in the shape of the shared document. */
    private fun local(context: Context): SyncMerge.Doc {
        val watched = Settings.string(context, PREFS_WATCHED, "").split(",").filter { it.isNotEmpty() }
        val settings = LinkedHashMap<String, String>()
        context.getSharedPreferences("lazytv", 0).all.forEach { (key, value) ->
            if (!synced(key)) return@forEach
            val encoded = when (value) {
                is Boolean -> JSONObject().put("b", value).toString()
                is String -> JSONObject().put("s", value).toString()
                else -> null
            }
            if (encoded != null) settings[key] = encoded
        }
        return SyncMerge.Doc(
            favorites = SyncMerge.titles(titles(Settings.string(context, PREFS_FAVORITES, "[]"))),
            watched = SyncMerge.terms(watched),
            history = SyncMerge.titles(titles(Settings.string(context, PREFS_HISTORY, "[]"))),
            search = SyncMerge.terms(terms(Settings.string(context, Settings.SEARCH_HISTORY, "[]"))),
            settings = settings,
        )
    }

    /** Writes the merged document into the settings this device reads; unchanged lists are left alone. */
    private fun apply(context: Context, doc: SyncMerge.Doc) {
        fun write(key: String, items: List<SyncMerge.Item>, plain: Boolean = false) {
            val array = JSONArray()
            items.forEach { array.put(if (plain) it.key else JSONObject(it.json)) }
            val text = array.toString()
            if (Settings.string(context, key, "[]") != text) Settings.setString(context, key, text)
        }
        write(PREFS_FAVORITES, doc.favorites)
        write(PREFS_HISTORY, doc.history)
        write(Settings.SEARCH_HISTORY, doc.search, plain = true)
        val watched = if (doc.watched.isEmpty()) "" else "," + doc.watched.joinToString(",") { it.key } + ","
        if (Settings.string(context, PREFS_WATCHED, "") != watched) Settings.setString(context, PREFS_WATCHED, watched)
        doc.settings.forEach { (key, encoded) ->
            val value = runCatching { JSONObject(encoded) }.getOrNull() ?: return@forEach
            when {
                value.has("b") && Settings.bool(context, key) != value.optBoolean("b") ->
                    Settings.setBool(context, key, value.optBoolean("b"))
                value.has("s") && Settings.string(context, key, "") != value.optString("s") ->
                    Settings.setString(context, key, value.optString("s"))
            }
        }
    }

    private fun titles(raw: String): List<JSONObject> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { runCatching { array.getJSONObject(it) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun terms(raw: String): List<String> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    // ---- Drive --------------------------------------------------------------------------------------------------

    /**
     * The folder of the shared copy: made on the first sync. The scope only shows files this app has made, so the search
     * finds our own folder and nothing that merely shares its name.
     */
    private fun folderId(context: Context): String {
        // oldest first: if two devices made a folder at once, every device settles on the same one
        val query = URLEncoder.encode("name='$FOLDER_NAME' and mimeType='$FOLDER_TYPE' and trashed=false", "UTF-8")
        val list = authorized(context) { send("$FILES?fields=files(id)&orderBy=createdTime&q=$query", "GET", null, null, it) }
        if (list.code == 403) {
            // a sign-in made with the old "appdata" scope cannot see Drive files: ask for the sign-in again
            prefs(context).edit().remove(REFRESH).remove(ACCESS).apply()
            throw IllegalStateException("Вход в Google устарел, подключите Google заново")
        }
        if (list.code !in 200..299) throw IllegalStateException("Google Диск не отвечает (${list.code})")
        val found = JSONObject(list.text).optJSONArray("files")?.optJSONObject(0)?.optString("id")
        if (!found.isNullOrEmpty()) return found
        val meta = JSONObject().put("name", FOLDER_NAME).put("mimeType", FOLDER_TYPE).toString()
        val made = authorized(context) { send("$FILES?fields=id", "POST", meta, null, it) }
        if (made.code !in 200..299) throw IllegalStateException("Google Диск не создал папку (${made.code})")
        return JSONObject(made.text).getString("id")
    }

    private fun download(context: Context, folder: String): String? {
        val id = fileId(context, folder) ?: return null
        val reply = authorized(context) { send("$FILES/$id?alt=media", "GET", null, null, it) }
        if (reply.code !in 200..299) throw IllegalStateException("Google Диск не отдал файл (${reply.code})")
        return reply.text
    }

    /**
     * A new copy is created and only then the old one removed: a failed upload then leaves the previous copy in place.
     * Drive wants PATCH for a file update, which HttpURLConnection does not speak, so a copy is replaced this way.
     */
    private fun upload(context: Context, text: String, folder: String) {
        val id = fileId(context, folder)
        val meta = JSONObject().put("name", FILE_NAME).put("parents", JSONArray().put(folder)).toString()
        val boundary = "lazytv-" + System.currentTimeMillis()
        val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta" +
            "\r\n--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$text\r\n--$boundary--\r\n"
        val reply = authorized(context) {
            send("$UPLOAD?uploadType=multipart&fields=id", "POST", body, "multipart/related; boundary=$boundary", it)
        }
        if (reply.code !in 200..299) throw IllegalStateException("Google Диск не принял данные (${reply.code})")
        if (id != null) authorized(context) { send("$FILES/$id", "DELETE", null, null, it) }
    }

    /** The copy in the folder, the newest one if an old removal was interrupted. */
    private fun fileId(context: Context, folder: String): String? {
        val query = URLEncoder.encode("name='$FILE_NAME' and trashed=false and '$folder' in parents", "UTF-8")
        val url = "$FILES?fields=files(id)&orderBy=modifiedTime%20desc&q=$query"
        val reply = authorized(context) { send(url, "GET", null, null, it) }
        if (reply.code == 404) return null
        if (reply.code !in 200..299) throw IllegalStateException("Google Диск не отвечает (${reply.code})")
        val files = JSONObject(reply.text).optJSONArray("files") ?: return null
        return files.optJSONObject(0)?.optString("id")?.ifEmpty { null }
    }

    /** Runs [call] with the access token, renewing it once when Google says it is stale. */
    private fun authorized(context: Context, call: (String) -> Reply): Reply {
        var reply = call(accessToken(context, false))
        if (reply.code == 401) reply = call(accessToken(context, true))
        return reply
    }

    private fun accessToken(context: Context, force: Boolean): String {
        val p = prefs(context)
        val token = p.getString(ACCESS, null)
        if (!force && token != null && System.currentTimeMillis() < p.getLong(EXPIRES, 0L) - 60_000L) return token
        val refresh = p.getString(REFRESH, null) ?: throw IllegalStateException("Google не подключён")
        val fields = mutableMapOf("client_id" to clientId(), "refresh_token" to refresh, "grant_type" to "refresh_token")
        if (clientSecret().isNotEmpty()) fields["client_secret"] = clientSecret()
        val reply = send(TOKEN_URL, "POST", form(fields), FORM, null)
        val body = JSONObject(reply.text.ifEmpty { "{}" })
        if (reply.code !in 200..299) {
            // the sign-in is gone for good (revoked, or the seven-day life of a test-mode app): ask for it again
            if (body.optString("error") == "invalid_grant") p.edit().remove(REFRESH).remove(ACCESS).apply()
            throw IllegalStateException(problem(body, reply.code))
        }
        store(context, body)
        return body.optString("access_token")
    }

    // ---- http ---------------------------------------------------------------------------------------------------

    private class Reply(val code: Int, val text: String)

    private fun send(url: String, method: String, body: String?, type: String?, token: String?): Reply {
        val c = Connectivity.open(url)
        try {
            c.requestMethod = method
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.instanceFollowRedirects = false
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setRequestProperty("Content-Type", type ?: "application/json; charset=utf-8")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return Reply(code, text)
        } finally {
            runCatching { c.disconnect() }
        }
    }

    private fun form(fields: Map<String, String>) =
        fields.entries.joinToString("&") { (key, value) -> key + "=" + URLEncoder.encode(value, "UTF-8") }

    private fun main(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
}
