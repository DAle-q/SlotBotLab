package com.example.slotbotlab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CatchLogEntry(
    val caughtAtMillis: Long,
    val slotName: String
)

object BotRuntime {
    private const val PREFS = "slot_bot_runtime"
    private const val KEY_RUNNING = "running"
    private const val KEY_INTERVAL_MS = "interval_ms"
    private const val KEY_DETECTIONS = "detections"
    private const val KEY_CLICK_ATTEMPTS = "click_attempts"
    private const val KEY_BOOK_CLICKS = "book_clicks"
    private const val KEY_CONFIRMATION_CLICKS = "confirmation_clicks"
    private const val KEY_REFRESH_ATTEMPTS = "refresh_attempts"
    private const val KEY_OVERLAY_VISIBLE = "overlay_visible"
    private const val KEY_NEXT_REFRESH_AT = "next_refresh_at"
    private const val KEY_CATCH_LOGS = "catch_logs"
    private const val MAX_CATCH_LOGS = 50
    private const val DUPLICATE_CATCH_WINDOW_MS = 30_000L

    fun isRunning(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RUNNING, false)

    fun setRunning(context: Context, running: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, running)
            .apply()

        if (!running) {
            setNextRefreshAt(context, 0L)
        }
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
        prefs(context).edit().putInt(KEY_DETECTIONS, detections(context) + count).apply()
    }

    fun recordClickAttempt(context: Context, count: Int) {
        if (count <= 0) return
        prefs(context).edit().putInt(KEY_CLICK_ATTEMPTS, clickAttempts(context) + count).apply()
    }

    fun recordBookClick(context: Context) {
        prefs(context).edit().putInt(KEY_BOOK_CLICKS, bookClicks(context) + 1).apply()
    }

    fun recordConfirmationClick(context: Context) {
        prefs(context).edit()
            .putInt(KEY_CONFIRMATION_CLICKS, confirmationClicks(context) + 1)
            .apply()
    }

    fun recordRefreshAttempt(context: Context) {
        prefs(context).edit()
            .putInt(KEY_REFRESH_ATTEMPTS, refreshAttempts(context) + 1)
            .apply()
    }

    fun detections(context: Context): Int = prefs(context).getInt(KEY_DETECTIONS, 0)

    fun clickAttempts(context: Context): Int = prefs(context).getInt(KEY_CLICK_ATTEMPTS, 0)

    fun bookClicks(context: Context): Int = prefs(context).getInt(KEY_BOOK_CLICKS, 0)

    fun confirmationClicks(context: Context): Int =
        prefs(context).getInt(KEY_CONFIRMATION_CLICKS, 0)

    fun refreshAttempts(context: Context): Int =
        prefs(context).getInt(KEY_REFRESH_ATTEMPTS, 0)

    fun isOverlayVisible(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY_VISIBLE, false)

    fun setOverlayVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_VISIBLE, visible).apply()
    }

    fun nextRefreshAt(context: Context): Long =
        prefs(context).getLong(KEY_NEXT_REFRESH_AT, 0L)

    fun setNextRefreshAt(context: Context, epochMillis: Long) {
        prefs(context).edit().putLong(KEY_NEXT_REFRESH_AT, epochMillis).apply()
    }

    @Synchronized
    fun recordCatch(
        context: Context,
        slotName: String,
        caughtAtMillis: Long = System.currentTimeMillis()
    ) {
        val normalizedSlotName = slotName.ifBlank { "Unknown session" }
        val existing = catchLogs(context)
        val newest = existing.firstOrNull()

        if (
            newest != null &&
            newest.slotName == normalizedSlotName &&
            caughtAtMillis - newest.caughtAtMillis < DUPLICATE_CATCH_WINDOW_MS
        ) {
            return
        }

        val updated = buildList {
            add(CatchLogEntry(caughtAtMillis, normalizedSlotName))
            addAll(existing)
        }.take(MAX_CATCH_LOGS)

        val json = JSONArray()
        updated.forEach { entry ->
            json.put(
                JSONObject()
                    .put("caughtAtMillis", entry.caughtAtMillis)
                    .put("slotName", entry.slotName)
            )
        }

        prefs(context).edit().putString(KEY_CATCH_LOGS, json.toString()).apply()
    }

    fun catchLogs(context: Context): List<CatchLogEntry> {
        val raw = prefs(context).getString(KEY_CATCH_LOGS, null) ?: return emptyList()

        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.optJSONObject(index) ?: continue
                    add(
                        CatchLogEntry(
                            caughtAtMillis = item.optLong("caughtAtMillis"),
                            slotName = item.optString("slotName", "Unknown session")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clearCatchLogs(context: Context) {
        prefs(context).edit().remove(KEY_CATCH_LOGS).apply()
    }

    fun resetStats(context: Context) {
        prefs(context).edit()
            .putInt(KEY_DETECTIONS, 0)
            .putInt(KEY_CLICK_ATTEMPTS, 0)
            .putInt(KEY_BOOK_CLICKS, 0)
            .putInt(KEY_CONFIRMATION_CLICKS, 0)
            .putInt(KEY_REFRESH_ATTEMPTS, 0)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
