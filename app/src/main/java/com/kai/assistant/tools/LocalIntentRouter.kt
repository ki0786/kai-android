package com.kai.assistant.tools

/**
 * Rule-based utterance → local phone command matcher.
 * Returns null when confidence is low so the cloud path can handle the turn.
 */
class LocalIntentRouter {
    fun match(utterance: String): LocalMatch? {
        val text = utterance.trim().lowercase()
            .replace(Regex("[?.!,]+$"), "")
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
        matchMaps(text)?.let { return it }
        matchCamera(text)?.let { return it }
        matchOpenAppAndPlay(text)?.let { return it }
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
        // "set an alarm for 7:30" / "set alarm for 7" / "set an alarm for 7 30 am"
        val withMinutes = Regex(
            """^set\s+(an\s+|a\s+)?alarm\s+(for\s+)?(\d{1,2})(?::|\s+)(\d{2})\s*(am|pm)?$""",
        ).find(text)
        if (withMinutes != null) {
            var hour = withMinutes.groupValues[3].toInt()
            val minute = withMinutes.groupValues[4].toInt().coerceIn(0, 59)
            val meridiem = withMinutes.groupValues[5]
            hour = normalizeHour(hour, meridiem)
            return LocalMatch(
                LocalCommand.SetAlarm(hour, minute, "KAI alarm"),
                requiresConfirmation = true,
                confirmationPrompt = "Set an alarm for ${formatClock(hour, minute)}?",
                spokenLabel = "Setting an alarm",
            )
        }

        val hourOnly = Regex(
            """^set\s+(an\s+|a\s+)?alarm\s+(for\s+)?(\d{1,2})\s*(am|pm)$""",
        ).find(text)
        if (hourOnly != null) {
            val hour = normalizeHour(hourOnly.groupValues[3].toInt(), hourOnly.groupValues[4])
            return LocalMatch(
                LocalCommand.SetAlarm(hour, 0, "KAI alarm"),
                requiresConfirmation = true,
                confirmationPrompt = "Set an alarm for ${formatClock(hour, 0)}?",
                spokenLabel = "Setting an alarm",
            )
        }
        return null
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

    /**
     * "open YouTube and play believer" → open app + play/search the track.
     * Matched before [matchLaunchApp] so the whole phrase is not treated as an app name.
     */
    private fun matchOpenAppAndPlay(text: String): LocalMatch? {
        val match = Regex(
            """^(open|launch|start)\s+(the\s+)?(.+?)\s+and\s+(play|search(?:\s+for)?)\s+(.+)$""",
        ).find(text) ?: return null
        val appName = match.groupValues[3].trim()
        val query = match.groupValues[5].trim()
        if (appName.isBlank() || query.isBlank()) return null
        val blocked = setOf(
            "settings", "wifi", "wi-fi", "bluetooth", "camera", "maps",
            "flashlight", "torch", "flash", "volume", "alarm", "timer",
        )
        if (appName in blocked) return null
        return LocalMatch(
            LocalCommand.OpenAppAndPlay(appName, query),
            requiresConfirmation = true,
            confirmationPrompt = "Open $appName and play \"$query\"?",
            spokenLabel = "Opening $appName to play $query",
        )
    }

    private fun matchLaunchApp(text: String): LocalMatch? {
        val match = Regex("""^(open|launch|start)\s+(the\s+)?(.+?)(\s+app)?$""").find(text) ?: return null
        val name = match.groupValues[3].trim()
        if (name.isBlank()) return null
        // Guard: compound "… and play …" must not become a bogus app label.
        if (Regex("""\band\s+(play|search)\b""").containsMatchIn(name)) return null
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
