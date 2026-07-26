package com.example.slotbotlab

import android.app.Application

class SlotBotApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // A package reinstall or process kill can happen without Service.onDestroy().
        // Clear the persisted UI flag before any floating service is started again.
        BotRuntime.setOverlayVisible(this, false)
    }
}
