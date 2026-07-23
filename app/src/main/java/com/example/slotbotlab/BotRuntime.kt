package com.example.slotbotlab

import android.content.Context

object BotRuntime {
    private const val PREFS = "slot_bot_runtime"
    private const val KEY_RUNNING = "running"
    private const val KEY_INTERVAL_MS = "interval_ms"
    private const val KEY_DETECTIONS = "detections"
    private const val KEY_CLICK_ATTEMPTS = "click_attempts"
    private const val KEY_BOOK_CLICKS = "book_clicks"
    private const val KEY_CONFIRMATION_CLICKS = "confirmation_clicks"

    fun isRunning(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RUNNING, false)

    fun setRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean(KEY_RUNNING, running).apply()
    }

    fun intervalMs(context: Context): Long =
        prefs(context).getLong(KEY_INTERVAL_MS, 5_000L)

    fun setIntervalMs(context: Context, intervalMs: Long) {
        prefs(context).edit()
            .putLong(KEY_INTERVAL_MS, intervalMs.coerceIn(2_000L, 60_000L))
            .apply()
    }

    fun recordDetection(context: Context, count: Int) {
        if (count <= 0) return
        val current = detections(context)
        prefs(context).edit().putInt(KEY_DETECTIONS, current + count).apply()
    }

    fun recordClickAttempt(context: Context, count: Int) {
        if (count <= 0) return
        val current = clickAttempts(context)
        prefs(context).edit().putInt(KEY_CLICK_ATTEMPTS, current + count).apply()
    }

    fun recordBookClick(context: Context) {
        prefs(context).edit().putInt(KEY_BOOK_CLICKS, bookClicks(context) + 1).apply()
    }

    fun recordConfirmationClick(context: Context) {
        prefs(context).edit()
            .putInt(KEY_CONFIRMATION_CLICKS, confirmationClicks(context) + 1)
            .apply()
    }

    fun detections(context: Context): Int =
        prefs(context).getInt(KEY_DETECTIONS, 0)

    fun clickAttempts(context: Context): Int =
        prefs(context).getInt(KEY_CLICK_ATTEMPTS, 0)

    fun bookClicks(context: Context): Int =
        prefs(context).getInt(KEY_BOOK_CLICKS, 0)

    fun confirmationClicks(context: Context): Int =
        prefs(context).getInt(KEY_CONFIRMATION_CLICKS, 0)

    fun resetStats(context: Context) {
        prefs(context).edit()
            .putInt(KEY_DETECTIONS, 0)
            .putInt(KEY_CLICK_ATTEMPTS, 0)
            .putInt(KEY_BOOK_CLICKS, 0)
            .putInt(KEY_CONFIRMATION_CLICKS, 0)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
