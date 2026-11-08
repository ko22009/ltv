package ru.lazytv

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import org.json.JSONArray

/**
 * The "Продолжить просмотр" entries of the system "Смотреть далее" row: one card per film or series that was started
 * in the player and not finished, with the progress bar. A film card starts the player on the saved stream (the
 * player restores the position); a series card opens the title card of the app to choose the season and the episode.
 */
object ContinueWatching {
    private const val LIVE = 0
    private const val HIDDEN = 1
    private const val GONE = 2

    private fun key(tv: Boolean, id: Long) = "continue_watching_${tv}_$id"
    private fun resumeKey(tv: Boolean, id: Long) = "continue_play_${tv}_$id"

    /**
     * The intent that starts the player on the stream the title was left on (the position comes from the player's own
     * memory); null when nothing was saved, then the card of the title is opened instead.
     */
    fun resumeIntent(context: Context, tv: Boolean, id: Long): Intent? {
        val raw = Settings.string(context, resumeKey(tv, id), "").ifEmpty { return null }
        val o = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return null
        // a series opens the season and episode screen on the episode to watch next (the saved stream links may be stale)
        if (o.optBoolean("series")) {
            return Intent(context, EpisodesActivity::class.java)
                .putExtra("source", o.optString("source")).putExtra("sourceId", o.optString("sourceId"))
                .putExtra("title", o.optString("title")).putExtra("note", o.optString("note")).putExtra("ref", o.optString("ref"))
                .putExtra("alt", o.optString("alt")).putExtra("description", o.optString("description"))
                .putExtra("mediaTitle", o.optString("mediaTitle")).putExtra("originalTitle", o.optString("originalTitle"))
                .putExtra("year", o.optString("year")).putExtra("tv", true)
                .putExtra("poster", o.optString("poster").ifEmpty { null }).putExtra("tmdbId", o.optLong("tmdbId"))
                .putExtra("episodeIndex", o.optInt("index", -1))
        }
        if (tv) return null
        if (o.optString("url").isEmpty()) return null
        return Intent(context, PlayerActivity::class.java)
            .putExtra("url", o.optString("url")).putExtra("name", o.optString("name")).putExtra("meta", o.optString("meta"))
            .putExtra("playlist", o.optString("playlist")).putExtra("index", o.optInt("index"))
            .putExtra("mediaTitle", o.optString("mediaTitle")).putExtra("originalTitle", o.optString("originalTitle"))
            .putExtra("year", o.optString("year")).putExtra("tv", o.optBoolean("tv"))
            .putExtra("poster", o.optString("poster").ifEmpty { null }).putExtra("tmdbId", o.optLong("tmdbId"))
            .putExtra("resumed", true)
    }

    /**
     * Adds the title to the row or moves its progress. [resume] is what the player needs to start the same stream again
     * (see [resumeIntent]). Runs the provider calls on the caller's thread: call it off the main thread.
     */
    fun update(context: Context, title: Title, positionMs: Long, durationMs: Long, resume: String, next: Boolean = false) {
        if (!Settings.bool(context, Settings.RECOMMENDATIONS) || title.id <= 0L || durationMs <= 0L) return
        if (resume.isNotEmpty()) Settings.setString(context, resumeKey(title.tv, title.id), resume)
        val builder = androidx.tvprovider.media.tv.WatchNextProgram.Builder()
            // "next": the episode just finished, the entry offers the following one; otherwise the episode is left half-way
            .setWatchNextType(if (next) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
            .setType(if (title.tv) TvContractCompat.PreviewPrograms.TYPE_TV_SERIES else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
            .setTitle(title.name)
            .setDescription(listOf(title.year, if (title.rating.isNotEmpty()) "★ ${title.rating}" else "").filter { it.isNotEmpty() }.joinToString(" · "))
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
            .setLastPlaybackPositionMillis(positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setDurationMillis(durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setInternalProviderId("${title.tv}:${title.id}")
            // through the exported MainActivity: it starts the player on the saved stream, or opens the title card if that is gone
            .setIntentUri(Uri.parse(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.OPEN_TITLE, true).putExtra(MainActivity.RESUME_PLAY, true)
                .putExtras(title.toExtras()).toUri(Intent.URI_INTENT_SCHEME)))
        if (!title.poster.isNullOrEmpty()) builder.setPosterArtUri(Uri.parse("https://image.tmdb.org/t/p/w780${title.poster}"))
        val helper = PreviewChannelHelper(context)
        val remembered = Settings.string(context, key(title.tv, title.id), "").toLongOrNull()
        // the entry may be gone, or the launcher may keep it hidden after the user removed it from the row: updating
        // such an entry changes nothing on the screen, so it is deleted and the title is published again
        val saved = remembered?.takeIf { id ->
            val uri = TvContractCompat.buildWatchNextProgramUri(id)
            val state = runCatching {
                context.contentResolver.query(uri, arrayOf(TvContractCompat.WatchNextPrograms.COLUMN_BROWSABLE), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) (if (c.getInt(0) == 0) HIDDEN else LIVE) else GONE }
            }.getOrNull() ?: LIVE // the check itself failed: keep the old behaviour and update
            if (state == HIDDEN) runCatching { context.contentResolver.delete(uri, null, null) }
            state == LIVE
        }
        runCatching {
            if (saved != null) {
                helper.updateWatchNextProgram(builder.build(), saved)
            } else {
                val id = helper.publishWatchNextProgram(builder.build())
                if (id > 0) Settings.setString(context, key(title.tv, title.id), id.toString())
            }
        }.onFailure { android.util.Log.w("Recommendations", "continue-watching entry was not saved: ${title.name}", it) }
    }

    /** Removes the title from the row (watched to the end). */
    fun clear(context: Context, tv: Boolean, id: Long) {
        val saved = Settings.string(context, key(tv, id), "").toLongOrNull() ?: return
        runCatching { context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(saved), null, null) }
        Settings.remove(context, key(tv, id))
        Settings.remove(context, resumeKey(tv, id))
    }
}

/**
 * The "Популярное" row on the Android TV home screen: one preview channel with the popular films of the
 * catalog. Channels are a system feature (API 26+); on launchers that no longer draw them the call simply
 * leaves the channel in the TV provider and the app keeps working.
 */
object Recommendations {
    private const val CHANNEL = "Популярное"
    private const val KEY_IDS = "recommendation_ids"
    private const val KEY_CHANNEL = "recommendation_channel"
    private const val LIMIT = 10

    fun refresh(context: Context) {
        if (!Settings.bool(context, Settings.RECOMMENDATIONS)) return
        val helper = PreviewChannelHelper(context)
        val channelId = channel(context, helper) ?: return
        val titles = Tmdb.loadCollection(context, Tmdb.Collection("Популярное", false, "popular")).take(LIMIT)
        // replace the previous programs instead of piling them up on every launch
        val old = runCatching { JSONArray(Settings.string(context, KEY_IDS, "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until old.length()) runCatching { helper.deletePreviewProgram(old.getLong(i)) }
        val ids = JSONArray()
        titles.forEachIndexed { index, title ->
            if (title.poster == null) return@forEachIndexed
            // the list has no runtime and only genre ids: the card details give both, a miss just leaves them out
            val details = runCatching { Tmdb.detailsCached(context, title.id, title.tv) }.getOrNull()
            val genre = details?.genres?.take(2)?.joinToString(", ").orEmpty().ifEmpty { title.genre }
            val minutes = details?.runtime ?: 0
            val program = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(if (title.tv) TvContractCompat.PreviewPrograms.TYPE_TV_SERIES else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                .setTitle(title.name)
                .setDescription(listOf(title.year, formatRuntime(minutes), genre, if (title.rating.isNotEmpty()) "★ ${title.rating}" else "")
                    .filter { it.isNotEmpty() }.joinToString(" · "))
                .setGenre(genre)
                .setDurationMillis(minutes * 60_000)
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                // w500 was upscaled by the launcher and looked soft; w780 is sharp on a 4K screen too
                .setPosterArtUri(Uri.parse("https://image.tmdb.org/t/p/w780${title.poster}"))
                // routed through exported MainActivity so non-exported DetailsActivity works in release builds too
                .setIntentUri(Uri.parse(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.OPEN_TITLE, true).putExtras(title.toExtras()).toUri(Intent.URI_INTENT_SCHEME)))
                .setWeight(LIMIT - index)
                .build()
            runCatching { helper.publishPreviewProgram(program) }
                .onFailure { android.util.Log.w("Recommendations", "program was not published: ${title.name}", it) }
                .getOrNull()?.takeIf { it > 0 }?.let { ids.put(it) }
        }
        android.util.Log.i("Recommendations", "published ${ids.length()} of ${titles.size} programs in channel $channelId")
        Settings.setString(context, KEY_IDS, ids.toString())
    }

    /** Removes the home-screen channel and its programs when the user switches recommendations off. */
    fun clear(context: Context) {
        val helper = PreviewChannelHelper(context)
        val old = runCatching { JSONArray(Settings.string(context, KEY_IDS, "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until old.length()) runCatching { helper.deletePreviewProgram(old.getLong(i)) }
        Settings.remove(context, KEY_IDS)
        Settings.string(context, KEY_CHANNEL, "").toLongOrNull()?.let {
            runCatching { helper.deletePreviewChannel(it) }
            Settings.remove(context, KEY_CHANNEL)
        }
    }

    private fun channel(context: Context, helper: PreviewChannelHelper): Long? {
        val saved = Settings.string(context, KEY_CHANNEL, "").toLongOrNull()
        if (saved != null && runCatching { helper.getPreviewChannel(saved) }.getOrNull() != null) return saved
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.banner)
        val published = runCatching {
            helper.publishDefaultChannel(
                PreviewChannel.Builder()
                    .setDisplayName(CHANNEL)
                    .setDescription("Фильмы из каталога LazyTV")
                    .setLogo(logo)
                    .setAppLinkIntentUri(Uri.parse(Intent(context, MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME)))
                    .build()
            )
        }.onFailure { android.util.Log.w("Recommendations", "channel was not published", it) }.getOrNull() ?: return null
        if (published <= 0) {
            android.util.Log.w("Recommendations", "channel was not published: id $published")
            return null
        }
        Settings.setString(context, KEY_CHANNEL, published.toString())
        return published
    }
}
