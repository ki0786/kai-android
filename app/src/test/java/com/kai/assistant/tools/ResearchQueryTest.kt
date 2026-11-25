package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchQueryTest {
    private val router = LocalIntentRouter()

    @Test
    fun webSearchIsResearchAfterLocalCommands() {
        assertNull(router.match("search the web for the supplier"))
        assertEquals("the supplier", researchQuery("search the web for the supplier"))
        assertEquals("android alarms", researchQuery("please search the internet for android alarms"))
        assertEquals("news", researchQuery("what's the news"))
        assertEquals("gold", researchQuery("latest news about gold"))
        assertEquals("price of gold", researchQuery("what is the current price of gold"))
        assertEquals(
            "who is the richest person in the world",
            researchQuery("who is the richest person in the world"),
        )
        assertEquals("What's the weather today", researchQuery("What's the weather today?"))
        assertEquals("what's the score", researchQuery("what's the score"))
        assertEquals("who is the current president", researchQuery("who is the current president"))
        assertEquals("what happened today", researchQuery("what happened today"))
        assertNull(router.match("who is the richest person in the world"))
        assertNull(researchQuery("what's on my schedule today"))
        assertNull(researchQuery("what's today's date"))
        assertTrue(RESEARCH_UNAVAILABLE.contains("failed"))
        assertTrue(RESEARCH_UNAVAILABLE.contains("won't guess"))
    }

    @Test
    fun phoneCommandsStayAheadOfResearch() {
        assertNotNull(router.match("call kaif"))
        assertNull(researchQuery("call kaif"))
        assertNotNull(router.match("set an alarm for 6:27 p.m."))
        assertNull(researchQuery("set an alarm for 6:27 p.m."))
        assertNotNull(router.match("remember that my name is Kaif"))
        assertNull(researchQuery("remember that my name is Kaif"))
        val memory = router.match("what did I say about the supplier")
        assertTrue(memory!!.command is LocalCommand.RecallMemory)
        assertNull(researchQuery("what did I say about the supplier"))
        assertNull(researchQuery("what time is it"))
        assertNull(researchQuery("sold a sofa for 25000"))
        assertNull(researchQuery("labour needs 10 sheets of plywood"))
        assertNull(researchQuery("You recorded selling a sofa for 25,000, and that labor needs 10 sheets of plywood."))
        assertNotNull(router.match("sold a sofa for 25000"))
        assertNotNull(router.match("labour needs 10 sheets of plywood"))
    }

    @Test
    fun replyUsesOnlyReturnedSources() {
        val sources = listOf(ResearchSource("Kitco", "https://kitco.example/gold"))
        val reply = formatResearchReply("Gold is up.", sources)
        assertTrue(reply.contains("Gold is up."))
        assertTrue(reply.contains("Kitco"))
        assertTrue(reply.contains("https://kitco.example/gold"))
        assertFalse(reply.contains("https://example.com"))
        assertEquals("Gold is up.", formatResearchReply("Gold is up.", emptyList()))
        assertFalse(formatResearchReply("", emptyList()).contains("http"))
        val spoken = formatResearchSpoken("Gold is up.", sources)
        assertTrue(spoken.contains("Kitco"))
        assertFalse(spoken.contains("http"))
        assertFalse(RESEARCH_OFFLINE.contains("http"))
        assertTrue(RESEARCH_OFFLINE.contains("offline"))
        assertFalse(RESEARCH_UNAVAILABLE.contains("http"))
    }
}
