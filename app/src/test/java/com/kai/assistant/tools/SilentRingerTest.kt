package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertTrue(effects.setsInterruptionFilter)
        assertFalse(effects.setsZenMode)
        assertFalse(silentRequestsZen())
        assertFalse(effects.setsNotificationPolicy)
        assertTrue(shouldForceZenOff())
        assertTrue(isSilentEndState(ringVolume = 0, zenMode = ZEN_OFF, interruptionFilterAll = true))
        assertEquals(PHONE_SILENT, silentSpeech(0, ZEN_OFF, interruptionFilterAll = true))
        assertNull(silentSpeech(0, zenMode = 1, interruptionFilterAll = true))
        assertNull(silentSpeech(0, ZEN_OFF, interruptionFilterAll = false))
        assertNull(silentSpeech(8, ZEN_OFF, interruptionFilterAll = true))
        assertNull(silentSpeech(4, zenMode = 1, interruptionFilterAll = false))
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

        val kept = planRingerRestore(7, maxVolume = 15)
        assertEquals(RINGER_NORMAL, kept.ringerMode)
        assertEquals(7, kept.ringVolume)

        val missing = planRingerRestore(null, maxVolume = 15)
        assertEquals(RINGER_NORMAL, missing.ringerMode)
        assertEquals(8, missing.ringVolume)

        val wasZero = planRingerRestore(0, maxVolume = 15)
        assertEquals(RINGER_NORMAL, wasZero.ringerMode)
        assertEquals(8, wasZero.ringVolume)

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

    @Test
    fun ringerOnRequiresNormalModeAndVolume() {
        assertNull(ringerRestoreSpeech(RINGER_NORMAL, 0, turnedDoNotDisturbOff = false))
        assertNull(ringerRestoreSpeech(RINGER_SILENT, 8, turnedDoNotDisturbOff = false))
        assertNull(ringerRestoreSpeech(RINGER_VIBRATE, 8, turnedDoNotDisturbOff = false))
        assertNull(ringerRestoreSpeech(RINGER_VIBRATE, 0, turnedDoNotDisturbOff = false))
        assertEquals("The ringer is on.", ringerRestoreSpeech(RINGER_NORMAL, 8, turnedDoNotDisturbOff = false))
        assertEquals(
            "The ringer is on. Do Not Disturb is off.",
            ringerRestoreSpeech(RINGER_NORMAL, 7, turnedDoNotDisturbOff = true),
        )
        assertFalse(ringerIsAudible(RINGER_VIBRATE, 8))
        assertFalse(ringerIsAudible(RINGER_SILENT, 1))
        assertFalse(ringerIsAudible(RINGER_NORMAL, 0))
        assertTrue(ringerIsAudible(RINGER_NORMAL, 1))
        val restored = planRingerRestore(previousVolume = 0, maxVolume = 15)
        assertEquals(RINGER_NORMAL, restored.ringerMode)
        assertTrue(restored.ringVolume > 0)
    }
}
