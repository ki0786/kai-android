package com.kai.assistant.business

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.kai.assistant.tools.WhatsAppSendAccess
import com.kai.assistant.tools.WhatsAppSendRequest

/**
 * Opens WhatsApp Business only, with an order the manager already checked.
 * Personal WhatsApp (`com.whatsapp`) is never used, and the text is refused when it
 * contains a price, UPI id, or payment wording.
 */
object BusinessWhatsAppSender {
    fun send(context: Context, digits: String, message: String): Boolean {
        if (!orderTextIsSafe(message)) return false
        if (!WhatsAppSendAccess.isEnabled(context)) return false
        if (digits.length !in 8..15 || digits.any { !it.isDigit() }) return false
        val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(WHATSAPP_BUSINESS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.packageManager.resolveActivity(intent, 0) == null) return false
        return try {
            WhatsAppSendRequest.arm(WHATSAPP_BUSINESS_PACKAGE, message, SystemClock.elapsedRealtime())
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            WhatsAppSendRequest.clear()
            false
        } catch (_: Exception) {
            WhatsAppSendRequest.clear()
            false
        }
    }
}
