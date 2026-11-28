package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Silent only mutes the ringer. Do Not Disturb changes when he says DND,
 * and undo silent puts the ringer back without a backend call.
 */
class SilentRingerTest {
    private val router = LocalIntentRouter()

    @Test
    fun silentDoesNotImplyDoNotDisturb() {
        val effects = silentSideEffects()
        assertFalse(effects.setsRingerModeSilent)
        assertFalse(effects.setsInterruptionFilter)
        assertFalse(effects.setsZenMode)
        assertFalse(effects.setsNotificationPolicy)
        assertFalse(shouldRevertDoNotDisturb(dndBefore = false, dndAfterWrite = false))
        assertTrue(shouldRevertDoNotDisturb(dndBefore = false, dndAfterWrite = true))
        assertFalse(appEnabledDndAfterSilence(dndBefore = false, dndAfterRevert = false))
        assertTrue(appEnabledDndAfterSilence(dndBefore = false, dndAfterRevert = true))
        assertFalse(appEnabledDndAfterSilence(dndBefore = true, dndAfterRevert = true))

        val silentPhrases = listOf(
            "silent my phone",
            "silence my phone",
            "mute my phone",
            "mute the phone",
            "turn on silent mode",
            "mute",
        )
        for (phrase in silentPhrases) {
            val command = router.match(phrase)!!.command
            assertFalse(phrase, command is LocalCommand.SetDoNotDisturb)
            assertTrue(command.staysOnDevice())
        }
        assertEquals(LocalCommand.SilencePhone, router.match("silent my phone")!!.command)
        assertEquals(LocalCommand.SilencePhone, router.match("turn on silent mode")!!.command)
        assertEquals(LocalCommand.SetVolume(0), router.match("mute")!!.command)
        assertEquals(LocalCommand.SetDoNotDisturb(true), router.match("turn on do not disturb")!!.command)
        assertEquals(LocalCommand.SetDoNotDisturb(false), router.match("turn off dnd")!!.command)
        assertEquals(LocalCommand.SetDoNotDisturb(true), router.match("notification silence")!!.command)
    }

    @Test
    fun undoSilentRestoresTheRingerLocally() {
        val phrases = listOf(
            "undo silent",
            "turn off silent",
            "silent off",
            "unsilent",
            "normal mode",
            "ring my phone",
        )
        for (phrase in phrases) {
            val match = router.match(phrase)
            assertEquals(phrase, LocalCommand.RestoreRinger, match?.command)
            assertTrue(match!!.command.staysOnDevice())
            assertFalse(match.requiresConfirmation)
        }
        assertEquals(LocalCommand.SetDoNotDisturb(false), router.match("turn off dnd")!!.command)

        val fromSilent = planRingerRestore(RINGER_NORMAL, 7, maxVolume = 15)
        assertEquals(RINGER_NORMAL, fromSilent.ringerMode)
        assertEquals(7, fromSilent.ringVolume)

        val wasSilent = planRingerRestore(RINGER_SILENT, 0, maxVolume = 10)
        assertEquals(RINGER_NORMAL, wasSilent.ringerMode)
        assertEquals(5, wasSilent.ringVolume)

        val vibrate = planRingerRestore(RINGER_VIBRATE, 0, maxVolume = 10)
        assertEquals(RINGER_VIBRATE, vibrate.ringerMode)

        assertTrue(
            shouldTurnOffDndOnRestore(
                appEnabledDoNotDisturb = true,
                explicitUserDoNotDisturb = false,
                currentlyDoNotDisturb = true,
                unmarkedLegacySilent = false,
            ),
        )
        assertFalse(
            shouldTurnOffDndOnRestore(
                appEnabledDoNotDisturb = true,
                explicitUserDoNotDisturb = true,
                currentlyDoNotDisturb = true,
                unmarkedLegacySilent = false,
            ),
        )
        assertFalse(
            shouldTurnOffDndOnRestore(
                appEnabledDoNotDisturb = false,
                explicitUserDoNotDisturb = false,
                currentlyDoNotDisturb = true,
                unmarkedLegacySilent = false,
            ),
        )
        assertTrue(
            shouldTurnOffDndOnRestore(
                appEnabledDoNotDisturb = false,
                explicitUserDoNotDisturb = false,
                currentlyDoNotDisturb = true,
                unmarkedLegacySilent = true,
            ),
        )
        assertEquals("The ringer is on.", ringerRestoredSpoken(turnedDoNotDisturbOff = false))
        assertEquals(
            "The ringer is on. Do Not Disturb is off.",
            ringerRestoredSpoken(turnedDoNotDisturbOff = true),
        )
    }
}
