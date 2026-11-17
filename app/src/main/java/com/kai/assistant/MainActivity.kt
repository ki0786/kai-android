package com.kai.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kai.assistant.ui.KaiApp
import com.kai.assistant.ui.theme.KaiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as KaiApplication
        setContent {
            val settings by app.settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue = com.kai.assistant.data.KaiSettings(),
            )
            KaiTheme(darkTheme = settings.darkTheme) {
                KaiApp(settingsRepository = app.settingsRepository)
            }
        }
    }
}
