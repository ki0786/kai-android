package com.kai.assistant.ondevice

import com.kai.assistant.data.ChatMessage
import com.kai.assistant.data.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDevicePolicyTest {
    @Test
    fun phoneCommandsWinOverTheModelAndTheBackend() {
        assertEquals(
            AnswerPath.LocalCommand,
            answerPath(hasLocalMatch = true, onDeviceEnabled = true, modelReady = true),
        )
        assertEquals(
            AnswerPath.LocalCommand,
            answerPath(hasLocalMatch = true, onDeviceEnabled = true, modelReady = false),
        )
        assertEquals(
            AnswerPath.LocalCommand,
            answerPath(hasLocalMatch = true, onDeviceEnabled = false, modelReady = false),
        )
    }

    @Test
    fun generalQuestionsUseOnDeviceOnlyWhenReady() {
        assertEquals(
            AnswerPath.OnDevice,
            answerPath(hasLocalMatch = false, onDeviceEnabled = true, modelReady = true),
        )
        assertEquals(
            AnswerPath.BackendFallback,
            answerPath(hasLocalMatch = false, onDeviceEnabled = true, modelReady = false),
        )
    }

    @Test
    fun switchOffUsesTheBackendDirectly() {
        assertEquals(
            AnswerPath.Backend,
            answerPath(hasLocalMatch = false, onDeviceEnabled = false, modelReady = true),
        )
    }

    @Test
    fun backendNoteIsPrefixedOnce() {
        val once = withBackendNote("Paris.")
        assertTrue(once.startsWith(OnDeviceModel.BACKEND_NOTE))
        assertEquals(once, withBackendNote(once))
    }

    @Test
    fun promptSkipsPhoneCommandsAndStripsTheBackendNote() {
        val prompt = buildOnDevicePrompt(
            listOf(
                ChatMessage(
                    role = MessageRole.User,
                    text = "open youtube",
                    staysOnDevice = true,
                ),
                ChatMessage(
                    role = MessageRole.Assistant,
                    text = "Opening youtube",
                    staysOnDevice = true,
                ),
                ChatMessage(
                    role = MessageRole.Assistant,
                    text = "${OnDeviceModel.BACKEND_NOTE}\n\nThe sky looks blue.",
                ),
                ChatMessage(role = MessageRole.User, text = "Why is it blue?"),
            ),
        )
        assertFalse(prompt.contains("youtube"))
        assertFalse(prompt.contains(OnDeviceModel.BACKEND_NOTE))
        assertTrue(prompt.contains("The sky looks blue."))
        assertTrue(prompt.contains("Why is it blue?"))
    }
}
