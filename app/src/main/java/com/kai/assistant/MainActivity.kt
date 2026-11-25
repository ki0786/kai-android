package com.kai.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kai.assistant.business.EnquiryStatusNotifier
import com.kai.assistant.ui.KaiApp
import com.kai.assistant.ui.theme.KaiTheme

class MainActivity : ComponentActivity() {
    private var openEnquiryInbox by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openEnquiryInbox = intent.getBooleanExtra(EnquiryStatusNotifier.EXTRA_OPEN_INBOX, false)
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
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openEnquiryInbox = intent.getBooleanExtra(EnquiryStatusNotifier.EXTRA_OPEN_INBOX, false)
    }
}
