package com.example.slotbotlab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.random.Random

class SlotBotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var loopScheduled = false

    private val loop = object : Runnable {
        override fun run() {
            if (!BotRuntime.isRunning(this@SlotBotAccessibilityService)) {
                BotRuntime.setNextRefreshAt(this@SlotBotAccessibilityService, 0L)
                handler.postDelayed(this, STOPPED_RECHECK_MS)
                return
            }

            if (supportedRoots().isEmpty()) {
                BotRuntime.setNextRefreshAt(this@SlotBotAccessibilityService, 0L)
                handler.postDelayed(this, OUTSIDE_SUPPORTED_APP_RECHECK_MS)
                return
            }

            BotRuntime.setNextRefreshAt(this@SlotBotAccessibilityService, 0L)

            // Always inspect the current UI before refreshing. This lets the bot finish an
            // already-open "Book session" confirmation immediately.
            scanCurrentScreen(attempt = 0, refreshIfEmpty = true)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!loopScheduled) {
            loopScheduled = true
            handler.post(loop)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The controlled state loop drives automation. Ignoring content-change callbacks avoids
        // overlapping loops when the target app publishes several accessibility updates at once.
    }

    override fun onInterrupt() {
        BotRuntime.setRunning(this, false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        BotRuntime.setNextRefreshAt(this, 0L)
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

        // Priority 1: finish an already-open confirmation screen.
        val confirmationTargets = findExactClickTargets(CONFIRM_BOOK_TEXT, roots)
        if (confirmationTargets.isNotEmpty()) {
            val slotName = extractSessionDescriptor(roots)

            if (clickNode(confirmationTargets.first())) {
                BotRuntime.recordClickAttempt(this, 1)
                BotRuntime.recordConfirmationClick(this)
                BotRuntime.recordCatch(this, slotName)

                handler.postDelayed(
                    { scanCurrentScreen(attempt = 0, refreshIfEmpty = false) },
                    POST_ACTION_SETTLE_MS
                )
            } else {
                retryOrContinue(attempt, refreshIfEmpty)
            }
            return
        }

        // Priority 2: open confirmation for exactly one available session at a time.
        val bookTargets = findExactClickTargets(BOOK_TEXT, roots)
        if (bookTargets.isNotEmpty()) {
            BotRuntime.recordDetection(this, bookTargets.size)

            if (clickNode(bookTargets.first())) {
                BotRuntime.recordClickAttempt(this, 1)
                BotRuntime.recordBookClick(this)

                handler.postDelayed(
                    { scanCurrentScreen(attempt = 0, refreshIfEmpty = false) },
                    POST_ACTION_SETTLE_MS
                )
            } else {
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
                BotRuntime.setNextRefreshAt(this, 0L)
                handler.postDelayed(loop, OUTSIDE_SESSIONS_SCREEN_RECHECK_MS)
                return
            }

            performPullToRefresh {
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

        BotRuntime.setNextRefreshAt(this, System.currentTimeMillis() + delayMs)
        handler.postDelayed(loop, delayMs)
    }

    private fun performPullToRefresh(onFinished: () -> Unit) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        // Start inside the scrollable session area, matching the real Available sessions screen.
        val x = width * 0.50f
        val startY = height * 0.60f
        val endY = height * 0.88f

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    550L
                )
            )
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onFinished()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    handler.postDelayed({ onFinished() }, 400L)
                }
            },
            handler
        )

        if (!dispatched) {
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
            val packageName = root.packageName?.toString() ?: return@forEach
            if (packageName !in SUPPORTED_PACKAGES) return@forEach

            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            val key = "$packageName:${bounds.toShortString()}"
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

                val text = node.text?.toString()?.trim()
                val description = node.contentDescription?.toString()?.trim()

                if (
                    text in AVAILABLE_SESSIONS_MARKERS ||
                    description in AVAILABLE_SESSIONS_MARKERS
                ) {
                    matched = true
                }
            }
        }

        return matched
    }

    private fun findExactClickTargets(
        exactLabel: String,
        roots: List<AccessibilityNodeInfo>
    ): List<AccessibilityNodeInfo> {
        val uniqueTargets = linkedMapOf<String, AccessibilityNodeInfo>()

        roots.forEach { root ->
            walkTree(root) { node ->
                if (!node.isVisibleToUser || !node.isEnabled) {
                    return@walkTree
                }

                val text = node.text?.toString()?.trim()
                val description = node.contentDescription?.toString()?.trim()

                if (text != exactLabel && description != exactLabel) {
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

        val area = labels.firstOrNull { label ->
            AREA_REGEX.matches(label)
        }

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

        // Fallback for UIs that expose the label but refuse ACTION_CLICK.
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

    companion object {
        private const val BOOK_TEXT = "Book"
        private const val CONFIRM_BOOK_TEXT = "Book session"

        private const val STOPPED_RECHECK_MS = 500L
        private const val OUTSIDE_SUPPORTED_APP_RECHECK_MS = 1_000L
        private const val OUTSIDE_SESSIONS_SCREEN_RECHECK_MS = 2_000L

        private const val MIN_REFRESH_INTERVAL_MS = 60_000L
        private const val MAX_REFRESH_INTERVAL_MS = 600_000L

        private const val MAX_SCAN_RETRIES = 6
        private const val SCAN_RETRY_DELAY_MS = 350L
        private const val POST_ACTION_SETTLE_MS = 700L
        private const val REFRESH_SETTLE_MS = 1_200L

        private val SUPPORTED_PACKAGES = setOf(
            "com.example.slotbotlab",
            "com.logistics.rider.glovo",
            "com.glovoapp.courier"
        )

        private val AVAILABLE_SESSIONS_MARKERS = setOf(
            "Available sessions",
            "AVAILABLE SESSIONS",
            "Applied filters:",
            "No sessions matching your filter"
        )

        private val SESSION_TIME_REGEX = Regex(
            """\b\d{1,2}:\d{2}\s*[-–]\s*\d{1,2}:\d{2}(?:\s*\(\d+h\))?"""
        )

        private val AREA_REGEX = Regex("""(?i)^Beg\s+(east|west)$""")
    }
}
