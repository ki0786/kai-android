package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phrase routing for the on-phone controls. A clear command stays on the device
 * and does not ask for confirmation.
 */
class PersonalControlsPhraseTest {
    private val router = LocalIntentRouter()

    @Test
    fun systemTogglesStayOnThePhone() {
        assertEquals(LocalCommand.Flashlight(true), command("torch on"))
        assertEquals(LocalCommand.Flashlight(false), command("turn off the torch"))
        assertEquals(LocalCommand.Flashlight(true), command("turn on the flashlight"))
        assertEquals(LocalCommand.SetDoNotDisturb(true), command("turn on do not disturb"))
        assertEquals(LocalCommand.SetDoNotDisturb(false), command("turn off dnd"))
        assertEquals(LocalCommand.SetDoNotDisturb(true), command("dnd on"))
        assertEquals(LocalCommand.SetDoNotDisturb(false), command("disable do not disturb"))
        assertEquals(LocalCommand.AdjustVolume(2), command("volume up"))
        assertEquals(LocalCommand.AdjustVolume(-2), command("volume down"))
        assertEquals(LocalCommand.SetVolume(0), command("mute"))
        assertEquals(LocalCommand.SetVolume(0), command("mute the music"))
        assertEquals(LocalCommand.AdjustBrightness(true), command("brightness up"))
        assertEquals(LocalCommand.AdjustBrightness(true), command("increase brightness"))
        assertEquals(LocalCommand.AdjustBrightness(true), command("turn up the brightness"))
        assertEquals(LocalCommand.AdjustBrightness(false), command("brightness down"))
        assertEquals(LocalCommand.AdjustBrightness(false), command("dim the screen"))
        assertEquals(LocalCommand.SetWifi(true), command("turn on wifi"))
        assertEquals(LocalCommand.SetWifi(false), command("turn off wi-fi"))
        assertEquals(LocalCommand.SetWifi(true), command("wifi on"))
        assertEquals(LocalCommand.SetBluetooth(false), command("turn off bluetooth"))
        assertEquals(LocalCommand.SetBluetooth(true), command("enable bluetooth"))
        assertEquals(LocalCommand.SetBluetooth(false), command("bluetooth off"))
    }

    @Test
    fun blockedSettingsStillOpenAScreenInsteadOfAFakeToggle() {
        assertEquals(LocalCommand.OpenWifiSettings, command("open wifi settings"))
        assertEquals(LocalCommand.OpenBluetoothSettings, command("open bluetooth settings"))
        assertEquals(
            LocalCommand.OpenSystemSettings(SettingsSection.Hotspot),
            command("turn on hotspot"),
        )
        assertEquals(
            LocalCommand.OpenSystemSettings(SettingsSection.Hotspot),
            command("turn off the hotspot"),
        )
        assertEquals(
            LocalCommand.OpenSystemSettings(SettingsSection.DoNotDisturb),
            command("open do not disturb settings"),
        )
        assertEquals(
            LocalCommand.OpenSystemSettings(SettingsSection.Display),
            command("turn on dark mode"),
        )
        assertNull(router.match("why is wifi slow"))
        assertNull(router.match("what is do not disturb"))
    }

    @Test
    fun mediaAndYouTubeStayDistinct() {
        assertEquals(LocalCommand.MediaControl(MediaAction.Play), command("play"))
        assertEquals(LocalCommand.MediaControl(MediaAction.Play), command("play the music"))
        assertEquals(LocalCommand.MediaControl(MediaAction.Pause), command("pause"))
        assertEquals(LocalCommand.MediaControl(MediaAction.Pause), command("stop the music"))
        assertEquals(LocalCommand.MediaControl(MediaAction.Next), command("next"))
        assertEquals(LocalCommand.MediaControl(MediaAction.Next), command("skip this song"))
        assertEquals(LocalCommand.MediaControl(MediaAction.Previous), command("previous"))
        assertEquals(LocalCommand.MediaControl(MediaAction.Previous), command("previous track"))
        assertNull(router.match("next week"))
        assertNull(router.match("play despacito"))

        assertEquals(LocalCommand.LaunchAppByName("youtube"), command("open youtube"))
        assertEquals(LocalCommand.LaunchAppByName("youtube"), command("okay open youtube"))
        val search = command("play believer on youtube") as LocalCommand.SearchYouTube
        assertEquals("believer", search.query)
        assertEquals(
            "https://www.youtube.com/results?search_query=believer",
            youtubeOpenUrl(search.query),
        )
        val video = command("play https://youtu.be/dQw4w9WgXcQ on youtube") as LocalCommand.SearchYouTube
        assertEquals("dQw4w9WgXcQ", youtubeVideoId(video.query))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", youtubeOpenUrl(video.query))
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            youtubeOpenUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        )

        val both = planSteps("turn on wifi and play") { router.match(it) }!!
        assertEquals(LocalCommand.SetWifi(true), both.steps[0].command)
        assertEquals(LocalCommand.MediaControl(MediaAction.Play), both.steps[1].command)
        assertTrue(both.leftover.isEmpty())
    }

    @Test
    fun latestNotificationIsReadOnThePhone() {
        assertEquals(LocalCommand.ReadLatestNotification, command("what's my latest notification"))
        assertEquals(LocalCommand.ReadLatestNotification, command("what is my latest notification"))
        assertEquals(LocalCommand.ReadLatestNotification, command("Hey KAI, what's my latest notification"))
        assertEquals(LocalCommand.ReadLatestNotification, command("read my latest notification please"))
        assertEquals(LocalCommand.ReadLatestNotification, command("read my notifications"))
        assertEquals(LocalCommand.ReadLatestNotification, command("any new notifications"))
        assertNull(router.match("what's a notification"))
        assertNull(router.match("how do notifications work"))
    }

    @Test
    fun clearCommandsDoNotAskAndDoNotLeaveThePhone() {
        val phrases = listOf(
            "torch on",
            "turn on do not disturb",
            "volume down",
            "mute",
            "brightness up",
            "turn off wifi",
            "bluetooth on",
            "play",
            "pause",
            "next song",
            "previous",
            "open youtube",
            "play believer on youtube",
            "what's my latest notification",
            "turn on hotspot",
        )
        phrases.forEach { phrase ->
            val match = router.match(phrase)
            assertTrue("\"$phrase\" should stay on the phone", match != null && match.command.staysOnDevice())
            assertFalse("\"$phrase\" should not ask", match!!.requiresConfirmation)
        }
    }

    @Test
    fun spokenResultsAndHealthUseTheSavedAddress() {
        assertTrue(BRAIN_OFFLINE.contains("brain is offline"))
        assertTrue(BRAIN_OFFLINE.contains("Calls, alarms, books, photos"))
        assertTrue(BRAIN_OFFLINE.contains("phone controls"))
        assertFalse(BRAIN_OFFLINE.contains("http"))
        assertEquals("http://10.0.0.8:8000/health", healthUrl("http://10.0.0.8:8000/"))
        assertEquals("http://10.0.0.8:8000/health", healthUrl(" http://10.0.0.8:8000 "))
        assertFalse(healthUrl("http://10.0.0.8:8000").contains("localhost"))

        assertEquals("Do Not Disturb is on.", dndSpoken(true))
        assertEquals("Do Not Disturb is off.", dndSpoken(false))
        assertFalse(radioUsesPanel(28))
        assertTrue(radioUsesPanel(29))
        val wifi = blockedRadioSpeech("Wi-Fi", enabled = false, usesPanel = true)
        assertTrue(wifi.contains("won't let an app turn Wi-Fi off"))
        assertTrue(wifi.contains("panel"))
        assertTrue(HOTSPOT_BLOCKED.contains("won't let an app turn the hotspot"))

        assertEquals(154, steppedBrightness(128, up = true))
        assertEquals(102, steppedBrightness(128, up = false))
        assertEquals(BRIGHTNESS_MAX, steppedBrightness(BRIGHTNESS_MAX, up = true))
        assertEquals(1, steppedBrightness(1, up = false))
        assertEquals("Brightness is about 60 percent.", brightnessSpoken(128, 154, switchedToManual = false))
        assertTrue(brightnessSpoken(128, 154, switchedToManual = true).contains("automatic brightness"))

        assertEquals("Playing.", mediaSpoken(MediaAction.Play, null))
        assertEquals("Paused Believer.", mediaSpoken(MediaAction.Pause, "Believer"))
        assertEquals("Next track.", mediaSpoken(MediaAction.Next, "Believer"))

        val notices = listOf(
            NoticeLine("com.kai.assistant", "KAI is listening", "", 500, groupSummary = false),
            NoticeLine("com.whatsapp", "Group", "", 400, groupSummary = true),
            NoticeLine("com.whatsapp", "Kaif", "On my way", 300, groupSummary = false),
            NoticeLine("com.android.systemui", "USB", "Charging", 200, groupSummary = false),
        )
        val picked = pickLatestNotice(notices, "com.kai.assistant")
        assertEquals("Kaif", picked!!.title)
        assertEquals(
            "Latest notification from WhatsApp. Kaif. On my way",
            speakNotice("WhatsApp", picked.title, picked.text),
        )
        val onlySummary = listOf(NoticeLine("com.whatsapp", "3 messages", "Kaif: hi", 10, groupSummary = true))
        assertEquals("3 messages", pickLatestNotice(onlySummary, "com.kai.assistant")!!.title)
        assertNull(pickLatestNotice(emptyList(), "com.kai.assistant"))
    }

    private fun command(phrase: String): LocalCommand {
        val match = router.match(phrase)
        assertNotNullPhrase(phrase, match)
        return match!!.command
    }

    private fun assertNotNullPhrase(phrase: String, match: LocalMatch?) {
        assertTrue("\"$phrase\" was not a phone command", match != null)
    }
}
