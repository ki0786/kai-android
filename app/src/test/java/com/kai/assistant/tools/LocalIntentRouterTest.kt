package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIntentRouterTest {
    private val router = LocalIntentRouter()

    @Test
    fun flashlightOnAndOff() {
        val on = router.match("turn on the flashlight")
        assertNotNull(on)
        assertEquals(LocalCommand.Flashlight(true), on!!.command)
        assertFalse(on.requiresConfirmation)

        val off = router.match("flashlight off")
        assertEquals(LocalCommand.Flashlight(false), off!!.command)
    }

    @Test
    fun batteryAndDateTime() {
        assertEquals(LocalCommand.BatteryStatus, router.match("what's my battery")!!.command)
        assertEquals(LocalCommand.DateTime, router.match("what time is it")!!.command)
    }

    @Test
    fun settingsIntents() {
        assertEquals(LocalCommand.OpenWifiSettings, router.match("open wifi settings")!!.command)
        assertEquals(
            LocalCommand.OpenBluetoothSettings,
            router.match("open bluetooth settings")!!.command,
        )
    }

    @Test
    fun volumeAndTimerNeedExpectedShapes() {
        assertEquals(LocalCommand.SetVolume(40), router.match("set volume to 40")!!.command)
        assertEquals(LocalCommand.AdjustVolume(2), router.match("volume up")!!.command)

        val timer = router.match("set a timer for 2 minutes")
        assertTrue(timer!!.requiresConfirmation)
        assertEquals(LocalCommand.StartTimer(120, "KAI timer"), timer.command)
    }

    @Test
    fun launchAppRequiresConfirmation() {
        val match = router.match("open chrome")
        assertNotNull(match)
        assertTrue(match!!.requiresConfirmation)
        assertEquals(LocalCommand.LaunchAppByName("chrome"), match.command)
    }

    @Test
    fun openYoutubeAndPlayIsNotASingleAppName() {
        val match = router.match("open YouTube and play believer")
        assertNotNull(match)
        assertTrue(match!!.requiresConfirmation)
        assertEquals(
            LocalCommand.OpenAppAndPlay(appName = "youtube", query = "believer"),
            match.command,
        )
        assertTrue(match.confirmationPrompt.contains("believer"))
    }

    @Test
    fun openAppAndPlayVariants() {
        val play = router.match("open Spotify and play Shape of You")
        assertEquals(
            LocalCommand.OpenAppAndPlay("spotify", query = "shape of you"),
            play!!.command,
        )
        val search = router.match("launch youtube and search for lo-fi beats")
        assertEquals(
            LocalCommand.OpenAppAndPlay("youtube", query = "lo-fi beats"),
            search!!.command,
        )
    }

    @Test
    fun unknownGoesToCloud() {
        assertNull(router.match("explain quantum entanglement simply"))
        assertNull(router.match("write a haiku about rain"))
    }
}
