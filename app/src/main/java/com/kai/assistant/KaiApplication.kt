package com.kai.assistant

import android.app.Application
import com.kai.assistant.data.SettingsRepository
import com.kai.assistant.ondevice.OnDeviceChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class KaiApplication : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var onDeviceChat: OnDeviceChat
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        onDeviceChat = OnDeviceChat(this)
        appScope.launch {
            settingsRepository.settings
                .map { it.onDeviceAnswers }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        onDeviceChat.warmIfDownloaded()
                    } else {
                        onDeviceChat.release()
                    }
                }
        }
    }
}
