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

            performPullToRefresh {
                // The UI refresh itself is asynchronous. Start scanning after it has had time
                // to settle and retry several times because Compose can publish semantics a bit later.
                handler.postDelayed({ scanForBookAndContinue(0) }, 900L)
            }
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
        // The controlled loop drives the automation. Content-change events are intentionally
        // ignored here so a single UI update cannot start several overlapping scan loops.
    }

    override fun onInterrupt() {
        BotRuntime.setRunning(this, false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        loopScheduled = false
        super.onDestroy()
    }

    private fun scanForBookAndContinue(attempt: Int) {
        if (!BotRuntime.isRunning(this)) {
            handler.postDelayed(loop, 500L)
            return
        }

        val bookNodes = findBookClickTargets()

        if (bookNodes.isNotEmpty()) {
            BotRuntime.recordDetection(this, bookNodes.size)

            var clickAttempts = 0
            bookNodes.forEach { node ->
                if (clickNode(node)) {
                    clickAttempts++
                }
            }

            BotRuntime.recordClickAttempt(this, clickAttempts)
            scheduleNextLoop()
            return
        }

        if (attempt < MAX_SCAN_RETRIES) {
            handler.postDelayed(
                { scanForBookAndContinue(attempt + 1) },
                SCAN_RETRY_DELAY_MS
            )
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

        // The mock now closely matches the real screen, so the actual scrollable session area
        // starts in the lower half of the display. Start the gesture inside that area.
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

    private fun findBookClickTargets(): List<AccessibilityNodeInfo> {
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
                val viewId = node.viewIdResourceName?.substringAfterLast('/')

                val matchesBook =
                    text == BOOK_TEXT ||
                        description == BOOK_TEXT ||
                        viewId?.startsWith("book_button") == true

                if (!matchesBook) {
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

        // Fallback for apps whose accessibility tree exposes the label but refuses ACTION_CLICK.
        // Tap the center of the detected node through the same AccessibilityService gesture API.
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
        private const val MAX_SCAN_RETRIES = 6
        private const val SCAN_RETRY_DELAY_MS = 350L
    }
}
