package com.example.slotbotlab

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    var overlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var overlayVisible by remember { mutableStateOf(BotRuntime.isOverlayVisible(context)) }
    var detections by remember { mutableIntStateOf(BotRuntime.detections(context)) }
    var clickAttempts by remember { mutableIntStateOf(BotRuntime.clickAttempts(context)) }
    var bookClicks by remember { mutableIntStateOf(BotRuntime.bookClicks(context)) }
    var confirmationClicks by remember { mutableIntStateOf(BotRuntime.confirmationClicks(context)) }
    var nextRefreshAt by remember { mutableStateOf(BotRuntime.nextRefreshAt(context)) }
    var catchLogs by remember { mutableStateOf(BotRuntime.catchLogs(context)) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            running = BotRuntime.isRunning(context)
            accessibilityEnabled = isAccessibilityServiceEnabled(context)
            overlayPermission = Settings.canDrawOverlays(context)
            overlayVisible = BotRuntime.isOverlayVisible(context)
            detections = BotRuntime.detections(context)
            clickAttempts = BotRuntime.clickAttempts(context)
            bookClicks = BotRuntime.bookClicks(context)
            confirmationClicks = BotRuntime.confirmationClicks(context)
            nextRefreshAt = BotRuntime.nextRefreshAt(context)
            catchLogs = BotRuntime.catchLogs(context)
            nowMillis = System.currentTimeMillis()
            delay(350L)
        }
    }

    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val nextRefreshText = when {
        !running -> "PAUSED"
        nextRefreshAt <= 0L -> "SCANNING / WAITING FOR GLOVO"
        nextRefreshAt <= nowMillis -> "NOW"
        else -> timeFormatter.format(Date(nextRefreshAt))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ControlBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = "SlotBot Control Center",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = "Glovo test mode - random refresh every 1 to 10 minutes",
                fontSize = 16.sp,
                color = ControlMuted
            )

            StatusCard(
                title = "Accessibility service",
                value = if (accessibilityEnabled) "ENABLED" else "DISABLED",
                healthy = accessibilityEnabled
            )

            StatusCard(
                title = "Floating controls",
                value = when {
                    !overlayPermission -> "NO PERMISSION"
                    overlayVisible -> "VISIBLE"
                    else -> "HIDDEN"
                },
                healthy = overlayPermission && overlayVisible
            )

            StatusCard(
                title = "Bot engine",
                value = if (running) "RUNNING" else "STOPPED",
                healthy = running
            )

            StatusCard(
                title = "Next refresh",
                value = nextRefreshText,
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
                    Text("Detected Book buttons: $detections", color = Color.White, fontSize = 17.sp)
                    Text("Book clicks: $bookClicks", color = Color.White, fontSize = 17.sp)
                    Text("Book session clicks: $confirmationClicks", color = Color.White, fontSize = 17.sp)
                    Text("All successful clicks: $clickAttempts", color = Color.White, fontSize = 17.sp)
                    Text("Refresh range: 01:00-10:00", color = Color.White, fontSize = 17.sp)
                    Text("Expected average: about 10.9 refreshes/hour", color = ControlMuted, fontSize = 15.sp)
                }
            }

            Button(
                onClick = {
                    when {
                        !overlayPermission -> openOverlayPermission(context)
                        !overlayVisible -> context.startForegroundService(
                            Intent(context, FloatingBotControlService::class.java)
                        )
                        else -> Unit
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        !overlayPermission -> "ALLOW DISPLAY OVER OTHER APPS"
                        overlayVisible -> "FLOATING CONTROLS ARE VISIBLE"
                        else -> "SHOW FLOATING CONTROLS"
                    }
                )
            }

            Button(
                onClick = {
                    if (!accessibilityEnabled) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } else {
                        BotRuntime.setRunning(context, true)
                        running = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (accessibilityEnabled) "START BOT" else "ENABLE ACCESSIBILITY FIRST")
            }

            if (running) {
                OutlinedButton(
                    onClick = {
                        BotRuntime.setRunning(context, false)
                        running = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("PAUSE BOT")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { openGlovoRider(context) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("OPEN GLOVO")
                }

                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("TEST SCREEN")
                }
            }

            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("OPEN ACCESSIBILITY SETTINGS")
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = ControlCard),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("CATCH LOG", color = ControlMuted, fontWeight = FontWeight.Bold)

                    if (catchLogs.isEmpty()) {
                        Text("No sessions caught yet", color = Color.White, fontSize = 16.sp)
                    } else {
                        catchLogs.take(10).forEach { entry ->
                            Text(
                                text = "${timeFormatter.format(Date(entry.caughtAtMillis))}  ${entry.slotName}",
                                color = Color.White,
                                fontSize = 16.sp
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            BotRuntime.clearCatchLogs(context)
                            catchLogs = emptyList()
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("CLEAR CATCH LOG")
                    }
                }
            }

            TextButton(
                onClick = {
                    BotRuntime.resetStats(context)
                    detections = 0
                    clickAttempts = 0
                    bookClicks = 0
                    confirmationClicks = 0
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("RESET STATS")
            }

            Spacer(Modifier.height(16.dp))
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
                fontSize = 15.sp
            )
        }
    }
}

private fun openOverlayPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
    context.startActivity(intent)
}

private fun openGlovoRider(context: Context) {
    val launchIntent = listOf(
        "com.logistics.rider.glovo",
        "com.glovoapp.courier"
    ).firstNotNullOfOrNull { packageName ->
        context.packageManager.getLaunchIntentForPackage(packageName)
    }

    launchIntent?.let(context::startActivity)
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, SlotBotAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}
