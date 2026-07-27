package com.example.slotbotlab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.roundToInt

class FloatingBotControlService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var toggleButton: Button? = null
    private var nightButton: Button? = null

    private var blackScreenView: View? = null
    private var blackScreenExitView: View? = null

    private val handler = Handler(Looper.getMainLooper())
    private val refreshUi = object : Runnable {
        override fun run() {
            updateButtons()
            handler.postDelayed(this, 300L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        setActiveState(false)

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val shown = runCatching {
            showOverlay()
            true
        }.getOrElse { false }

        if (!shown) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        setActiveState(true)
        handler.post(refreshUi)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT_BLACK_SCREEN -> hideBlackScreen()
        }

        if (overlayView == null && Settings.canDrawOverlays(this)) {
            runCatching { showOverlay() }
                .onSuccess { setActiveState(true) }
                .onFailure { stopSelf() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideBlackScreen()

        val manager = windowManager
        val view = overlayView
        if (manager != null && view != null) {
            runCatching { manager.removeView(view) }
        }

        overlayView = null
        toggleButton = null
        nightButton = null
        setActiveState(false)
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val manager = windowManager ?: error("WindowManager is not initialized")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            background = roundedBackground(Color.rgb(22, 22, 22), 28f)
            elevation = dp(12).toFloat()
        }

        val dragHandle = TextView(this).apply {
            text = "⋮⋮"
            textSize = 18f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }

        val toggle = compactButton(
            text = "Start",
            textSize = 14f,
            backgroundColor = Color.rgb(0, 140, 115),
            horizontalPadding = 14
        ).apply {
            setOnClickListener {
                val nextRunning = !BotRuntime.isRunning(this@FloatingBotControlService)
                BotRuntime.setRunning(this@FloatingBotControlService, nextRunning)

                if (nextRunning) {
                    BotRuntime.requestImmediateRefresh(this@FloatingBotControlService)
                    SlotBotAccessibilityService.wakeForManualRefresh()
                }

                updateButtons()
            }
        }
        toggleButton = toggle

        val refresh = compactButton(
            text = "↻",
            textSize = 19f,
            backgroundColor = Color.rgb(70, 91, 120),
            horizontalPadding = 11
        ).apply {
            setOnClickListener {
                BotRuntime.setRunning(this@FloatingBotControlService, true)
                BotRuntime.requestImmediateRefresh(this@FloatingBotControlService)
                SlotBotAccessibilityService.wakeForManualRefresh()
                updateButtons()
            }
        }

        val night = compactButton(
            text = "●",
            textSize = 17f,
            backgroundColor = Color.rgb(45, 48, 54),
            horizontalPadding = 11
        ).apply {
            contentDescription = "Black screen"
            setOnClickListener {
                if (blackScreenView == null) showBlackScreen() else hideBlackScreen()
                updateButtons()
            }
        }
        nightButton = night

        val close = compactButton(
            text = "×",
            textSize = 19f,
            backgroundColor = Color.rgb(73, 76, 80),
            horizontalPadding = 12
        ).apply {
            setOnClickListener {
                BotRuntime.setRunning(this@FloatingBotControlService, false)
                stopSelf()
            }
        }

        container.addView(dragHandle, LinearLayout.LayoutParams(dp(42), dp(44)))
        container.addView(
            toggle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(44)
            ).apply { marginEnd = dp(6) }
        )
        container.addView(
            refresh,
            LinearLayout.LayoutParams(dp(48), dp(44)).apply { marginEnd = dp(6) }
        )
        container.addView(
            night,
            LinearLayout.LayoutParams(dp(48), dp(44)).apply { marginEnd = dp(6) }
        )
        container.addView(close, LinearLayout.LayoutParams(dp(46), dp(44)))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(150)
        }

        dragHandle.setOnTouchListener(DragTouchListener(params))
        manager.addView(container, params)
        overlayView = container
        updateButtons()
    }

    /**
     * Adds a nearly opaque black OLED-saving layer. Android 12+ blocks pass-through touches
     * through an untrusted application overlay when its opacity is above 0.8, so the window
     * alpha is intentionally kept just below that limit. The view itself is not touchable,
     * therefore Accessibility gestures continue to reach Glovo underneath it.
     */
    private fun showBlackScreen() {
        if (blackScreenView != null) return
        val manager = windowManager ?: return

        val blackView = View(this).apply {
            setBackgroundColor(Color.BLACK)
            contentDescription = "SlotBot black screen"
        }

        val blackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = BLACK_SCREEN_ALPHA
        }

        val exitButton = compactButton(
            text = "☀",
            textSize = 20f,
            backgroundColor = Color.rgb(40, 40, 40),
            horizontalPadding = 12
        ).apply {
            contentDescription = "Exit black screen"
            setOnClickListener { hideBlackScreen() }
        }

        val exitParams = WindowManager.LayoutParams(
            dp(54),
            dp(48),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(14)
            y = dp(52)
        }

        runCatching {
            manager.addView(blackView, blackParams)
            blackScreenView = blackView

            // Added afterwards so the exit button always stays above the black layer.
            manager.addView(exitButton, exitParams)
            blackScreenExitView = exitButton

            BotRuntime.setStatus(this, "Black screen active")
            updateNotification()
        }.onFailure {
            runCatching { manager.removeView(blackView) }
            runCatching { manager.removeView(exitButton) }
            blackScreenView = null
            blackScreenExitView = null
            BotRuntime.setStatus(this, "Could not enable black screen")
        }
    }

    private fun hideBlackScreen() {
        val manager = windowManager
        blackScreenExitView?.let { view ->
            if (manager != null) runCatching { manager.removeView(view) }
        }
        blackScreenView?.let { view ->
            if (manager != null) runCatching { manager.removeView(view) }
        }

        blackScreenExitView = null
        blackScreenView = null
        updateButtons()
        updateNotification()
    }

    private fun compactButton(
        text: String,
        textSize: Float,
        backgroundColor: Int,
        horizontalPadding: Int
    ) = Button(this).apply {
        this.text = text
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(horizontalPadding), dp(7), dp(horizontalPadding), dp(7))
        this.textSize = textSize
        setTextColor(Color.WHITE)
        background = roundedBackground(backgroundColor, 22f)
    }

    private fun setActiveState(active: Boolean) {
        isActive = active
        BotRuntime.setOverlayVisible(this, active)
    }

    private fun updateButtons() {
        val running = BotRuntime.isRunning(this)
        toggleButton?.apply {
            text = if (running) "Pause" else "Start"
            background = roundedBackground(
                if (running) Color.rgb(181, 72, 72) else Color.rgb(0, 140, 115),
                22f
            )
        }

        nightButton?.apply {
            text = if (blackScreenView == null) "●" else "☀"
            background = roundedBackground(
                if (blackScreenView == null) Color.rgb(45, 48, 54) else Color.rgb(105, 88, 35),
                22f
            )
        }
    }

    private inner class DragTouchListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).roundToInt()
                    params.y = startY + (event.rawY - touchY).roundToInt()
                    val manager = windowManager
                    val overlay = overlayView
                    if (manager != null && overlay != null) {
                        manager.updateViewLayout(overlay, params)
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("SlotBot floating controls")
        .setContentText(
            if (blackScreenView == null) {
                "Start, pause, refresh, black screen, or close SlotBot"
            } else {
                "Black screen active - tap action to exit"
            }
        )
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, BotControlActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Exit black screen",
            PendingIntent.getService(
                this,
                1,
                Intent(this, FloatingBotControlService::class.java)
                    .setAction(ACTION_EXIT_BLACK_SCREEN),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "SlotBot overlay",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    companion object {
        @Volatile
        var isActive: Boolean = false
            private set

        private const val CHANNEL_ID = "slotbot_overlay"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_EXIT_BLACK_SCREEN =
            "com.example.slotbotlab.action.EXIT_BLACK_SCREEN"

        // Android's maximum pass-through obscuring opacity is 0.8 on Android 12+.
        private const val BLACK_SCREEN_ALPHA = 0.79f
    }
}
