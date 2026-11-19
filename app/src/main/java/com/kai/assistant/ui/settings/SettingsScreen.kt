package com.kai.assistant.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
// Required: capital-M Modifier class (not the modifier package).
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kai.assistant.api.BackendUrlResolver
import com.kai.assistant.api.KaiApiClient
import com.kai.assistant.data.SettingsRepository
import com.kai.assistant.data.VoiceGender
import com.kai.assistant.tools.LocalToolRouter
import com.kai.assistant.tools.toast
import com.kai.assistant.ui.components.MicStatus
import com.kai.assistant.ui.components.MicStatusChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
) {
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = com.kai.assistant.data.KaiSettings(),
    )
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tools = remember(context) { LocalToolRouter(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result reflected via checkSelfPermission below on recomposition */ }

    val micGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Privacy")
            MicStatusChip(status = MicStatus.Off)
            Text(
                text = "Mic stays off unless you start a voice session. KAI never records in the background.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (micGranted) "Microphone permission: granted" else "Microphone permission: not granted",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                ) { Text("Request mic") }
                TextButton(
                    onClick = { tools.openAppSettings().toast(context) },
                ) { Text("App permissions") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Language")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("en" to "English", "hi" to "Hindi", "kn" to "Kannada").forEach { (code, label) ->
                    FilterChip(
                        selected = settings.languageCode == code,
                        onClick = { scope.launch { settingsRepository.setLanguage(code) } },
                        label = { Text(label) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Voice")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.voiceGender == VoiceGender.Female,
                    onClick = { scope.launch { settingsRepository.setVoiceGender(VoiceGender.Female) } },
                    label = { Text("Female") },
                )
                FilterChip(
                    selected = settings.voiceGender == VoiceGender.Male,
                    onClick = { scope.launch { settingsRepository.setVoiceGender(VoiceGender.Male) } },
                    label = { Text("Male") },
                )
            }
            Text(
                text = "Speech rate: ${"%.1f".format(settings.speechRate)}x",
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = settings.speechRate,
                onValueChange = { value ->
                    scope.launch { settingsRepository.setSpeechRate(value) }
                },
                valueRange = 0.5f..2.0f,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Memory")
            SettingsToggleRow(
                title = "Remember approved facts",
                checked = settings.memoryEnabled,
                onCheckedChange = { enabled ->
                    scope.launch { settingsRepository.setMemoryEnabled(enabled) }
                },
            )
            Text(
                text = "Memory stays off the cloud until you explicitly save something. Sensitive data requires consent.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Appearance")
            SettingsToggleRow(
                title = "Dark theme",
                checked = settings.darkTheme,
                onCheckedChange = { enabled ->
                    scope.launch { settingsRepository.setDarkTheme(enabled) }
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Local tools")
            Text(
                text = "On-device demos — also reachable by voice/text (\"battery\", \"flashlight on\", …).",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { tools.batteryStatus().toast(context) }) { Text("Battery") }
                TextButton(onClick = { tools.dateTime().toast(context) }) { Text("Date/time") }
                TextButton(onClick = { tools.connectivity().toast(context) }) { Text("Network") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { tools.openWifiSettings().toast(context) }) { Text("Wi‑Fi") }
                TextButton(onClick = { tools.openBluetoothSettings().toast(context) }) { Text("Bluetooth") }
                TextButton(onClick = { tools.flashlightOn().toast(context) }) { Text("Flash on") }
                TextButton(onClick = { tools.flashlightOff().toast(context) }) { Text("Flash off") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { tools.setVolume(50).toast(context) }) { Text("Vol 50%") }
                TextButton(onClick = { tools.openCamera().toast(context) }) { Text("Camera") }
                TextButton(onClick = { tools.startTimer(60, "KAI timer").toast(context) }) {
                    Text("1‑min timer")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle("Backend")
            BackendUrlSection(settingsRepository = settingsRepository)

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BackendUrlSection(settingsRepository: SettingsRepository) {
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val effective = remember(settings.backendBaseUrl) {
        BackendUrlResolver.resolve(settings.backendBaseUrl)
    }
    var draft by remember { mutableStateOf(settings.backendBaseUrl) }
    LaunchedEffect(settings.backendBaseUrl) {
        draft = settings.backendBaseUrl
    }
    val emulator = remember { BackendUrlResolver.isEmulator() }
    var testing by remember { mutableStateOf(false) }

    Text(
        text = if (emulator) {
            "Running on emulator — default is ${BackendUrlResolver.EMULATOR_LOOPBACK} " +
                "unless you override below."
        } else {
            "Physical device — set your PC's LAN IP below (same Wi‑Fi). " +
                "Do not use 10.0.2.2 (emulator-only)."
        },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Backend URL") },
        placeholder = {
            Text(
                if (emulator) {
                    BackendUrlResolver.EMULATOR_LOOPBACK
                } else {
                    BackendUrlResolver.PHYSICAL_PLACEHOLDER
                },
            )
        },
        supportingText = {
            Text(
                if (effective.isBlank()) {
                    "Not set — cloud chat will ask you to configure this."
                } else {
                    "Active: $effective"
                },
            )
        },
    )
    Text(
        text = "Example: http://192.168.1.10:8000 — keys stay on kai-backend, never in the app.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = {
                scope.launch {
                    settingsRepository.setBackendBaseUrl(draft)
                    Toast.makeText(context, "Backend URL saved", Toast.LENGTH_SHORT).show()
                }
            },
        ) { Text("Save") }
        TextButton(
            onClick = {
                scope.launch {
                    settingsRepository.setBackendBaseUrl("")
                    draft = ""
                    Toast.makeText(context, "Cleared — using device default", Toast.LENGTH_SHORT)
                        .show()
                }
            },
        ) { Text("Use default") }
        TextButton(
            enabled = !testing && effective.isNotBlank(),
            onClick = {
                testing = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        KaiApiClient(effective).health()
                    }
                    testing = false
                    val msg = if (result.ok) {
                        "Backend reachable (${result.code})"
                    } else {
                        "Unreachable — use PC LAN IP on same Wi‑Fi, not 10.0.2.2. " +
                            "(${result.body.take(80)})"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            },
        ) { Text(if (testing) "Testing…" else "Test") }
        TextButton(
            enabled = effective.isNotBlank(),
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(effective))
                runCatching { context.startActivity(intent) }
            },
        ) { Text("Open") }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
