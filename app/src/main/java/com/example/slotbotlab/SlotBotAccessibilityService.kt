package com.example.slotbotlab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class SlotBotAccessibilityService : AccessibilityService() {

    private data class ScreenPoint(
        val x: Float,
        val y: Float,
        val source: String
    )

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var loopScheduled = false

    private var cycleActive = false
    private var cycleDays = mutableListOf<Int>()
    private var currentDayIndex: Int? = null

    private var waitingForConfirmation = false
    private var confirmationPollsLeft = 0
    private var pendingSlotName = "Unknown session"

    private val loop = object : Runnable {
        override fun run() {
            val foregroundPackage = rootInActiveWindow?.packageName?.toString()
            rememberExternalPackage(foregroundPackage)

            if (!BotRuntime.isRunning(this@SlotBotAccessibilityService)) {
                resetCycleState()
                BotRuntime.setNextRefreshAt(this@SlotBotAccessibilityService, 0L)
                BotRuntime.setStatus(this@SlotBotAccessibilityService, "Paused")
                handler.postDelayed(this, STOPPED_RECHECK_MS)
                return
            }

            if (BotRuntime.totalDayTargets(this@SlotBotAccessibilityService) <= 0) {
                finishPlan()
                handler.postDelayed(this, STOPPED_RECHECK_MS)
                return
            }

            if (BotRuntime.consumeImmediateRefreshRequest(this@SlotBotAccessibilityService)) {
                BotRuntime.setNextRefreshAt(this@SlotBotAccessibilityService, 0L)
                resetCycleState()
            }

            val now = System.currentTimeMillis()
            val nextRefreshAt = BotRuntime.nextRefreshAt(this@SlotBotAccessibilityService)
            if (nextRefreshAt > now) {
                BotRuntime.setStatus(
                    this@SlotBotAccessibilityService,
                    "Waiting for ${timeFormatter.format(Date(nextRefreshAt))}"
                )
                handler.postDelayed(
                    this,
                    minOf(SCHEDULE_HEARTBEAT_MS, nextRefreshAt - now)
                )
                return
            }

            val roots = supportedRoots()
            if (roots.isEmpty()) {
                BotRuntime.setNextRefreshAt(this@SlotBotAccessibilityService, 0L)
                BotRuntime.setStatus(
                    this@SlotBotAccessibilityService,
                    "Open Available sessions (${foregroundPackage ?: "no active package"})"
                )
                handler.postDelayed(this, OUTSIDE_SUPPORTED_APP_RECHECK_MS)
                return
            }

            roots.firstOrNull { it.packageName?.toString() != packageName }
                ?.packageName
                ?.toString()
                ?.let { BotRuntime.setActivePackage(this@SlotBotAccessibilityService, it) }

            if (!cycleActive) {
                beginCycle()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        BotRuntime.setStatus(this, "Accessibility service connected")

        if (!loopScheduled) {
            loopScheduled = true
            handler.post(loop)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        rememberExternalPackage(event?.packageName?.toString())
    }

    override fun onInterrupt() {
        resetCycleState()
        BotRuntime.setRunning(this, false)
        BotRuntime.setStatus(this, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        resetCycleState()
        BotRuntime.setNextRefreshAt(this, 0L)
        BotRuntime.setStatus(this, "Accessibility service stopped")
        loopScheduled = false
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    private fun rememberExternalPackage(candidate: String?) {
        if (
            !candidate.isNullOrBlank() &&
            candidate != packageName &&
            !candidate.startsWith("com.android.systemui")
        ) {
            BotRuntime.setActivePackage(this, candidate)
        }
    }

    private fun wakeImmediately() {
        handler.removeCallbacks(loop)
        resetCycleState()
        BotRuntime.setNextRefreshAt(this, 0L)
        BotRuntime.setStatus(this, "Immediate cycle requested")
        handler.post(loop)
    }

    private fun beginCycle() {
        val requestedDays = BotRuntime.requestedDayIndices(this)
        if (requestedDays.isEmpty()) {
            finishPlan()
            return
        }

        cycleActive = true
        cycleDays = requestedDays.toMutableList()
        currentDayIndex = null
        resetPendingBooking()

        BotRuntime.setStatus(
            this,
            "Refreshing before checking ${requestedDays.joinToString { BotRuntime.dayLabels[it] }}"
        )

        performKnownGoodRefreshSwipe {
            handler.postDelayed(
                { selectNextRequestedDay() },
                REFRESH_SETTLE_MS
            )
        }
    }

    private fun selectNextRequestedDay() {
        if (!BotRuntime.isRunning(this)) {
            resetCycleState()
            handler.postDelayed(loop, STOPPED_RECHECK_MS)
            return
        }

        if (BotRuntime.totalDayTargets(this) <= 0) {
            finishPlan()
            return
        }

        while (cycleDays.isNotEmpty() && BotRuntime.dayTarget(this, cycleDays.first()) <= 0) {
            cycleDays.removeAt(0)
        }

        if (cycleDays.isEmpty()) {
            finishCycleAndScheduleNext()
            return
        }

        val dayIndex = cycleDays.removeAt(0)
        currentDayIndex = dayIndex
        BotRuntime.setActiveTargetDay(this, dayIndex)

        switchToDay(
            dayIndex = dayIndex,
            attempt = 0,
            onReady = {
                handler.postDelayed(
                    { scanCurrentDay(attempt = 0) },
                    randomDelayMs(DAY_SWITCH_SETTLE_MIN_MS, DAY_SWITCH_SETTLE_MAX_MS)
                )
            }
        )
    }

    private fun switchToDay(
        dayIndex: Int,
        attempt: Int,
        onReady: () -> Unit
    ) {
        if (dayIndex !in BotRuntime.dayLabels.indices) {
            selectNextRequestedDay()
            return
        }

        val roots = supportedRoots()
        if (roots.isEmpty()) {
            if (attempt < MAX_CALENDAR_TAP_RETRIES) {
                handler.postDelayed(
                    { switchToDay(dayIndex, attempt + 1, onReady) },
                    CALENDAR_RETRY_MS
                )
            } else {
                selectNextRequestedDay()
            }
            return
        }

        val point = resolveCalendarPoint(dayIndex, roots)
        val label = BotRuntime.dayLabels[dayIndex]

        BotRuntime.setStatus(
            this,
            "Selecting $label near ${point.x.toInt()},${point.y.toInt()} (${point.source})"
        )

        dispatchCalendarTap(
            x = point.x,
            y = point.y,
            label = label,
            onAccepted = onReady,
            onRejected = {
                if (attempt < MAX_CALENDAR_TAP_RETRIES) {
                    handler.postDelayed(
                        { switchToDay(dayIndex, attempt + 1, onReady) },
                        CALENDAR_RETRY_MS
                    )
                } else {
                    BotRuntime.setStatus(this, "Skipping $label after failed calendar taps")
                    selectNextRequestedDay()
                }
            }
        )
    }

    private fun resolveCalendarPoint(
        dayIndex: Int,
        roots: List<AccessibilityNodeInfo>
    ): ScreenPoint {
        findWeekdayLabelPoint(dayIndex, roots)?.let { return it }
        findDateRowPoint(dayIndex, roots)?.let { return it }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val firstX = width * CALENDAR_FIRST_DAY_X_RATIO
        val lastX = width * CALENDAR_LAST_DAY_X_RATIO
        val x = firstX + ((lastX - firstX) / 6f) * dayIndex
        val y = (width * CALENDAR_DATE_Y_TO_WIDTH_RATIO)
            .coerceIn(height * 0.25f, height * 0.42f)

        return ScreenPoint(x, y, "fallback")
    }

    private fun findWeekdayLabelPoint(
        dayIndex: Int,
        roots: List<AccessibilityNodeInfo>
    ): ScreenPoint? {
        val acceptedLabels = WEEKDAY_NODE_LABELS[dayIndex]
        val metrics = resources.displayMetrics
        val screenHeight = metrics.heightPixels
        var best: ScreenPoint? = null

        roots.forEach { root ->
            walkTree(root) { node ->
                if (!node.isVisibleToUser || !node.isEnabled) return@walkTree
                val labels = labelsOf(node)
                if (labels.none { candidate ->
                        acceptedLabels.any { it.equals(candidate, ignoreCase = true) }
                    }
                ) {
                    return@walkTree
                }

                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) return@walkTree
                if (bounds.centerY() !in (screenHeight * 0.12f).toInt()..(screenHeight * 0.42f).toInt()) {
                    return@walkTree
                }

                val point = ScreenPoint(
                    x = bounds.exactCenterX(),
                    y = bounds.bottom + dp(34).toFloat(),
                    source = "weekday label"
                )

                if (best == null || bounds.centerY() > best!!.y - dp(34)) {
                    best = point
                }
            }
        }

        return best
    }

    private fun findDateRowPoint(
        dayIndex: Int,
        roots: List<AccessibilityNodeInfo>
    ): ScreenPoint? {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val candidates = mutableListOf<ScreenPoint>()

        roots.forEach { root ->
            walkTree(root) { node ->
                if (!node.isVisibleToUser || !node.isEnabled) return@walkTree
                val text = node.text?.toString()?.trim() ?: return@walkTree
                if (!DAY_NUMBER_REGEX.matches(text)) return@walkTree

                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) return@walkTree
                if (bounds.centerY() < height * 0.18f || bounds.centerY() > height * 0.45f) {
                    return@walkTree
                }
                if (bounds.width() > width * 0.25f) return@walkTree

                candidates.add(
                    ScreenPoint(
                        x = bounds.exactCenterX(),
                        y = bounds.exactCenterY(),
                        source = "date node"
                    )
                )
            }
        }

        if (candidates.size < 4) return null

        val bestRow = candidates
            .groupBy { point -> (point.y / dp(18).coerceAtLeast(1)).toInt() }
            .values
            .maxByOrNull { row -> row.map { it.x }.distinct().size }
            ?.sortedBy { it.x }
            ?: return null

        if (bestRow.size < 4) return null

        val rowY = bestRow.map { it.y }.average().toFloat()
        val firstX = width * CALENDAR_FIRST_DAY_X_RATIO
        val lastX = width * CALENDAR_LAST_DAY_X_RATIO
        val targetX = firstX + ((lastX - firstX) / 6f) * dayIndex
        val nearest = bestRow.minByOrNull { abs(it.x - targetX) }

        return if (nearest != null && abs(nearest.x - targetX) < width * 0.09f) {
            ScreenPoint(nearest.x, rowY, "date row")
        } else {
            ScreenPoint(targetX, rowY, "date-row interpolation")
        }
    }

    private fun dispatchCalendarTap(
        x: Float,
        y: Float,
        label: String,
        onAccepted: () -> Unit,
        onRejected: () -> Unit
    ) {
        val point = randomizedPointAround(
            x = x,
            y = y,
            maxXJitterDp = CALENDAR_TAP_JITTER_X_DP,
            maxYJitterDp = CALENDAR_TAP_JITTER_Y_DP
        )
        val preTapDelay = randomDelayMs(
            CALENDAR_PRE_TAP_DELAY_MIN_MS,
            CALENDAR_PRE_TAP_DELAY_MAX_MS
        )
        val tapDuration = randomDelayMs(TAP_DURATION_MIN_MS, TAP_DURATION_MAX_MS)
        val description =
            "calendar $label at ${point.x.toInt()},${point.y.toInt()} delay=${preTapDelay}ms"

        BotRuntime.setLastGesture(this, "Queued $description")

        handler.postDelayed({
            if (!BotRuntime.isRunning(this)) return@postDelayed

            val path = Path().apply { moveTo(point.x, point.y) }
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0L,
                        tapDuration
                    )
                )
                .build()

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        BotRuntime.setLastGesture(
                            this@SlotBotAccessibilityService,
                            "Completed at ${timeFormatter.format(Date())}: $description"
                        )
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        BotRuntime.setLastGesture(
                            this@SlotBotAccessibilityService,
                            "Cancelled at ${timeFormatter.format(Date())}: $description"
                        )
                    }
                },
                handler
            )

            if (!dispatched) {
                BotRuntime.setLastGesture(this, "Android rejected $description")
                onRejected()
                return@postDelayed
            }

            BotRuntime.setLastGesture(this, "Dispatched $description")
            handler.postDelayed(
                { onAccepted() },
                randomDelayMs(
                    CALENDAR_CONTINUE_DELAY_MIN_MS,
                    CALENDAR_CONTINUE_DELAY_MAX_MS
                )
            )
        }, preTapDelay)
    }

    private fun scanCurrentDay(attempt: Int) {
        if (!BotRuntime.isRunning(this)) {
            resetCycleState()
            handler.postDelayed(loop, STOPPED_RECHECK_MS)
            return
        }

        val plannedDay = currentDayIndex
        if (plannedDay == null) {
            selectNextRequestedDay()
            return
        }

        if (BotRuntime.dayTarget(this, plannedDay) <= 0) {
            selectNextRequestedDay()
            return
        }

        val roots = supportedRoots()
        if (roots.isEmpty()) {
            BotRuntime.setStatus(this, "Lost Available sessions screen")
            resetCycleState()
            BotRuntime.setNextRefreshAt(this, 0L)
            handler.postDelayed(loop, OUTSIDE_SUPPORTED_APP_RECHECK_MS)
            return
        }

        val confirmationTargets = findExactClickTargets(CONFIRM_BOOK_TEXT, roots)
        if (confirmationTargets.isNotEmpty()) {
            if (waitingForConfirmation) {
                BotRuntime.recordBookClick(this)
                waitingForConfirmation = false
            }

            val actualDay = extractConfirmationWeekday(roots) ?: plannedDay
            val session = extractSessionDescriptor(roots)
                .takeUnless { it == "Unknown session" }
                ?: pendingSlotName
            val logName = "${BotRuntime.dayLabels[actualDay]} | $session"

            BotRuntime.setStatus(this, "Confirming $logName")
            tapNodeByCoordinates(
                node = confirmationTargets.first(),
                label = CONFIRM_BOOK_TEXT,
                onCompleted = {
                    BotRuntime.recordClickAttempt(this, 1)
                    BotRuntime.recordConfirmationClick(this)
                    BotRuntime.recordCatch(this, logName)

                    val targetDayToDecrement = if (BotRuntime.dayTarget(this, actualDay) > 0) {
                        actualDay
                    } else {
                        plannedDay
                    }
                    val remaining = BotRuntime.decrementDayTarget(this, targetDayToDecrement)
                    resetPendingBooking()

                    BotRuntime.setStatus(
                        this,
                        "Caught $logName - ${BotRuntime.dayLabels[targetDayToDecrement]} remaining: $remaining"
                    )

                    handler.postDelayed(
                        {
                            when {
                                BotRuntime.totalDayTargets(this) <= 0 -> finishPlan()
                                targetDayToDecrement == plannedDay && remaining > 0 -> {
                                    scanCurrentDay(attempt = 0)
                                }
                                else -> selectNextRequestedDay()
                            }
                        },
                        POST_CONFIRM_SETTLE_MS
                    )
                },
                onFailed = {
                    BotRuntime.setStatus(this, "Book session tap failed")
                    resetPendingBooking()
                    selectNextRequestedDay()
                }
            )
            return
        }

        if (waitingForConfirmation) {
            if (confirmationPollsLeft > 0) {
                confirmationPollsLeft--
                BotRuntime.setStatus(
                    this,
                    "Waiting for Book session (${confirmationPollsLeft + 1})"
                )
                handler.postDelayed(
                    { scanCurrentDay(attempt = 0) },
                    CONFIRMATION_POLL_DELAY_MS
                )
            } else {
                BotRuntime.setStatus(this, "Book tap did not open confirmation")
                resetPendingBooking()
                handler.postDelayed(
                    { selectNextRequestedDay() },
                    FAILED_BOOK_COOLDOWN_MS
                )
            }
            return
        }

        val bookTargets = findExactClickTargets(BOOK_TEXT, roots)
        if (bookTargets.isNotEmpty()) {
            BotRuntime.recordDetection(this, bookTargets.size)
            pendingSlotName = extractSessionDescriptorNearNode(bookTargets.first())
            waitingForConfirmation = true
            confirmationPollsLeft = MAX_CONFIRMATION_POLLS

            BotRuntime.setStatus(
                this,
                "Tapping Book on ${BotRuntime.dayLabels[plannedDay]} for $pendingSlotName"
            )
            tapNodeByCoordinates(
                node = bookTargets.first(),
                label = BOOK_TEXT,
                onCompleted = {
                    BotRuntime.recordClickAttempt(this, 1)
                    BotRuntime.setStatus(this, "Book tapped - waiting for confirmation")
                    handler.postDelayed(
                        { scanCurrentDay(attempt = 0) },
                        CONFIRMATION_POLL_DELAY_MS
                    )
                },
                onFailed = {
                    BotRuntime.setStatus(this, "Book coordinate tap failed")
                    resetPendingBooking()
                    selectNextRequestedDay()
                }
            )
            return
        }

        if (attempt < MAX_SCAN_RETRIES) {
            handler.postDelayed(
                { scanCurrentDay(attempt + 1) },
                SCAN_RETRY_DELAY_MS
            )
        } else {
            BotRuntime.setStatus(
                this,
                "No slot on ${BotRuntime.dayLabels[plannedDay]} - checking next day"
            )
            selectNextRequestedDay()
        }
    }

    private fun extractConfirmationWeekday(
        roots: List<AccessibilityNodeInfo>
    ): Int? {
        var result: Int? = null
        roots.forEach { root ->
            walkTree(root) { node ->
                if (result != null || !node.isVisibleToUser) return@walkTree
                labelsOf(node).forEach { label ->
                    FULL_WEEKDAY_NAMES.forEachIndexed { index, weekday ->
                        if (label.contains(weekday, ignoreCase = true)) {
                            result = index
                            return@forEachIndexed
                        }
                    }
                }
            }
        }
        return result
    }

    private fun performKnownGoodRefreshSwipe(onFinished: () -> Unit) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val x = width * 0.50f
        val startY = (width * REFRESH_START_Y_TO_WIDTH_RATIO)
            .coerceAtMost(height - dp(220))
        val endY = (startY + width * REFRESH_DISTANCE_TO_WIDTH_RATIO)
            .coerceAtMost(height - dp(35))

        if (endY - startY < dp(120)) {
            BotRuntime.setStatus(this, "Refresh area is too small")
            handler.postDelayed({ onFinished() }, 400L)
            return
        }

        val description =
            "${startY.toInt()}→${endY.toInt()} x=${x.toInt()} fixed-width-scaled"
        BotRuntime.setLastGesture(this, "Dispatched $description")
        BotRuntime.setStatus(this, "Dispatching refresh swipe")

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    REFRESH_GESTURE_DURATION_MS
                )
            )
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    BotRuntime.recordRefreshAttempt(this@SlotBotAccessibilityService)
                    BotRuntime.setLastGesture(
                        this@SlotBotAccessibilityService,
                        "Completed at ${timeFormatter.format(Date())}: $description"
                    )
                    BotRuntime.setStatus(this@SlotBotAccessibilityService, "Refresh completed")
                    onFinished()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    BotRuntime.setLastGesture(
                        this@SlotBotAccessibilityService,
                        "Cancelled at ${timeFormatter.format(Date())}: $description"
                    )
                    BotRuntime.setStatus(this@SlotBotAccessibilityService, "Refresh cancelled")
                    handler.postDelayed({ onFinished() }, 500L)
                }
            },
            handler
        )

        if (!dispatched) {
            BotRuntime.setLastGesture(this, "Android rejected refresh: $description")
            BotRuntime.setStatus(this, "Android rejected refresh swipe")
            handler.postDelayed({ onFinished() }, 500L)
        }
    }

    private fun finishCycleAndScheduleNext() {
        resetCycleState()

        if (!BotRuntime.isRunning(this)) {
            BotRuntime.setNextRefreshAt(this, 0L)
            handler.postDelayed(loop, STOPPED_RECHECK_MS)
            return
        }

        if (BotRuntime.totalDayTargets(this) <= 0) {
            finishPlan()
            return
        }

        val delayMs = Random.nextLong(
            MIN_REFRESH_INTERVAL_MS,
            MAX_REFRESH_INTERVAL_MS + 1L
        )
        val next = System.currentTimeMillis() + delayMs
        BotRuntime.setNextRefreshAt(this, next)
        BotRuntime.setStatus(this, "Next cycle at ${timeFormatter.format(Date(next))}")
        handler.postDelayed(loop, SCHEDULE_HEARTBEAT_MS)
    }

    private fun finishPlan() {
        resetCycleState()
        BotRuntime.setRunning(this, false)
        BotRuntime.setActiveTargetDay(this, null)
        BotRuntime.setStatus(this, "Plan completed - all weekday targets are zero")
    }

    private fun resetPendingBooking() {
        waitingForConfirmation = false
        confirmationPollsLeft = 0
        pendingSlotName = "Unknown session"
    }

    private fun resetCycleState() {
        resetPendingBooking()
        cycleActive = false
        cycleDays.clear()
        currentDayIndex = null
        BotRuntime.setActiveTargetDay(this, null)
    }

    private fun tapNodeByCoordinates(
        node: AccessibilityNodeInfo,
        label: String,
        onCompleted: () -> Unit,
        onFailed: () -> Unit
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty || !node.isVisibleToUser || !node.isEnabled) {
            onFailed()
            return
        }

        val point = randomizedPointInside(bounds)
        val preTapDelay = randomDelayMs(
            BUTTON_PRE_TAP_DELAY_MIN_MS,
            BUTTON_PRE_TAP_DELAY_MAX_MS
        )
        val tapDuration = randomDelayMs(TAP_DURATION_MIN_MS, TAP_DURATION_MAX_MS)
        val description =
            "$label at ${point.x.toInt()},${point.y.toInt()} bounds=${bounds.toShortString()} delay=${preTapDelay}ms"
        BotRuntime.setLastGesture(this, "Tap queued: $description")

        handler.postDelayed({
            if (!BotRuntime.isRunning(this)) return@postDelayed

            val path = Path().apply { moveTo(point.x, point.y) }
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0L,
                        tapDuration
                    )
                )
                .build()

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        BotRuntime.setLastGesture(
                            this@SlotBotAccessibilityService,
                            "Tap completed at ${timeFormatter.format(Date())}: $description"
                        )
                        onCompleted()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        BotRuntime.setLastGesture(
                            this@SlotBotAccessibilityService,
                            "Tap cancelled at ${timeFormatter.format(Date())}: $description"
                        )
                        onFailed()
                    }
                },
                handler
            )

            if (!dispatched) {
                BotRuntime.setLastGesture(this, "Tap rejected: $description")
                onFailed()
            }
        }, preTapDelay)
    }

    private fun randomizedPointInside(bounds: Rect): ScreenPoint {
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val edgeMarginX = minOf(dp(TAP_EDGE_MARGIN_DP), bounds.width() / 4)
        val edgeMarginY = minOf(dp(TAP_EDGE_MARGIN_DP), bounds.height() / 4)

        val minX = bounds.left + edgeMarginX
        val maxX = bounds.right - edgeMarginX
        val minY = bounds.top + edgeMarginY
        val maxY = bounds.bottom - edgeMarginY

        val availableX = ((maxX - minX) / 2).coerceAtLeast(0)
        val availableY = ((maxY - minY) / 2).coerceAtLeast(0)
        val jitterX = minOf(dp(BUTTON_TAP_JITTER_X_DP), availableX)
        val jitterY = minOf(dp(BUTTON_TAP_JITTER_Y_DP), availableY)

        val x = (centerX + randomSignedOffset(jitterX))
            .coerceIn(minX.toFloat(), maxX.toFloat())
        val y = (centerY + randomSignedOffset(jitterY))
            .coerceIn(minY.toFloat(), maxY.toFloat())

        return ScreenPoint(x, y, "button bounds")
    }

    private fun randomizedPointAround(
        x: Float,
        y: Float,
        maxXJitterDp: Int,
        maxYJitterDp: Int
    ): ScreenPoint {
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - 1).coerceAtLeast(1).toFloat()
        val maxY = (metrics.heightPixels - 1).coerceAtLeast(1).toFloat()

        return ScreenPoint(
            x = (x + randomSignedOffset(dp(maxXJitterDp))).coerceIn(1f, maxX),
            y = (y + randomSignedOffset(dp(maxYJitterDp))).coerceIn(1f, maxY),
            source = "bounded jitter"
        )
    }

    private fun randomSignedOffset(maxAbsolutePx: Int): Float {
        if (maxAbsolutePx <= 0) return 0f
        return Random.nextInt(-maxAbsolutePx, maxAbsolutePx + 1).toFloat()
    }

    private fun randomDelayMs(minInclusive: Long, maxInclusive: Long): Long {
        if (maxInclusive <= minInclusive) return minInclusive
        return Random.nextLong(minInclusive, maxInclusive + 1L)
    }

    private fun supportedRoots(): List<AccessibilityNodeInfo> {
        val roots = buildList {
            rootInActiveWindow?.let(::add)
            windows.mapNotNull { it.root }.forEach(::add)
        }

        val unique = linkedMapOf<String, AccessibilityNodeInfo>()
        roots.forEach { root ->
            val rootPackageName = root.packageName?.toString() ?: return@forEach
            val supportedByPackage = rootPackageName in SUPPORTED_PACKAGES
            val supportedBySignature = rootMatchesTargetSignature(root)
            if (!supportedByPackage && !supportedBySignature) return@forEach

            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            unique.putIfAbsent("$rootPackageName:${bounds.toShortString()}", root)
        }

        return unique.values.toList()
    }

    private fun rootMatchesTargetSignature(root: AccessibilityNodeInfo): Boolean {
        var score = 0
        walkTree(root) { node ->
            if (score >= 4 || !node.isVisibleToUser) return@walkTree
            labelsOf(node).forEach { label ->
                score += when {
                    label.equals("Available sessions", ignoreCase = true) -> 2
                    label.equals("AVAILABLE SESSIONS", ignoreCase = true) -> 2
                    label.equals("MY SESSIONS", ignoreCase = true) -> 1
                    label.startsWith("Applied filters", ignoreCase = true) -> 1
                    label.startsWith("Filters (", ignoreCase = true) -> 1
                    label.equals("Filters", ignoreCase = true) -> 1
                    label.contains("No sessions matching", ignoreCase = true) -> 1
                    label.equals("Book this session?", ignoreCase = true) -> 2
                    label.equals(CONFIRM_BOOK_TEXT, ignoreCase = true) -> 2
                    else -> 0
                }
            }
        }
        return score >= 3
    }

    private fun findExactClickTargets(
        exactLabel: String,
        roots: List<AccessibilityNodeInfo>
    ): List<AccessibilityNodeInfo> {
        val uniqueTargets = linkedMapOf<String, AccessibilityNodeInfo>()

        roots.forEach { root ->
            walkTree(root) { node ->
                if (!node.isVisibleToUser || !node.isEnabled) return@walkTree
                if (labelsOf(node).none { it.equals(exactLabel, ignoreCase = true) }) {
                    return@walkTree
                }

                val target = findClickableAncestor(node) ?: node
                val bounds = Rect()
                target.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    uniqueTargets.putIfAbsent(bounds.toShortString(), target)
                }
            }
        }

        return uniqueTargets.values.sortedBy {
            Rect().also(it::getBoundsInScreen).top
        }
    }

    private fun extractSessionDescriptor(
        roots: List<AccessibilityNodeInfo>
    ): String {
        val labels = mutableListOf<String>()
        roots.forEach { root ->
            walkTree(root) { node ->
                if (node.isVisibleToUser) labels.addAll(labelsOf(node))
            }
        }

        val time = labels.firstNotNullOfOrNull {
            SESSION_TIME_REGEX.find(it)?.value
        }
        val area = labels.firstOrNull { AREA_REGEX.matches(it) }

        return listOfNotNull(time, area)
            .distinct()
            .joinToString(" | ")
            .ifBlank { "Unknown session" }
    }

    private fun extractSessionDescriptorNearNode(
        node: AccessibilityNodeInfo
    ): String {
        val buttonBounds = Rect()
        node.getBoundsInScreen(buttonBounds)
        val roots = supportedRoots()
        val candidates = mutableListOf<Pair<Int, String>>()

        roots.forEach { root ->
            walkTree(root) { candidate ->
                if (!candidate.isVisibleToUser) return@walkTree

                val bounds = Rect()
                candidate.getBoundsInScreen(bounds)
                if (bounds.isEmpty) return@walkTree

                val distance = abs(bounds.centerY() - buttonBounds.centerY())
                labelsOf(candidate).forEach { label ->
                    if (
                        SESSION_TIME_REGEX.containsMatchIn(label) ||
                        AREA_REGEX.matches(label)
                    ) {
                        candidates.add(distance to label)
                    }
                }
            }
        }

        val time = candidates
            .filter { SESSION_TIME_REGEX.containsMatchIn(it.second) }
            .minByOrNull { it.first }
            ?.second
            ?.let { SESSION_TIME_REGEX.find(it)?.value }

        val area = candidates
            .filter { AREA_REGEX.matches(it.second) }
            .minByOrNull { it.first }
            ?.second

        return listOfNotNull(time, area)
            .distinct()
            .joinToString(" | ")
            .ifBlank { "Unknown session" }
    }

    private fun labelsOf(node: AccessibilityNodeInfo): List<String> =
        listOfNotNull(
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }
        ).distinct()

    private inline fun walkTree(
        root: AccessibilityNodeInfo,
        visit: (AccessibilityNodeInfo) -> Unit
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            visit(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
    }

    private fun findClickableAncestor(
        startNode: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = startNode

        repeat(10) {
            val current = node ?: return null
            val hasClickAction = current.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_CLICK
            }

            if (
                current.isEnabled &&
                current.isVisibleToUser &&
                (current.isClickable || hasClickAction)
            ) {
                return current
            }

            node = current.parent
        }

        return null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        private var activeInstance: SlotBotAccessibilityService? = null

        fun wakeForManualRefresh() {
            activeInstance?.wakeImmediately()
        }

        private const val BOOK_TEXT = "Book"
        private const val CONFIRM_BOOK_TEXT = "Book session"

        private const val STOPPED_RECHECK_MS = 500L
        private const val SCHEDULE_HEARTBEAT_MS = 500L
        private const val OUTSIDE_SUPPORTED_APP_RECHECK_MS = 1_000L

        private const val MIN_REFRESH_INTERVAL_MS = 60_000L
        private const val MAX_REFRESH_INTERVAL_MS = 600_000L

        private const val MAX_SCAN_RETRIES = 6
        private const val SCAN_RETRY_DELAY_MS = 350L
        private const val REFRESH_SETTLE_MS = 1_400L
        private const val REFRESH_GESTURE_DURATION_MS = 850L

        private const val CONFIRMATION_POLL_DELAY_MS = 350L
        private const val MAX_CONFIRMATION_POLLS = 12
        private const val FAILED_BOOK_COOLDOWN_MS = 1_000L
        private const val POST_CONFIRM_SETTLE_MS = 1_000L

        private const val DAY_SWITCH_SETTLE_MIN_MS = 430L
        private const val DAY_SWITCH_SETTLE_MAX_MS = 620L
        private const val CALENDAR_RETRY_MS = 700L
        private const val MAX_CALENDAR_TAP_RETRIES = 2

        private const val CALENDAR_PRE_TAP_DELAY_MIN_MS = 45L
        private const val CALENDAR_PRE_TAP_DELAY_MAX_MS = 125L
        private const val CALENDAR_CONTINUE_DELAY_MIN_MS = 300L
        private const val CALENDAR_CONTINUE_DELAY_MAX_MS = 460L
        private const val BUTTON_PRE_TAP_DELAY_MIN_MS = 25L
        private const val BUTTON_PRE_TAP_DELAY_MAX_MS = 85L
        private const val TAP_DURATION_MIN_MS = 90L
        private const val TAP_DURATION_MAX_MS = 145L

        private const val CALENDAR_TAP_JITTER_X_DP = 5
        private const val CALENDAR_TAP_JITTER_Y_DP = 4
        private const val BUTTON_TAP_JITTER_X_DP = 6
        private const val BUTTON_TAP_JITTER_Y_DP = 5
        private const val TAP_EDGE_MARGIN_DP = 5

        private const val CALENDAR_FIRST_DAY_X_RATIO = 0.094f
        private const val CALENDAR_LAST_DAY_X_RATIO = 0.906f
        private const val CALENDAR_DATE_Y_TO_WIDTH_RATIO = 0.70f

        private const val REFRESH_START_Y_TO_WIDTH_RATIO = 1.30f
        private const val REFRESH_DISTANCE_TO_WIDTH_RATIO = 0.51f

        private val SUPPORTED_PACKAGES = setOf(
            "com.logistics.rider.glovo",
            "com.glovoapp.courier",
            "com.glovoapp.rider"
        )

        private val WEEKDAY_NODE_LABELS = listOf(
            setOf("M", "Mo", "Mon"),
            setOf("Tu", "Tue"),
            setOf("W", "We", "Wed"),
            setOf("Th", "Thu"),
            setOf("F", "Fr", "Fri"),
            setOf("Sa", "Sat"),
            setOf("Su", "Sun")
        )

        private val FULL_WEEKDAY_NAMES = listOf(
            "Monday",
            "Tuesday",
            "Wednesday",
            "Thursday",
            "Friday",
            "Saturday",
            "Sunday"
        )

        private val DAY_NUMBER_REGEX = Regex("""^(0?[1-9]|[12]\d|3[01])$""")

        private val SESSION_TIME_REGEX = Regex(
            """\b\d{1,2}:\d{2}\s*[-–]\s*\d{1,2}:\d{2}(?:\s*\(\d+h\))?"""
        )

        private val AREA_REGEX = Regex("""(?i)^Beg\s+(east|west)$""")
    }
}
