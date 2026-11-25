package com.kai.assistant.ui.business

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kai.assistant.business.AutoReplySettings
import com.kai.assistant.business.BusinessInfoStore
import com.kai.assistant.business.Enquiry
import com.kai.assistant.business.EnquiryStatus
import com.kai.assistant.business.EnquiryStore
import com.kai.assistant.business.FollowUpScheduler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnquiryInboxScreen(
    onBack: () -> Unit,
    onOpenBusinessInfo: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { EnquiryStore.get(context) }
    val infoStore = remember { BusinessInfoStore.get(context) }
    val autoReply = remember { AutoReplySettings.get(context) }
    val enquiries by store.enquiries.collectAsStateWithLifecycle()
    val info by infoStore.info.collectAsStateWithLifecycle()
    val enabled by autoReply.enabled.collectAsStateWithLifecycle()
    val accessGranted = AutoReplySettings.notificationAccessGranted(context)
    val now = System.currentTimeMillis()
    val sorted = remember(enquiries, now) {
        enquiries.sortedWith(
            compareBy<Enquiry> { it.status(now).ordinal }
                .thenByDescending { it.lastCustomerAtMs },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Enquiry inbox") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenBusinessInfo) {
                        Icon(Icons.Outlined.Storefront, contentDescription = "Business info")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-reply on WhatsApp Business", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when {
                            !info.readyForAutoReply -> "Add showroom address and hours in Business info first."
                            !accessGranted -> "Turn on notification access for KAI, then flip this switch."
                            enabled -> "On — KAI answers common questions from Business info."
                            else -> "Off by default. Only WhatsApp Business is read."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        if (on && !info.readyForAutoReply) return@Switch
                        if (on && !accessGranted) {
                            runCatching {
                                context.startActivity(AutoReplySettings.notificationAccessIntent())
                            }
                            return@Switch
                        }
                        autoReply.setEnabled(on)
                    },
                    enabled = info.readyForAutoReply,
                )
            }
            if (!accessGranted) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(AutoReplySettings.notificationAccessIntent())
                        }
                    },
                ) { Text("Open notification access") }
            }
            TextButton(onClick = onOpenBusinessInfo) { Text("Edit business info") }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            if (sorted.isEmpty()) {
                Text(
                    text = "No enquiries yet. When a customer messages WhatsApp Business, they appear here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(sorted, key = { it.chatKey }) { enquiry ->
                        EnquiryRow(
                            enquiry = enquiry,
                            nowMs = now,
                            onResumeKai = {
                                store.update(enquiry.chatKey) {
                                    it.copy(ownerHandling = false, needsOwner = false, needsOwnerReason = "")
                                }
                                FollowUpScheduler.scheduleNext(context)
                            },
                            onMarkDone = {
                                store.update(enquiry.chatKey) { it.copy(done = true, needsOwner = false) }
                                FollowUpScheduler.scheduleNext(context)
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EnquiryRow(
    enquiry: Enquiry,
    nowMs: Long,
    onResumeKai: () -> Unit,
    onMarkDone: () -> Unit,
) {
    val status = enquiry.status(nowMs)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = enquiry.customerName.ifBlank { "Customer" },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = status == EnquiryStatus.NeedsYou || status == EnquiryStatus.FollowUpDue,
                onClick = {},
                label = { Text(status.label) },
                enabled = false,
            )
        }
        Text(
            text = enquiry.lastCustomerText.ifBlank { "—" },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
        )
        if (enquiry.needsOwnerReason.isNotBlank()) {
            Text(
                text = enquiry.needsOwnerReason,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (enquiry.ownerHandling) {
                TextButton(onClick = onResumeKai) { Text("Let KAI reply again") }
            }
            if (!enquiry.done) {
                TextButton(onClick = onMarkDone) { Text("Mark done") }
            }
        }
    }
}
