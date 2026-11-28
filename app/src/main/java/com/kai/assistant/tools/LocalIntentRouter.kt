package com.kai.assistant.tools

import com.kai.assistant.books.BooksAction
import com.kai.assistant.books.BooksUtterance

/**
 * Rule-based utterance → local phone command matcher.
 * Returns null when no phone action fits so the cloud path can handle the turn.
 * [phoneActionHint] covers commands that look like phone actions but miss a detail.
 */
class LocalIntentRouter {
    fun match(utterance: String): LocalMatch? {
        val spoken = PhraseNormalizer.clean(utterance)
        val text = spoken.lowercase()
            .replace(Regex("""\b([ap])\s*\.\s*m\s*\.?(?=\s|$)"""), "$1m")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isBlank()) return null
        val numeric = digitsForNumberWords(text)

        matchFlashlight(text)?.let { return it }
        matchBattery(text)?.let { return it }
        matchDateTime(text)?.let { return it }
        matchConnectivity(text)?.let { return it }
        matchLatestNotification(text)?.let { return it }
        matchPersonalControls(numeric)?.let { return it }
        matchSilence(text)?.let { return it }
        matchSettings(text)?.let { return it }
        matchVolume(numeric)?.let { return it }
        matchTimer(numeric)?.let { return it }
        matchAlarm(numeric)?.let { return it }
        matchScreenshot(text)?.let { return it }
        matchReminderEdit(text)?.let { return it }
        matchReminder(numeric)?.let { return it }
        matchScreen(text)?.let { return it }
        matchPhoto(text)?.let { return it }
        matchCalendar(spoken)?.let { return it }
        matchBooks(text)?.let { return it }
        matchMemory(spoken)?.let { return it }
        matchMaps(text)?.let { return it }
        matchCamera(text)?.let { return it }
        matchSms(text, spoken)?.let { return it }
        matchWhatsApp(text, spoken)?.let { return it }
        matchCall(text)?.let { return it }
        matchYouTube(spoken)?.let { return it }
        matchMedia(text)?.let { return it }
        matchLaunchApp(text)?.let { return it }
        return null
    }

    private fun local(command: LocalCommand, label: String) = LocalMatch(
        command = command,
        requiresConfirmation = false,
        confirmationPrompt = "",
        spokenLabel = label,
    )

    private fun matchFlashlight(text: String): LocalMatch? {
        val mentionsLight = Regex("""\b(flashlight|torch|flash light)\b""").containsMatchIn(text) ||
            Regex("""^(?:turn|switch)\s+(?:on|off)\s+(?:the\s+)?flash$|^flash\s+(?:on|off)$|^(?:turn|switch)\s+(?:the\s+)?flash\s+(?:on|off)$""")
                .containsMatchIn(text)
        if (!mentionsLight) return null

        val wantsOff = Regex("""\b(off|disable|kill|stop)\b""").containsMatchIn(text)
        val wantsOn = Regex("""\b(on|enable|start)\b""").containsMatchIn(text)
        return when {
            wantsOff -> local(LocalCommand.Flashlight(false), "Turning flashlight off")
            wantsOn -> local(LocalCommand.Flashlight(true), "Turning flashlight on")
            else -> null
        }
    }

    private fun matchBattery(text: String): LocalMatch? {
        if (!Regex("""\b(battery|charging|charge level|power left)\b""").containsMatchIn(text)) {
            return null
        }
        if (Regex("""\b(set|change|open|settings|saver)\b""").containsMatchIn(text)) return null
        return local(LocalCommand.BatteryStatus, "Checking battery")
    }

    private fun matchDateTime(text: String): LocalMatch? {
        val hit = Regex(
            """^(?:what(?:'?s| is)?\s+)?(?:the\s+)?(?:current\s+)?(?:date|time|day)(?:\s+(?:is it|now|today|right now|please))*$""" +
                """|^what (?:time|day|date) is it(?:\s+(?:now|today|right now))?$""" +
                """|^what(?:'?s| is) (?:today'?s date|the date today|today)$|^(?:tell me|say) the (?:time|date)$""" +
                """|^which day is (?:it|today)$|^today'?s date$|^time now$|^what day of the week is it$""",
        ).containsMatchIn(text)
        return if (hit) local(LocalCommand.DateTime, "Checking date and time") else null
    }

    private fun matchConnectivity(text: String): LocalMatch? {
        val hit = Regex(
            """\b(am i (online|connected)|check (internet|connectivity|connection)|""" +
                """(internet|network) (status|connection)|are we online|is (the )?internet working)\b""",
        ).containsMatchIn(text)
        return if (hit) local(LocalCommand.Connectivity, "Checking connectivity") else null
    }

    private val toggleWords = Regex(
        """\b(open|show|launch|settings|turn|switch|enable|disable|on|off|connect|disconnect|start|stop|""" +
            """increase|decrease|reduce|raise|lower|change|set|adjust)\b""",
    )

    private val brightnessLevel = Regex(
        """^(?:set|change|turn|put|make|adjust)?\s*(?:the\s+|my\s+)?(?:screen\s+)?brightness\s+(?:to\s+|at\s+)?(\d{1,3})\s*(?:%|percent(?:age)?)?$""" +
            """|^(\d{1,3})\s*(?:%|percent(?:age)?)\s+brightness$""",
    )

    private val brightnessUp = Regex(
        """^(?:turn|crank|bump)\s+up\s+(?:the\s+)?(?:screen\s+)?brightness$""" +
            """|^(?:turn|crank|bump)\s+(?:the\s+)?(?:screen\s+)?brightness\s+up$""" +
            """|^brightness\s+up$""" +
            """|^(?:increase|raise|boost)\s+(?:the\s+)?(?:screen\s+)?brightness$""" +
            """|^make\s+(?:it|the\s+screen)\s+brighter$""" +
            """|^brighter$""" +
            """|^brighten(?:\s+(?:the\s+)?(?:screen|display))?$""",
    )

    private val brightnessDown = Regex(
        """^(?:turn|crank)\s+down\s+(?:the\s+)?(?:screen\s+)?brightness$""" +
            """|^(?:turn|crank)\s+(?:the\s+)?(?:screen\s+)?brightness\s+down$""" +
            """|^brightness\s+down$""" +
            """|^(?:decrease|lower|reduce)\s+(?:the\s+)?(?:screen\s+)?brightness$""" +
            """|^dim\s+(?:the\s+)?(?:screen|brightness)$""" +
            """|^make\s+(?:it|the\s+screen)\s+dimmer$""" +
            """|^dimmer$""",
    )

    private val mediaPlay = Regex(
        """^(?:play|resume|unpause)(?:\s+(?:the\s+|a\s+|some\s+)?(?:music|song|track|playback|media))?$""" +
            """|^start\s+(?:the\s+)?(?:music|song|playback)$""",
    )

    private val mediaPause = Regex(
        """^pause(?:\s+(?:the\s+|a\s+)?(?:music|song|track|playback|media))?$""" +
            """|^stop\s+(?:the\s+)?(?:music|song|track|playback|media)$""",
    )

    private val mediaNext = Regex(
        """^(?:next|skip)(?:\s+(?:this\s+|the\s+)?(?:song|track|music))?$""",
    )

    private val mediaPrevious = Regex(
        """^(?:previous|prev)(?:\s+(?:the\s+)?(?:song|track))?$""" +
            """|^(?:last|previous)\s+(?:song|track)$""" +
            """|^go\s+back\s+(?:a\s+)?(?:song|track)$""",
    )

    private val silencePhone = Regex(
        """^(?:please\s+)?(?:silent|silence|mute)(?:\s+(?:my|the))?\s+phone$""" +
            """|^(?:please\s+)?(?:put|set|make|turn)(?:\s+(?:my|the))?\s+phone(?:\s+on)?\s+silent(?:\s+mode)?$""" +
            """|^(?:please\s+)?(?:turn\s+on\s+)?silent\s+mode$""" +
            """|^(?:please\s+)?phone\s+on\s+silent$""",
    )

    private val latestNotification = Regex(
        """^what(?:'s| is|s)\s+(?:my\s+|the\s+)?(?:latest|last|newest)\s+notification$""" +
            """|^(?:read|tell me|say)(?:\s+me)?\s+(?:my\s+)?(?:latest|last|newest)\s+notification$""" +
            """|^read\s+(?:the\s+)?(?:latest|last|newest)\s+notification$""" +
            """|^(?:read|what(?:'s| is|s))\s+(?:my\s+)?notifications$""" +
            """|^any\s+new\s+notifications?$""" +
            """|^(?:latest|last|newest)\s+notification$""",
    )

    private fun matchSettings(text: String): LocalMatch? {
        if (Regex("""\b(wi-?fi|wifi)\b""").containsMatchIn(text) && toggleWords.containsMatchIn(text)) {
            return local(LocalCommand.OpenWifiSettings, "Opening Wi‑Fi settings")
        }
        if (text.contains("bluetooth") && toggleWords.containsMatchIn(text)) {
            return local(LocalCommand.OpenBluetoothSettings, "Opening Bluetooth settings")
        }
        if (Regex("""^(?:open|show)\s+(?:the\s+)?(?:kai\s+)?app\s+(?:settings|permissions|info)$|^(?:open|show)\s+(?:the\s+)?permissions$|^(?:open\s+)?kai\s+(?:app\s+)?settings$""").matches(text)) {
            return local(LocalCommand.OpenAppSettings, "Opening app settings")
        }
        val sections = listOf(
            SettingsSection.MobileData to """\bmobile\s+data\b|\bcellular\s+data\b|\bdata\s+(?:on|off)\b""",
            SettingsSection.Airplane to """\b(?:airplane|aeroplane|flight)\s+mode\b""",
            SettingsSection.Hotspot to """\bhot\s?spot\b|\btethering\b""",
            SettingsSection.Location to """\b(?:location|gps)\b""",
            SettingsSection.DoNotDisturb to """\bdo\s+not\s+disturb\b|\bdnd\b""",
            SettingsSection.Display to """\bdark\s+mode\b|\bdisplay\s+settings\b|\bbrightness\s+settings\b""",
            SettingsSection.Sound to """\bsound\s+settings\b|\bringtone\b|\bsilent\s+mode\b|\bvibrate\s+mode\b""",
            SettingsSection.Battery to """\bbattery\s+(?:saver|settings)\b|\bpower\s+saving\b""",
        )
        for ((section, pattern) in sections) {
            if (Regex(pattern).containsMatchIn(text) && toggleWords.containsMatchIn(text)) {
                return local(LocalCommand.OpenSystemSettings(section), "Opening ${section.label}")
            }
        }
        if (Regex("""^(?:open|show|launch|go to)\s+(?:the\s+)?(?:phone\s+|system\s+|android\s+)?settings(?:\s+app)?$|^settings$""").matches(text)) {
            return local(LocalCommand.OpenSystemSettings(SettingsSection.Main), "Opening settings")
        }
        return null
    }

    private fun matchSilence(text: String): LocalMatch? {
        if (text.contains(" and ")) return null
        if (isControlQuestion(text)) return null
        if (!silencePhone.matches(text)) return null
        return local(LocalCommand.SilencePhone, "Silencing the phone")
    }

    private fun matchVolume(text: String): LocalMatch? {
        if (!Regex("""\b(volume|louder|quieter|mute|unmute|sound)\b""").containsMatchIn(text)) return null
        if (text.contains("settings")) return null
        Regex(
            """^(?:set|change|turn|put|make|adjust)?\s*(?:the\s+|my\s+)?(?:media\s+|phone\s+)?(?:volume|sound)\s+(?:to\s+|at\s+)?(\d{1,3})\s*(?:%|percent(?:age)?)?$""" +
                """|^(\d{1,3})\s*(?:%|percent(?:age)?)\s+(?:volume|sound)$""",
        )
            .find(text)
            ?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotBlank() }
            ?.toIntOrNull()
            ?.let { raw ->
                val percent = raw.coerceIn(0, 100)
                return local(LocalCommand.SetVolume(percent), "Setting volume to $percent percent")
            }
        return when {
            Regex("""^(?:set\s+)?(?:the\s+)?volume\s+(?:to\s+)?(?:max|maximum|full|100)$|^(?:max|maximum|full)\s+volume$""").matches(text) ->
                local(LocalCommand.SetVolume(100), "Setting volume to maximum")
            Regex("""^(?:turn|crank|pump)\s+(?:it|the\s+volume|the\s+sound|volume)\s+up$|^(?:turn|crank)\s+up\s+(?:the\s+)?(?:volume|sound)$|^(?:the\s+)?volume\s+up$|^louder$|^(?:increase|raise)\s+(?:the\s+)?(?:volume|sound)$""").matches(text) ->
                local(LocalCommand.AdjustVolume(2), "Turning volume up")
            Regex("""^(?:turn)\s+(?:it|the\s+volume|the\s+sound|volume)\s+down$|^turn\s+down\s+(?:the\s+)?(?:volume|sound)$|^(?:the\s+)?volume\s+down$|^quieter$|^(?:decrease|lower|reduce)\s+(?:the\s+)?(?:volume|sound)$""").matches(text) ->
                local(LocalCommand.AdjustVolume(-2), "Turning volume down")
            Regex("""^mute$|^mute\s+(?:the\s+)?(?:volume|sound|phone|media|music)$""").matches(text) ->
                local(LocalCommand.SetVolume(0), "Muting volume")
            Regex("""^unmute(?:\s+(?:the\s+)?(?:volume|sound|phone|media|music))?$""").matches(text) ->
                local(LocalCommand.SetVolume(50), "Unmuting")
            else -> null
        }
    }

    private val timerFiller = setOf(
        "set", "start", "create", "make", "put", "run", "begin", "add", "a", "an", "the", "my", "new",
        "timer", "countdown", "for", "of", "in", "after", "on", "alarm", "wake", "me", "up", "and",
    )

    private val durationPart = Regex("""(\d{1,4})\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)\b""")

    private fun durationSeconds(text: String): Int? {
        val parts = durationPart.findAll(text).toList()
        if (parts.isEmpty()) return null
        val total = parts.sumOf { part ->
            val amount = part.groupValues[1].toInt()
            val unit = part.groupValues[2]
            when {
                unit.startsWith("h") -> amount * 3600
                unit.startsWith("m") -> amount * 60
                else -> amount
            }
        }
        return total.coerceIn(1, 24 * 3600)
    }

    private fun matchTimer(text: String): LocalMatch? {
        if (Regex("""^(?:cancel|stop|delete|remove|end|turn off|dismiss)\s+(?:the\s+|my\s+)?(?:timer|countdown)$""").matches(text)) {
            return local(LocalCommand.CancelTimer, "Cancelling the timer")
        }
        val timerWord = Regex("""\b(timer|countdown)\b""").containsMatchIn(text)
        val alarmIn = Regex("""^(?:set\s+(?:an?\s+)?alarm|wake\s+me(?:\s+up)?|alarm)\s+(?:in|after)\s+""").containsMatchIn(text)
        if (!timerWord && !alarmIn) return null
        if (timerWord && Regex("""^(?:what|how|why|when|is|are|does|do)\b""").containsMatchIn(text)) return null
        val residue = durationPart.replace(text, " ")
            .replace(Regex("""\d+"""), " ")
            .split(Regex("""[\s-]+"""))
            .filter { it.isNotBlank() && it !in timerFiller }
        if (residue.isNotEmpty()) return null
        val seconds = durationSeconds(text) ?: run {
            Regex("""^(?:set\s+(?:a\s+)?)?timer\s+(?:for\s+)?(\d{1,4})$""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?.let { minutes -> (minutes * 60).coerceIn(1, 24 * 3600) }
        } ?: return null
        return local(LocalCommand.StartTimer(seconds, "KAI timer"), "Starting a timer for ${describeDuration(seconds)}")
    }

    private val alarmLead = Regex(
        """^(?:set|create|add|put|keep|make)\s+(?:an?\s+|the\s+|my\s+)?alarm(?:\s+clock)?\s*(?:for|at|to|on)?\s*(.*)$""" +
            """|^alarm\s+(?:for\s+|at\s+)?(.*)$""" +
            """|^wake\s+me(?:\s+up)?\s*(?:at|by|for|on)?\s*(.*)$""",
    )
    private val alarmTime = Regex(
        """^(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?(?:\s+(in the morning|morning|in the evening|evening|at night|night|tonight|in the afternoon|afternoon))?$""",
    )

    private fun matchAlarm(text: String): LocalMatch? {
        val (stripped, repeat, weekday) = stripReminderRepeat(text)
        val lead = alarmLead.find(stripped) ?: return null
        var rest = lead.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        var dayOffset = 0
        Regex("""^(?:for\s+|at\s+)?tomorrow\s+(?:morning\s+)?(?:at\s+)?""").find(rest)?.let { day ->
            if (day.value.contains("morning")) rest = rest.removePrefix(day.value).trim() + " morning"
            else rest = rest.removePrefix(day.value).trim()
            dayOffset = 1
        }
        Regex("""\s+tomorrow(?:\s+morning)?$""").find(rest)?.let { day ->
            rest = rest.substring(0, day.range.first).trim() + if (day.value.contains("morning")) " morning" else ""
            dayOffset = 1
        }
        rest = rest.removePrefix("at ").removePrefix("for ").trim()
        val time = alarmTime.find(rest) ?: return null
        val hour = time.groupValues[1].toInt()
        val minute = time.groupValues[2].toIntOrNull() ?: 0
        if (hour > 23 || minute > 59) return null
        val meridiem = time.groupValues[3].ifBlank {
            when (time.groupValues[4]) {
                "" -> ""
                "in the morning", "morning" -> "am"
                else -> "pm"
            }
        }
        val flexible = meridiem.isBlank() && hour in 1..12
        val hour24 = normalizeHour(hour, meridiem)
        val spoken = alarmSpokenLine(hour24, minute, repeat, weekday)
        return LocalMatch(
            LocalCommand.SetAlarm(hour24, minute, "KAI alarm", repeat, weekday, dayOffset, flexible),
            requiresConfirmation = false,
            confirmationPrompt = "Set an alarm${spoken.removePrefix("Alarm set").removeSuffix(".")}?",
            spokenLabel = "Setting an alarm",
        )
    }

    private fun matchPhoto(text: String): LocalMatch? {
        val ask = parsePhotoAsk(text) ?: return null
        return local(LocalCommand.DescribePhoto(useBackend = ask.useBackend), "Describing a photo")
    }

    private fun matchCalendar(utterance: String): LocalMatch? {
        val request = parseCalendarRequest(utterance) ?: return null
        val command = when (request) {
            is CalendarRequest.Add -> LocalCommand.AddCalendarEvent(
                title = request.title,
                dayOffset = request.dayOffset,
                hour = request.hour,
                minute = request.minute,
                meridiem = request.meridiem,
            )
            is CalendarRequest.Read -> LocalCommand.ReadSchedule(request.dayOffset)
            is CalendarRequest.Note -> LocalCommand.SaveNote(request.text)
            CalendarRequest.Briefing -> LocalCommand.DailyBriefing
        }
        val label = when (request) {
            is CalendarRequest.Add -> "Adding a calendar event"
            is CalendarRequest.Read -> "Reading your schedule"
            is CalendarRequest.Note -> "Saving a note"
            CalendarRequest.Briefing -> "Daily briefing"
        }
        return local(command, label)
    }

    private fun matchBooks(text: String): LocalMatch? {
        val action = BooksUtterance.parse(text) ?: return null
        val label = if (action is BooksAction.Summary) "Checking the books" else "Updating the books"
        return local(LocalCommand.Books(action), label)
    }

    private fun matchMemory(utterance: String): LocalMatch? {
        val request = parseMemoryRequest(utterance) ?: return null
        val command = when (request) {
            is MemoryRequest.Save -> LocalCommand.SaveMemory(
                request.text,
                project = request.kind == MemoryKind.Project,
            )
            is MemoryRequest.Delete -> LocalCommand.DeleteMemory(request.query, request.all)
            is MemoryRequest.Edit -> LocalCommand.EditMemory(request.query, request.text)
            is MemoryRequest.Search -> LocalCommand.RecallMemory(RecallMode.Search, request.query)
            MemoryRequest.ListAll -> LocalCommand.RecallMemory(RecallMode.List)
            MemoryRequest.ListProjects -> LocalCommand.RecallMemory(RecallMode.Projects)
            MemoryRequest.RecentTalk -> LocalCommand.RecallMemory(RecallMode.Recent)
        }
        return local(command, "Checking memory")
    }

    private fun matchScreenshot(text: String): LocalMatch? {
        val hit = Regex(
            """^(?:take|grab|capture|get)\s+(?:a\s+|the\s+)?screenshot(?:\s+(?:now|of (?:this|the|my) screen))?$""" +
                """|^screenshot(?:\s+(?:this|now|the screen))?$|^capture\s+(?:the\s+|my\s+|this\s+)?screen$""",
        ).matches(text)
        return if (hit) local(LocalCommand.TakeScreenshot, "Taking a screenshot") else null
    }

    private fun matchReminderEdit(text: String): LocalMatch? {
        ReminderUtterance.parseCancel(text)?.let { query ->
            return local(LocalCommand.CancelReminder(query), "Cancelling a reminder")
        }
        ReminderUtterance.parseChange(text)?.let { command ->
            return local(command, "Changing a reminder")
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
        return local(command, if (items.size == 1) "Setting a reminder" else "Setting reminders")
    }

    private val weekdayNumbers = mapOf(
        "monday" to 1,
        "tuesday" to 2,
        "wednesday" to 3,
        "thursday" to 4,
        "friday" to 5,
        "saturday" to 6,
        "sunday" to 7,
    )

    private fun stripReminderRepeat(text: String): Triple<String, ReminderRepeat, Int?> {
        val weekly = Regex(
            """\s+(?:every|on)\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?$""",
        ).find(text)
        if (weekly != null) {
            val day = weekdayNumbers[weekly.groupValues[1]] ?: return Triple(text, ReminderRepeat.Once, null)
            return Triple(text.removeSuffix(weekly.value).trim(), ReminderRepeat.Weekly, day)
        }
        val daily = Regex("""\s+(?:every\s+day|everyday|daily|every\s+morning)$""").find(text)
        if (daily != null) return Triple(text.removeSuffix(daily.value).trim(), ReminderRepeat.Daily, null)
        val weekdays = Regex("""\s+(?:every\s+weekday|on\s+weekdays|weekdays)$""").find(text)
        if (weekdays != null) return Triple(text.removeSuffix(weekdays.value).trim(), ReminderRepeat.Weekdays, null)
        return Triple(text, ReminderRepeat.Once, null)
    }

    private fun matchScreen(text: String): LocalMatch? {
        val hit = Regex(
            """^(?:what(?:'s| is)?|whats)\s+on\s+(?:my\s+|the\s+|this\s+)?screen$""" +
                """|^(?:read|describe)\s+(?:out\s+)?(?:my|the|this)\s+screen$|^what am i looking at$""",
        ).matches(text)
        return if (hit) local(LocalCommand.DescribeScreen, "Looking at the screen") else null
    }

    private fun matchMaps(text: String): LocalMatch? {
        val patterns = listOf(
            Regex("""^(?:navigate|directions|route|drive)\s+(?:me\s+)?to\s+(.+)$"""),
            Regex("""^(?:get|give|show)\s+(?:me\s+)?directions\s+to\s+(.+)$"""),
            Regex("""^take\s+me\s+(?:to\s+)?(home|.+)$"""),
            Regex("""^how\s+(?:do|can)\s+i\s+(?:get|go)\s+to\s+(.+)$"""),
            Regex("""^show\s+(?:me\s+)?(.+?)\s+on\s+(?:the\s+)?maps?$"""),
            Regex("""^(?:find|show me|search for)\s+(?:a\s+|the\s+)?(.+?\s+near\s*(?:me|by))$"""),
            Regex("""^(?:where(?:'s| is)\s+the\s+nearest)\s+(.+)$"""),
            Regex("""^(.+?\s+near\s+me)$"""),
        )
        for (pattern in patterns) {
            val query = pattern.find(text)?.groupValues?.get(1)?.trim().orEmpty()
            if (query.isNotBlank()) {
                val target = if (pattern.pattern.contains("nearest")) "$query near me" else query
                return local(LocalCommand.OpenMaps(target), "Opening maps")
            }
        }
        val openMaps = Regex("""^(?:open|launch)\s+(?:google\s+)?maps(?:\s+(?:to|for)\s+(.+))?$""").find(text)
        if (openMaps != null) {
            val query = openMaps.groupValues.getOrNull(1)?.trim().orEmpty().ifBlank { "nearby" }
            return local(LocalCommand.OpenMaps(query), "Opening maps")
        }
        return null
    }

    private fun matchCamera(text: String): LocalMatch? {
        val hit = Regex(
            """^(?:open|launch|start)\s+(?:the\s+)?camera(?:\s+app)?$|^(?:take|click|capture)\s+(?:a\s+|me\s+a\s+)?(?:photo|picture|pic|selfie|snap)$""",
        ).matches(text)
        return if (hit) local(LocalCommand.OpenCamera, "Opening camera") else null
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
        """^(?:(?:make|place|start|do)\s+a\s+)?(?:normal\s+|regular\s+|voice\s+|phone\s+)?(?:phone\s+call|call|phone|dial|ring(?:\s+up)?)(?:\s+to)?(?:\s+the)?\s+(\S(?:.*\S)?)$""",
    )
    private val giveCall = Regex(
        """^give\s+(.+?)\s+a\s+(?:phone\s+)?(?:call|ring|buzz)$""",
    )
    private val hinglishCall = Regex(
        """^(.+?)\s+(?:ko|ku)\s+(?:phone|call|fone)\s+(?:karo|kar|lagao|laga|kardo|kar do|karna|lagana|kijiye|kariye)$""" +
            """|^(?:call|phone)\s+(?:karo|lagao|laga|kar)\s+(.+?)(?:\s+(?:ko|ku))?$""",
    )
    private val typedSuffix = Regex(
        """^(.*\S)\s+(?:on|at)(?:\s+(?:his|her|their))?\s+(mobile|cell(?:\s+phone)?|cellphone|home|work|office)(?:\s+number)?$""",
    )
    private val possessiveType = Regex(
        """^(.*\S)'s\s+(mobile|cell(?:\s+phone)?|cellphone|home|work|office)(?:\s+number)?$""",
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
        "number", "people", "person", "time", "times", "about", "this", "that",
    )

    private fun matchSms(text: String, spoken: String): LocalMatch? {
        val parsed = SmsUtterance.parse(text) ?: return null
        val options = parsed.map { it.copy(message = originalCase(spoken, it.message)) }
        val command = LocalCommand.SendSms(options = options)
        val label = if (command.rawNumber) command.nameQuery else ContactMatcher.displayQuery(command.nameQuery)
        return local(command, "Texting $label")
    }

    private fun matchWhatsApp(text: String, spoken: String): LocalMatch? {
        val hinglishVideo = Regex("""^(.+?)\s+(?:ko|ku)\s+(video|whatsapp|whatsapp video)\s+call\s+(?:karo|kar|lagao|laga|kardo|kar do)$""").find(text)
        val call = WhatsAppUtterance.parseCall(text)
            ?: hinglishVideo?.let { match ->
                val name = match.groupValues[1].trim()
                if (WhatsAppUtterance.isContactName(name)) {
                    WhatsAppUtterance.Call(name, video = match.groupValues[2].contains("video"))
                } else {
                    null
                }
            }
        if (call != null) {
            val label = ContactMatcher.displayQuery(call.nameQuery)
            val spokenLine = if (call.video) {
                "Video calling $label on WhatsApp"
            } else {
                "Calling $label on WhatsApp"
            }
            return local(LocalCommand.WhatsAppCall(nameQuery = call.nameQuery, video = call.video), spokenLine)
        }
        val parsed = WhatsAppUtterance.parse(text) ?: return null
        val options = parsed.options.map { it.copy(message = originalCase(spoken, it.message)) }
        val first = options.first()
        val label = ContactMatcher.displayQuery(first.nameQuery)
        val line = if (first.message.isBlank()) {
            "Opening WhatsApp chat with $label"
        } else {
            "Sending ${first.message} to $label on WhatsApp"
        }
        return local(LocalCommand.SendWhatsApp(options = options), line)
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
        return local(command, "Calling $label")
    }

    private fun extractCallTarget(text: String): String? {
        if (text.startsWith("video ")) return null
        hinglishCall.find(text)?.let { match ->
            return match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()
        }
        callVerb.find(text)?.let { return it.groupValues[1].trim() }
        giveCall.find(text)?.let { return it.groupValues[1].trim() }
        return null
    }

    private fun parseCallTarget(rawTarget: String): ParsedCall? {
        val stripped = stripTrailingConfirmation(rawTarget)
        var target = stripped.target
            .replace(Regex("""\s+on\s+(?:this|the|my)\s+phone$"""), "")
            .replace(Regex("""\s+(?:on|with)\s+(?:the\s+)?speaker(?:\s*phone)?$"""), "")
            .replace(Regex("""\s+(?:using|with|from)\s+(?:the\s+)?(?:sim\s*\d|dialer|phone app)$"""), "")
            .replace(Regex("""^my\s+"""), "")
            .trim()
        if (target.isBlank()) return null

        var phoneType: PhoneKind? = null
        (typedSuffix.find(target) ?: possessiveType.find(target))?.let { match ->
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

    private fun matchLatestNotification(text: String): LocalMatch? {
        if (text.contains(" and ")) return null
        if (!latestNotification.matches(text)) return null
        return local(LocalCommand.ReadLatestNotification, "Reading the latest notification")
    }

    private fun matchPersonalControls(text: String): LocalMatch? {
        if (text.contains(" and ")) return null
        matchBrightness(text)?.let { return it }
        matchDoNotDisturb(text)?.let { return it }
        matchRadio(text)?.let { return it }
        return null
    }

    private fun matchBrightness(text: String): LocalMatch? {
        if (isControlQuestion(text)) return null
        if (Regex("""\b(?:settings|dark\s+mode|display\s+settings)\b""").containsMatchIn(text)) return null
        brightnessLevel.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()?.let { raw ->
            val percent = raw.coerceIn(0, 100)
            return local(LocalCommand.SetBrightness(percent), "Setting brightness to $percent percent")
        }
        return when {
            brightnessUp.matches(text) -> local(LocalCommand.AdjustBrightness(true), "Turning brightness up")
            brightnessDown.matches(text) -> local(LocalCommand.AdjustBrightness(false), "Turning brightness down")
            else -> null
        }
    }

    private fun matchDoNotDisturb(text: String): LocalMatch? {
        if (isControlQuestion(text)) return null
        if (!Regex("""\b(?:do\s+not\s+disturb|dnd)\b""").containsMatchIn(text)) return null
        if (Regex("""\bsettings\b""").containsMatchIn(text)) return null
        if (Regex("""^(?:open|show|launch)\b""").containsMatchIn(text)) return null
        val enabled = radioEnabled(text) ?: return null
        return local(
            LocalCommand.SetDoNotDisturb(enabled),
            if (enabled) "Turning Do Not Disturb on" else "Turning Do Not Disturb off",
        )
    }

    private fun matchRadio(text: String): LocalMatch? {
        if (isControlQuestion(text)) return null
        val wifi = Regex("""\b(?:wi-?fi|wifi)\b""").containsMatchIn(text)
        val bluetooth = Regex("""\bbluetooth\b""").containsMatchIn(text)
        if (wifi == bluetooth) return null
        if (Regex("""\b(?:settings|panel)\b""").containsMatchIn(text)) return null
        if (Regex("""^(?:open|show|launch)\b""").containsMatchIn(text)) return null
        val enabled = radioEnabled(text) ?: return null
        return if (wifi) {
            local(LocalCommand.SetWifi(enabled), if (enabled) "Turning Wi-Fi on" else "Turning Wi-Fi off")
        } else {
            local(
                LocalCommand.SetBluetooth(enabled),
                if (enabled) "Turning Bluetooth on" else "Turning Bluetooth off",
            )
        }
    }

    private fun radioEnabled(text: String): Boolean? {
        val off = Regex("""\b(?:off|disable|disconnect)\b""").containsMatchIn(text)
        val on = Regex("""\b(?:on|enable|connect)\b""").containsMatchIn(text)
        return when {
            off && !on -> false
            on && !off -> true
            else -> null
        }
    }

    private fun isControlQuestion(text: String): Boolean =
        Regex(
            """^(?:what|what's|whats|how|why|who|when|where|which|is|are|do|does|did|can|could|should|would)\b""",
        ).containsMatchIn(text)

    private fun matchMedia(text: String): LocalMatch? {
        if (text.contains(" and ")) return null
        if (isControlQuestion(text)) return null
        val action = when {
            mediaPlay.matches(text) -> MediaAction.Play
            mediaPause.matches(text) -> MediaAction.Pause
            mediaNext.matches(text) -> MediaAction.Next
            mediaPrevious.matches(text) -> MediaAction.Previous
            else -> return null
        }
        val label = when (action) {
            MediaAction.Play -> "Playing"
            MediaAction.Pause -> "Pausing"
            MediaAction.Next -> "Next track"
            MediaAction.Previous -> "Previous track"
        }
        return local(LocalCommand.MediaControl(action), label)
    }

    private fun matchLaunchApp(text: String): LocalMatch? {
        // "okay open youtube" was reaching cloud chat, which only says it opened the app.
        val spoken = text.replace(Regex("""^(?:okay|ok|sure|alright)[\s,]+"""), "")
        val match = Regex("""^(?:open|launch|start|open up)\s+(?:the\s+|my\s+)?(.+?)(?:\s+app(?:lication)?)?$""").find(spoken) ?: return null
        val name = match.groupValues[1].trim()
        if (name.isBlank() || name.contains(" and ") || name.split(" ").size > 4) return null
        val blocked = setOf(
            "settings", "wifi", "wi-fi", "bluetooth", "camera", "maps",
            "flashlight", "torch", "flash", "volume", "alarm", "timer",
        )
        if (name in blocked) return null
        if (name.startsWith("wifi") || name.startsWith("wi-fi") || name.startsWith("bluetooth")) return null
        val spokenName = spokenLaunchName("", name)
        return local(LocalCommand.LaunchAppByName(name), "Opening $spokenName")
    }

    private fun matchYouTube(text: String): LocalMatch? {
        val query = parseYouTubeSearch(text) ?: return null
        return local(LocalCommand.SearchYouTube(query), "Searching YouTube")
    }

    private fun normalizeHour(hour: Int, meridiem: String): Int {
        var h = hour.coerceIn(0, 23)
        when (meridiem) {
            "am" -> if (h == 12) h = 0
            "pm" -> if (h in 1..11) h += 12
        }
        return h
    }

    private fun describeDuration(seconds: Int): String = durationLabel(seconds)
}

/** Message words as the user typed or said them, when the lowercase copy can be found. */
internal fun originalCase(spoken: String, lowered: String): String {
    if (lowered.isEmpty()) return lowered
    val index = spoken.lowercase().lastIndexOf(lowered)
    if (index < 0 || spoken.length != spoken.lowercase().length) return lowered
    return spoken.substring(index, index + lowered.length)
}

private val looksLikeNumber = Regex("""^[+(\d][\d\s().-]*$""")

internal fun looksLikePhoneNumber(target: String): Boolean {
    val digits = target.count { it.isDigit() }
    if (digits !in 3..15) return false
    return looksLikeNumber.matches(target.trim())
}
