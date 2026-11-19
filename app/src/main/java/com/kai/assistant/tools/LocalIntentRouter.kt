package com.kai.assistant.tools

/**
 * Rule-based utterance → local phone command matcher.
 * Returns null when confidence is low so the cloud path can handle the turn.
 */
class LocalIntentRouter {
    fun match(utterance: String): LocalMatch? {
        val text = utterance.trim().lowercase()
            .replace(Regex("[?.!,]+$"), "")
            .replace(Regex("""\b([ap])\s*\.\s*m\s*\.?(?=\s|$)"""), "$1m")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isBlank()) return null

        matchFlashlight(text)?.let { return it }
        matchBattery(text)?.let { return it }
        matchDateTime(text)?.let { return it }
        matchConnectivity(text)?.let { return it }
        matchWifi(text)?.let { return it }
        matchBluetooth(text)?.let { return it }
        matchAppSettings(text)?.let { return it }
        matchVolume(text)?.let { return it }
        matchAlarm(text)?.let { return it }
        matchTimer(text)?.let { return it }
        matchScreenshot(text)?.let { return it }
        matchReminderEdit(text)?.let { return it }
        matchReminder(text)?.let { return it }
        matchScreen(text)?.let { return it }
        matchMaps(text)?.let { return it }
        matchCamera(text)?.let { return it }
        matchWhatsApp(text)?.let { return it }
        matchCall(text)?.let { return it }
        matchLaunchApp(text)?.let { return it }
        return null
    }

    private fun matchFlashlight(text: String): LocalMatch? {
        val mentionsLight = Regex("""\b(flashlight|torch|flash light)\b""").containsMatchIn(text) ||
            text == "flash on" || text == "flash off" ||
            text.startsWith("turn on flash") || text.startsWith("turn off flash")
        if (!mentionsLight) return null

        val wantsOff = Regex("""\b(off|disable|kill)\b""").containsMatchIn(text)
        val wantsOn = Regex("""\b(on|enable)\b""").containsMatchIn(text)
        return when {
            wantsOff -> LocalMatch(
                LocalCommand.Flashlight(false),
                requiresConfirmation = false,
                confirmationPrompt = "",
                spokenLabel = "Turning flashlight off",
            )
            wantsOn -> LocalMatch(
                LocalCommand.Flashlight(true),
                requiresConfirmation = false,
                confirmationPrompt = "",
                spokenLabel = "Turning flashlight on",
            )
            else -> null
        }
    }

    private fun matchBattery(text: String): LocalMatch? {
        if (!Regex("""\b(battery|charging|charge level|power left)\b""").containsMatchIn(text)) {
            return null
        }
        if (Regex("""\b(set|change|open)\b""").containsMatchIn(text)) return null
        return LocalMatch(
            LocalCommand.BatteryStatus,
            requiresConfirmation = false,
            confirmationPrompt = "",
            spokenLabel = "Checking battery",
        )
    }

    private fun matchDateTime(text: String): LocalMatch? {
        val hit = Regex(
            """^(what('?s| is)?\s+)?(the\s+)?(current\s+)?(date|time)(\s+(is it|now))?$""" +
                """|^what time is it$|^what('?s| is) today'?s date$|^tell me the (time|date)$""",
        ).containsMatchIn(text)
        return if (hit) {
            LocalMatch(
                LocalCommand.DateTime,
                requiresConfirmation = false,
                confirmationPrompt = "",
                spokenLabel = "Checking date and time",
            )
        } else {
            null
        }
    }

    private fun matchConnectivity(text: String): LocalMatch? {
        val hit = Regex(
            """\b(am i (online|connected)|check (internet|connectivity|connection)|""" +
                """(internet|network) (status|connection)|are we online)\b""",
        ).containsMatchIn(text)
        return if (hit) {
            LocalMatch(
                LocalCommand.Connectivity,
                requiresConfirmation = false,
                confirmationPrompt = "",
                spokenLabel = "Checking connectivity",
            )
        } else {
            null
        }
    }

    private fun matchWifi(text: String): LocalMatch? {
        if (!Regex("""\b(wi-?fi|wifi)\b""").containsMatchIn(text)) return null
        if (!Regex("""\b(open|show|launch|settings)\b""").containsMatchIn(text)) return null
        return LocalMatch(
            LocalCommand.OpenWifiSettings,
            requiresConfirmation = false,
            confirmationPrompt = "",
            spokenLabel = "Opening Wi‑Fi settings",
        )
    }

    private fun matchBluetooth(text: String): LocalMatch? {
        if (!text.contains("bluetooth")) return null
        if (!Regex("""\b(open|show|launch|settings)\b""").containsMatchIn(text)) return null
        return LocalMatch(
            LocalCommand.OpenBluetoothSettings,
            requiresConfirmation = false,
            confirmationPrompt = "",
            spokenLabel = "Opening Bluetooth settings",
        )
    }

    private fun matchAppSettings(text: String): LocalMatch? {
        val hit = Regex(
            """^(open|show)\s+(app\s+)?(settings|permissions)$|^kai (app )?settings$""",
        ).containsMatchIn(text)
        return if (hit) {
            LocalMatch(
                LocalCommand.OpenAppSettings,
                requiresConfirmation = false,
                confirmationPrompt = "",
                spokenLabel = "Opening app settings",
            )
        } else {
            null
        }
    }

    private fun matchVolume(text: String): LocalMatch? {
        Regex("""^(set|change|turn)\s+(the\s+)?volume\s+(to\s+)?(\d{1,3})\s*%?$""")
            .find(text)
            ?.groupValues
            ?.getOrNull(4)
            ?.toIntOrNull()
            ?.let { raw ->
                val percent = raw.coerceIn(0, 100)
                return LocalMatch(
                    LocalCommand.SetVolume(percent),
                    requiresConfirmation = false,
                    confirmationPrompt = "",
                    spokenLabel = "Setting volume to $percent percent",
                )
            }

        when {
            Regex("""^(turn\s+)?(the\s+)?volume\s+up$|^louder$|^increase volume$""").containsMatchIn(text) ->
                return LocalMatch(
                    LocalCommand.AdjustVolume(2),
                    requiresConfirmation = false,
                    confirmationPrompt = "",
                    spokenLabel = "Turning volume up",
                )
            Regex("""^(turn\s+)?(the\s+)?volume\s+down$|^quieter$|^decrease volume$""").containsMatchIn(text) ->
                return LocalMatch(
                    LocalCommand.AdjustVolume(-2),
                    requiresConfirmation = false,
                    confirmationPrompt = "",
                    spokenLabel = "Turning volume down",
                )
            Regex("""^mute$|^mute volume$""").containsMatchIn(text) ->
                return LocalMatch(
                    LocalCommand.SetVolume(0),
                    requiresConfirmation = false,
                    confirmationPrompt = "",
                    spokenLabel = "Muting volume",
                )
        }
        return null
    }

    private fun matchAlarm(text: String): LocalMatch? {
        val (core, repeat, weekday) = stripReminderRepeat(text)
        // "set an alarm for 7:30" / "set alarm for 7" / "set an alarm for 6:30 a.m."
        val withMinutes = Regex(
            """^set\s+(an\s+|a\s+)?alarm\s+(for\s+)?(\d{1,2})(?::|\s+)(\d{2})\s*(am|pm)?$""",
        ).find(core)
        if (withMinutes != null) {
            var hour = withMinutes.groupValues[3].toInt()
            val minute = withMinutes.groupValues[4].toInt().coerceIn(0, 59)
            val meridiem = withMinutes.groupValues[5]
            hour = normalizeHour(hour, meridiem)
            return alarmMatch(hour, minute, repeat, weekday)
        }

        val hourOnly = Regex(
            """^set\s+(an\s+|a\s+)?alarm\s+(for\s+)?(\d{1,2})\s*(am|pm)$""",
        ).find(core)
        if (hourOnly != null) {
            val hour = normalizeHour(hourOnly.groupValues[3].toInt(), hourOnly.groupValues[4])
            return alarmMatch(hour, 0, repeat, weekday)
        }
        return null
    }

    private fun alarmMatch(hour: Int, minute: Int, repeat: ReminderRepeat, weekday: Int?): LocalMatch {
        val spoken = alarmSpokenLine(hour, minute, repeat, weekday)
        return LocalMatch(
            LocalCommand.SetAlarm(hour, minute, "KAI alarm", repeat, weekday),
            requiresConfirmation = false,
            confirmationPrompt = "Set an alarm${spoken.removePrefix("Alarm set").removeSuffix(".")}?",
            spokenLabel = "Setting an alarm",
        )
    }

    private fun matchTimer(text: String): LocalMatch? {
        val match = Regex(
            """^set\s+(a\s+|an\s+)?timer\s+(for\s+)?(\d{1,4})\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)?$""" +
                """|^timer\s+(\d{1,4})\s*(seconds?|secs?|minutes?|mins?)?$""",
        ).find(text) ?: return null

        val amount = (match.groupValues[3].ifBlank { match.groupValues[5] }).toIntOrNull() ?: return null
        val unit = (match.groupValues[4].ifBlank { match.groupValues[6] }).ifBlank { "seconds" }
        val seconds = when {
            unit.startsWith("hour") || unit.startsWith("hr") -> amount * 3600
            unit.startsWith("min") -> amount * 60
            else -> amount
        }.coerceIn(1, 24 * 3600)

        return LocalMatch(
            LocalCommand.StartTimer(seconds, "KAI timer"),
            requiresConfirmation = true,
            confirmationPrompt = "Start a timer for ${describeDuration(seconds)}?",
            spokenLabel = "Starting a timer",
        )
    }

    private fun matchScreenshot(text: String): LocalMatch? {
        val hit = text == "screenshot" ||
            text == "take a screenshot" ||
            text == "take screenshot" ||
            text == "capture the screen" ||
            text == "capture my screen"
        if (!hit) return null
        return LocalMatch(
            LocalCommand.TakeScreenshot,
            requiresConfirmation = false,
            confirmationPrompt = "",
            spokenLabel = "Taking a screenshot",
        )
    }

    private fun matchReminderEdit(text: String): LocalMatch? {
        ReminderUtterance.parseCancel(text)?.let { query ->
            return LocalMatch(
                LocalCommand.CancelReminder(query),
                requiresConfirmation = false,
                confirmationPrompt = "",
                spokenLabel = "Cancelling a reminder",
            )
        }
        ReminderUtterance.parseChange(text)?.let { command ->
            return LocalMatch(
                command,
                requiresConfirmation = false,
                confirmationPrompt = "",
                spokenLabel = "Changing a reminder",
            )
        }
        return null
    }

    private fun matchReminder(text: String): LocalMatch? {
        val parsed = ReminderUtterance.parseAll(text) ?: return null
        val items = parsed.map { item ->
            LocalCommand.SetReminder(
                text = item.text,
                hour = item.hour,
                minute = item.minute,
                meridiem = item.meridiem,
                repeat = item.repeat,
                weekday = item.weekday,
                inMinutes = item.inMinutes,
                dayOffset = item.dayOffset,
            )
        }
        val command = if (items.size == 1) items.first() else LocalCommand.SetReminders(items)
        return LocalMatch(
            command,
            requiresConfirmation = false,
            confirmationPrompt = "",
            spokenLabel = if (items.size == 1) "Setting a reminder" else "Setting reminders",
        )
    }

    private fun stripReminderRepeat(text: String): Triple<String, ReminderRepeat, Int?> {
        val weekly = Regex(
            """\s+every\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)$""",
        ).find(text)
        if (weekly != null) {
            val day = mapOf(
                "monday" to 1,
                "tuesday" to 2,
                "wednesday" to 3,
                "thursday" to 4,
                "friday" to 5,
                "saturday" to 6,
                "sunday" to 7,
            ).getValue(weekly.groupValues[1])
            return Triple(text.removeSuffix(weekly.value).trim(), ReminderRepeat.Weekly, day)
        }
        val daily = Regex("""\s+(?:every\s+day|daily)$""").find(text)
        if (daily != null) return Triple(text.removeSuffix(daily.value).trim(), ReminderRepeat.Daily, null)
        val weekdays = Regex("""\s+(?:every\s+weekday|on\s+weekdays|weekdays)$""").find(text)
        if (weekdays != null) return Triple(text.removeSuffix(weekdays.value).trim(), ReminderRepeat.Weekdays, null)
        return Triple(text, ReminderRepeat.Once, null)
    }

    private fun matchScreen(text: String): LocalMatch? {
        val hit = Regex(
            """^(?:what(?:'s| is)?|whats)\s+on\s+(?:my\s+|the\s+)?screen$""",
        ).matches(text)
        if (!hit) return null
        return LocalMatch(
            LocalCommand.DescribeScreen,
            requiresConfirmation = false,
            confirmationPrompt = "",
            spokenLabel = "Looking at the screen",
        )
    }

    private fun matchMaps(text: String): LocalMatch? {
        val navigate = Regex("""^(navigate|directions)\s+to\s+(.+)$""").find(text)
        if (navigate != null) {
            val query = navigate.groupValues[2].trim()
            if (query.isBlank()) return null
            return LocalMatch(
                LocalCommand.OpenMaps(query),
                requiresConfirmation = true,
                confirmationPrompt = "Open maps navigation to \"$query\"?",
                spokenLabel = "Opening maps",
            )
        }
        val openMaps = Regex("""^open\s+maps(?:\s+(to|for)\s+(.+))?$""").find(text)
        if (openMaps != null) {
            val query = openMaps.groupValues.getOrNull(2)?.trim().orEmpty().ifBlank { "nearby" }
            return LocalMatch(
                LocalCommand.OpenMaps(query),
                requiresConfirmation = query != "nearby",
                confirmationPrompt = if (query == "nearby") "" else "Open maps for \"$query\"?",
                spokenLabel = "Opening maps",
            )
        }
        return null
    }

    private fun matchCamera(text: String): LocalMatch? {
        val hit = Regex("""^(open|launch)\s+(the\s+)?camera$|^take\s+(a\s+)?(photo|picture)$""").containsMatchIn(text)
        return if (hit) {
            LocalMatch(
                LocalCommand.OpenCamera,
                requiresConfirmation = true,
                confirmationPrompt = "Open the camera?",
                spokenLabel = "Opening camera",
            )
        } else {
            null
        }
    }

    private val confirmationPrefix = Regex(
        """^(yes|yeah|yep|yup|confirmed|confirm|okay|ok|go ahead and|go ahead)\s+""",
    )
    private val politePrefix = Regex(
        """^(please|can you|could you|would you|will you)\s+""",
    )
    private val trailingConfirmation = Regex(
        """\s+(confirmed|confirm|okay|ok|yes|yeah|yep|yup|please|now)$""",
    )
    private val callVerb = Regex(
        """^(?:(?:make|place)\s+a\s+)?(?:phone\s+call|call|phone|dial)(?:\s+to)?(?:\s+the)?\s+(\S(?:.*\S)?)$""",
    )
    private val giveCall = Regex(
        """^give\s+(.+?)\s+a\s+(?:phone\s+)?call$""",
    )
    private val typedSuffix = Regex(
        """^(.*\S)\s+(?:on|at)(?:\s+(?:his|her|their))?\s+(mobile|cell(?:\s+phone)?|cellphone|home|work|office)$""",
    )
    private val bareTypeSuffix = Regex(
        """^(.*\S)\s+(mobile|cell(?:\s+phone)?|cellphone|home|work|office)$""",
    )
    private val nameWord = Regex("""^\p{L}[\p{L}.'’\-]*$""")
    private val notAName = setOf(
        "me", "you", "him", "her", "them", "us", "it", "someone", "somebody",
        "anyone", "everybody", "back", "later", "tomorrow", "today", "tonight",
        "yesterday", "off", "out", "soon", "again", "settings", "help", "a", "an",
        "the", "my", "your", "our", "day", "quits", "up", "down", "now", "please",
        "yes", "yeah", "yep", "yup", "ok", "okay", "confirm", "confirmed",
        "number", "people", "person", "time", "times", "about",
    )

    private fun matchWhatsApp(text: String): LocalMatch? {
        WhatsAppUtterance.parseCall(text)?.let { call ->
            val label = ContactMatcher.displayQuery(call.nameQuery)
            val spoken = if (call.video) {
                "Video calling $label on WhatsApp"
            } else {
                "Calling $label on WhatsApp"
            }
            return LocalMatch(
                command = LocalCommand.WhatsAppCall(nameQuery = call.nameQuery, video = call.video),
                requiresConfirmation = false,
                confirmationPrompt = "",
                spokenLabel = spoken,
            )
        }
        val parsed = WhatsAppUtterance.parse(text) ?: return null
        val label = ContactMatcher.displayQuery(parsed.nameQuery)
        return LocalMatch(
            command = LocalCommand.SendWhatsApp(options = parsed.options),
            requiresConfirmation = false,
            confirmationPrompt = "",
            spokenLabel = "Sending ${parsed.message} to $label on WhatsApp",
        )
    }

    private fun matchCall(text: String): LocalMatch? {
        val cleaned = text.replace(",", " ").replace(Regex("\\s+"), " ").trim()
        var rest = cleaned
        while (true) {
            val before = rest
            confirmationPrefix.find(rest)?.let { prefix ->
                rest = rest.substring(prefix.value.length).trim()
            }
            politePrefix.find(rest)?.let { polite ->
                rest = rest.substring(polite.value.length).trim()
            }
            if (rest == before) break
        }

        val stripped = stripTrailingConfirmation(rest)
        val target = extractCallTarget(stripped.target) ?: return null
        val parsed = parseCallTarget(target) ?: return null
        val command = parsed.command
        val label = command.resolvedName ?: ContactMatcher.displayQuery(
            if (command.rawNumber) command.resolvedName ?: command.query else command.query,
        )
        // A single match places the call immediately. No confirm dialog.
        return LocalMatch(
            command = command,
            requiresConfirmation = false,
            confirmationPrompt = "",
            spokenLabel = "Calling $label",
        )
    }

    private fun extractCallTarget(text: String): String? {
        callVerb.find(text)?.let { return it.groupValues[1].trim() }
        giveCall.find(text)?.let { return it.groupValues[1].trim() }
        return null
    }

    private fun parseCallTarget(rawTarget: String): ParsedCall? {
        val stripped = stripTrailingConfirmation(rawTarget)
        var target = stripped.target
        if (target.isBlank()) return null

        var phoneType: PhoneKind? = null
        typedSuffix.find(target)?.let { match ->
            phoneType = phoneKind(match.groupValues[2])
            target = match.groupValues[1].trim()
        }
        if (phoneType == null) {
            bareTypeSuffix.find(target)?.let { match ->
                val kind = phoneKind(match.groupValues[2])
                val name = match.groupValues[1].trim()
                if (kind != null && looksLikeContactName(name)) {
                    phoneType = kind
                    target = name
                }
            }
        }
        if (target.isBlank()) return null

        if (looksLikePhoneNumber(target)) {
            return ParsedCall(
                LocalCommand.CallContact(
                    query = target,
                    phoneType = phoneType,
                    rawNumber = true,
                    resolvedNumber = target,
                ),
                confirmed = stripped.confirmed,
            )
        }

        splitNameAndNumber(target)?.let { (name, number) ->
            return ParsedCall(
                LocalCommand.CallContact(
                    query = number,
                    phoneType = phoneType,
                    rawNumber = true,
                    resolvedNumber = number,
                    resolvedName = ContactMatcher.displayQuery(name),
                ),
                confirmed = stripped.confirmed,
            )
        }

        if (!looksLikeContactName(target)) return null
        return ParsedCall(
            LocalCommand.CallContact(query = target, phoneType = phoneType),
            confirmed = stripped.confirmed,
        )
    }

    private fun stripTrailingConfirmation(rawTarget: String): StrippedTarget {
        var target = rawTarget.trim()
        var confirmed = false
        if (target.endsWith(" right now")) {
            target = target.removeSuffix(" right now").trim()
        }
        while (true) {
            val suffix = trailingConfirmation.find(target) ?: break
            val word = suffix.groupValues[1]
            if (word != "please" && word != "now") confirmed = true
            target = target.substring(0, suffix.range.first).trim()
        }
        return StrippedTarget(target, confirmed)
    }

    private fun splitNameAndNumber(target: String): Pair<String, String>? {
        val tokens = target.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size < 2) return null
        for (index in 1 until tokens.size) {
            val name = tokens.take(index).joinToString(" ")
            val number = tokens.drop(index).joinToString(" ")
            if (looksLikeContactName(name) && looksLikePhoneNumber(number)) {
                return name to number
            }
        }
        return null
    }

    private fun looksLikePhoneNumber(target: String): Boolean {
        val digits = target.count { it.isDigit() }
        if (digits !in 3..15) return false
        return target.all { it.isDigit() || it in "+-(). " }
    }

    private fun looksLikeContactName(target: String): Boolean {
        val parts = target.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.size > 4) return false
        if (!parts.all { nameWord.matches(it) }) return false
        if (parts.any { it in notAName }) return false
        return true
    }

    private fun phoneKind(word: String): PhoneKind? = when (word) {
        "mobile", "cell", "cellphone", "cell phone" -> PhoneKind.Mobile
        "home" -> PhoneKind.Home
        "work", "office" -> PhoneKind.Work
        else -> null
    }

    private data class ParsedCall(
        val command: LocalCommand.CallContact,
        val confirmed: Boolean,
    )

    private data class StrippedTarget(
        val target: String,
        val confirmed: Boolean,
    )

    private fun matchLaunchApp(text: String): LocalMatch? {
        val match = Regex("""^(open|launch|start)\s+(the\s+)?(.+?)(\s+app)?$""").find(text) ?: return null
        val name = match.groupValues[3].trim()
        if (name.isBlank()) return null
        // Avoid stealing more specific intents already handled above.
        val blocked = setOf(
            "settings", "wifi", "wi-fi", "bluetooth", "camera", "maps",
            "flashlight", "torch", "flash", "volume", "alarm", "timer",
        )
        if (name in blocked) return null
        if (name.startsWith("wifi") || name.startsWith("wi-fi") || name.startsWith("bluetooth")) return null
        return LocalMatch(
            LocalCommand.LaunchAppByName(name),
            requiresConfirmation = true,
            confirmationPrompt = "Open the \"$name\" app?",
            spokenLabel = "Opening $name",
        )
    }

    private fun normalizeHour(hour: Int, meridiem: String): Int {
        var h = hour.coerceIn(0, 23)
        when (meridiem) {
            "am" -> if (h == 12) h = 0
            "pm" -> if (h in 1..11) h += 12
        }
        return h
    }

    private fun formatClock(hour: Int, minute: Int): String =
        "%d:%02d".format(hour, minute)

    private fun describeDuration(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} hour${if (seconds == 3600) "" else "s"}"
        seconds % 60 == 0 -> "${seconds / 60} minute${if (seconds == 60) "" else "s"}"
        else -> "$seconds seconds"
    }
}
