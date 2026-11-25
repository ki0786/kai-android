package com.kai.assistant.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePolicyTest {
    @Test
    fun android15BootNeverStartsTheMicrophoneService() {
        assertEquals(BootHandoff.Ignore, bootHandoff(enabled = false, sdkInt = 35))
        assertEquals(BootHandoff.StartService, bootHandoff(enabled = true, sdkInt = 34))
        assertEquals(BootHandoff.PostTapNotification, bootHandoff(enabled = true, sdkInt = 35))
        for (sdk in 35..40) {
            val started = booleanArrayOf(false)
            val posted = booleanArrayOf(false)
            applyBootHandoff(
                enabled = true,
                sdkInt = sdk,
                actions = object : BootActions {
                    override fun startMicrophoneService() {
                        started[0] = true
                    }

                    override fun postTapNotification() {
                        posted[0] = true
                    }
                },
            )
            assertFalse(started[0])
            assertTrue(posted[0])
        }
    }

    @Test
    fun disabledBootDoesNothing() {
        val started = booleanArrayOf(false)
        applyBootHandoff(
            enabled = false,
            sdkInt = 28,
            actions = object : BootActions {
                override fun startMicrophoneService() {
                    started[0] = true
                }

                override fun postTapNotification() {
                    started[0] = true
                }
            },
        )
        assertFalse(started[0])
    }

    @Test
    fun sensitivityMapsOntoTheTriggerThreshold() {
        assertEquals(0.45f, keywordsThreshold(0f), 0.001f)
        assertEquals(0.25f, keywordsThreshold(0.5f), 0.001f)
        assertEquals(0.05f, keywordsThreshold(1f), 0.001f)
        assertEquals(0.45f, keywordsThreshold(-2f), 0.001f)
        assertEquals(0.05f, keywordsThreshold(4f), 0.001f)
    }

    @Test
    fun keywordLineIsTheHeyKaiTokens() {
        val line = heyKaiKeywordLine(0.5f)
        assertTrue(line.startsWith("$HEY_KAI_TOKENS "))
        assertTrue(line.contains(":1.50"))
        assertTrue(line.contains("#0.25"))
        assertTrue(line.endsWith("@HEYKAI"))
        assertFalse(line.contains(","))
    }

    @Test
    fun keywordLineDoesNotContainBareKaiToken() {
        for (sensitivity in floatArrayOf(0f, 0.5f, 1f)) {
            val line = heyKaiKeywordLine(sensitivity)
            val words = line.trim().split(Regex("\\s+"))
            assertFalse(words.any { it.equals("KAI", ignoreCase = true) })
            assertEquals(listOf("▁HE", "Y", "▁K", "A", "I"), words.take(5))
            assertTrue(isHeyKaiSpot(words.last().removePrefix("@")))
        }
    }

    @Test
    fun spotResultMatchesHeyKaiOnly() {
        assertTrue(isHeyKaiSpot("HEY KAI"))
        assertTrue(isHeyKaiSpot("hey kai"))
        assertTrue(isHeyKaiSpot("HEYKAI"))
        assertTrue(isHeyKaiSpot("heykai"))
        assertTrue(isHeyKaiSpot(HEY_KAI_TOKENS))
        assertFalse(isHeyKaiSpot(""))
        assertFalse(isHeyKaiSpot("HEY SIRI"))
        assertFalse(isHeyKaiSpot("KAI"))
    }

    @Test
    fun pauseReasonsCoverCallCommandAndMic() {
        assertNull(pauseReason(userPaused = false, audioMode = 0, micHeld = false, commandBusy = false))
        assertEquals(
            WakePauseReason.User,
            pauseReason(userPaused = true, audioMode = 0, micHeld = false, commandBusy = false),
        )
        assertEquals(
            WakePauseReason.InCall,
            pauseReason(userPaused = false, audioMode = 2, micHeld = false, commandBusy = false),
        )
        assertEquals(
            WakePauseReason.InCall,
            pauseReason(userPaused = false, audioMode = 3, micHeld = true, commandBusy = true),
        )
        assertEquals(
            WakePauseReason.MicHeld,
            pauseReason(userPaused = false, audioMode = 0, micHeld = true, commandBusy = false),
        )
        assertEquals(
            WakePauseReason.Command,
            pauseReason(userPaused = false, audioMode = 0, micHeld = true, commandBusy = true),
        )
        assertEquals(
            WakePauseReason.Command,
            pauseReason(userPaused = false, audioMode = 0, micHeld = false, commandBusy = true),
        )
        assertTrue(isCallAudioMode(1))
        assertFalse(isCallAudioMode(0))
    }

    @Test
    fun presenceChipStates() {
        assertEquals(WakePresence.Off, wakePresence(serviceRunning = false, reason = null))
        assertEquals(WakePresence.Standby, wakePresence(serviceRunning = true, reason = null))
        assertEquals(
            WakePresence.Listening,
            wakePresence(serviceRunning = true, reason = WakePauseReason.Command),
        )
        assertEquals(
            WakePresence.Paused,
            wakePresence(serviceRunning = true, reason = WakePauseReason.InCall),
        )
    }

    @Test
    fun detectReleasesTheMicBeforeTheChime() {
        val steps = mutableListOf<String>()
        runKeywordHandoff(
            releaseMic = { steps += "release" },
            chime = { steps += "chime" },
            openVoice = { steps += "voice" },
        )
        assertEquals(listOf("release", "chime", "voice"), steps)
    }

    @Test
    fun commandHoldSurvivesTheScreenOpeningIdle() {
        val gate = WakeTurnGateState()
        gate.onDetected(now = 1_000)
        gate.onVoiceSession(busy = false)
        assertTrue(gate.commandBusy)
        gate.onVoiceSession(busy = true)
        gate.onVoiceSession(busy = false)
        assertFalse(gate.commandBusy)
    }

    @Test
    fun detectWindowExpiresIfTheScreenNeverTakesOver() {
        val gate = WakeTurnGateState()
        gate.onDetected(now = 1_000)
        assertFalse(gate.expire(now = 1_000 + DETECT_WINDOW_MS))
        assertTrue(gate.expire(now = 1_000 + DETECT_WINDOW_MS + 1))
        assertFalse(gate.commandBusy)
    }

    @Test
    fun archiveKeepsOnlyTheInt8EnglishModel() {
        val root = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/"
        assertTrue(shouldKeepArchiveEntry(root + "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"))
        assertTrue(shouldKeepArchiveEntry(root + "tokens.txt"))
        assertFalse(shouldKeepArchiveEntry(root + "encoder-epoch-12-avg-2-chunk-16-left-64.onnx"))
        assertFalse(shouldKeepArchiveEntry(root + "bpe.model"))
        assertFalse(shouldKeepArchiveEntry(root + "test_wavs/0.wav"))
        assertFalse(shouldKeepArchiveEntry("../tokens.txt"))
        assertFalse(shouldKeepArchiveEntry(root + "../../tokens.txt"))
        assertNull(archiveEntryName(root))
    }

    @Test
    fun chunkSixteenDownsampleIsSixteenFrames() {
        assertEquals(80, KWS_FEATURE_DIM)
        assertEquals(45, KWS_ENCODER_FRAMES)
        assertEquals(16, kwsSubsampledFrames(KWS_ENCODER_FRAMES))
        assertEquals(KWS_CHUNK_SIZE, kwsSubsampledFrames(KWS_ENCODER_FRAMES))
        assertEquals(8 * 2, kwsSubsampledFrames(KWS_ENCODER_FRAMES))
        assertTrue(kwsDownsampleAccepts(kwsSubsampledFrames(KWS_ENCODER_FRAMES)))
        assertFalse(kwsDownsampleAccepts(17))
        val encoder = WakeModelSpec.file("encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx")
        assertEquals(4_807_159L, encoder!!.bytes)
        assertFalse(WakeModelSpec.ARCHIVE_URL.contains("-mobile"))
    }

    @Test
    fun modelDownloadIsThePublicReleaseAndNotAnApi() {
        assertTrue(WakeModelSpec.ARCHIVE_URL.startsWith("https://github.com/k2-fsa/sherpa-onnx/"))
        assertTrue(WakeModelSpec.ARCHIVE_URL.contains("gigaspeech"))
        assertTrue(WakeModelSpec.ARCHIVE_URL.endsWith("sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"))
        assertFalse(WakeModelSpec.ARCHIVE_URL.contains("api_key"))
        assertEquals(4, WakeModelSpec.files.size)
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun listeningNotificationAndFirstRunCopy() {
        assertEquals("KAI is listening for 'Hey KAI'", LISTENING_NOTIFICATION)
        val label = modelStatusLabel(WakeModelStatus.Downloading(4_000, 10_000))
        assertTrue(label.contains("40%"))
        assertTrue(label.contains("Audio is not uploaded"))
        assertTrue(modelStatusLabel(WakeModelStatus.Ready).contains("on this phone"))
        assertTrue(modelStatusLabel(WakeModelStatus.Failed("offline")).contains("offline"))
    }
}
