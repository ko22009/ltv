package ru.lazytv

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * Where the user was last. The install script asks for it right after an update (see docs/workflow.md): the app
 * returns to the title card or the list, and the menu returns to its page (the search, the catalogue), not to the
 * overview. Only own screens are saved and opened. Stored apart from [Settings]: the last screen is not a setting,
 * and writing it must not look like a settings change to the screens that watch `Settings.snapshot`.
 */
object LastScreen {
    private const val PREFS = "last_screen"
    private const val SCREEN = "intent"
    private const val PAGE = "page"
    private const val MENU = "menu_open"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, 0)

    /** The player must not start on its own after an update; on the menu the page is remembered instead of a screen. */
    fun remember(activity: Activity) {
        if (activity is PlayerActivity) return
        if (activity is MainActivity) {
            // the menu is home to every page: the page is saved by the menu itself, the screen over it is dropped
            prefs(activity).edit().remove(SCREEN).apply()
            return
        }
        val intent = activity.intent ?: return
        if (intent.component?.packageName != activity.packageName) return
        runCatching { prefs(activity).edit().putString(SCREEN, intent.toUri(Intent.URI_INTENT_SCHEME)).apply() }
    }

    /** Opens the remembered screen; false when there is nothing to open or the saved line is not ours. */
    fun restore(activity: Activity): Boolean {
        val saved = prefs(activity).getString(SCREEN, "") ?: ""
        if (saved.isEmpty()) return false
        val intent = runCatching { Intent.parseUri(saved, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return false
        // the saved line is data, not an order: only our own screens are opened
        if (intent.component?.packageName != activity.packageName) return false
        return runCatching { activity.startActivity(intent); true }.getOrDefault(false)
    }

    /** The page of the menu the user was on, by the name of its header. */
    fun rememberPage(context: Context, page: String) {
        prefs(context).edit().putString(PAGE, page).apply()
    }

    fun page(context: Context): String = prefs(context).getString(PAGE, "") ?: ""

    /** Whether the wide menu was open or the icon rail stood in its place. */
    fun rememberMenu(context: Context, open: Boolean) {
        prefs(context).edit().putBoolean(MENU, open).apply()
    }

    fun menuOpen(context: Context): Boolean = prefs(context).getBoolean(MENU, true)
}
