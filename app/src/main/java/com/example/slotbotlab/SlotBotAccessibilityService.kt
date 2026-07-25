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

    private val loop = object : Runnable {
        override fun run() {
            val activePackage = rootInActiveWindow?.packageName?.toString()
            BotRuntime.setActivePackage(this@SlotBotAccessibilityService, activePackage)

            if (!BotRuntime.isRunning(this@SlotBotAccessibilityService)) {
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
                handler.postDelayed(
                    this,
                    minOf(SCHEDULE_HEARTBEAT_MS, nextRefreshAt - now)
                )
                return
            }

            val roots = supportedRoots()
            if (roots.isEmpty()) {
                val packageLabel = activePackage ?: "no active package"
                BotRuntime.setStatus(
                    this@SlotBotAccessibilityService,
                    "Waiting for supported app ($packageLabel)"
                )
                BotRuntime.setNextRefreshAt(this@SlotBotAccessibilityService, 0L)
                handler.postDelayed(this, OUTSIDE_SUPPORTED_APP_RECHECK_MS)
                return
            }

            BotRuntime.setNextRefreshAt(this@SlotBotAccessibilityService, 0L)
            BotRuntime.setStatus(this@SlotBotAccessibilityService, "Scanning current screen")

            // Always inspect before refreshing so an already-open confirmation is completed first.
            scanCurrentScreen(attempt = 0, refreshIfEmpty = true)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        BotRuntime.setStatus(this, "Accessibility service connected")
        if (!loopScheduled) {
            loopScheduled = true
            handler.post(loop)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.let { BotRuntime.setActivePackage(this, it) }
    }

    override fun onInterrupt() {
        BotRuntime.setRunning(this, false)
        BotRuntime.setStatus(this, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        BotRuntime.setNextRefreshAt(this, 0L)
        BotRuntime.setStatus(this, "Accessibility service stopped")
        loopScheduled = false
        super.onDestroy()
    }

    private fun scanCurrentScreen(
        attempt: Int,
        refreshIfEmpty: Boolean
    ) {
        if (!BotRuntime.isRunning(this)) {
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
            val slotName = extractSessionDescriptor(roots)
            BotRuntime.setStatus(this, "Confirming $slotName")

            if (clickNode(confirmationTargets.first())) {
                BotRuntime.recordClickAttempt(this, 1)
                BotRuntime.recordConfirmationClick(this)
                BotRuntime.recordCatch(this, slotName)
                BotRuntime.setStatus(this, "Caught $slotName")

                handler.postDelayed(
                    { scanCurrentScreen(attempt = 0, refreshIfEmpty = false) },
                    POST_ACTION_SETTLE_MS
                )
            } else {
                BotRuntime.setStatus(this, "Book session click failed")
                retryOrContinue(attempt, refreshIfEmpty)
            }
            return
        }

        val bookTargets = findExactClickTargets(BOOK_TEXT, roots)
        if (bookTargets.isNotEmpty()) {
            BotRuntime.recordDetection(this, bookTargets.size)
            BotRuntime.setStatus(this, "Found ${bookTargets.size} Book button(s)")

            if (clickNode(bookTargets.first())) {
                BotRuntime.recordClickAttempt(this, 1)
                BotRuntime.recordBookClick(this)
                BotRuntime.setStatus(this, "Opened booking confirmation")

                handler.postDelayed(
                    { scanCurrentScreen(attempt = 0, refreshIfEmpty = false) },
                    POST_ACTION_SETTLE_MS
                )
            } else {
                BotRuntime.setStatus(this, "Book click failed")
                retryOrContinue(attempt, refreshIfEmpty)
            }
            return
        }

        retryOrContinue(attempt, refreshIfEmpty)
    }

    private fun retryOrContinue(
        attempt: Int,
        refreshIfEmpty: Boolean
    ) {
        if (attempt < MAX_SCAN_RETRIES) {
            handler.postDelayed(
                {
                    scanCurrentScreen(
                        attempt = attempt + 1,
                        refreshIfEmpty = refreshIfEmpty
                    )
                },
                SCAN_RETRY_DELAY_MS
            )
            return
        }

        if (refreshIfEmpty) {
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
        } else {
            scheduleNextRandomRefresh()
        }
    }

    private fun scheduleNextRandomRefresh() {
        if (!BotRuntime.isRunning(this)) {
            BotRuntime.setNextRefreshAt(this, 0L)
            handler.postDelayed(loop, STOPPED_RECHECK_MS)
            return
        }

        val delayMs = Random.nextLong(
            MIN_REFRESH_INTERVAL_MS,
            MAX_REFRESH_INTERVAL_MS + 1L
        )
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

        val bounds = scrollable?.let {
            Rect().also(it::getBoundsInScreen)
        }
        performPullToRefresh(bounds, onFinished)
    }

    private fun performPullToRefresh(
        preferredBounds: Rect?,
        onFinished: () -> Unit
    ) {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val safeBottom = screenHeight - dp(110)

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
                (screenHeight * 0.78f).toInt()
            )

        usable.top = usable.top.coerceAtLeast((screenHeight * 0.18f).toInt())
        usable.bottom = usable.bottom.coerceAtMost(safeBottom)

        if (usable.height() < dp(180)) {
            usable.top = (screenHeight * 0.35f).toInt()
            usable.bottom = (screenHeight * 0.72f).toInt().coerceAtMost(safeBottom)
        }

        val x = usable.centerX().toFloat()
        val startY = usable.top + usable.height() * 0.25f
        val endY = usable.top + usable.height() * 0.78f

        val gestureDescription = "${startY.toInt()}→${endY.toInt()} in ${usable.toShortString()}"
        BotRuntime.setLastGesture(this, "Dispatched $gestureDescription")
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

    private fun supportedRoots(): List<AccessibilityNodeInfo> {
        val roots = buildList {
            rootInActiveWindow?.let(::add)
            windows.mapNotNull { it.root }.forEach(::add)
        }

        val unique = linkedMapOf<String, AccessibilityNodeInfo>()
        roots.forEach { root ->
            val rootPackageName = root.packageName?.toString() ?: return@forEach
            if (rootPackageName !in SUPPORTED_PACKAGES) return@forEach

            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            val key = "$rootPackageName:${bounds.toShortString()}"
            unique.putIfAbsent(key, root)
        }

        return unique.values.toList()
    }

    private fun isAvailableSessionsScreen(roots: List<AccessibilityNodeInfo>): Boolean {
        if (roots.isEmpty()) return false

        var matched = false
        roots.forEach { root ->
            walkTree(root) { node ->
                if (matched || !node.isVisibleToUser) return@walkTree

                val labels = listOfNotNull(
                    node.text?.toString()?.trim(),
                    node.contentDescription?.toString()?.trim()
                )

                if (labels.any(::matchesAvailableSessionsMarker)) {
                    matched = true
                }
            }
        }

        return matched
    }

    private fun matchesAvailableSessionsMarker(label: String): Boolean {
        return label.equals("Available sessions", ignoreCase = true) ||
            label.equals("AVAILABLE SESSIONS", ignoreCase = true) ||
            label.startsWith("Applied filters", ignoreCase = true) ||
            label.contains("No sessions matching", ignoreCase = true) ||
            label.startsWith("Filters (", ignoreCase = true)
    }

    private fun findBestScrollableNode(
        roots: List<AccessibilityNodeInfo>
    ): AccessibilityNodeInfo? {
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

                val text = node.text?.toString()?.trim()
                val description = node.contentDescription?.toString()?.trim()

                if (text != exactLabel && description != exactLabel) return@walkTree

                val target = findClickableAncestor(node) ?: node
                val bounds = Rect()
                target.getBoundsInScreen(bounds)

                if (!bounds.isEmpty) {
                    uniqueTargets.putIfAbsent(bounds.toShortString(), target)
                }
            }
        }

        return uniqueTargets.values.toList()
    }

    private fun extractSessionDescriptor(roots: List<AccessibilityNodeInfo>): String {
        val labels = mutableListOf<String>()

        roots.forEach { root ->
            walkTree(root) { node ->
                if (!node.isVisibleToUser) return@walkTree

                val text = node.text?.toString()?.trim()
                val description = node.contentDescription?.toString()?.trim()

                if (!text.isNullOrBlank()) labels.add(text)
                if (!description.isNullOrBlank() && description != text) labels.add(description)
            }
        }

        val time = labels.firstNotNullOfOrNull { label ->
            SESSION_TIME_REGEX.find(label)?.value
        }
        val area = labels.firstOrNull { label -> AREA_REGEX.matches(label) }

        return listOfNotNull(time, area)
            .distinct()
            .joinToString(" | ")
            .ifBlank { "Unknown session" }
    }

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

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        val path = Path().apply {
            moveTo(bounds.exactCenterX(), bounds.exactCenterY())
        }

        val tap = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    80L
                )
            )
            .build()

        return dispatchGesture(tap, null, handler)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
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
        private const val POST_ACTION_SETTLE_MS = 700L
        private const val REFRESH_SETTLE_MS = 1_400L
        private const val REFRESH_GESTURE_DURATION_MS = 700L

        private const val MAX_SCROLL_TO_TOP_STEPS = 5
        private const val SCROLL_TO_TOP_SETTLE_MS = 250L

        private val SUPPORTED_PACKAGES = setOf(
            "com.example.slotbotlab",
            "com.logistics.rider.glovo",
            "com.glovoapp.courier",
            "com.glovoapp.rider"
        )

        private val SESSION_TIME_REGEX = Regex(
            """\b\d{1,2}:\d{2}\s*[-–]\s*\d{1,2}:\d{2}(?:\s*\(\d+h\))?"""
        )

        private val AREA_REGEX = Regex("""(?i)^Beg\s+(east|west)$""")
    }
}
