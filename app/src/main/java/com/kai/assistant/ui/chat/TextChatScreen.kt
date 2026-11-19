package com.kai.assistant.ui.chat

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
// Required: capital-M Modifier class (not the modifier package).
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kai.assistant.assistant.AssistantTurnProcessor
import com.kai.assistant.assistant.TurnOutcome
import com.kai.assistant.data.ChatMessage
import com.kai.assistant.data.MessageRole
import com.kai.assistant.data.SessionMessageStore
import com.kai.assistant.tools.AccessibilityTarget
import com.kai.assistant.tools.LocalMatch
import com.kai.assistant.tools.ScreenReaderAccess
import com.kai.assistant.tools.ScreenshotCaptureService
import com.kai.assistant.tools.WhatsAppSendAccess
import com.kai.assistant.tools.staysOnDevice
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextChatScreen(
    onBack: () -> Unit,
    onOpenVoice: () -> Unit,
) {
    val context = LocalContext.current
    val processor = remember(context) { AssistantTurnProcessor(context) }
    val messages by SessionMessageStore.messages.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<LocalMatch?>(null) }
    var contactsRequest by remember { mutableStateOf<Pair<Long, LocalMatch>?>(null) }
    var callRequest by remember { mutableStateOf<Pair<Long, LocalMatch>?>(null) }
    var accessRequest by remember { mutableStateOf(0L) }
    var accessTarget by remember { mutableStateOf(AccessibilityTarget.WhatsAppSend) }
    var notificationRequest by remember { mutableStateOf<Pair<Long, LocalMatch>?>(null) }
    var screenshotRequest by remember { mutableStateOf(0L) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun applyCallOutcome(outcome: TurnOutcome?) {
        when (outcome) {
            is TurnOutcome.NeedsConfirmation -> pending = outcome.match
            is TurnOutcome.NeedsContactsPermission -> {
                contactsRequest = System.nanoTime() to outcome.pending
            }
            is TurnOutcome.NeedsCallPermission -> {
                callRequest = System.nanoTime() to outcome.pending
            }
            is TurnOutcome.NeedsAccessibilitySettings -> {
                accessTarget = outcome.target
                accessRequest = System.nanoTime()
            }
            is TurnOutcome.NeedsNotificationPermission -> {
                notificationRequest = System.nanoTime() to outcome.pending
            }
            is TurnOutcome.NeedsScreenshotConsent -> {
                screenshotRequest = System.nanoTime()
            }
            else -> Unit
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val match = contactsRequest?.second ?: return@rememberLauncherForActivityResult
        contactsRequest = null
        sending = true
        scope.launch {
            val outcome = if (granted) {
                processor.continueCall(match)
            } else {
                processor.noteContactsPermissionDenied(match.command)
                null
            }
            applyCallOutcome(outcome)
            sending = false
        }
    }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val match = callRequest?.second ?: return@rememberLauncherForActivityResult
        callRequest = null
        sending = true
        scope.launch {
            val outcome = if (granted) {
                processor.continueAfterCallPermission(match)
            } else {
                processor.noteCallPermissionDenied(match.command)
                null
            }
            applyCallOutcome(outcome)
            sending = false
        }
    }

    LaunchedEffect(contactsRequest?.first) {
        if (contactsRequest != null) {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    LaunchedEffect(callRequest?.first) {
        if (callRequest != null) {
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val match = notificationRequest?.second ?: return@rememberLauncherForActivityResult
        notificationRequest = null
        sending = true
        scope.launch {
            val outcome = if (granted) {
                processor.continueAfterNotificationPermission(match)
            } else {
                processor.noteNotificationDenied()
                null
            }
            applyCallOutcome(outcome)
            sending = false
        }
    }

    val screenshotConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenshotCaptureService.grantAndCapture(context, result.resultCode, data)
        } else {
            processor.noteScreenshotDenied()
        }
    }

    LaunchedEffect(accessRequest) {
        if (accessRequest != 0L) {
            val intent = if (accessTarget == AccessibilityTarget.ScreenReader) {
                ScreenReaderAccess.settingsIntent(context)
            } else {
                WhatsAppSendAccess.settingsIntent(context)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    LaunchedEffect(notificationRequest?.first) {
        if (notificationRequest != null && Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(screenshotRequest) {
        if (screenshotRequest != 0L) {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            screenshotConsentLauncher.launch(manager.createScreenCaptureIntent())
        }
    }

    LaunchedEffect(Unit) {
        if (SessionMessageStore.snapshot().isEmpty()) {
            SessionMessageStore.add(
                ChatMessage(
                    role = MessageRole.Assistant,
                    text = "Hi — I'm KAI. Local phone commands, including calls, run on-device; " +
                        "everything else goes to the backend chat API.",
                ),
            )
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    pending?.let { match ->
        AlertDialog(
            onDismissRequest = {
                processor.cancelPendingConfirmation(deviceOnly = match.command.staysOnDevice())
                pending = null
            },
            title = { Text("Confirm action") },
            text = { Text(match.confirmationPrompt) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val confirmed = match
                        pending = null
                        sending = true
                        scope.launch {
                            processor.confirmAndExecute(confirmed)
                            sending = false
                        }
                    },
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        processor.cancelPendingConfirmation(
                            deviceOnly = match.command.staysOnDevice(),
                        )
                        pending = null
                    },
                ) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Text chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenVoice) {
                        Icon(Icons.Filled.Mic, contentDescription = "Voice session")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message KAI") },
                    maxLines = 4,
                    shape = RoundedCornerShape(18.dp),
                    enabled = !sending && pending == null,
                )
                IconButton(
                    enabled = draft.isNotBlank() && !sending && pending == null,
                    onClick = {
                        val text = draft.trim()
                        if (text.isEmpty()) return@IconButton
                        draft = ""
                        sending = true
                        scope.launch {
                            applyCallOutcome(processor.handleUserText(text))
                            sending = false
                        }
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

private fun linkify(text: String, color: androidx.compose.ui.graphics.Color): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val urls = Regex("""https?://[^\s)]+""")
    var cursor = 0
    for (match in urls.findAll(text)) {
        val start = match.range.first
        if (start > cursor) builder.append(text.substring(cursor, start))
        val url = match.value.trimEnd('.', ',', ';')
        builder.pushLink(
            LinkAnnotation.Url(
                url,
                TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
            ),
        )
        builder.append(url)
        builder.pop()
        cursor = start + url.length
    }
    if (cursor < text.length) builder.append(text.substring(cursor))
    return builder.toAnnotatedString()
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val mine = message.role == MessageRole.User
    val bg = if (mine) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (mine) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (mine) 18.dp else 4.dp,
                        bottomEnd = if (mine) 4.dp else 18.dp,
                    ),
                )
                .background(bg)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = linkify(message.text, fg),
                color = fg,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
