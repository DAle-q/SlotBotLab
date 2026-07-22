package com.example.slotbotlab

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val GlovoTeal = Color(0xFF008C73)
private val MintDot = Color(0xFF63C8B8)
private val AlmostBlack = Color(0xFF161616)
private val DividerColor = Color(0xFFE8E8E8)
private val LightPill = Color(0xFFF1F1F1)
private val MutedText = Color(0xFF777777)
private val DisabledText = Color(0xFFD8D8D8)
private val FlameOrange = Color(0xFFC45B0B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = GlovoTeal,
                    background = Color.White,
                    surface = Color.White,
                    onBackground = AlmostBlack,
                    onSurface = AlmostBlack
                )
            ) {
                SlotBotLabApp()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlotBotLabApp() {
    var debugVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        GlovoMockScreen(
            onTitleLongPress = { debugVisible = !debugVisible }
        )

        if (debugVisible) {
            DebugPanel(
                modifier = Modifier.align(Alignment.BottomCenter),
                onClose = { debugVisible = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GlovoMockScreen(
    onTitleLongPress: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Header(onTitleLongPress)
        SessionTabs()
        CalendarSection()

        HorizontalDivider(
            thickness = 1.dp,
            color = DividerColor
        )

        SortAndFilterRow()
        AppliedFilters()

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
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (MockSessionRepository.sessions.isEmpty()) {
                    item {
                        EmptySessionsState()
                    }
                } else {
                    items(
                        items = MockSessionRepository.sessions,
                        key = { it.id }
                    ) { session ->
                        SessionRow(session)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Header(onTitleLongPress: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "←",
            fontSize = 38.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.align(Alignment.CenterStart)
        )

        Text(
            text = "Available sessions",
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = onTitleLongPress
            )
        )
    }
}

@Composable
private fun SessionTabs() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TopTab(
            icon = "▣",
            text = "AVAILABLE SESSIONS",
            selected = true
        )
        TopTab(
            icon = "▣",
            text = "MY SESSIONS",
            selected = false
        )
        TopTab(
            icon = "↶",
            text = "HISTORY",
            selected = false
        )
    }
}

@Composable
private fun TopTab(
    icon: String,
    text: String,
    selected: Boolean
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .background(if (selected) AlmostBlack else Color.White)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = if (selected) Color.Transparent else Color(0xFFD8D8D8),
                shape = RoundedCornerShape(28.dp)
            )
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text(
            text = icon,
            fontSize = 20.sp,
            color = if (selected) Color.White else AlmostBlack
        )
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else Color(0xFF4B4B4B),
            maxLines = 1
        )
    }
}

@Composable
private fun CalendarSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 44.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "July 2026",
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Today",
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = GlovoTeal
            )
        }

        Spacer(Modifier.height(30.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            CalendarDay("Mo", "20", disabled = true)
            CalendarDay("Tu", "21", disabled = true)
            CalendarDay("We", "22", selected = true)
            CalendarDay("Th", "23", hasDot = true)
            CalendarDay("Fr", "24", hasDot = true)
            CalendarDay("Sa", "25", hasDot = true)
            CalendarDay("Su", "26", hasDot = true)
        }
    }
}

@Composable
private fun RowScope.CalendarDay(
    weekday: String,
    date: String,
    selected: Boolean = false,
    disabled: Boolean = false,
    hasDot: Boolean = false
) {
    val textColor = when {
        selected -> Color.White
        disabled -> DisabledText
        weekday == "We" -> GlovoTeal
        else -> Color(0xFF666666)
    }

    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = weekday,
            fontSize = 17.sp,
            color = if (disabled) DisabledText else if (weekday == "We") GlovoTeal else Color(0xFF666666)
        )

        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) GlovoTeal else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = date,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }

        Spacer(Modifier.height(5.dp))

        if (hasDot) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MintDot)
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SortAndFilterRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 30.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "↕",
                fontSize = 25.sp,
                color = Color(0xFF888888)
            )
            Text(
                text = "Earliest start time",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF777777)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "≡",
                fontSize = 26.sp,
                color = GlovoTeal
            )
            Text(
                text = "Filters (2)",
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = GlovoTeal
            )
        }
    }
}

@Composable
private fun AppliedFilters() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 29.dp, vertical = 4.dp)
    ) {
        Text(
            text = "Applied filters:",
            fontSize = 16.sp,
            color = Color(0xFF444444)
        )

        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilterChip("10:00-23:59")
            FilterChip("BEG WEST")
        }

        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun FilterChip(text: String) {
    Row(
        modifier = Modifier
            .border(
                width = 2.dp,
                color = Color(0xFFDEDEDE),
                shape = RoundedCornerShape(28.dp)
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "×",
            fontSize = 25.sp,
            fontWeight = FontWeight.Light
        )
    }
}

@Composable
private fun EmptySessionsState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 55.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "🔭",
            fontSize = 72.sp
        )

        Spacer(Modifier.height(18.dp))

        Text(
            text = "No sessions matching your filter",
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Try another filter or a combination of filters.",
            fontSize = 17.sp,
            color = Color(0xFF666666),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SessionRow(session: MockSession) {
    val startHour = session.start.substringBefore(":").toIntOrNull() ?: 0
    val endHour = session.end.substringBefore(":").toIntOrNull() ?: startHour
    val duration = (endHour - startHour).coerceAtLeast(1)

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 30.dp, end = 30.dp, top = 22.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "${session.start} - ${session.end} (${duration}h)",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "Beg west",
                    fontSize = 20.sp
                )

                Spacer(Modifier.height(13.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "♨",
                        fontSize = 24.sp,
                        color = FlameOrange,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = session.multiplier,
                        fontSize = 20.sp,
                        color = GlovoTeal,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "↗",
                        fontSize = 23.sp,
                        color = GlovoTeal,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Swap",
                        fontSize = 20.sp,
                        color = GlovoTeal,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Button(
                onClick = { MockSessionRepository.book(session.id) },
                enabled = !session.booked,
                modifier = Modifier
                    .width(140.dp)
                    .height(58.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (session.booked) "Booked" else "Book"
                    },
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LightPill,
                    contentColor = AlmostBlack,
                    disabledContainerColor = Color(0xFFE3E3E3),
                    disabledContentColor = Color(0xFF777777)
                ),
                contentPadding = PaddingValues(horizontal = 18.dp)
            ) {
                Text(
                    text = if (session.booked) "Booked" else "Book",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 30.dp),
            thickness = 1.dp,
            color = DividerColor
        )
    }
}

@Composable
private fun DebugPanel(
    modifier: Modifier,
    onClose: () -> Unit
) {
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

    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 18.dp,
        color = Color.White
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SlotBot test controls",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onClose) {
                    Text("CLOSE")
                }
            }

            Text(
                text = if (running) "BOT RUNNING" else "BOT STOPPED",
                fontWeight = FontWeight.Bold,
                color = if (running) GlovoTeal else MutedText
            )
            Text("Detected Book: $detections | Clicks: $clickAttempts")
            Text("Refreshes: ${MockSessionRepository.refreshCount} | Booked: ${MockSessionRepository.bookedCount}")

            Spacer(Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val next = !running
                        BotRuntime.setRunning(context, next)
                        running = next
                    }
                ) {
                    Text(if (running) "STOP" else "START BOT")
                }

                OutlinedButton(
                    onClick = { MockSessionRepository.createSession() }
                ) {
                    Text("CREATE SLOT")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = {
                        BotRuntime.setRunning(context, false)
                        running = false
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                ) {
                    Text("ACCESSIBILITY")
                }

                TextButton(
                    onClick = {
                        BotRuntime.resetStats(context)
                        detections = 0
                        clickAttempts = 0
                    }
                ) {
                    Text("RESET STATS")
                }

                TextButton(
                    onClick = { MockSessionRepository.clear() }
                ) {
                    Text("CLEAR")
                }
            }
        }
    }
}
