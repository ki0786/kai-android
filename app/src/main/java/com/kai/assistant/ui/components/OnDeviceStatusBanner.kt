package com.kai.assistant.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kai.assistant.KaiApplication
import com.kai.assistant.data.KaiSettings
import com.kai.assistant.ondevice.OnDeviceModel
import com.kai.assistant.ondevice.OnDevicePhase

@Composable
fun OnDeviceStatusBanner(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as KaiApplication
    val status by app.onDeviceChat.status.collectAsStateWithLifecycle()
    val settings by app.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = KaiSettings(),
    )
    val scheme = MaterialTheme.colorScheme
    val color = when {
        !settings.onDeviceAnswers -> scheme.onSurfaceVariant
        status.phase == OnDevicePhase.Failed -> scheme.error
        status.phase == OnDevicePhase.Ready -> scheme.primary
        else -> scheme.onSurfaceVariant
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = if (settings.onDeviceAnswers) {
                "On-device: ${status.shortLabel()}"
            } else {
                "On-device: off"
            },
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
        if (settings.onDeviceAnswers && status.phase == OnDevicePhase.Downloading) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { status.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val detail = when {
            !settings.onDeviceAnswers -> ""
            status.phase == OnDevicePhase.Ready -> OnDeviceModel.DISPLAY_NAME
            else -> status.detail
        }
        if (detail.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.phase == OnDevicePhase.Failed && settings.onDeviceAnswers) {
                    scheme.error
                } else {
                    scheme.onSurfaceVariant
                },
            )
        }
    }
}
