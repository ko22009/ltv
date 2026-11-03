package ru.lazytv

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Title(
    val id: Long,
    val name: String,
    val overview: String,
    val poster: String?,
    val tv: Boolean,
    val year: String = "",
    val rating: String = "",
    /** Up to two genre names taken from the list answer; empty when the list carries none. */
    val genre: String = "",
)

/** A list or a service category: what to ask TMDB for. */
data class Category(val id: String, val title: String, val tv: Boolean, val query: String, val color: Int, val onByDefault: Boolean)

/** A video source the lists draw from; switched on or off in the settings. */
data class Service(val id: String, val name: String)

object Tmdb {
    private const val BASE = "https://api.themoviedb.org/3"

    /**
     * Lists on the left menu: films and series only. There is no list per genre and no "18+": the genre, country,
     * years, age mark and the rest are picked with the filters of films and series.
     */
    val categories = listOf(
        Category("movies", "Фильмы", false, "", 0xFF0555A0.toInt(), true),
        Category("serials", "Сериалы", true, "", 0xFF673AB7.toInt(), true),
    )

    val services = listOf(
        Service("encyclopedia", "ENCYCLOPEDIA"),
        Service("hdrezka", "HDREZKA"),
        Service("kinodb", "KINODB"),
        Service("zetflix", "ZETFLIX"),
        Service("seasonvar", "SEASONVAR"),
        Service("zona", "ZONA"),
        Service("octopus", "OCTOPUS"),
        Service("lordfilm", "LORDFILM"),
        Service("kinogo", "KINOGO"),
        Service("lostfilm", "LOSTFILM"),
        Service("anilibria", "ANILIBRIA"),
        Service("torrentino", "TORRENTINO"),
    )

    /** Rows shown inside every category, named like the Kinopoisk collections: title to sort order. */
    val rows = listOf(
        "Популярное" to "popularity.desc",
        "Лучшее за всё время" to "vote_average.desc&vote_count.gte=2000",
        "Новинки" to "primary_release_date.desc&vote_count.gte=50",
        "Лучшее в этом году" to "vote_average.desc&vote_count.gte=200&year=THIS_YEAR",
    )

    /** Overview rows built from ready-made TMDB lists: title, kind (movie/tv), list path, extra query. */
    class Collection(val title: String, val tv: Boolean, val path: String, val extra: String = "")

    val overview = listOf(
        Collection("Популярное", false, "popular"),
        Collection("Сейчас в кино", false, "now_playing", "&region=RU"),
        Collection("Скоро в кино", false, "upcoming", "&region=RU"),
        Collection("Топ фильмов", false, "top_rated"),
        Collection("Популярные сериалы", true, "popular"),
        Collection("Сейчас в эфире", true, "on_the_air"),
        Collection("Топ сериалов", true, "top_rated"),
    )

    fun loadCollection(context: Context, collection: Collection, page: Int = 1): List<Title> {
        val language = Settings.string(context, Settings.LANGUAGE, "ru-RU")
        val kind = if (collection.tv) "tv" else "movie"
        val url = "$BASE/$kind/${collection.path}?api_key=${BuildConfig.TMDB_KEY}&language=$language${collection.extra}&page=$page"
        cachedList(url)?.let { return it }
        return parse(download(context, url), collection.tv).also { rememberList(url, it) }
    }

    fun listKey(category: Category) = "list_${category.id}"

    /** Lists that are switched on in the settings. */
    fun visibleCategories(context: Context): List<Category> =
        categories.filter { Settings.bool(context, listKey(it), it.onByDefault) }

    fun load(context: Context, category: Category, sort: String, page: Int = 1, extra: String = ""): List<Title> {
        val url = listUrl(context, category, sort, page, extra)
        cachedList(url)?.let { return it }
        return parse(download(context, url), category.tv).also { rememberList(url, it) }
    }

    /** The page already fetched: it stands at once, without skeletons. */
    fun peekPage(context: Context, category: Category, sort: String, page: Int = 1, extra: String = ""): List<Title>? =
        cachedList(listUrl(context, category, sort, page, extra))

    private fun listUrl(context: Context, category: Category, sort: String, page: Int, extra: String): String {
        val kind = if (category.tv) "tv" else "movie"
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val yearParam = if (category.tv) "first_air_date_year" else "primary_release_year"
        val sortKey = (if (category.tv) sort.replace("primary_release_date", "first_air_date") else sort)
            .replace("year=THIS_YEAR", "$yearParam=$year")
        val language = Settings.string(context, Settings.LANGUAGE, "ru-RU")
        return "$BASE/discover/$kind?api_key=${BuildConfig.TMDB_KEY}&language=$language" +
            "&sort_by=$sortKey${category.query}$extra&page=$page"
    }

    private fun parse(json: String, tv: Boolean): List<Title> {
        val results = JSONObject(json).getJSONArray("results")
        return (0 until results.length()).mapNotNull { toTitle(results.getJSONObject(it), tv) }
    }

    private fun toTitle(item: JSONObject, tv: Boolean): Title? {
        val name = item.optString("title").ifEmpty { item.optString("name") }
        val date = item.optString("release_date").ifEmpty { item.optString("first_air_date") }
        return if (name.isEmpty()) null
        else Title(
            item.getLong("id"), name, item.optString("overview"),
            item.optString("poster_path").ifEmpty { null }, tv,
            date.take(4), "%.1f".format(item.optDouble("vote_average", 0.0)),
            genreText(item.optJSONArray("genre_ids")),
        )
    }

    /** Short Russian names of TMDB genre ids (films and series use different ids for a few genres). */
    private val genreNames = mapOf(
        28 to "Боевик", 12 to "Приключения", 16 to "Мультфильм", 35 to "Комедия", 80 to "Криминал",
        99 to "Документальный", 18 to "Драма", 10751 to "Семейный", 14 to "Фэнтези", 36 to "История",
        27 to "Ужасы", 10402 to "Музыка", 9648 to "Детектив", 10749 to "Мелодрама", 878 to "Фантастика",
        10770 to "Телефильм", 53 to "Триллер", 10752 to "Военный", 37 to "Вестерн",
        10759 to "Боевик и приключения", 10762 to "Детский", 10764 to "Реалити", 10765 to "Фантастика",
        10766 to "Мыльная опера", 10767 to "Ток-шоу", 10768 to "Политика",
    )

    private fun genreText(ids: org.json.JSONArray?): String {
        if (ids == null) return ""
        return (0 until ids.length()).mapNotNull { genreNames[ids.optInt(it)] }.take(2).joinToString(", ")
    }

    /** Movies and series matching the typed or spoken words (people are skipped). */
    fun search(context: Context, query: String): List<Title> {
        val language = Settings.string(context, Settings.LANGUAGE, "ru-RU")
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val results = JSONObject(download(context, "$BASE/search/multi?api_key=${BuildConfig.TMDB_KEY}&language=$language&include_adult=false&query=$encoded"))
            .getJSONArray("results")
        return (0 until results.length()).mapNotNull { i ->
            val item = results.getJSONObject(i)
            when (item.optString("media_type")) {
                "movie" -> toTitle(item, false)
                "tv" -> toTitle(item, true)
                else -> null
            }
        }
    }

    class Details(
        val originalTitle: String, val tagline: String, val genres: List<String>, val countries: List<String>,
        val runtime: Int, val age: String, val date: String, val director: String, val cast: List<String>,
        val seasons: Int, val backdrop: String,
        /** The next announced episode of a series, already formatted; empty for films and finished series. */
        val nextEpisode: String = "",
        /** The same cast with TMDB person ids, so a name can open the actor page. */
        val castPeople: List<Person> = emptyList(),
        /** Poster path from TMDB; used when the list did not pass a poster to the card. */
        val poster: String = "",
    )

    /** An actor of the cast: the TMDB id of the person page, the name, the photo path and the role played. */
    data class Person(val id: Long, val name: String, val photo: String = "", val role: String = "")

    private val detailCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Details>>()
    private val detailPending = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.FutureTask<Details>>()
    private const val DETAIL_TTL_MS = 15 * 60 * 1000L

    /** True when the details are already in the memory cache: the card opens without waiting for the network. */
    fun isCached(id: Long, tv: Boolean): Boolean =
        detailCache["$tv:$id"]?.let { System.currentTimeMillis() - it.first < DETAIL_TTL_MS } ?: false

    /**
     * Card details with a short memory cache: when the user only moves through the lists, the card is prefetched
     * on focus, so opening it does not wait for the network a second time.
     */
    fun detailsCached(context: Context, id: Long, tv: Boolean): Details {
        val key = "$tv:$id"
        detailCache[key]?.let { (time, details) ->
            if (System.currentTimeMillis() - time < DETAIL_TTL_MS) return details
        }
        val task = java.util.concurrent.FutureTask { details(context, id, tv) }
        val pending = detailPending.putIfAbsent(key, task)
        if (pending != null) return pending.get()
        try {
            task.run()
            val details = task.get()
            detailCache[key] = System.currentTimeMillis() to details
            // the cache is only for fast card opening: never let it grow without a limit
            if (detailCache.size > 80) {
                detailCache.entries.sortedBy { it.value.first }.take(detailCache.size - 80)
                    .forEach { detailCache.remove(it.key) }
            }
            return details
        } finally {
            detailPending.remove(key)
        }
    }

    /** Full card data: original title, genres, countries, runtime, age rating, director and cast. */
    fun details(context: Context, id: Long, tv: Boolean): Details {
        val kind = if (tv) "tv" else "movie"
        val language = Settings.string(context, Settings.LANGUAGE, "ru-RU")
        val extra = if (tv) "credits,content_ratings" else "credits,release_dates"
        val o = JSONObject(download(context, "$BASE/$kind/$id?api_key=${BuildConfig.TMDB_KEY}&language=$language&append_to_response=$extra"))
        fun names(key: String): List<String> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { a.getJSONObject(it).optString("name") }.filter { it.isNotEmpty() }
        }
        val credits = o.optJSONObject("credits")
        val castPeople = credits?.optJSONArray("cast")?.let { a ->
            (0 until minOf(a.length(), 20)).mapNotNull { i ->
                val p = a.getJSONObject(i)
                val name = p.optString("name")
                if (name.isEmpty()) null else Person(p.optLong("id"), name, p.optString("profile_path"), p.optString("character"))
            }
        } ?: emptyList()
        val cast = castPeople.map { it.name }
        val crew = credits?.optJSONArray("crew")
        var director = ""
        if (crew != null) {
            for (i in 0 until crew.length()) {
                val member = crew.getJSONObject(i)
                if (member.optString("job") == "Director") { director = member.optString("name"); break }
            }
        }
        if (director.isEmpty()) director = names("created_by").joinToString(", ")
        var age = ""
        if (tv) {
            val a = o.optJSONObject("content_ratings")?.optJSONArray("results")
            if (a != null) for (i in 0 until a.length()) {
                val r = a.getJSONObject(i)
                if (r.optString("iso_3166_1") == "RU" || (age.isEmpty() && r.optString("iso_3166_1") == "US")) age = r.optString("rating")
            }
        } else {
            val a = o.optJSONObject("release_dates")?.optJSONArray("results")
            if (a != null) for (i in 0 until a.length()) {
                val r = a.getJSONObject(i)
                if (r.optString("iso_3166_1") == "RU") {
                    val list = r.optJSONArray("release_dates")
                    if (list != null) for (j in 0 until list.length()) {
                        val c = list.getJSONObject(j).optString("certification")
                        if (c.isNotEmpty()) { age = c; break }
                    }
                }
            }
        }
        val runtime = if (tv) o.optJSONArray("episode_run_time")?.optInt(0) ?: 0 else o.optInt("runtime")
        // TMDB names the countries in English whatever the language is: take the name from the country code instead
        val countries = o.optJSONArray("production_countries")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { c ->
                java.util.Locale("", c.optString("iso_3166_1")).getDisplayCountry(java.util.Locale.forLanguageTag(language))
                    .ifEmpty { c.optString("name") }
            }
        } ?: emptyList()
        val next = o.optJSONObject("next_episode_to_air")
        val nextEpisode = if (next == null) "" else {
            val season = next.optInt("season_number")
            val episode = next.optInt("episode_number")
            listOf(
                if (season > 0 && episode > 0) "S${season}E$episode" else "",
                next.optString("name"), next.optString("air_date"),
            ).filter { it.isNotEmpty() }.joinToString(" • ")
        }
        return Details(
            o.optString("original_title").ifEmpty { o.optString("original_name") }, o.optString("tagline"),
            names("genres"), countries, runtime, age,
            o.optString("release_date").ifEmpty { o.optString("first_air_date") }, director, cast,
            o.optInt("number_of_seasons"), o.optString("backdrop_path"), nextEpisode, castPeople,
            o.optString("poster_path"),
        )
    }

    /** One acting credit of a person: a catalog title and the role. */
    class Credit(val title: Title, val role: String)

    /** The actor page: photo, facts, biography and the best-known films and series. */
    class PersonPage(
        val name: String, val biography: String, val birthday: String, val place: String,
        val photo: String?, val credits: List<Credit>,
    )

    fun person(context: Context, id: Long): PersonPage {
        val language = Settings.string(context, Settings.LANGUAGE, "ru-RU")
        val o = JSONObject(download(context, "$BASE/person/$id?api_key=${BuildConfig.TMDB_KEY}&language=$language&append_to_response=combined_credits"))
        val cast = o.optJSONObject("combined_credits")?.optJSONArray("cast")
        val credits = if (cast == null) emptyList() else (0 until cast.length()).mapNotNull { i ->
            val c = cast.getJSONObject(i)
            val tv = c.optString("media_type") == "tv"
            val name = c.optString("title").ifEmpty { c.optString("name") }
            if (name.isEmpty()) return@mapNotNull null
            val date = c.optString("release_date").ifEmpty { c.optString("first_air_date") }
            val title = Title(
                c.optLong("id"), name, c.optString("overview"), c.optString("poster_path").takeIf { it.isNotEmpty() }, tv,
                date.take(4), "%.1f".format(c.optDouble("vote_average", 0.0)),
            )
            Credit(title, c.optString("character")) to c.optDouble("popularity", 0.0)
        }.sortedByDescending { it.second }.take(40).map { it.first }
        return PersonPage(
            o.optString("name"), o.optString("biography"), o.optString("birthday"), o.optString("place_of_birth"),
            o.optString("profile_path").takeIf { it.isNotEmpty() }, credits,
        )
    }

    /** YouTube key of the first trailer, trying the catalog language first and English after it. */
    fun trailerKey(context: Context, id: Long, tv: Boolean): String? {
        val kind = if (tv) "tv" else "movie"
        for (language in listOf(Settings.string(context, Settings.LANGUAGE, "ru-RU"), "en-US").distinct()) {
            val results = JSONObject(download(context, "$BASE/$kind/$id/videos?api_key=${BuildConfig.TMDB_KEY}&language=$language"))
                .getJSONArray("results")
            for (i in 0 until results.length()) {
                val video = results.getJSONObject(i)
                if (video.optString("site") == "YouTube" && video.optString("type") == "Trailer") {
                    return video.getString("key")
                }
            }
        }
        return null
    }

    /**
     * The answers are kept on the device: the lists and the cards are opened again and again, and every opening used to
     * ask TMDB. Half a day old data is still better than a spinner; the lifetime is one number to tune later.
     */
    private fun download(context: Context, address: String): String {
        val file = cacheFile(context, address)
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < CACHE_MS) return file.readText()
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        runCatching { file.apply { parentFile?.mkdirs(); writeText(body) } }
        return body
    }

    /** Lists already fetched: a page opened again stands at once instead of waiting for the network and showing skeletons. */
    private class CachedList(val titles: List<Title>, val at: Long)
    private val listCache = java.util.concurrent.ConcurrentHashMap<String, CachedList>()
    private const val LIST_TTL_MS = 10 * 60 * 1000L

    private fun cachedList(url: String): List<Title>? {
        val hit = listCache[url] ?: return null
        if (System.currentTimeMillis() - hit.at > LIST_TTL_MS) {
            listCache.remove(url)
            return null
        }
        return hit.titles
    }

    private fun rememberList(url: String, titles: List<Title>) {
        listCache[url] = CachedList(titles, System.currentTimeMillis())
    }

    private fun cacheFile(context: Context, address: String): java.io.File {
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(address.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return java.io.File(java.io.File(context.cacheDir, "tmdb"), hash)
    }

    private const val CACHE_MS = 12 * 60 * 60 * 1000L
}
