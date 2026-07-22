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
                // Give the app enough time to finish its simulated network refresh.
                handler.postDelayed({
                    val exactBookNodes = findExactBookNodes()
                    BotRuntime.recordDetection(
                        this@SlotBotAccessibilityService,
                        exactBookNodes.size
                    )

                    var successfulClicks = 0
                    val clickedBounds = mutableSetOf<String>()

                    for (node in exactBookNodes) {
                        val clickable = findClickableAncestor(node) ?: continue
                        val bounds = Rect()
                        clickable.getBoundsInScreen(bounds)
                        val key = bounds.toShortString()

                        if (clickedBounds.add(key) &&
                            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        ) {
                            successfulClicks++
                        }
                    }

                    BotRuntime.recordClickAttempt(
                        this@SlotBotAccessibilityService,
                        successfulClicks
                    )

                    val interval = BotRuntime.intervalMs(this@SlotBotAccessibilityService)
                    handler.postDelayed(this, interval)
                }, 1_400L)
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
        // We deliberately do not react immediately to every event.
        // The automation is driven by the controlled refresh loop above.
    }

    override fun onInterrupt() {
        BotRuntime.setRunning(this, false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        loopScheduled = false
        super.onDestroy()
    }

    private fun performPullToRefresh(onFinished: () -> Unit) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val x = width * 0.50f
        val startY = height * 0.30f
        val endY = height * 0.68f

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    500L
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
                    handler.postDelayed({ onFinished() }, 500L)
                }
            },
            handler
        )

        if (!dispatched) {
            handler.postDelayed({ onFinished() }, 500L)
        }
    }

    private fun findExactBookNodes(): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()

        return root.findAccessibilityNodeInfosByText("Book")
            .filter { node ->
                node.isVisibleToUser &&
                    node.isEnabled &&
                    node.text?.toString()?.trim() == "Book"
            }
    }

    private fun findClickableAncestor(
        startNode: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = startNode

        repeat(8) {
            val current = node ?: return null
            if (current.isClickable && current.isEnabled && current.isVisibleToUser) {
                return current
            }
            node = current.parent
        }

        return null
    }
}
