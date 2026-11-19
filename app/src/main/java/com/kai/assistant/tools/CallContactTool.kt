package com.kai.assistant.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

sealed class CallResolution {
    data class Ready(
        val displayName: String,
        val number: String,
        val confirmationPrompt: String,
    ) : CallResolution()

    data class Speak(val message: String) : CallResolution()
}

/**
 * Resolves "call <name>" against the on-device contacts database and hands the
 * number to the system phone app with [Intent.ACTION_DIAL].
 *
 * ACTION_CALL / CALL_PHONE would let KAI place the call itself. That is a more
 * invasive permission than the rest of this app, which hands sensitive actions
 * to system UI. The dialer opens with the number filled in after KAI confirms.
 */
class CallContactTool(private val context: Context) {
    fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED

    fun resolve(command: LocalCommand.CallContact): CallResolution {
        if (command.rawNumber) {
            val number = command.resolvedNumber ?: command.query
            val name = command.resolvedName ?: number
            val prompt = if (name == number) {
                "Call $number?"
            } else {
                "Call $name at $number?"
            }
            return CallResolution.Ready(name, number, prompt)
        }

        if (!hasContactsPermission()) {
            val name = ContactMatcher.displayQuery(command.query)
            return CallResolution.Speak(
                "I need Contacts permission to find $name on this phone. " +
                    "Allow contacts when Android asks. The lookup stays on this device " +
                    "and is not sent to the server.",
            )
        }

        val phones = try {
            lookup(command.query)
        } catch (_: SecurityException) {
            val name = ContactMatcher.displayQuery(command.query)
            return CallResolution.Speak(
                "I need Contacts permission to find $name on this phone. Nothing was sent to the server.",
            )
        }

        return when (val matched = ContactMatcher.match(command.query, phones, command.phoneType)) {
            is ContactMatchResult.One -> CallResolution.Ready(
                displayName = matched.displayName,
                number = matched.number,
                confirmationPrompt = ContactMatcher.confirmationPrompt(
                    matched.displayName,
                    matched.number,
                    command.phoneType,
                ),
            )
            else -> CallResolution.Speak(ContactMatcher.describe(matched))
        }
    }

    fun place(command: LocalCommand.CallContact): ToolResult {
        val number = command.resolvedNumber
        val name = command.resolvedName
        if (!number.isNullOrBlank() && !name.isNullOrBlank()) {
            return dial(number, name)
        }
        return when (val resolved = resolve(command)) {
            is CallResolution.Ready -> dial(resolved.number, resolved.displayName)
            is CallResolution.Speak -> ToolResult.Ok(resolved.message)
        }
    }

    fun lookup(query: String): List<ContactPhone> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val filterResult = runCatching { queryFilter(trimmed) }
        val fromFilter = filterResult.getOrElse { error ->
            if (error is SecurityException) throw error
            emptyList()
        }
        val fromName = runCatching { queryByDisplayName(trimmed) }.getOrElse { error ->
            if (error is SecurityException) throw error
            if (filterResult.isFailure) throw error
            emptyList()
        }
        return (fromFilter + fromName).distinctBy {
            it.contactId to ContactMatcher.normalizeNumber(it.number)
        }
    }

    private fun dial(number: String, displayName: String): ToolResult {
        val sanitized = sanitizeTel(number)
        if (sanitized.count { it.isDigit() } < 2) {
            return ToolResult.Error("That number for $displayName isn't usable.")
        }
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", sanitized, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ToolResult.Ok(
                "Opening the phone app to call $displayName at $number. Tap the call button to connect.",
            )
        } catch (_: ActivityNotFoundException) {
            ToolResult.Unsupported("No phone app is available to call $displayName.")
        } catch (error: Exception) {
            ToolResult.Error(error.message ?: "Could not open the phone app to call $displayName.")
        }
    }

    private fun queryFilter(query: String): List<ContactPhone> = queryPhones(
        uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(query),
        ),
        selection = null,
        args = null,
    )

    private fun queryByDisplayName(query: String): List<ContactPhone> = queryPhones(
        uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
        args = arrayOf(likeArg(query)),
    )

    private fun queryPhones(
        uri: Uri,
        selection: String?,
        args: Array<String>?,
    ): List<ContactPhone> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
        )
        val rows = mutableListOf<ContactPhone>()
        context.contentResolver.query(
            uri,
            projection,
            selection,
            args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
            while (cursor.moveToNext()) {
                val number = cursor.getString(numberIndex)?.trim().orEmpty()
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                if (number.isEmpty() || name.isEmpty()) continue
                rows.add(
                    ContactPhone(
                        contactId = cursor.getLong(idIndex),
                        displayName = name,
                        number = number,
                        kind = kindOf(cursor.getInt(typeIndex)),
                    ),
                )
            }
        }
        return rows
    }

    private fun kindOf(type: Int): PhoneKind = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE,
        -> PhoneKind.Mobile
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> PhoneKind.Home
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> PhoneKind.Work
        else -> PhoneKind.Other
    }

    private fun likeArg(query: String): String {
        val escaped = query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }

    private fun sanitizeTel(number: String): String = buildString {
        number.forEachIndexed { index, char ->
            when {
                char.isDigit() || char == '*' || char == '#' || char == ',' || char == ';' -> append(char)
                char == '+' && index == 0 -> append(char)
            }
        }
    }
}
