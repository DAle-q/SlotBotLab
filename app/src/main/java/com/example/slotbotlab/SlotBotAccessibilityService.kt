package com.example.slotbotlab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SlotBotAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var loopScheduled = false

    private val loop = object : Runnable {
        override fun run() {
            if (!BotRuntime.isRunning(this@SlotBotAccessibilityService)) {
                handler.postDelayed(this, 500L)
                return
            }

            // Always inspect the current UI before refreshing. This is important for the
            // confirmation screen: "Book session" must be handled before any new swipe.
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
        // The controlled state loop drives automation. Ignoring content-change callbacks here
        // prevents overlapping loops when Compose publishes several semantics updates at once.
    }

    override fun onInterrupt() {
        BotRuntime.setRunning(this, false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        loopScheduled = false
        super.onDestroy()
    }

    private fun scanCurrentScreen(
        attempt: Int,
        refreshIfEmpty: Boolean
    ) {
        if (!BotRuntime.isRunning(this)) {
            handler.postDelayed(loop, 500L)
            return
        }

        // Priority 1: finish an already-open confirmation screen.
        val confirmationTargets = findExactClickTargets(CONFIRM_BOOK_TEXT)
        if (confirmationTargets.isNotEmpty()) {
            if (clickNode(confirmationTargets.first())) {
                BotRuntime.recordClickAttempt(this, 1)
                BotRuntime.recordConfirmationClick(this)
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
        val bookTargets = findExactClickTargets(BOOK_TEXT)
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
            performPullToRefresh {
                handler.postDelayed(
                    { scanCurrentScreen(attempt = 0, refreshIfEmpty = false) },
                    REFRESH_SETTLE_MS
                )
            }
        } else {
            scheduleNextLoop()
        }
    }

    private fun scheduleNextLoop() {
        val interval = BotRuntime.intervalMs(this)
        handler.postDelayed(loop, interval)
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

    private fun findExactClickTargets(exactLabel: String): List<AccessibilityNodeInfo> {
        val roots = buildList {
            rootInActiveWindow?.let(::add)
            windows
                .mapNotNull { it.root }
                .forEach(::add)
        }

        val uniqueTargets = linkedMapOf<String, AccessibilityNodeInfo>()

        roots.forEach { root ->
            if (root.packageName?.toString() != packageName) {
                return@forEach
            }

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
        private const val MAX_SCAN_RETRIES = 6
        private const val SCAN_RETRY_DELAY_MS = 350L
        private const val POST_ACTION_SETTLE_MS = 500L
        private const val REFRESH_SETTLE_MS = 900L
    }
}
