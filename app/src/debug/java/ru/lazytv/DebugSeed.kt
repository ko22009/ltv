package ru.lazytv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: puts a ready value into a setting so a new screen can be looked at at once, without typing the data by hand.
 * `adb shell am broadcast -a ru.lazytv.SEED -n ru.lazytv/.DebugSeed --es key search_history --es value '["матрица","дюна"]'`
 * (the keys are the constants of [Settings]; the value is written as it is). The screen reads settings when shown: reopen it.
 */
class DebugSeed : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val value = intent.getStringExtra("value") ?: return
        Settings.setString(context, key, value)
    }
}
