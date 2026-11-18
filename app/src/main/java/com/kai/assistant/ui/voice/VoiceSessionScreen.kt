package com.kai.assistant.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
// Required: capital-M Modifier class (not the modifier package).
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kai.assistant.data.MessageRole
import com.kai.assistant.ui.components.MicStatusChip
import com.kai.assistant.ui.components.VoiceActivityIndicator
import com.kai.assistant.voice.VoicePhase
import com.kai.assistant.voice.VoiceSessionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSessionScreen(
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    viewModel: VoiceSessionViewModel = viewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val micStatus by viewModel.micStatus.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.onPushToTalkPressed()
        }
    }

    fun ensureMicThen(start: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val holding = ui.phase == VoicePhase.Listening
    val activeWave = ui.phase == VoicePhase.Listening || ui.phase == VoicePhase.Speaking

    ui.pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelPending() },
            title = { Text("Confirm action") },
            text = { Text(pending.confirmationPrompt) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPending() }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelPending() }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice session") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenChat) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Text chat")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        ),
                    ),
                )
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MicStatusChip(status = micStatus)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No background recording — mic is only active while you hold.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(16.dp))

            VoiceActivityIndicator(
                active = activeWave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = ui.statusLine,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(16.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages.takeLast(8), key = { it.id }) { message ->
                    val prefix = when (message.role) {
                        MessageRole.User -> "You"
                        MessageRole.Assistant -> "KAI"
                        MessageRole.System -> "System"
                    }
                    Text(
                        text = "$prefix: ${message.text}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PushToTalkButton(
                holding = holding,
                enabled = ui.pendingConfirmation == null && ui.phase != VoicePhase.Processing,
                onHoldChanged = { isHolding ->
                    if (isHolding) {
                        ensureMicThen { viewModel.onPushToTalkPressed() }
                    } else {
                        viewModel.onPushToTalkReleased()
                    }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = { viewModel.interruptSpeech() },
                enabled = ui.phase == VoicePhase.Speaking,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Text("Interrupt")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PushToTalkButton(
    holding: Boolean,
    enabled: Boolean,
    onHoldChanged: (Boolean) -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (holding) 1.12f else 1f,
        animationSpec = tween(140),
        label = "ptt-scale",
    )
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        holding -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        holding -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(container)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            onHoldChanged(true)
                            try {
                                awaitRelease()
                            } finally {
                                onHoldChanged(false)
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Push to talk",
                tint = content,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = if (holding) "Release to send" else "Hold to talk",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
