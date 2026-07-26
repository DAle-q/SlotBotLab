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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
private val ControlRed = Color(0xFFFF6B6B)
private val CounterButton = Color(0xFF2A3036)

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

    var dayTargets by remember { mutableStateOf(BotRuntime.dayTargets(context)) }
    var activeTargetDay by remember { mutableIntStateOf(BotRuntime.activeTargetDay(context)) }
    var running by remember { mutableStateOf(BotRuntime.isRunning(context)) }
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var overlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var overlayVisible by remember { mutableStateOf(BotRuntime.isOverlayVisible(context)) }
    var detections by remember { mutableIntStateOf(BotRuntime.detections(context)) }
    var clickAttempts by remember { mutableIntStateOf(BotRuntime.clickAttempts(context)) }
    var bookClicks by remember { mutableIntStateOf(BotRuntime.bookClicks(context)) }
    var confirmationClicks by remember { mutableIntStateOf(BotRuntime.confirmationClicks(context)) }
    var refreshAttempts by remember { mutableIntStateOf(BotRuntime.refreshAttempts(context)) }
    var nextRefreshAt by remember { mutableStateOf(BotRuntime.nextRefreshAt(context)) }
    var catchLogs by remember { mutableStateOf(BotRuntime.catchLogs(context)) }
    var activePackage by remember { mutableStateOf(BotRuntime.activePackage(context)) }
    var lastStatus by remember { mutableStateOf(BotRuntime.lastStatus(context)) }
    var lastGesture by remember { mutableStateOf(BotRuntime.lastGesture(context)) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            dayTargets = BotRuntime.dayTargets(context)
            activeTargetDay = BotRuntime.activeTargetDay(context)
            running = BotRuntime.isRunning(context)
            accessibilityEnabled = isAccessibilityServiceEnabled(context)
            overlayPermission = Settings.canDrawOverlays(context)
            overlayVisible = BotRuntime.isOverlayVisible(context)
            detections = BotRuntime.detections(context)
            clickAttempts = BotRuntime.clickAttempts(context)
            bookClicks = BotRuntime.bookClicks(context)
            confirmationClicks = BotRuntime.confirmationClicks(context)
            refreshAttempts = BotRuntime.refreshAttempts(context)
            nextRefreshAt = BotRuntime.nextRefreshAt(context)
            catchLogs = BotRuntime.catchLogs(context)
            activePackage = BotRuntime.activePackage(context)
            lastStatus = BotRuntime.lastStatus(context)
            lastGesture = BotRuntime.lastGesture(context)
            nowMillis = System.currentTimeMillis()
            delay(350L)
        }
    }

    val totalTargets = dayTargets.sum()
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val nextRefreshText = when {
        !running -> "PAUSED"
        nextRefreshAt <= 0L -> "SCANNING"
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            DayTargetPlanner(
                targets = dayTargets,
                activeDay = activeTargetDay,
                onIncrement = { dayIndex ->
                    BotRuntime.incrementDayTarget(context, dayIndex)
                    dayTargets = BotRuntime.dayTargets(context)
                },
                onDecrement = { dayIndex ->
                    BotRuntime.decrementDayTarget(context, dayIndex)
                    dayTargets = BotRuntime.dayTargets(context)
                }
            )

            Text(
                text = "SlotBot Control Center",
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = "One refresh per cycle, then every requested weekday is checked",
                fontSize = 15.sp,
                color = ControlMuted
            )

            StatusCard(
                title = "Slots remaining",
                value = totalTargets.toString(),
                healthy = totalTargets > 0
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
                title = "Next cycle",
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
                    Text("DIAGNOSTICS", color = ControlMuted, fontWeight = FontWeight.Bold)
                    Text("Active package:", color = ControlMuted, fontSize = 14.sp)
                    Text(activePackage, color = Color.White, fontSize = 15.sp)
                    Text("Bot status:", color = ControlMuted, fontSize = 14.sp)
                    Text(lastStatus, color = Color.White, fontSize = 16.sp)
                    Text("Last gesture:", color = ControlMuted, fontSize = 14.sp)
                    Text(lastGesture, color = Color.White, fontSize = 15.sp)
                }
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
                    Text("LIVE STATS", color = ControlMuted, fontWeight = FontWeight.Bold)
                    Text("Refreshes: $refreshAttempts", color = Color.White, fontSize = 17.sp)
                    Text("Detected Book buttons: $detections", color = Color.White, fontSize = 17.sp)
                    Text("Book clicks: $bookClicks", color = Color.White, fontSize = 17.sp)
                    Text("Book session clicks: $confirmationClicks", color = Color.White, fontSize = 17.sp)
                    Text("All successful clicks: $clickAttempts", color = Color.White, fontSize = 17.sp)
                    Text("Refresh range: 01:00-10:00", color = Color.White, fontSize = 17.sp)
                    Text("Expected average: about 10.9 cycles/hour", color = ControlMuted, fontSize = 15.sp)
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
                        BotRuntime.requestImmediateRefresh(context)
                        SlotBotAccessibilityService.wakeForManualRefresh()
                        running = BotRuntime.isRunning(context)
                    }
                },
                enabled = !accessibilityEnabled || totalTargets > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        !accessibilityEnabled -> "ENABLE ACCESSIBILITY FIRST"
                        totalTargets <= 0 -> "SET WEEKDAY TARGETS FIRST"
                        else -> "START BOT"
                    }
                )
            }

            OutlinedButton(
                onClick = {
                    BotRuntime.setRunning(context, true)
                    BotRuntime.requestImmediateRefresh(context)
                    SlotBotAccessibilityService.wakeForManualRefresh()
                    running = BotRuntime.isRunning(context)
                },
                enabled = accessibilityEnabled && totalTargets > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("RUN CYCLE NOW")
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

            OutlinedButton(
                onClick = { openGlovoRider(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("OPEN GLOVO")
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
                        catchLogs.take(15).forEach { entry ->
                            Text(
                                text = "${timeFormatter.format(Date(entry.caughtAtMillis))}  ${entry.slotName}",
                                color = Color.White,
                                fontSize = 15.sp
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
                    refreshAttempts = 0
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
private fun DayTargetPlanner(
    targets: List<Int>,
    activeDay: Int,
    onIncrement: (Int) -> Unit,
    onDecrement: (Int) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ControlCard),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "SLOTS NEEDED",
                color = ControlMuted,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BotRuntime.dayLabels.forEachIndexed { index, label ->
                    val value = targets.getOrElse(index) { 0 }
                    val active = index == activeDay

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (active) ControlGreen else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )

                        CounterButton(
                            text = "+",
                            enabled = value < 20,
                            onClick = { onIncrement(index) }
                        )

                        Text(
                            text = value.toString(),
                            color = if (value > 0) Color.White else ControlMuted,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold
                        )

                        CounterButton(
                            text = "−",
                            enabled = value > 0,
                            onClick = { onDecrement(index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CounterButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) CounterButton else Color(0xFF181B1E))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else Color(0xFF555B61),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
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
                color = if (healthy) ControlGreen else ControlRed,
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
        "com.glovoapp.courier",
        "com.glovoapp.rider"
    ).firstNotNullOfOrNull { packageName ->
        context.packageManager.getLaunchIntentForPackage(packageName)
    }

    launchIntent?.let { context.startActivity(it) }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, SlotBotAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}
