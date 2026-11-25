package com.kai.assistant.business

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Finds a free-text reply action on a WhatsApp Business notification and sends through it. */
object NotificationReply {
    data class Action(
        val pendingIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
    )

    fun find(notification: Notification): Action? {
        val actions = notification.actions ?: return null
        for (action in actions) {
            val inputs = action.remoteInputs ?: continue
            if (inputs.none { it.allowFreeFormInput }) continue
            val intent = action.actionIntent ?: continue
            return Action(pendingIntent = intent, remoteInputs = inputs)
        }
        // Wearable extender actions (some WA builds put reply only there).
        val wear = androidx.core.app.NotificationCompat.WearableExtender(notification)
        for (action in wear.actions) {
            val compatInputs = action.remoteInputs ?: continue
            if (compatInputs.none { it.allowFreeFormInput }) continue
            val intent = action.actionIntent ?: continue
            val platform = compatInputs.map { input ->
                RemoteInput.Builder(input.resultKey)
                    .setLabel(input.label)
                    .setAllowFreeFormInput(input.allowFreeFormInput)
                    .build()
            }.toTypedArray()
            return Action(pendingIntent = intent, remoteInputs = platform)
        }
        return null
    }

    fun send(context: Context, action: Action, text: String): Boolean {
        if (text.isBlank() || action.remoteInputs.isEmpty()) return false
        val intent = Intent()
        val results = Bundle()
        action.remoteInputs.forEach { input ->
            results.putCharSequence(input.resultKey, text)
        }
        RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
        return try {
            action.pendingIntent.send(context, 0, intent)
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
