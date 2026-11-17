package com.kai.assistant

import android.app.Application
import com.kai.assistant.data.SettingsRepository

class KaiApplication : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
    }
}
