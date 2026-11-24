package com.kai.assistant.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.kai.assistant.BuildConfig
import com.kai.assistant.api.BackendEndpoint
import com.kai.assistant.api.BackendLocator
import com.kai.assistant.data.SettingsRepository
import com.kai.assistant.data.VoiceGender
import com.kai.assistant.tools.LocalToolRouter
import com.kai.assistant.tools.MemoryKind
import com.kai.assistant.tools.PersonalMemoryStore
import com.kai.assistant.tools.ScreenReaderAccess
import com.kai.assistant.tools.WhatsAppSendAccess
import com.kai.assistant.tools.toast
import com.kai.assistant.ui.components.MicStatus
import com.kai.assistant.ui.components.MicStatusChip
import kotlinx.coroutines.launch

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
    val memoryStore = remember(context) { PersonalMemoryStore(context.applicationContext) }
    val memories by memoryStore.memories.collectAsStateWithLifecycle(emptyList())
    val backendEndpoint = remember(context) { BackendEndpoint.get(context) }
    val backendLocator = remember(context) { BackendLocator(context.applicationContext, backendEndpoint) }
    val backendUrl by backendEndpoint.url.collectAsStateWithLifecycle()
    var backendStatus by remember { mutableStateOf("") }
    var backendSearching by remember { mutableStateOf(false) }
    var editingId by remember { mutableLongStateOf(-1L) }
    var draft by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result reflected via checkSelfPermission below on recomposition */ }

    val micGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    val contactsGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_CONTACTS,
    ) == PackageManager.PERMISSION_GRANTED
    val callGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CALL_PHONE,
    ) == PackageManager.PERMISSION_GRANTED
    var calendarGranted by remember { mutableStateOf(tools.calendarGranted()) }
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { calendarGranted = tools.calendarGranted() }
    val whatsAppSendOn = WhatsAppSendAccess.isEnabled(context)
    val screenReaderOn = ScreenReaderAccess.isEnabled(context)

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
                text = "Hey KAI listens only on the voice screen. Android won't keep the microphone on in the background.",
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
            Text(
                text = if (contactsGranted) {
                    "Contacts permission: granted"
                } else {
                    "Contacts permission: not granted"
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "Used only on this phone to match “call <name>” and WhatsApp sends. Numbers are not sent to the server.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
            ) { Text("Request contacts") }
            Text(
                text = if (callGranted) {
                    "Phone permission: granted"
                } else {
                    "Phone permission: not granted"
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "Lets KAI start “call <name>” immediately. The number stays on this phone.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { permissionLauncher.launch(Manifest.permission.CALL_PHONE) },
            ) { Text("Request phone calls") }
            Text(
                text = if (calendarGranted) {
                    "Calendar permission: granted"
                } else {
                    "Calendar permission: not granted"
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "Used on this phone to add events, read today's schedule, and speak the daily briefing.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    calendarPermissionLauncher.launch(
                        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                    )
                },
            ) { Text("Request calendar") }
            Text(
                text = if (whatsAppSendOn) "WhatsApp send: on" else "WhatsApp send: off",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "Used only to tap Send after you ask KAI to send a WhatsApp message.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    runCatching { context.startActivity(WhatsAppSendAccess.settingsIntent(context)) }
                },
            ) { Text(if (whatsAppSendOn) "Accessibility settings" else "Turn on WhatsApp send") }
            Text(
                text = if (screenReaderOn) "Screen reader: on" else "Screen reader: off",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "Used only when you ask what is on the screen. The description stays on this phone.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    runCatching { context.startActivity(ScreenReaderAccess.settingsIntent(context)) }
                },
            ) { Text(if (screenReaderOn) "Screen reader settings" else "Turn on screen reader") }
            if (Build.VERSION.SDK_INT >= 33) {
                TextButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                ) { Text("Request notifications") }
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
                text = "Memory stays on this phone. A fact is saved only when you ask to remember it. " +
                    "Secrets are saved only if you explicitly ask.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsToggleRow(
                title = "Keep recent chat history",
                checked = settings.chatHistoryEnabled,
                onCheckedChange = { enabled ->
                    scope.launch { settingsRepository.setChatHistoryEnabled(enabled) }
                    if (!enabled) memoryStore.clearTurns()
                },
            )
            Text(
                text = "Off by default. When on, the last 100 lines of your chats stay on this phone " +
                    "so you can ask \"what did we talk about\". Turning it off deletes them.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (memories.isEmpty()) {
                Text(
                    text = "No saved memories yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            memories.forEach { memory ->
                val label = if (memory.kind == MemoryKind.Project) "Project" else "Fact"
                Text(
                    text = "$label: ${memory.text}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (editingId == memory.id) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Edit memory") },
                    )
                    Row {
                        TextButton(
                            onClick = {
                                memoryStore.update(memory.id, draft)
                                editingId = -1L
                            },
                        ) { Text("Save") }
                        TextButton(onClick = { editingId = -1L }) { Text("Cancel") }
                    }
                } else {
                    Row {
                        TextButton(
                            onClick = {
                                editingId = memory.id
                                draft = memory.text
                            },
                        ) { Text("Edit") }
                        TextButton(onClick = { memoryStore.delete(memory.id) }) { Text("Delete") }
                    }
                }
            }

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
            Text(
                text = "In use: $backendUrl",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = if (backendUrl == BuildConfig.KAI_API_BASE_URL) {
                    "From the build (KAI_API_BASE_URL)."
                } else {
                    "Found on this Wi‑Fi. Build default: ${BuildConfig.KAI_API_BASE_URL}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "If this address stops answering, KAI looks for the backend on port 8000 " +
                    "on your current Wi‑Fi and switches to it. Keys never ship in the app.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (backendStatus.isNotEmpty()) {
                Text(text = backendStatus, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = !backendSearching,
                    onClick = {
                        backendSearching = true
                        backendStatus = "Looking on this Wi‑Fi…"
                        scope.launch {
                            val found = backendLocator.rediscover(backendEndpoint.current(), force = true)
                            backendStatus = found?.let { "Using $it." }
                                ?: "No KAI backend answered on this Wi‑Fi. Is it running on port 8000?"
                            backendSearching = false
                        }
                    },
                ) { Text(if (backendSearching) "Searching…" else "Find on this Wi‑Fi") }
                TextButton(
                    onClick = {
                        backendEndpoint.reset()
                        backendStatus = ""
                    },
                ) { Text("Use build address") }
            }
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(backendUrl))
                    runCatching { context.startActivity(intent) }
                },
            ) { Text("Open address") }

            Spacer(modifier = Modifier.height(24.dp))
        }
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
