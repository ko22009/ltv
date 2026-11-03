package ru.lazytv

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * The screen the cursor was on last. The install script asks for it right after an update, so the app comes back to
 * the list or the title card instead of the menu (see the install step in docs/workflow.md). Only own screens are
 * saved and opened. Stored apart from [Settings]: the last screen is not a setting, and writing it must not look like
 * a settings change to the screens that watch `Settings.snapshot`.
 */
object LastScreen {
    private const val PREFS = "last_screen"
    private const val KEY = "intent"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    /** The menu is where a cold start already lands; the player must not start on its own after an update. */
    fun remember(activity: Activity) {
        if (activity is MainActivity || activity is PlayerActivity) return
        val intent = activity.intent ?: return
        if (intent.component?.packageName != activity.packageName) return
        runCatching { prefs(activity).edit().putString(KEY, intent.toUri(Intent.URI_INTENT_SCHEME)).apply() }
    }

    /** Opens the remembered screen; false when there is nothing to open or the saved line is not ours. */
    fun restore(activity: Activity): Boolean {
        val saved = prefs(activity).getString(KEY, "") ?: ""
        if (saved.isEmpty()) return false
        val intent = runCatching { Intent.parseUri(saved, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return false
        // the saved line is data, not an order: only our own screens are opened
        if (intent.component?.packageName != activity.packageName) return false
        return runCatching { activity.startActivity(intent); true }.getOrDefault(false)
    }
}
