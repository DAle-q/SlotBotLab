package com.example.slotbotlab

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

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
    var detections by remember { mutableIntStateOf(BotRuntime.detections(context)) }
    var clickAttempts by remember { mutableIntStateOf(BotRuntime.clickAttempts(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            running = BotRuntime.isRunning(context)
            detections = BotRuntime.detections(context)
            clickAttempts = BotRuntime.clickAttempts(context)
            delay(400L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "SlotBot Controls",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = if (running) "BOT RUNNING" else "BOT STOPPED",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text("Detected Book buttons: $detections")
        Text("Successful click attempts: $clickAttempts")
        Text("Refreshes: ${MockSessionRepository.refreshCount}")
        Text("Booked: ${MockSessionRepository.bookedCount}")

        Spacer(Modifier.height(22.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = {
                    val next = !running
                    BotRuntime.setRunning(context, next)
                    running = next
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (running) "STOP BOT" else "START BOT")
            }

            OutlinedButton(
                onClick = { MockSessionRepository.createSession() },
                modifier = Modifier.weight(1f)
            ) {
                Text("CREATE SLOT")
            }
        }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                context.startActivity(Intent(context, MainActivity::class.java))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("OPEN TEST SCREEN")
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = {
                BotRuntime.setRunning(context, false)
                running = false
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("OPEN ACCESSIBILITY SETTINGS")
        }

        Spacer(Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(
                onClick = {
                    BotRuntime.resetStats(context)
                    detections = 0
                    clickAttempts = 0
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("RESET STATS")
            }

            TextButton(
                onClick = { MockSessionRepository.clear() },
                modifier = Modifier.weight(1f)
            ) {
                Text("CLEAR TEST DATA")
            }
        }
    }
}
