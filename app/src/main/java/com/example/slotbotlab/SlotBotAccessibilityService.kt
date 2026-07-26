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
import kotlin.random.Random

class SlotBotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var loopScheduled = false
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var waitingForConfirmation = false
    private var pendingSlotName = "Unknown session"
    private var confirmationPollsLeft = 0

    private val loop = object : Runnable {
        override fun run() {
            val foregroundPackage = rootInActiveWindow?.packageName?.toString()
            rememberExternalPackage(foregroundPackage)

            if (!BotRuntime.isRunning(this@SlotBotAccessibilityService)) {
                resetPendingBooking()
                BotRuntime.setNextRefreshAt(this@SlotBotAccessibilityService, 0L)
                BotRuntime.setStatus(this@SlotBotAccessibilityService, "Paused")
                handler.postDelayed(this, STOPPED_RECHECK_MS)
                return
            }

            if (BotRuntime.consumeImmediateRefreshRequest(this@SlotBotAccessibilityService)) {
                BotRuntime.setNextRefreshAt(this@SlotBotAccessibilityService, 0L)
            }

            val nextRefreshAt = BotRuntime.nextRefreshAt(this@SlotBotAccessibilityService)
            val now = System.currentTimeMillis()
            if (nextRefreshAt > now) {
                BotRuntime.setStatus(
                    this@SlotBotAccessibilityService,
                    "Waiting for ${timeFormatter.format(Date(nextRefreshAt))}"
                )
                handler.postDelayed(this, minOf(SCHEDULE_HEARTBEAT_MS, nextRefreshAt - now))
                return
            }

            val roots = supportedRoots()
            if (roots.isEmpty()) {
                val packageLabel = foregroundPackage ?: "no active package"
                BotRuntime.setStatus(
                    this@SlotBotAccessibilityService,
                    "Waiting for sessions screen ($packageLabel)"
                )
                BotRuntime.setNextRefreshAt(this@SlotBotAccessibilityService, 0L)
                handler.postDelayed(this, OUTSIDE_SUPPORTED_APP_RECHECK_MS)
                return
            }

            roots.firstOrNull { it.packageName?.toString() != packageName }
                ?.packageName
                ?.toString()
                ?.let { BotRuntime.setActivePackage(this@SlotBotAccessibilityService, it) }

            BotRuntime.setNextRefreshAt(this@SlotBotAccessibilityService, 0L)
            BotRuntime.setStatus(this@SlotBotAccessibilityService, "Scanning current screen")
            scanCurrentScreen(attempt = 0, refreshIfEmpty = true)
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
        resetPendingBooking()
        BotRuntime.setRunning(this, false)
        BotRuntime.setStatus(this, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        resetPendingBooking()
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
        BotRuntime.setStatus(this, "Manual refresh wake received")
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    private fun scanCurrentScreen(
        attempt: Int,
        refreshIfEmpty: Boolean
    ) {
        if (!BotRuntime.isRunning(this)) {
            resetPendingBooking()
            BotRuntime.setNextRefreshAt(this, 0L)
            handler.postDelayed(loop, STOPPED_RECHECK_MS)
            return
        }

        val roots = supportedRoots()
        if (roots.isEmpty()) {
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

            val slotName = extractSessionDescriptor(roots)
                .takeUnless { it == "Unknown session" }
                ?: pendingSlotName

            BotRuntime.setStatus(this, "Tapping Book session for $slotName")
            tapNodeByCoordinates(
                node = confirmationTargets.first(),
                label = CONFIRM_BOOK_TEXT,
                onCompleted = {
                    BotRuntime.recordClickAttempt(this, 1)
                    BotRuntime.recordConfirmationClick(this)
                    BotRuntime.recordCatch(this, slotName)
                    BotRuntime.setStatus(this, "Confirmation tap completed for $slotName")
                    resetPendingBooking()
                    handler.postDelayed(
                        { scanCurrentScreen(attempt = 0, refreshIfEmpty = false) },
                        POST_CONFIRM_SETTLE_MS
                    )
                },
                onFailed = {
                    BotRuntime.setStatus(this, "Book session coordinate tap failed")
                    resetPendingBooking()
                    retryOrContinue(attempt, refreshIfEmpty)
                }
            )
            return
        }

        if (waitingForConfirmation) {
            if (confirmationPollsLeft > 0) {
                confirmationPollsLeft--
                BotRuntime.setStatus(
                    this,
                    "Waiting for confirmation (${confirmationPollsLeft + 1})"
                )
                handler.postDelayed(
                    { scanCurrentScreen(attempt = 0, refreshIfEmpty = false) },
                    CONFIRMATION_POLL_DELAY_MS
                )
            } else {
                BotRuntime.setStatus(this, "Book tap did not open confirmation")
                resetPendingBooking()
                handler.postDelayed(
                    { scanCurrentScreen(attempt = 0, refreshIfEmpty = false) },
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

            BotRuntime.setStatus(this, "Coordinate tapping Book for $pendingSlotName")
            tapNodeByCoordinates(
                node = bookTargets.first(),
                label = BOOK_TEXT,
                onCompleted = {
                    BotRuntime.recordClickAttempt(this, 1)
                    BotRuntime.setStatus(this, "Book tap completed, waiting for confirmation")
                    handler.postDelayed(
                        { scanCurrentScreen(attempt = 0, refreshIfEmpty = false) },
                        CONFIRMATION_POLL_DELAY_MS
                    )
                },
                onFailed = {
                    BotRuntime.setStatus(this, "Book coordinate tap failed")
                    resetPendingBooking()
                    retryOrContinue(attempt, refreshIfEmpty)
                }
            )
            return
        }

        retryOrContinue(attempt, refreshIfEmpty)
    }

    private fun resetPendingBooking() {
        waitingForConfirmation = false
        pendingSlotName = "Unknown session"
        confirmationPollsLeft = 0
    }

    private fun retryOrContinue(
        attempt: Int,
        refreshIfEmpty: Boolean
    ) {
        if (attempt < MAX_SCAN_RETRIES) {
            handler.postDelayed(
                { scanCurrentScreen(attempt + 1, refreshIfEmpty) },
                SCAN_RETRY_DELAY_MS
            )
            return
        }

        if (!refreshIfEmpty) {
            scheduleNextRandomRefresh()
            return
        }

        val roots = supportedRoots()
        if (!isAvailableSessionsScreen(roots)) {
            BotRuntime.setStatus(this, "Open the Available sessions screen")
            BotRuntime.setNextRefreshAt(this, 0L)
            handler.postDelayed(loop, OUTSIDE_SESSIONS_SCREEN_RECHECK_MS)
            return
        }

        BotRuntime.setStatus(this, "Preparing pull-to-refresh")
        scrollToTopThenRefresh(step = 0) {
            handler.postDelayed(
                { scanCurrentScreen(attempt = 0, refreshIfEmpty = false) },
                REFRESH_SETTLE_MS
            )
        }
    }

    private fun scheduleNextRandomRefresh() {
        if (!BotRuntime.isRunning(this)) {
            BotRuntime.setNextRefreshAt(this, 0L)
            handler.postDelayed(loop, STOPPED_RECHECK_MS)
            return
        }

        val delayMs = Random.nextLong(MIN_REFRESH_INTERVAL_MS, MAX_REFRESH_INTERVAL_MS + 1L)
        val next = System.currentTimeMillis() + delayMs

        BotRuntime.setNextRefreshAt(this, next)
        BotRuntime.setStatus(this, "Waiting for ${timeFormatter.format(Date(next))}")
        handler.postDelayed(loop, SCHEDULE_HEARTBEAT_MS)
    }

    private fun scrollToTopThenRefresh(
        step: Int,
        onFinished: () -> Unit
    ) {
        val roots = supportedRoots()
        val scrollable = findBestScrollableNode(roots)

        if (
            scrollable != null &&
            step < MAX_SCROLL_TO_TOP_STEPS &&
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        ) {
            BotRuntime.setStatus(this, "Moving session list to top")
            handler.postDelayed(
                { scrollToTopThenRefresh(step + 1, onFinished) },
                SCROLL_TO_TOP_SETTLE_MS
            )
            return
        }

        val scrollableBounds = scrollable?.let { Rect().also(it::getBoundsInScreen) }
        performPullToRefresh(roots, scrollableBounds, onFinished)
    }

    private fun performPullToRefresh(
        roots: List<AccessibilityNodeInfo>,
        preferredBounds: Rect?,
        onFinished: () -> Unit
    ) {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val safeBottom = screenHeight - dp(105)

        val usable = preferredBounds
            ?.takeIf {
                !it.isEmpty &&
                    it.width() > screenWidth * 0.35f &&
                    it.height() > screenHeight * 0.15f
            }
            ?.let { Rect(it) }
            ?: Rect(
                (screenWidth * 0.08f).toInt(),
                (screenHeight * 0.30f).toInt(),
                (screenWidth * 0.92f).toInt(),
                (screenHeight * 0.82f).toInt()
            )

        usable.top = usable.top.coerceAtLeast((screenHeight * 0.18f).toInt())
        usable.bottom = usable.bottom.coerceAtMost(safeBottom)

        fun randomOffset(maxOffsetPx: Int): Float =
            Random.nextInt(-maxOffsetPx, maxOffsetPx + 1).toFloat()

        val listAnchorBottom = findSessionListAnchorBottom(roots)
        val horizontalPadding = dp(18).toFloat()
        val topLimit = usable.top + dp(18).toFloat()
        val bottomLimit = minOf(usable.bottom - dp(18), safeBottom).toFloat()
        val usableHeight = (bottomLimit - topLimit).coerceAtLeast(0f)

        val x = (
            usable.centerX().toFloat() + randomOffset(150)
        ).coerceIn(
            usable.left + horizontalPadding,
            usable.right - horizontalPadding
        )

        val desiredStartY = topLimit + usableHeight * 0.51f
        val startRandomness = (usableHeight * 0.01f).toInt()
        val startY = (
            desiredStartY + randomOffset(startRandomness)
        ).coerceIn(
            topLimit + usableHeight * 0.50f,
            bottomLimit - usableHeight * 0.42f
        )

        val baseSwipeDistance = usableHeight * 0.50f
        val swipeRandomness = (usableHeight * 0.02f).toInt()
        val swipeDistance = (
            baseSwipeDistance + randomOffset(swipeRandomness)
        ).coerceAtLeast(usableHeight * 0.42f)

        val endY = (startY + swipeDistance).coerceAtMost(bottomLimit)

        val targetPackage = roots
            .mapNotNull { it.packageName?.toString() }
            .firstOrNull { it != packageName }
            ?: roots.firstOrNull()?.packageName?.toString()
            ?: "Unknown"
        BotRuntime.setActivePackage(this, targetPackage)

        val anchorText = listAnchorBottom?.toString() ?: "none"
        val gestureDescription =
            "${startY.toInt()}→${endY.toInt()} anchor=$anchorText bounds=${usable.toShortString()} pkg=$targetPackage"

        BotRuntime.setLastGesture(this, "Dispatched $gestureDescription")
        BotRuntime.setStatus(this, "Dispatching lower-list refresh swipe")

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x + dp(6), startY + (endY - startY) * 0.35f)
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
                override fun onCompleted(gestureDescriptionResult: GestureDescription?) {
                    BotRuntime.recordRefreshAttempt(this@SlotBotAccessibilityService)
                    BotRuntime.setLastGesture(
                        this@SlotBotAccessibilityService,
                        "Completed at ${timeFormatter.format(Date())}: $gestureDescription"
                    )
                    BotRuntime.setStatus(this@SlotBotAccessibilityService, "Refresh swipe completed")
                    onFinished()
                }

                override fun onCancelled(gestureDescriptionResult: GestureDescription?) {
                    BotRuntime.setLastGesture(
                        this@SlotBotAccessibilityService,
                        "Cancelled at ${timeFormatter.format(Date())}: $gestureDescription"
                    )
                    BotRuntime.setStatus(this@SlotBotAccessibilityService, "Refresh swipe cancelled")
                    handler.postDelayed({ onFinished() }, 400L)
                }
            },
            handler
        )

        if (!dispatched) {
            BotRuntime.setLastGesture(this, "dispatchGesture returned false: $gestureDescription")
            BotRuntime.setStatus(this, "Android rejected refresh swipe")
            handler.postDelayed({ onFinished() }, 400L)
        }
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

        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val tapDescription = "$label at ${x.toInt()},${y.toInt()} bounds=${bounds.toShortString()}"
        BotRuntime.setLastGesture(this, "Tap dispatched: $tapDescription")

        val path = Path().apply { moveTo(x, y) }
        val tap = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    TAP_DURATION_MS
                )
            )
            .build()

        val dispatched = dispatchGesture(
            tap,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescriptionResult: GestureDescription?) {
                    BotRuntime.setLastGesture(
                        this@SlotBotAccessibilityService,
                        "Tap completed at ${timeFormatter.format(Date())}: $tapDescription"
                    )
                    onCompleted()
                }

                override fun onCancelled(gestureDescriptionResult: GestureDescription?) {
                    BotRuntime.setLastGesture(
                        this@SlotBotAccessibilityService,
                        "Tap cancelled at ${timeFormatter.format(Date())}: $tapDescription"
                    )
                    onFailed()
                }
            },
            handler
        )

        if (!dispatched) {
            BotRuntime.setLastGesture(this, "Tap rejected: $tapDescription")
            onFailed()
        }
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
            val supportedByScreenSignature = rootMatchesTargetSignature(root)
            if (!supportedByPackage && !supportedByScreenSignature) return@forEach

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
                    label.equals("MY SESSIONS", ignoreCase = true) -> 1
                    label.startsWith("Applied filters", ignoreCase = true) -> 1
                    label.startsWith("Filters (", ignoreCase = true) -> 1
                    label.contains("No sessions matching", ignoreCase = true) -> 1
                    label.equals("Book this session?", ignoreCase = true) -> 2
                    label.equals(CONFIRM_BOOK_TEXT, ignoreCase = true) -> 2
                    else -> 0
                }
            }
        }

        return score >= 3
    }

    private fun isAvailableSessionsScreen(roots: List<AccessibilityNodeInfo>): Boolean {
        var matched = false
        roots.forEach { root ->
            walkTree(root) { node ->
                if (matched || !node.isVisibleToUser) return@walkTree
                if (labelsOf(node).any(::matchesAvailableSessionsMarker)) matched = true
            }
        }
        return matched
    }

    private fun matchesAvailableSessionsMarker(label: String): Boolean =
        label.equals("Available sessions", ignoreCase = true) ||
            label.equals("AVAILABLE SESSIONS", ignoreCase = true) ||
            label.startsWith("Applied filters", ignoreCase = true) ||
            label.contains("No sessions matching", ignoreCase = true) ||
            label.startsWith("Filters (", ignoreCase = true)

    private fun findSessionListAnchorBottom(roots: List<AccessibilityNodeInfo>): Int? {
        var anchorBottom = 0
        roots.forEach { root ->
            walkTree(root) { node ->
                if (!node.isVisibleToUser) return@walkTree
                val isAnchor = labelsOf(node).any { label ->
                    label.startsWith("Applied filters", ignoreCase = true) ||
                        label == "BEG WEST" ||
                        label == "BEG EAST" ||
                        FILTER_TIME_REGEX.matches(label)
                }
                if (isAnchor) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) anchorBottom = maxOf(anchorBottom, bounds.bottom)
                }
            }
        }
        return anchorBottom.takeIf { it > 0 }
    }

    private fun findBestScrollableNode(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        val metrics = resources.displayMetrics
        val screenArea = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0L

        roots.forEach { root ->
            walkTree(root) { node ->
                if (!node.isVisibleToUser || !node.isEnabled) return@walkTree
                val hasScrollAction = node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                        it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                if (!node.isScrollable && !hasScrollAction) return@walkTree

                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) return@walkTree

                val area = bounds.width().toLong() * bounds.height().toLong()
                if (area < screenArea / 12L) return@walkTree
                if (area > bestScore) {
                    bestScore = area
                    bestNode = node
                }
            }
        }
        return bestNode
    }

    private fun findExactClickTargets(
        exactLabel: String,
        roots: List<AccessibilityNodeInfo>
    ): List<AccessibilityNodeInfo> {
        val uniqueTargets = linkedMapOf<String, AccessibilityNodeInfo>()

        roots.forEach { root ->
            walkTree(root) { node ->
                if (!node.isVisibleToUser || !node.isEnabled) return@walkTree
                if (labelsOf(node).none { it.equals(exactLabel, ignoreCase = true) }) return@walkTree

                val target = findClickableAncestor(node) ?: node
                val bounds = Rect()
                target.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) uniqueTargets.putIfAbsent(bounds.toShortString(), target)
            }
        }

        return uniqueTargets.values
            .sortedBy {
                Rect().also(it::getBoundsInScreen).top
            }
    }

    private fun extractSessionDescriptor(roots: List<AccessibilityNodeInfo>): String {
        val labels = mutableListOf<String>()
        roots.forEach { root ->
            walkTree(root) { node ->
                if (node.isVisibleToUser) labels.addAll(labelsOf(node))
            }
        }

        val time = labels.firstNotNullOfOrNull { SESSION_TIME_REGEX.find(it)?.value }
        val area = labels.firstOrNull { AREA_REGEX.matches(it) }
        return listOfNotNull(time, area).distinct().joinToString(" | ")
            .ifBlank { "Unknown session" }
    }

    private fun extractSessionDescriptorNearNode(node: AccessibilityNodeInfo): String {
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

                val verticalDistance = kotlin.math.abs(bounds.centerY() - buttonBounds.centerY())
                labelsOf(candidate).forEach { label ->
                    if (SESSION_TIME_REGEX.containsMatchIn(label) || AREA_REGEX.matches(label)) {
                        candidates.add(verticalDistance to label)
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

        return listOfNotNull(time, area).distinct().joinToString(" | ")
            .ifBlank { "Unknown session" }
    }

    private fun labelsOf(node: AccessibilityNodeInfo): List<String> =
        listOfNotNull(
            node.text?.toString()?.trim()?.takeIf(String::isNotBlank),
            node.contentDescription?.toString()?.trim()?.takeIf(String::isNotBlank)
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

    private fun findClickableAncestor(startNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
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
        private const val OUTSIDE_SESSIONS_SCREEN_RECHECK_MS = 2_000L

        private const val MIN_REFRESH_INTERVAL_MS = 60_000L
        private const val MAX_REFRESH_INTERVAL_MS = 600_000L

        private const val MAX_SCAN_RETRIES = 6
        private const val SCAN_RETRY_DELAY_MS = 350L
        private const val REFRESH_SETTLE_MS = 1_400L
        private const val REFRESH_GESTURE_DURATION_MS = 850L
        private const val MAX_SCROLL_TO_TOP_STEPS = 5
        private const val SCROLL_TO_TOP_SETTLE_MS = 250L

        private const val TAP_DURATION_MS = 120L
        private const val CONFIRMATION_POLL_DELAY_MS = 350L
        private const val MAX_CONFIRMATION_POLLS = 12
        private const val FAILED_BOOK_COOLDOWN_MS = 1_000L
        private const val POST_CONFIRM_SETTLE_MS = 1_000L

        private val SUPPORTED_PACKAGES = setOf(
            "com.example.slotbotlab",
            "com.logistics.rider.glovo",
            "com.glovoapp.courier",
            "com.glovoapp.rider"
        )

        private val FILTER_TIME_REGEX = Regex(
            """^\d{1,2}:\d{2}-\d{1,2}:\d{2}$"""
        )

        private val SESSION_TIME_REGEX = Regex(
            """\b\d{1,2}:\d{2}\s*[-–]\s*\d{1,2}:\d{2}(?:\s*\(\d+h\))?"""
        )

        private val AREA_REGEX = Regex("""(?i)^Beg\s+(east|west)$""")
    }
}
