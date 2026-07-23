package com.example.slotbotlab

import android.app.Application

class SlotBotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MockSessionRepository.attachApplication(this)
    }
}
