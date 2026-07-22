package com.example.slotbotlab

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val ControlBackground = Color(0xFF101214)
private val ControlCard = Color(0xFF1C2024)
private val ControlGreen = Color(0xFF00A884)
private val ControlMuted = Color(0xFF9AA4AE)

class BotControlActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                BotControlScreen()
            }
        }
    }
}

@Composable
private fun BotControlScreen() {
    val context = LocalContext.current

    var running by remember { mutableStateOf(BotRuntime.isRunning(context)) }
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var detections by remember { mutableIntStateOf(BotRuntime.detections(context)) }
    var clickAttempts by remember { mutableIntStateOf(BotRuntime.clickAttempts(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            running = BotRuntime.isRunning(context)
            accessibilityEnabled = isAccessibilityServiceEnabled(context)
            detections = BotRuntime.detections(context)
            clickAttempts = BotRuntime.clickAttempts(context)
            delay(350L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ControlBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = "SlotBot Control Center",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = "Automation test console",
                fontSize = 16.sp,
                color = ControlMuted
            )

            StatusCard(
                title = "Accessibility service",
                value = if (accessibilityEnabled) "ENABLED" else "DISABLED",
                healthy = accessibilityEnabled
            )

            StatusCard(
                title = "Bot engine",
                value = if (running) "RUNNING" else "STOPPED",
                healthy = running
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = ControlCard),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("LIVE STATS", color = ControlMuted, fontWeight = FontWeight.Bold)
                    Text("Detected Book buttons: $detections", color = Color.White, fontSize = 18.sp)
                    Text("Successful clicks: $clickAttempts", color = Color.White, fontSize = 18.sp)
                    Text("Refreshes: ${MockSessionRepository.refreshCount}", color = Color.White, fontSize = 18.sp)
                    Text("Booked sessions: ${MockSessionRepository.bookedCount}", color = Color.White, fontSize = 18.sp)
                }
            }

            Button(
                onClick = {
                    if (!accessibilityEnabled) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } else {
                        BotRuntime.setRunning(context, true)
                        running = true
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (accessibilityEnabled) "START BOT AND OPEN TEST SCREEN" else "ENABLE ACCESSIBILITY FIRST")
            }

            if (running) {
                OutlinedButton(
                    onClick = {
                        BotRuntime.setRunning(context, false)
                        running = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("STOP BOT")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { MockSessionRepository.createSession() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("CREATE SLOT")
                }

                OutlinedButton(
                    onClick = { MockSessionRepository.clear() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("CLEAR DATA")
                }
            }

            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("OPEN ACCESSIBILITY SETTINGS")
            }

            TextButton(
                onClick = {
                    BotRuntime.resetStats(context)
                    detections = 0
                    clickAttempts = 0
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("RESET STATS")
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    value: String,
    healthy: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ControlCard),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color.White, fontSize = 18.sp)
            Text(
                text = value,
                color = if (healthy) ControlGreen else Color(0xFFFF6B6B),
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        }
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, SlotBotAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}
