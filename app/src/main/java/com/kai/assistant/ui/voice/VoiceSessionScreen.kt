package com.kai.assistant.ui.voice

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kai.assistant.ui.components.MicStatus
import com.kai.assistant.ui.components.MicStatusChip
import com.kai.assistant.ui.components.VoiceActivityIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSessionScreen(
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
) {
    var holding by remember { mutableStateOf(false) }
    var assistantSpeaking by remember { mutableStateOf(false) }
    var lastHeard by remember { mutableStateOf("Hold the mic to talk. STT/TTS wiring comes next.") }

    val micStatus = when {
        holding -> MicStatus.Listening
        assistantSpeaking -> MicStatus.Processing
        else -> MicStatus.Off
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
                        Icon(Icons.Filled.Chat, contentDescription = "Text chat")
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

            Spacer(modifier = Modifier.weight(0.2f))

            VoiceActivityIndicator(
                active = holding || assistantSpeaking,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = lastHeard,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(16.dp),
            )

            Spacer(modifier = Modifier.weight(0.35f))

            PushToTalkButton(
                holding = holding,
                onHoldChanged = { isHolding ->
                    holding = isHolding
                    if (isHolding) {
                        assistantSpeaking = false
                        lastHeard = "Listening… (STT stub)"
                    } else {
                        // Simulate a short assistant turn after release.
                        lastHeard = "You: (utterance captured locally — cloud STT TODO)"
                        assistantSpeaking = true
                    }
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = {
                    // Interrupt stub — stops "speaking" without background capture.
                    assistantSpeaking = false
                    lastHeard = "Interrupted. Ready when you are."
                },
                enabled = assistantSpeaking,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Text("Interrupt")
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun PushToTalkButton(
    holding: Boolean,
    onHoldChanged: (Boolean) -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (holding) 1.12f else 1f,
        animationSpec = tween(140),
        label = "ptt-scale",
    )
    val container = if (holding) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val content = if (holding) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(container)
                .pointerInput(Unit) {
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
