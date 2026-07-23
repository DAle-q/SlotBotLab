package com.example.slotbotlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ConfirmationTeal = Color(0xFF008C73)
private val ConfirmationBlack = Color(0xFF161616)
private val ConfirmationOrange = Color(0xFFC45B0B)

class BookingConfirmationActivity : ComponentActivity() {
    private var sessionId: Int = -1
    private var bookingCompleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        val session = MockSessionRepository.sessionById(sessionId)

        if (session == null || session.booked) {
            finish()
            return
        }

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = ConfirmationTeal,
                    background = Color.Transparent,
                    surface = Color.White,
                    onSurface = ConfirmationBlack
                )
            ) {
                BookingConfirmationScreen(
                    session = session,
                    onConfirm = {
                        bookingCompleted = true
                        MockSessionRepository.confirmBooking(session.id)
                        finish()
                    },
                    onBack = {
                        MockSessionRepository.cancelBooking(session.id)
                        finish()
                    }
                )
            }
        }
    }

    override fun finish() {
        if (!bookingCompleted && sessionId != -1) {
            MockSessionRepository.cancelBooking(sessionId)
        }
        super.finish()
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}

@Composable
private fun BookingConfirmationScreen(
    session: MockSession,
    onConfirm: () -> Unit,
    onBack: () -> Unit
) {
    val duration = sessionDurationHours(session)
    val timeText = "${session.start} - ${session.end} (${duration}h)"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.58f))
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = Color.White,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 18.dp)
            ) {
                Text(
                    text = "Book this session?",
                    fontSize = 29.sp,
                    fontWeight = FontWeight.Bold,
                    color = ConfirmationBlack
                )

                Spacer(Modifier.height(28.dp))

                BookingDetailRow("Date", "Wednesday, 22 Jul 2026")
                BookingDetailRow("Time", timeText)
                BookingDetailRow("Area", "Beg west")
                BookingDetailRow(
                    label = "Promo",
                    value = "♨ ${session.multiplier}",
                    valueColor = ConfirmationBlack
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeText,
                        fontSize = 17.sp,
                        color = ConfirmationBlack
                    )
                    Text(
                        text = session.multiplier,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ConfirmationBlack
                    )
                }

                Spacer(Modifier.height(30.dp))

                Text(
                    text = "Try to attend the session after booking it. When you can't attend the session, you can offer it for Swap beforehand.",
                    fontSize = 17.sp,
                    lineHeight = 28.sp,
                    color = ConfirmationBlack
                )

                Spacer(Modifier.height(28.dp))

                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Book session"
                        },
                    shape = RoundedCornerShape(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ConfirmationBlack,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp)
                ) {
                    Text(
                        text = "Book session",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                TextButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Back to sessions",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ConfirmationTeal,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun BookingDetailRow(
    label: String,
    value: String,
    valueColor: Color = ConfirmationBlack
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            color = ConfirmationBlack
        )

        Text(
            text = value,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            textAlign = TextAlign.End
        )
    }
}

private fun sessionDurationHours(session: MockSession): Int {
    val startHour = session.start.substringBefore(":").toIntOrNull() ?: 0
    val endHour = session.end.substringBefore(":").toIntOrNull() ?: startHour
    return (endHour - startHour).coerceAtLeast(1)
}
