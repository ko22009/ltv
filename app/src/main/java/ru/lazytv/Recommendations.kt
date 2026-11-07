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
