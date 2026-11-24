package com.kai.assistant.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Hands a message or a voice/video call to the installed WhatsApp app.
 * Text opens the chat, then the accessibility service taps Send once.
 * Calls do not arm that tap.
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
            contacts.allPhones()
        } catch (_: SecurityException) {
            val name = ContactMatcher.displayQuery(command.nameQuery)
            return WhatsAppResolution.Speak(
                "I need Contacts permission to find $name for WhatsApp. Nothing was sent to the server.",
            )
        }
        return WhatsAppResolver.resolve(command.options, phones)
    }

    fun resolveCall(command: LocalCommand.WhatsAppCall): WhatsAppCallResolution {
        if (!contacts.hasContactsPermission()) {
            val name = ContactMatcher.displayQuery(command.nameQuery)
            return WhatsAppCallResolution.Speak(
                "I need Contacts permission to find $name on this phone so I can start a WhatsApp call. " +
                    "Allow contacts when Android asks. This stays on the device and is not sent to the server.",
            )
        }
        val phones = try {
            contacts.allPhones()
        } catch (_: SecurityException) {
            val name = ContactMatcher.displayQuery(command.nameQuery)
            return WhatsAppCallResolution.Speak(
                "I need Contacts permission to find $name for WhatsApp. Nothing was sent to the server.",
            )
        }
        return WhatsAppResolver.resolveContact(command.nameQuery, phones)
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

    fun call(command: LocalCommand.WhatsAppCall): ToolResult {
        val contactId = command.resolvedContactId
        val name = command.resolvedName
        val number = command.resolvedNumber
        if (contactId != null && !name.isNullOrBlank() && !number.isNullOrBlank()) {
            return launchCall(contactId, number, name, command.video)
        }
        return when (val resolved = resolveCall(command)) {
            is WhatsAppCallResolution.Ready ->
                launchCall(resolved.contactId, resolved.number, resolved.displayName, command.video)
            is WhatsAppCallResolution.Speak -> ToolResult.Ok(resolved.message)
        }
    }

    private fun launch(number: String, displayName: String, message: String): ToolResult {
        val jid = messageJid(number, defaultCallingCode())
            ?: return ToolResult.Error("That number for $displayName isn't usable on WhatsApp.")
        val pkg = installedPackage()
            ?: return ToolResult.Unsupported(
                "WhatsApp isn't installed on this phone, so I didn't send it.",
            )
        val digits = jid.substringBefore("@")
        openFilledChat(pkg, digits, displayName, message)?.let { return it }
        return openJidSend(pkg, jid, displayName, message)
    }

    /**
     * wa.me fills the composer for any number. The accessibility service then taps Send.
     */
    private fun openFilledChat(
        pkg: String,
        digits: String,
        displayName: String,
        message: String,
    ): ToolResult? {
        val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (appContext.packageManager.resolveActivity(intent, 0) == null) return null
        return try {
            armSendTap(pkg, message)
            appContext.startActivity(intent)
            finishSend(displayName, message)
        } catch (_: ActivityNotFoundException) {
            WhatsAppSendRequest.clear()
            null
        } catch (error: Exception) {
            WhatsAppSendRequest.clear()
            ToolResult.Error(error.message ?: "Could not open WhatsApp for $displayName.")
        }
    }

    private fun openJidSend(
        pkg: String,
        jid: String,
        displayName: String,
        message: String,
    ): ToolResult {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(pkg)
            putExtra(Intent.EXTRA_TEXT, message)
            putExtra(JID_EXTRA, jid)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (appContext.packageManager.resolveActivity(send, 0) == null) {
            return ToolResult.Unsupported(
                "WhatsApp isn't installed on this phone, so I didn't send it.",
            )
        }
        return try {
            armSendTap(pkg, message)
            appContext.startActivity(send)
            finishSend(displayName, message)
        } catch (_: ActivityNotFoundException) {
            WhatsAppSendRequest.clear()
            ToolResult.Unsupported("WhatsApp isn't installed on this phone, so I didn't send it.")
        } catch (error: Exception) {
            WhatsAppSendRequest.clear()
            ToolResult.Error(error.message ?: "Could not open WhatsApp for $displayName.")
        }
    }

    private fun armSendTap(packageName: String, message: String) {
        WhatsAppSendRequest.arm(packageName, message, SystemClock.elapsedRealtime())
    }

    private fun finishSend(displayName: String, message: String): ToolResult {
        if (WhatsAppSendAccess.isEnabled(appContext)) {
            return ToolResult.Ok("Sending “$message” to $displayName on WhatsApp.")
        }
        val line = "WhatsApp is open to $displayName with the text ready. " +
            "Turn on KAI in Accessibility settings once so I can tap Send."
        return if (WhatsAppSendAccess.askOnce(appContext)) {
            ToolResult.OpenAccessibilitySettings(line)
        } else {
            ToolResult.Ok(line)
        }
    }

    private fun launchCall(
        contactId: Long,
        number: String,
        displayName: String,
        video: Boolean,
    ): ToolResult {
        val packages = installedPackages()
        if (packages.isEmpty()) {
            return ToolResult.Unsupported(
                "WhatsApp isn't installed on this phone, so I didn't start the call.",
            )
        }
        for (pkg in packages) {
            val rowId = findCallRow(contactId, number, video, pkg) ?: continue
            val mime = callMime(pkg, video)
            val data = Uri.withAppendedPath(ContactsContract.Data.CONTENT_URI, rowId.toString())
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(data, mime)
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                appContext.startActivity(intent)
                val line = if (video) {
                    "Starting a WhatsApp video call to $displayName."
                } else {
                    "Calling $displayName on WhatsApp."
                }
                return ToolResult.Ok(line)
            } catch (_: ActivityNotFoundException) {
                continue
            } catch (_: SecurityException) {
                return ToolResult.Error("I couldn't start the WhatsApp call to $displayName.")
            } catch (error: Exception) {
                return ToolResult.Error(
                    error.message ?: "Could not start the WhatsApp call to $displayName.",
                )
            }
        }
        val kind = if (video) "video call" else "call"
        return ToolResult.Ok("I couldn't start a WhatsApp $kind to $displayName.")
    }

    private fun findCallRow(contactId: Long, number: String, video: Boolean, pkg: String): Long? {
        val mime = callMime(pkg, video)
        val projection = arrayOf(
            ContactsContract.Data._ID,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA3,
        )
        val selection = "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?"
        val tail = number.filter { it.isDigit() }.takeLast(8)
        return appContext.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            arrayOf(contactId.toString(), mime),
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(ContactsContract.Data._ID)
            val data1 = cursor.getColumnIndex(ContactsContract.Data.DATA1)
            val data3 = cursor.getColumnIndex(ContactsContract.Data.DATA3)
            if (idCol < 0) return@use null
            var fallback: Long? = null
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val blob = buildString {
                    if (data1 >= 0) append(cursor.getString(data1).orEmpty())
                    append(' ')
                    if (data3 >= 0) append(cursor.getString(data3).orEmpty())
                }
                val rowDigits = blob.filter { it.isDigit() }
                if (tail.length >= 6 && rowDigits.contains(tail)) return@use id
                if (fallback == null) fallback = id
            }
            fallback
        }
    }

    private fun defaultCallingCode(): String? {
        val telephony = appContext.getSystemService(TelephonyManager::class.java)
        val iso = runCatching {
            telephony?.simCountryIso?.ifBlank { null } ?: telephony?.networkCountryIso?.ifBlank { null }
        }.getOrNull() ?: Locale.getDefault().country
        return callingCodeFor(iso)
    }

    private fun installedPackage(): String? = installedPackages().firstOrNull()

    private fun installedPackages(): List<String> {
        val pm = appContext.packageManager
        return PACKAGES.filter { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        }
    }

    private companion object {
        const val JID_EXTRA = "jid"
        val PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    }
}

internal fun messageJid(number: String, defaultCallingCode: String? = null): String? {
    val digits = internationalDigits(number, defaultCallingCode)
    if (digits.length < 3) return null
    return "$digits@s.whatsapp.net"
}

/**
 * WhatsApp needs the country code. Contacts are often saved as "98765 43210" or
 * "098765 43210", so a national number gets the SIM's calling code in front.
 */
internal fun internationalDigits(number: String, defaultCallingCode: String?): String {
    val trimmed = number.trim()
    val digits = trimmed.filter { it.isDigit() }
    if (trimmed.startsWith("+")) return digits
    if (digits.startsWith("00")) return digits.drop(2)
    val code = defaultCallingCode?.filter { it.isDigit() }.orEmpty()
    if (code.isEmpty()) return digits
    val national = digits.trimStart('0')
    if (digits.startsWith("0")) return code + national
    if (digits.length <= 10) return code + digits
    return digits
}

internal fun callingCodeFor(iso: String?): String? = when (iso?.lowercase(Locale.ROOT)) {
    "in" -> "91"
    "us", "ca" -> "1"
    "gb" -> "44"
    "ae" -> "971"
    "sa" -> "966"
    "qa" -> "974"
    "kw" -> "965"
    "om" -> "968"
    "bh" -> "973"
    "pk" -> "92"
    "bd" -> "880"
    "np" -> "977"
    "lk" -> "94"
    "sg" -> "65"
    "my" -> "60"
    "au" -> "61"
    "de" -> "49"
    "fr" -> "33"
    else -> null
}

internal fun callMime(packageName: String, video: Boolean): String {
    val app = if (packageName == "com.whatsapp.w4b") "vnd.com.whatsapp.w4b" else "vnd.com.whatsapp"
    val kind = if (video) "video.call" else "voip.call"
    return "vnd.android.cursor.item/$app.$kind"
}
