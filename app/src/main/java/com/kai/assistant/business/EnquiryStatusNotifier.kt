package com.kai.assistant.business

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kai.assistant.MainActivity
import com.kai.assistant.R

/** Owner-facing notifications for enquiry alerts, follow-ups, and the listening indicator. */
object EnquiryStatusNotifier {
    private const val CHANNEL_ALERTS = "kai_enquiry_alerts"
    private const val CHANNEL_STATUS = "kai_enquiry_status"
    private const val STATUS_ID = 7101
    private const val FOLLOW_UP_BASE = 7200

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ALERTS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Enquiry alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "When a WhatsApp Business customer needs you"
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_STATUS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_STATUS,
                    "Enquiry auto-reply",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows while KAI is answering WhatsApp Business enquiries"
                    setShowBadge(false)
                },
            )
        }
    }

    fun updateListeningIndicator(context: Context, listening: Boolean) {
        ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        if (!listening) {
            manager.cancel(STATUS_ID)
            return
        }
        val open = PendingIntent.getActivity(
            context,
            STATUS_ID,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_INBOX, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("KAI answering enquiries")
            .setContentText("WhatsApp Business auto-reply is on")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { manager.notify(STATUS_ID, notification) }
    }

    fun alertOwner(
        context: Context,
        customerName: String,
        customerText: String,
        reason: String,
        chatKey: String,
    ) {
        ensureChannels(context)
        val id = (chatKey.hashCode() and 0x7fffffff) % 50_000 + 10_000
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_INBOX, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = customerText.trim().ifEmpty { reason }.take(180)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(customerName.ifBlank { "Customer" })
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (reason.isBlank()) body else "$reason\n\n$body",
                ),
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun followUpDue(context: Context, enquiry: Enquiry) {
        ensureChannels(context)
        val id = FOLLOW_UP_BASE + (enquiry.chatKey.hashCode() and 0xff)
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_INBOX, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snippet = enquiry.lastCustomerText.take(120)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Follow up: ${enquiry.customerName.ifBlank { "Customer" }}")
            .setContentText(snippet.ifBlank { "No reply for a day — follow up when you can." })
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "They have been quiet for a day. Message them yourself if you want to follow up." +
                        if (snippet.isBlank()) "" else "\n\nLast message: $snippet",
                ),
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    const val EXTRA_OPEN_INBOX = "open_enquiry_inbox"
}
