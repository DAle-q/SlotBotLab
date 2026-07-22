package com.example.slotbotlab

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                SlotBotLabApp()
            }
        }
    }
}

@Composable
private fun SlotBotLabApp() {
    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "SlotBot Lab",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Safe mock screen - automation is restricted to this app",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            BotControlPanel()
            HorizontalDivider()
            MockSessionsScreen(
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BotControlPanel() {
    val context = LocalContext.current
    var running by remember { mutableStateOf(BotRuntime.isRunning(context)) }
    var detections by remember { mutableIntStateOf(BotRuntime.detections(context)) }
    var clickAttempts by remember { mutableIntStateOf(BotRuntime.clickAttempts(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            running = BotRuntime.isRunning(context)
            detections = BotRuntime.detections(context)
            clickAttempts = BotRuntime.clickAttempts(context)
            delay(500L)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (running) "BOT: RUNNING" else "BOT: STOPPED",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Refresh interval: 5 seconds",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Button(
                    onClick = {
                        val next = !running
                        BotRuntime.setRunning(context, next)
                        running = next
                    }
                ) {
                    Text(if (running) "STOP" else "START BOT")
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = "Detected Book buttons: $detections  |  Click attempts: $clickAttempts",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(8.dp))

            Row {
                OutlinedButton(
                    onClick = {
                        // Stop first so the bot cannot swipe while system settings are open.
                        BotRuntime.setRunning(context, false)
                        running = false

                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                ) {
                    Text("OPEN ACCESSIBILITY SETTINGS")
                }

                Spacer(Modifier.width(8.dp))

                TextButton(
                    onClick = {
                        BotRuntime.resetStats(context)
                        detections = 0
                        clickAttempts = 0
                    }
                ) {
                    Text("RESET STATS")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MockSessionsScreen(
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Available sessions",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Applied filters:",
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChipLike("10:00-23:59")
                FilterChipLike("BEG WEST")
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = "Pull the list down to refresh. Every third refresh creates a test slot.",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "Refreshes: ${MockSessionRepository.refreshCount}  |  Booked: ${MockSessionRepository.bookedCount}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = MockSessionRepository.lastEvent,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { MockSessionRepository.createSession() }
                ) {
                    Text("CREATE TEST SESSION")
                }

                TextButton(
                    onClick = { MockSessionRepository.clear() }
                ) {
                    Text("CLEAR")
                }
            }
        }

        HorizontalDivider()

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                if (!isRefreshing) {
                    isRefreshing = true
                    scope.launch {
                        delay(700L)
                        MockSessionRepository.onRefresh()
                        isRefreshing = false
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (MockSessionRepository.sessions.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "No sessions matching your filter",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text("Pull down to refresh")
                            }
                        }
                    }
                } else {
                    items(
                        items = MockSessionRepository.sessions,
                        key = { it.id }
                    ) { session ->
                        SessionCard(session)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipLike(text: String) {
    Card(
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SessionCard(session: MockSession) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "${session.start} - ${session.end}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(5.dp))

                Text(
                    text = session.area,
                    style = MaterialTheme.typography.bodyLarge
                )

                Spacer(Modifier.height(5.dp))

                Text(
                    text = session.multiplier,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = {
                    MockSessionRepository.book(session.id)
                },
                enabled = !session.booked,
                colors = ButtonDefaults.buttonColors()
            ) {
                // Keep this exact visible text.
                // The accessibility bot deliberately searches for exactly "Book".
                Text(if (session.booked) "Booked" else "Book")
            }
        }
    }
}
