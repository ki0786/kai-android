package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

/**
 * Phrases people actually say to a phone assistant, typed or recognised by speech,
 * and where each must go. Anything the phone can do has to stay on the phone.
 */
class RoutingPhraseTableTest {
    private val router = LocalIntentRouter()

    private val onPhone: List<Pair<String, KClass<out LocalCommand>>> = listOf(
        // Calls
        "call kaif" to LocalCommand.CallContact::class,
        "Call Kaif." to LocalCommand.CallContact::class,
        "please call mom" to LocalCommand.CallContact::class,
        "can you call mohammed kaif" to LocalCommand.CallContact::class,
        "hey kai call dad" to LocalCommand.CallContact::class,
        "Hey KAI, call dad" to LocalCommand.CallContact::class,
        "ok kai call dad" to LocalCommand.CallContact::class,
        "call my mom" to LocalCommand.CallContact::class,
        "dial 9876543210" to LocalCommand.CallContact::class,
        "call +91 98765 43210" to LocalCommand.CallContact::class,
        "phone rahul" to LocalCommand.CallContact::class,
        "ring ayesha" to LocalCommand.CallContact::class,
        "give kaif a ring" to LocalCommand.CallContact::class,
        "kaif ko call karo" to LocalCommand.CallContact::class,
        "call karo kaif ko" to LocalCommand.CallContact::class,
        "call kaif on speaker" to LocalCommand.CallContact::class,
        "call kaif's mobile" to LocalCommand.CallContact::class,
        "cal kaif" to LocalCommand.CallContact::class,
        "call nurdine" to LocalCommand.CallContact::class,
        "call muhammad kef" to LocalCommand.CallContact::class,
        "call mohamed cab" to LocalCommand.CallContact::class,
        "I want to call imran bhai" to LocalCommand.CallContact::class,
        // WhatsApp calls
        "video call kaif" to LocalCommand.WhatsAppCall::class,
        "vedio call kaif" to LocalCommand.WhatsAppCall::class,
        "make a video call to kaif" to LocalCommand.WhatsAppCall::class,
        "start a video call with mom" to LocalCommand.WhatsAppCall::class,
        "whatsapp call kaif" to LocalCommand.WhatsAppCall::class,
        "call kaif on whatsapp" to LocalCommand.WhatsAppCall::class,
        "call kaif on what's up" to LocalCommand.WhatsAppCall::class,
        "video call kaif on whatsapp" to LocalCommand.WhatsAppCall::class,
        "open whatsapp and video call kaif" to LocalCommand.WhatsAppCall::class,
        "whats app video call muhammad kev" to LocalCommand.WhatsAppCall::class,
        // WhatsApp messages
        "whatsapp kaif i'm on my way" to LocalCommand.SendWhatsApp::class,
        "send hi to kaif on whatsapp" to LocalCommand.SendWhatsApp::class,
        "message kaif running late" to LocalCommand.SendWhatsApp::class,
        "text kaif come home" to LocalCommand.SendWhatsApp::class,
        "send hi to mom" to LocalCommand.SendWhatsApp::class,
        "tell kaif that i'm late" to LocalCommand.SendWhatsApp::class,
        "tell kaif i'm late on whatsapp" to LocalCommand.SendWhatsApp::class,
        "send a message to kaif saying hello" to LocalCommand.SendWhatsApp::class,
        "send a whatsapp to mom that dinner is ready" to LocalCommand.SendWhatsApp::class,
        "whatsapp kaif" to LocalCommand.SendWhatsApp::class,
        "open chat with kaif" to LocalCommand.SendWhatsApp::class,
        "msg kaif ok" to LocalCommand.SendWhatsApp::class,
        "message hi to muhammad kef on whatsapp" to LocalCommand.SendWhatsApp::class,
        // SMS
        "sms kaif running late" to LocalCommand.SendSms::class,
        "send an sms to mom saying call me" to LocalCommand.SendSms::class,
        "text message dad i'm home" to LocalCommand.SendSms::class,
        "send hi to 9876543210 by sms" to LocalCommand.SendSms::class,
        // Timers
        "set a timer for 5 minutes" to LocalCommand.StartTimer::class,
        "timer for 10 min" to LocalCommand.StartTimer::class,
        "5 minute timer" to LocalCommand.StartTimer::class,
        "set a 1 minute timer" to LocalCommand.StartTimer::class,
        "set timer 3" to LocalCommand.StartTimer::class,
        "start a countdown for 30 seconds" to LocalCommand.StartTimer::class,
        "set an alarm in 10 minutes" to LocalCommand.StartTimer::class,
        "set a timer for half an hour" to LocalCommand.StartTimer::class,
        "set a timer for two minutes please" to LocalCommand.StartTimer::class,
        "timer for 1 hour 30 minutes" to LocalCommand.StartTimer::class,
        "set a timmer for 5 minutes" to LocalCommand.StartTimer::class,
        "cancel the timer" to LocalCommand.CancelTimer::class,
        "stop timer" to LocalCommand.CancelTimer::class,
        // Alarms
        "set an alarm for 7 am" to LocalCommand.SetAlarm::class,
        "set alarm for 6:30" to LocalCommand.SetAlarm::class,
        "wake me up at 6" to LocalCommand.SetAlarm::class,
        "wake me up tomorrow at 5:45 am" to LocalCommand.SetAlarm::class,
        "alarm at 7 pm" to LocalCommand.SetAlarm::class,
        "set an alarm for seven thirty" to LocalCommand.SetAlarm::class,
        "set alarm for 6 tomorrow morning" to LocalCommand.SetAlarm::class,
        "set an alarm for 9 every day" to LocalCommand.SetAlarm::class,
        "set an alrm for 7am" to LocalCommand.SetAlarm::class,
        "set an alarm for 6:27 p.m." to LocalCommand.SetAlarm::class,
        "please set an alarm for 5 in the morning" to LocalCommand.SetAlarm::class,
        // Reminders
        "remind me to call mom at 5" to LocalCommand.SetReminder::class,
        "remind me to leave in 10 minutes" to LocalCommand.SetReminder::class,
        "remind me to take medicine at 9 every day" to LocalCommand.SetReminder::class,
        "remind me at 5 to call mom" to LocalCommand.SetReminder::class,
        "remind me in 20 minutes to check the oven" to LocalCommand.SetReminder::class,
        "remind me in 2 hours to leave" to LocalCommand.SetReminder::class,
        "set a reminder to pay rent at 9" to LocalCommand.SetReminder::class,
        "set a reminder for 6 pm to call kaif" to LocalCommand.SetReminder::class,
        "remember to call kaif at 6" to LocalCommand.SetReminder::class,
        "remind me to call kaif tomorrow at 10 am" to LocalCommand.SetReminder::class,
        "remind me tomorrow at 10 to pay the supplier" to LocalCommand.SetReminder::class,
        "remind me about the meeting at 4" to LocalCommand.SetReminder::class,
        // Settings the phone can open
        "turn on wifi" to LocalCommand.SetWifi::class,
        "turn off wi-fi" to LocalCommand.SetWifi::class,
        "turn off bluetooth" to LocalCommand.SetBluetooth::class,
        "open settings" to LocalCommand.OpenSystemSettings::class,
        "turn on mobile data" to LocalCommand.OpenSystemSettings::class,
        "turn on airplane mode" to LocalCommand.OpenSystemSettings::class,
        "turn on hotspot" to LocalCommand.OpenSystemSettings::class,
        "turn on location" to LocalCommand.OpenSystemSettings::class,
        "turn on do not disturb" to LocalCommand.SetDoNotDisturb::class,
        "increase brightness" to LocalCommand.AdjustBrightness::class,
        "turn on battery saver" to LocalCommand.OpenSystemSettings::class,
        "open kai app settings" to LocalCommand.OpenAppSettings::class,
        // Device
        "turn on the flashlight" to LocalCommand.Flashlight::class,
        "torch on" to LocalCommand.Flashlight::class,
        "flash light off" to LocalCommand.Flashlight::class,
        "set volume to 40" to LocalCommand.SetVolume::class,
        "volume up" to LocalCommand.AdjustVolume::class,
        "take a screenshot" to LocalCommand.TakeScreenshot::class,
        "battery" to LocalCommand.BatteryStatus::class,
        "what time is it" to LocalCommand.DateTime::class,
        // Apps, media, maps
        "open instagram" to LocalCommand.LaunchAppByName::class,
        "open youtube" to LocalCommand.LaunchAppByName::class,
        "launch spotify" to LocalCommand.LaunchAppByName::class,
        "play despacito on youtube" to LocalCommand.SearchYouTube::class,
        "play" to LocalCommand.MediaControl::class,
        "pause" to LocalCommand.MediaControl::class,
        "next song" to LocalCommand.MediaControl::class,
        "previous" to LocalCommand.MediaControl::class,
        "what's my latest notification" to LocalCommand.ReadLatestNotification::class,
        "mute" to LocalCommand.SetVolume::class,
        "silent my phone" to LocalCommand.SilencePhone::class,
        "mute my phone" to LocalCommand.SilencePhone::class,
        "volume down" to LocalCommand.AdjustVolume::class,
        "navigate to mg road" to LocalCommand.OpenMaps::class,
        "directions to the airport" to LocalCommand.OpenMaps::class,
        "take a photo" to LocalCommand.OpenCamera::class,
        // Business books stay on the phone. They do not pay anyone or message a supplier.
        "I paid 5000 to the wood supplier" to LocalCommand.Books::class,
        "Rahul owes me 12000" to LocalCommand.Books::class,
        "electricity bill 2400 due on the 5th" to LocalCommand.Books::class,
        "sold a sofa for 45000 advance 10000" to LocalCommand.Books::class,
        "sold a sofa for 25000" to LocalCommand.Books::class,
        "labour needs 10 sheets of plywood" to LocalCommand.Books::class,
        "how are the books" to LocalCommand.Books::class,
        "open business books" to LocalCommand.Books::class,
        "rent 15000" to LocalCommand.Books::class,
        "personal lunch 400" to LocalCommand.Books::class,
        "a personal lunch 400" to LocalCommand.Books::class,
        "bank loan 50000" to LocalCommand.Books::class,
        "a bank loan 50000" to LocalCommand.Books::class,
        "I paid 15000 for rent" to LocalCommand.Books::class,
    )

    private val toChat = listOf(
        "what is the capital of france",
        "tell me a joke",
        "call me back later",
        "how do I make a video call",
        "write a poem about rain",
        "explain how alarms work on android",
        "what's the difference between sms and whatsapp",
        "send me a summary of world war two",
        "text me later",
        "set a timer for 2 minutes and remind me to stretch at 8",
        "who is the prime minister of india",
    )

    @Test
    fun everyPhoneActionStaysOnThePhone() {
        val failures = onPhone.mapNotNull { (phrase, expected) ->
            val match = router.match(phrase)
            when {
                match == null -> "\"$phrase\" went to chat, expected ${expected.simpleName}"
                !expected.isInstance(match.command) ->
                    "\"$phrase\" became ${match.command::class.simpleName}, expected ${expected.simpleName}"
                match.requiresConfirmation -> "\"$phrase\" asks to confirm"
                !match.command.staysOnDevice() -> "\"$phrase\" would be sent to the server"
                else -> null
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun questionsAndChatterAreNotPhoneActions() {
        val wrong = toChat.mapNotNull { phrase ->
            router.match(phrase)?.let { "\"$phrase\" became ${it.command::class.simpleName}" }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun fieldsComeOutRight() {
        assertEquals("kaif", (router.match("Hey KAI, call Kaif please")!!.command as LocalCommand.CallContact).query)
        assertEquals("mom", (router.match("call my mom")!!.command as LocalCommand.CallContact).query)
        assertEquals("kaif", (router.match("kaif ko call karo")!!.command as LocalCommand.CallContact).query)
        assertEquals("kaif", (router.match("give kaif a ring")!!.command as LocalCommand.CallContact).query)
        assertEquals(PhoneKind.Mobile, (router.match("call kaif's mobile")!!.command as LocalCommand.CallContact).phoneType)
        assertTrue((router.match("dial 9876543210")!!.command as LocalCommand.CallContact).rawNumber)

        val video = router.match("vedio call kaif")!!.command as LocalCommand.WhatsAppCall
        assertTrue(video.video)
        assertEquals("kaif", video.nameQuery)
        assertFalse((router.match("call kaif on whatsapp")!!.command as LocalCommand.WhatsAppCall).video)

        assertEquals(1800, (router.match("set a timer for half an hour")!!.command as LocalCommand.StartTimer).seconds)
        assertEquals(5400, (router.match("timer for 1 hour 30 minutes")!!.command as LocalCommand.StartTimer).seconds)
        assertEquals(180, (router.match("set timer 3")!!.command as LocalCommand.StartTimer).seconds)
        assertEquals(600, (router.match("set an alarm in 10 minutes")!!.command as LocalCommand.StartTimer).seconds)

        val seven = router.match("set an alarm for 7 am")!!.command as LocalCommand.SetAlarm
        assertEquals(7, seven.hour)
        assertFalse(seven.flexible)
        val loose = router.match("set an alarm for 7")!!.command as LocalCommand.SetAlarm
        assertTrue(loose.flexible)
        val tomorrow = router.match("wake me up tomorrow at 5:45 am")!!.command as LocalCommand.SetAlarm
        assertEquals(5, tomorrow.hour)
        assertEquals(45, tomorrow.minute)
        assertEquals(1, tomorrow.dayOffset)
        val morning = router.match("set alarm for 6 tomorrow morning")!!.command as LocalCommand.SetAlarm
        assertEquals(6, morning.hour)
        assertEquals(1, morning.dayOffset)
        assertEquals(19, (router.match("alarm at 7 pm")!!.command as LocalCommand.SetAlarm).hour)
        val half = router.match("set an alarm for seven thirty")!!.command as LocalCommand.SetAlarm
        assertEquals(30, half.minute)
        assertEquals(ReminderRepeat.Daily, (router.match("set an alarm for 9 every day")!!.command as LocalCommand.SetAlarm).repeat)

        assertEquals(
            LocalCommand.OpenSystemSettings(SettingsSection.MobileData),
            router.match("turn on mobile data")!!.command,
        )
        assertEquals(LocalCommand.OpenSystemSettings(SettingsSection.Main), router.match("open settings")!!.command)
    }

    @Test
    fun reminderPhrasingsAgree() {
        fun reminder(phrase: String) = router.match(phrase)!!.command as LocalCommand.SetReminder
        assertEquals(LocalCommand.SetReminder("call mom", 5, 0, null), reminder("remind me at 5 to call mom"))
        assertEquals(120, reminder("remind me in 2 hours to leave").inMinutes)
        assertEquals("check the oven", reminder("remind me in 20 minutes to check the oven").text)
        assertEquals("pay rent", reminder("set a reminder to pay rent at 9").text)
        val evening = reminder("set a reminder for 6 pm to call kaif")
        assertEquals("call kaif", evening.text)
        assertEquals("pm", evening.meridiem)
        val tomorrow = reminder("remind me tomorrow at 10 to pay the supplier")
        assertEquals("pay the supplier", tomorrow.text)
        assertEquals(1, tomorrow.dayOffset)
        assertEquals(10, tomorrow.hour)
        assertEquals(1, reminder("remind me to call kaif tomorrow at 10 am").dayOffset)
        assertEquals("call kaif", reminder("remember to call kaif at 6").text)
    }

    @Test
    fun messagesKeepTheirWordsAndCase() {
        val tell = router.match("Tell Kaif that I'm Late")!!.command as LocalCommand.SendWhatsApp
        assertEquals(WhatsAppUtterance.Option("kaif", "I'm Late"), tell.options.single())
        val saying = router.match("send a message to kaif saying hello there")!!.command as LocalCommand.SendWhatsApp
        assertEquals("hello there", saying.options.single().message)
        val open = router.match("whatsapp kaif")!!.command as LocalCommand.SendWhatsApp
        assertEquals(WhatsAppUtterance.Option("kaif", ""), open.options.last())
        val sms = router.match("send an sms to mom saying call me")!!.command as LocalCommand.SendSms
        assertEquals(WhatsAppUtterance.Option("mom", "call me"), sms.options.single())
        val number = router.match("send hi to 9876543210 by sms")!!.command as LocalCommand.SendSms
        assertTrue(number.rawNumber)
    }

    @Test
    fun compoundCommandsKeepEveryPhoneStep() {
        val timerAndApp = planSteps("set a 1 minute timer and then open youtube") { router.match(it) }!!
        assertEquals(listOf(LocalCommand.StartTimer::class, LocalCommand.LaunchAppByName::class), timerAndApp.steps.map { it.command::class })
        assertTrue(timerAndApp.leftover.isEmpty())

        val callAndTell = planSteps("call kaif and tell him I'm running late") { router.match(it) }!!
        assertEquals(1, callAndTell.steps.size)
        assertTrue(callAndTell.steps.single().command is LocalCommand.CallContact)
        assertTrue(callAndTell.leftover.isEmpty())

        val openThenMessage = planSteps("open whatsapp and message kaif hi") { router.match(it) }!!
        assertEquals(1, openThenMessage.steps.size)
        assertTrue(openThenMessage.steps.single().command is LocalCommand.SendWhatsApp)

        val withChat = planSteps("turn on the flashlight and tell me a joke") { router.match(it) }!!
        assertTrue(withChat.steps.single().command is LocalCommand.Flashlight)
        assertEquals(listOf("tell me a joke"), withChat.leftover)

        assertNull(planSteps("tell me a joke and write a poem") { router.match(it) })
    }

    @Test
    fun halfParsedPhoneCommandsGetAHintNotChat() {
        listOf("call", "set an alarm", "set a timer", "remind me", "video call", "whatsapp", "wake me up")
            .forEach { phrase ->
                assertNull("\"$phrase\" should not fully parse", router.match(phrase))
                assertNotNull("\"$phrase\" needs a local hint", phoneActionHint(phrase))
            }
        listOf("call me back later", "how do i call abroad", "tell me a joke", "what is an alarm clock", "why do timers beep?")
            .forEach { phrase -> assertNull("\"$phrase\" belongs to chat", phoneActionHint(phrase)) }
    }

    @Test
    fun chatRepliesNeverSayThePhoneCantAct() {
        val fixed = fixCantActReply("I'm sorry, but I can't make phone calls. I'm a text-based AI.")
        assertFalse(fixed.contains("can't make phone calls"))
        assertTrue(fixed.contains("call Kaif"))
        assertTrue(fixCantActReply("As an AI, I cannot send WhatsApp messages for you.").startsWith("I can do that"))
        assertFalse(fixCantActReply("I am unable to set alarms on your device.").contains("unable"))
        val untouched = "Paris is the capital of France."
        assertEquals(untouched, fixCantActReply(untouched))
        assertEquals("I can't say for sure, it depends.", fixCantActReply("I can't say for sure, it depends."))
        assertTrue(KAI_SYSTEM_PROMPT.contains("Never say you can't make calls"))
    }

    @Test
    fun normalizerFixesSpeechAndTypos() {
        assertEquals("set an alarm for 7am", PhraseNormalizer.clean("Hey KAI, please set an alrm for 7am."))
        assertEquals("video call kaif on whatsapp", PhraseNormalizer.clean("vedio call kaif on what's up"))
        assertEquals("call kaif", PhraseNormalizer.clean("can you call kaif for me please"))
        assertEquals("set a timer for 30 minutes", digitsForNumberWords("set a timer for half an hour"))
        assertEquals("set a timer for 2 minutes", digitsForNumberWords("set a timer for two minutes"))
        assertEquals("set an alarm for 7:30", digitsForNumberWords("set an alarm for seven thirty"))
    }
}
