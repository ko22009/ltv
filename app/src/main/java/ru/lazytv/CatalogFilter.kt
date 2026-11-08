package ru.lazytv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Sort orders offered on the "Сортировка" screen: label, TMDB sort string (series names are mapped in [Tmdb.load]). */
enum class CatalogSort(val key: String, val label: String, val sort: String) {
    POPULAR("popular", "По популярности", "popularity.desc"),
    DATE("date", "По дате выхода", "primary_release_date.desc&vote_count.gte=20"),
    RATING("rating", "По рейтингу", "vote_average.desc&vote_count.gte=500");

    companion object {
        fun of(key: String) = values().firstOrNull { it.key == key } ?: POPULAR
    }
}

/** What the user picked on the "Фильтры" screen for one catalogue list; empty fields mean "any". */
data class CatalogFilter(
    val genres: Set<Int> = emptySet(),
    val countries: Set<String> = emptySet(),
    val yearFrom: Int = 0,
    val yearTo: Int = 0,
    val ratingFrom: Int = 1,
    val ratingTo: Int = 10,
    /** Highest age mark allowed, like "16+"; empty — no limit. */
    val age: String = "",
    val hideWatched: Boolean = false,
    /** Films only: "" any, "soon" not out yet, "now" in the cinemas now, "out" already released (see [CatalogOptions.releases]). */
    val release: String = "",
) {
    val count get() = listOf(
        genres.isNotEmpty(), countries.isNotEmpty(), yearFrom != 0 || yearTo != 0,
        ratingFrom != 1 || ratingTo != 10, age.isNotEmpty(), hideWatched, release.isNotEmpty(),
    ).count { it }

    /** What is chosen now, comma-separated, for the line under the list's buttons. */
    fun describe(category: Category): String {
        val parts = mutableListOf<String>()
        val pickedGenres = (if (category.tv) CatalogOptions.tvGenres else CatalogOptions.movieGenres)
            .filter { it.first in genres }.map { it.second }
        if (pickedGenres.isNotEmpty()) parts += pickedGenres.joinToString(", ")
        val pickedCountries = (CatalogOptions.popularCountries + CatalogOptions.otherCountries)
            .filter { it.first in countries }.map { it.second }
        if (pickedCountries.isNotEmpty()) parts += pickedCountries.joinToString(", ")
        when {
            yearFrom > 0 && yearTo > 0 -> parts += if (yearFrom == yearTo) "$yearFrom" else "$yearFrom–$yearTo"
            yearFrom > 0 -> parts += "с $yearFrom"
            yearTo > 0 -> parts += "до $yearTo"
        }
        if (ratingFrom != 1 || ratingTo != 10) parts += "рейтинг $ratingFrom–$ratingTo"
        if (age.isNotEmpty()) parts += age
        CatalogOptions.releases.firstOrNull { it.first == release && release.isNotEmpty() }?.let { parts += it.second }
        if (hideWatched) parts += "без просмотренных"
        return parts.joinToString(", ")
    }

    /** The part of the TMDB discover address that the filter adds. */
    fun query(tv: Boolean): String = buildString {
        if (genres.isNotEmpty()) append("&with_genres=").append(genres.joinToString(","))
        if (countries.isNotEmpty()) append("&with_origin_country=").append(countries.joinToString("|"))
        val field = if (tv) "first_air_date" else "primary_release_date"
        if (yearFrom != 0) append("&$field.gte=$yearFrom-01-01")
        // "released" is an upper bound on the same field as the last year: one value, the earlier of the two
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val released = !tv && release == "out"
        val upper = listOfNotNull(if (yearTo != 0) "$yearTo-12-31" else null, if (released) today else null).minOrNull()
        if (upper != null) append("&$field.lte=$upper")
        // the cinemas of Russia: wide (3) and limited (2) theatrical releases, as in TMDB's own "now playing" list
        if (!tv && (release == "now" || release == "soon")) {
            val cal = java.util.Calendar.getInstance()
            fun day(offset: Int) = (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, offset) }
                .let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(it.time) }
            append("&region=RU&with_release_type=2%7C3")
            if (release == "now") append("&release_date.gte=").append(day(-45)).append("&release_date.lte=").append(today)
            else append("&release_date.gte=").append(day(1))
        }
        if (ratingFrom != 1 || ratingTo != 10) {
            append("&vote_average.gte=$ratingFrom&vote_average.lte=$ratingTo&vote_count.gte=50")
        }
        if (age.isNotEmpty()) append("&certification_country=RU&certification.lte=").append(age.replace("+", "%2B"))
    }

    fun toJson(): String = JSONObject()
        .put("genres", JSONArray(genres.toList())).put("countries", JSONArray(countries.toList()))
        .put("from", yearFrom).put("to", yearTo).put("rfrom", ratingFrom).put("rto", ratingTo)
        .put("age", age).put("hide", hideWatched).put("rel", release).toString()

    companion object {
        private const val PREFS = "catalog_filters"

        // kept apart from Settings: a change there redraws the whole menu, and the page would jump to its start
        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

        fun load(context: Context, category: String): CatalogFilter {
            val raw = prefs(context).getString("filter_$category", null) ?: return CatalogFilter()
            return runCatching {
                val o = JSONObject(raw)
                CatalogFilter(
                    (0 until o.getJSONArray("genres").length()).map { o.getJSONArray("genres").getInt(it) }.toSet(),
                    (0 until o.getJSONArray("countries").length()).map { o.getJSONArray("countries").getString(it) }.toSet(),
                    o.getInt("from"), o.getInt("to"), o.getInt("rfrom"), o.getInt("rto"), o.getString("age"), o.getBoolean("hide"),
                    // absent in filters saved by older versions
                    o.optString("rel"),
                )
            }.getOrDefault(CatalogFilter())
        }

        fun save(context: Context, category: String, filter: CatalogFilter) {
            prefs(context).edit().putString("filter_$category", filter.toJson()).apply()
        }

        fun sort(context: Context, category: String) = CatalogSort.of(prefs(context).getString("sort_$category", "") .orEmpty())

        fun saveSort(context: Context, category: String, sort: CatalogSort) {
            prefs(context).edit().putString("sort_$category", sort.key).apply()
        }

        /** Changes with every choice on either screen: the page compares it to know whether to be drawn again. */
        fun signature(context: Context, category: String) = "${load(context, category).toJson()}|${sort(context, category).key}"

        /** Lists that carry the two buttons. */
        fun supports(category: String) = category == "movies" || category == "serials"
    }
}

/** Genres of TMDB, named in Russian; series have their own ids for a few of them. */
object CatalogOptions {
    val movieGenres = listOf(
        35 to "Комедия", 10751 to "Семейный", 10749 to "Мелодрама", 18 to "Драма", 53 to "Триллер", 27 to "Ужасы",
        28 to "Боевик", 37 to "Вестерн", 10752 to "Военный", 80 to "Криминал", 9648 to "Детектив", 36 to "История",
        12 to "Приключения", 878 to "Фантастика", 14 to "Фэнтези", 16 to "Мультфильм", 99 to "Документальный",
        10402 to "Музыка", 10770 to "Телефильм",
    )
    val tvGenres = listOf(
        35 to "Комедия", 10751 to "Семейный", 18 to "Драма", 80 to "Криминал", 9648 to "Детектив",
        10759 to "Боевик и приключения", 10765 to "Фантастика и фэнтези", 16 to "Мультфильм", 99 to "Документальный",
        10762 to "Детский", 10764 to "Реалити", 10767 to "Ток-шоу", 10768 to "Политика", 37 to "Вестерн",
        10766 to "Мыльная опера",
    )

    val popularCountries = listOf("US" to "США", "GB" to "Великобритания", "FR" to "Франция", "RU" to "Россия", "DE" to "Германия")
    val otherCountries = listOf(
        "AU" to "Австралия", "AT" to "Австрия", "AR" to "Аргентина", "BE" to "Бельгия", "BR" to "Бразилия",
        "HU" to "Венгрия", "HK" to "Гонконг", "GR" to "Греция", "DK" to "Дания", "IN" to "Индия", "IE" to "Ирландия",
        "ES" to "Испания", "IT" to "Италия", "CA" to "Канада", "CN" to "Китай", "KR" to "Корея Южная",
        "MX" to "Мексика", "NL" to "Нидерланды", "NZ" to "Новая Зеландия", "NO" to "Норвегия", "PL" to "Польша",
        "SU" to "СССР", "TR" to "Турция", "UA" to "Украина", "FI" to "Финляндия", "CZ" to "Чехия",
        "CH" to "Швейцария", "SE" to "Швеция", "JP" to "Япония",
    )

    /** The "Выход" filter of films: key to caption; the empty key is "any". */
    val releases = listOf("" to "Любой", "soon" to "Скоро в кино", "now" to "Идёт в кино", "out" to "Вышло")

    val ages = listOf("18+" to "Взрослые", "16+" to "Подростки старше 16 лет", "12+" to "Дети старше 12 лет", "6+" to "Дети старше 6 лет", "0+" to "Для самых маленьких")

    /** Preset periods of the years screen: label to (from, to); 0 means open end. */
    fun yearPresets(now: Int): List<Triple<String, Int, Int>> = listOf(
        Triple("Все годы", 0, 0),
        Triple("$now", now, now), Triple("${now - 1}", now - 1, now - 1), Triple("${now - 2}", now - 2, now - 2),
        Triple("${now - 6}–$now", now - 6, now),
        Triple("2010-е", 2010, 2019), Triple("2000-е", 2000, 2009), Triple("90-е", 1990, 1999),
        Triple("80-е", 1980, 1989), Triple("70-е", 1970, 1979), Triple("60-е", 1960, 1969),
        Triple("50-е", 1950, 1959), Triple("40-е", 1940, 1949), Triple("30-е", 1930, 1939), Triple("До 30-х", 0, 1929),
    )
}
