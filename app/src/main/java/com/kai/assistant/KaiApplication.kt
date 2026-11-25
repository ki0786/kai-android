package com.kai.assistant

import android.app.Application
import com.kai.assistant.business.AutoReplySettings
import com.kai.assistant.business.EnquiryStatusNotifier
import com.kai.assistant.business.FollowUpScheduler
import com.kai.assistant.data.SettingsRepository
import com.kai.assistant.tools.LocalClockAlarmScheduler
import com.kai.assistant.tools.ReminderScheduler
import com.kai.assistant.tools.TimerScheduler

class KaiApplication : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        TimerScheduler(this).restore()
        ReminderScheduler(this).restore()
        LocalClockAlarmScheduler(this).restore()
        EnquiryStatusNotifier.ensureChannels(this)
        FollowUpScheduler.scheduleNext(this)
        val auto = AutoReplySettings.get(this)
        EnquiryStatusNotifier.updateListeningIndicator(
            this,
            auto.isEnabled() && AutoReplySettings.notificationAccessGranted(this),
        )
    }
}
