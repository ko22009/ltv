package ru.lazytv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The launcher of Android TV asks an app to prepare its home-screen rows (after the install, after a reboot, when the
 * user turns the channel on). Without an answer the "Популярное" row appears only after the app was opened by hand.
 */
class RecommendationsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                runCatching { Recommendations.refresh(app) }
                    .onFailure { android.util.Log.w("Recommendations", "refresh on launcher request failed", it) }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
