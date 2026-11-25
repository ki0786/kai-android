package com.kai.assistant.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kai.assistant.R
import com.kai.assistant.data.KaiSettings
import com.kai.assistant.data.SettingsRepository
import com.kai.assistant.voice.wake.WakeModelStatus
import com.kai.assistant.voice.wake.WakeRuntime
import com.kai.assistant.voice.wake.WakeWordController
import com.kai.assistant.voice.wake.modelStatusLabel
import kotlinx.coroutines.launch

@Composable
fun BackgroundHeyKaiControl(
    settingsRepository: SettingsRepository,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = KaiSettings())
    val model by WakeRuntime.model.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf("") }
    var enableAfterPermission by remember { mutableStateOf(false) }

    fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun finishEnable() {
        promptBatteryUnrestricted(context)
        scope.launch { settingsRepository.setBackgroundHeyKai(true) }
        if (WakeWordController.micGranted(context)) {
            WakeWordController.start(context)
            note = ""
        } else {
            note = "Microphone permission is required for Hey KAI."
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (enableAfterPermission) finishEnable()
        enableAfterPermission = false
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!enableAfterPermission) return@rememberLauncherForActivityResult
        if (!granted) {
            enableAfterPermission = false
            note = "Microphone permission is required for Hey KAI."
            return@rememberLauncherForActivityResult
        }
        if (!notificationsGranted()) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            enableAfterPermission = false
            finishEnable()
        }
    }

    fun turnOn() {
        enableAfterPermission = true
        when {
            !WakeWordController.micGranted(context) ->
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            !notificationsGranted() ->
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> {
                enableAfterPermission = false
                finishEnable()
            }
        }
    }

    val statusLine = when {
        note.isNotBlank() -> note
        !settings.backgroundHeyKai && model is WakeModelStatus.Missing ->
            stringResource(R.string.privacy_no_background)
        else -> modelStatusLabel(model)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.hey_kai_toggle),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = settings.backgroundHeyKai,
            onCheckedChange = { enabled ->
                if (enabled) {
                    turnOn()
                } else {
                    note = ""
                    scope.launch { settingsRepository.setBackgroundHeyKai(false) }
                    WakeWordController.stop(context)
                }
            },
        )
    }
    Text(
        text = statusLine,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = "On-device keyword spotting. The microphone audio is not uploaded.",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    if (model is WakeModelStatus.Failed && settings.backgroundHeyKai) {
        TextButton(
            onClick = { WakeWordController.retry(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Retry download") }
    }
    }
}

private fun promptBatteryUnrestricted(context: Context) {
    val power = context.getSystemService(PowerManager::class.java) ?: return
    if (power.isIgnoringBatteryOptimizations(context.packageName)) return
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    runCatching { context.startActivity(intent) }
}
