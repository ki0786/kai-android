package com.kai.assistant

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kai.assistant.business.EnquiryStatusNotifier
import com.kai.assistant.tools.ForegroundActivity
import com.kai.assistant.ui.KaiApp
import com.kai.assistant.ui.theme.KaiTheme
import com.kai.assistant.voice.wake.WakeNotifications
import com.kai.assistant.voice.wake.WakeWordController

class MainActivity : ComponentActivity() {
    private var openEnquiryInbox by mutableStateOf(false)
    private var voiceLaunchToken by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeIntent(intent)
        enableEdgeToEdge()
        val app = application as KaiApplication
        setContent {
            val settings by app.settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = com.kai.assistant.data.KaiSettings(),
            )
            KaiTheme(darkTheme = settings.darkTheme) {
                KaiApp(
                    settingsRepository = app.settingsRepository,
                    openEnquiryInbox = openEnquiryInbox,
                    voiceLaunchToken = voiceLaunchToken,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The activity is in the foreground here, including after a reboot when the user opens KAI.
        WakeWordController.startIfEnabled(this)
    }

    override fun onResume() {
        super.onResume()
        ForegroundActivity.resumed(this)
    }

    override fun onStop() {
        ForegroundActivity.paused(this)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent) {
        openEnquiryInbox = intent.getBooleanExtra(EnquiryStatusNotifier.EXTRA_OPEN_INBOX, false)
        val token = intent.getLongExtra(EXTRA_AUTO_LISTEN, 0L)
        val voice = intent.getBooleanExtra(EXTRA_OPEN_VOICE, false) || token != 0L
        if (voice) {
            voiceLaunchToken = if (token != 0L) token else System.nanoTime()
            showVoiceOverLockScreen()
            WakeNotifications.cancelVoiceScreen(this)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
        }
    }

    private fun showVoiceOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_VOICE = "com.kai.assistant.extra.OPEN_VOICE"
        const val EXTRA_AUTO_LISTEN = "com.kai.assistant.extra.AUTO_LISTEN"
    }
}
