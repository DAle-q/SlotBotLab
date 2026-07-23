package com.example.slotbotlab

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class MockSession(
    val id: Int,
    val start: String,
    val end: String,
    val area: String = "BEG WEST",
    val multiplier: String = "1.5x",
    val booked: Boolean = false
)

object MockSessionRepository {
    val sessions = mutableStateListOf<MockSession>()

    var refreshCount by mutableIntStateOf(0)
        private set

    var bookedCount by mutableIntStateOf(0)
        private set

    var pendingSessionId by mutableStateOf<Int?>(null)
        private set

    var lastEvent by mutableStateOf("Waiting for the first refresh")
        private set

    private var nextId = 1
    private var applicationContext: Context? = null

    fun attachApplication(context: Context) {
        applicationContext = context.applicationContext
    }

    fun onRefresh() {
        refreshCount++

        // Deterministic test behavior:
        // every third refresh creates exactly one new available session.
        if (refreshCount % 3 == 0) {
            createSession()
            lastEvent = "A new test session appeared"
        } else {
            lastEvent = "Refresh completed, no new session"
        }
    }

    fun createSession() {
        val id = nextId++
        val startHour = 10 + ((id - 1) * 2) % 12
        val duration = if (id % 2 == 0) 2 else 3
        val endHour = (startHour + duration).coerceAtMost(23)

        sessions.add(
            0,
            MockSession(
                id = id,
                start = "%02d:00".format(startHour),
                end = "%02d:00".format(endHour)
            )
        )
        lastEvent = "Test session #$id created"
    }

    /**
     * First booking step. The existing mock UI already calls book(id) when its Book button is
     * pressed, so this method now opens the confirmation screen instead of booking immediately.
     */
    fun book(id: Int) {
        val session = sessions.firstOrNull { it.id == id } ?: return
        if (session.booked || pendingSessionId != null) return

        val context = applicationContext ?: return

        pendingSessionId = id
        lastEvent = "Waiting for confirmation for session #$id"

        context.startActivity(
            Intent(context, BookingConfirmationActivity::class.java)
                .putExtra(BookingConfirmationActivity.EXTRA_SESSION_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    fun sessionById(id: Int): MockSession? = sessions.firstOrNull { it.id == id }

    fun confirmBooking(id: Int) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index == -1 || sessions[index].booked) {
            pendingSessionId = null
            return
        }

        sessions[index] = sessions[index].copy(booked = true)
        bookedCount++
        pendingSessionId = null
        lastEvent = "Session #$id booked"
    }

    fun cancelBooking(id: Int) {
        if (pendingSessionId == id) {
            pendingSessionId = null
            lastEvent = "Booking confirmation cancelled for session #$id"
        }
    }

    fun clear() {
        sessions.clear()
        refreshCount = 0
        bookedCount = 0
        pendingSessionId = null
        nextId = 1
        lastEvent = "Test state cleared"
    }
}
