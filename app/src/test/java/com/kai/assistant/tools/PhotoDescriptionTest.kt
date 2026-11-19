package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoDescriptionTest {
    private val router = LocalIntentRouter()

    @Test
    fun whatsInThisPhotoStaysOnThePhone() {
        val match = router.match("what's in this photo")
        val command = match!!.command as LocalCommand.DescribePhoto
        assertFalse(command.useBackend)
        assertFalse(match.requiresConfirmation)
        assertTrue(command.staysOnDevice())
        assertNull(researchQuery("what's in this photo"))
        assertNull(planLocalSteps("what's in this photo") { router.match(it) })
        assertTrue(router.match("what's on my screen")!!.command is LocalCommand.DescribeScreen)
        assertTrue(router.match("what's on my schedule today")!!.command is LocalCommand.ReadSchedule)
    }

    @Test
    fun backendAskDoesNotMarkThePhotoForUpload() {
        val match = router.match("describe this picture using the backend")
        val command = match!!.command as LocalCommand.DescribePhoto
        assertTrue(command.useBackend)
        assertTrue(command.staysOnDevice())
        val spoken = describePhoto(
            labels = listOf("Cat"),
            textLines = listOf("Hello"),
            askedForBackend = true,
        )
        assertEquals(
            "In this photo: Cat. Text in the photo: Hello. I didn't upload the photo.",
            spoken,
        )
    }

    @Test
    fun labelsAndColorsBecomeASpokenDescription() {
        assertEquals(
            listOf("Cat"),
            acceptedPhotoLabels(listOf("Cat" to 0.9f, "Dog" to 0.2f, "Cat" to 0.8f)),
        )
        assertEquals(
            "In this photo: mostly blue.",
            describePhoto(colorLabels(List(8) { 0xFF0000FF.toInt() }), emptyList()),
        )
        assertEquals(
            "I couldn't tell what's in this photo.",
            describePhoto(emptyList(), emptyList()),
        )
        assertEquals("blue", colorName(0xFF0000FF.toInt()))
        assertEquals("white", colorName(0xFFFFFFFF.toInt()))
    }
}
