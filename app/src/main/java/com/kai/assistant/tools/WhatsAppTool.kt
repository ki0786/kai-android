package com.kai.assistant.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens WhatsApp to one on-device contact with the message filled in.
 * Uses https://wa.me when WhatsApp is installed. Does not call the backend.
 */
class WhatsAppTool(context: Context) {
    private val appContext = context.applicationContext
    private val contacts = CallContactTool(appContext)

    fun resolve(command: LocalCommand.SendWhatsApp): WhatsAppResolution {
        if (!contacts.hasContactsPermission()) {
            val name = ContactMatcher.displayQuery(command.nameQuery)
            return WhatsAppResolution.Speak(
                "I need Contacts permission to find $name on this phone so I can open WhatsApp. " +
                    "Allow contacts when Android asks. This stays on the device and is not sent to the server.",
            )
        }
        val phones = try {
            command.options
                .flatMap { contacts.lookup(it.nameQuery) }
                .distinctBy { it.contactId to ContactMatcher.normalizeNumber(it.number) }
        } catch (_: SecurityException) {
            val name = ContactMatcher.displayQuery(command.nameQuery)
            return WhatsAppResolution.Speak(
                "I need Contacts permission to find $name for WhatsApp. Nothing was sent to the server.",
            )
        }
        return WhatsAppResolver.resolve(command.options, phones)
    }

    fun open(command: LocalCommand.SendWhatsApp): ToolResult {
        val number = command.resolvedNumber
        val name = command.resolvedName
        val message = command.resolvedMessage
        if (!number.isNullOrBlank() && !name.isNullOrBlank() && message != null) {
            return launch(number, name, message)
        }
        return when (val resolved = resolve(command)) {
            is WhatsAppResolution.Ready -> launch(resolved.number, resolved.displayName, resolved.message)
            is WhatsAppResolution.Speak -> ToolResult.Ok(resolved.message)
        }
    }

    private fun launch(number: String, displayName: String, message: String): ToolResult {
        val digits = number.filter { it.isDigit() }
        if (digits.length < 3) {
            return ToolResult.Error("That number for $displayName isn't usable on WhatsApp.")
        }
        val pkg = installedPackage()
            ?: return ToolResult.Unsupported(
                "WhatsApp isn't installed on this phone, so I didn't open it.",
            )
        val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            ToolResult.Ok("Opening WhatsApp to $displayName.")
        } catch (_: ActivityNotFoundException) {
            ToolResult.Unsupported("WhatsApp isn't installed on this phone, so I didn't open it.")
        } catch (error: Exception) {
            ToolResult.Error(error.message ?: "Could not open WhatsApp for $displayName.")
        }
    }

    private fun installedPackage(): String? {
        val pm = appContext.packageManager
        return PACKAGES.firstOrNull { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        }
    }

    private companion object {
        val PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
