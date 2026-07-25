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

    private val handler = Handler(Looper.getMainLooper())
    private val refreshUi = object : Runnable {
        override fun run() {
            updateToggleButton()
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
        }.getOrElse {
            false
        }

        if (!shown) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        setActiveState(true)
        handler.post(refreshUi)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        val manager = windowManager
        val view = overlayView
        if (manager != null && view != null) {
            runCatching { manager.removeView(view) }
        }

        overlayView = null
        toggleButton = null
        setActiveState(false)
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val manager = windowManager
            ?: error("WindowManager is not initialized")

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

        val toggle = Button(this).apply {
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(14), dp(7), dp(14), dp(7))
            textSize = 14f
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(0, 140, 115), 22f)
            setOnClickListener {
                BotRuntime.setRunning(
                    this@FloatingBotControlService,
                    !BotRuntime.isRunning(this@FloatingBotControlService)
                )
                updateToggleButton()
            }
        }
        toggleButton = toggle

        val close = Button(this).apply {
            text = "×"
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), dp(7), dp(12), dp(7))
            textSize = 19f
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(73, 76, 80), 22f)
            setOnClickListener {
                BotRuntime.setRunning(this@FloatingBotControlService, false)
                stopSelf()
            }
        }

        container.addView(
            dragHandle,
            LinearLayout.LayoutParams(dp(42), dp(44))
        )
        container.addView(
            toggle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(44)
            ).apply {
                marginEnd = dp(6)
            }
        )
        container.addView(
            close,
            LinearLayout.LayoutParams(dp(46), dp(44))
        )

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
        updateToggleButton()
    }

    private fun setActiveState(active: Boolean) {
        isActive = active
        BotRuntime.setOverlayVisible(this, active)
    }

    private fun updateToggleButton() {
        val running = BotRuntime.isRunning(this)
        toggleButton?.apply {
            text = if (running) "Pause" else "Start"
            background = roundedBackground(
                if (running) Color.rgb(181, 72, 72) else Color.rgb(0, 140, 115),
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
        .setContentText("Floating Start/Pause controls are visible")
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
        .build()

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
    }
}
