package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalMemoryTest {
    private val router = LocalIntentRouter()

    @Test
    fun rememberNameStaysOnThePhone() {
        val match = router.match("remember that my name is Kaif")
        val command = match!!.command as LocalCommand.SaveMemory
        assertEquals("my name is Kaif", command.text)
        assertFalse(command.project)
        assertFalse(match.requiresConfirmation)
        assertTrue(command.staysOnDevice())
        assertEquals("I'll remember that my name is Kaif.", formatRememberReply(command.text))
    }

    @Test
    fun rememberProjectAndRecallIt() {
        val saved = router.match("remember that I'm working on the supplier portal")!!.command
            as LocalCommand.SaveMemory
        assertTrue(saved.project)
        assertEquals("the supplier portal", saved.text)
        assertEquals(
            "You're working on the supplier portal.",
            formatProjectsReply(
                listOf(SavedMemory(1, saved.text, MemoryKind.Project, 1L)),
            ),
        )
        val recall = router.match("what am I working on")!!.command as LocalCommand.RecallMemory
        assertEquals(RecallMode.Projects, recall.mode)
        assertTrue(recall.staysOnDevice())
    }

    @Test
    fun searchChatsAndDeleteOrEditByVoice() {
        val search = router.match("what did I say about the supplier")!!.command as LocalCommand.RecallMemory
        assertEquals(RecallMode.Search, search.mode)
        assertEquals("the supplier", search.query)
        assertTrue(search.staysOnDevice())

        val memories = listOf(SavedMemory(1, "my name is Kaif", MemoryKind.Fact, 1L))
        val turns = listOf(StoredTurn(1L, "user", "call the supplier tomorrow"))
        assertEquals(
            "You said: call the supplier tomorrow.",
            formatSearchReply("the supplier", memories, turns),
        )
        assertEquals(
            "You said: Kaif mentioned the supplier. I remember: my name is Kaif.",
            formatSearchReply(
                "Kaif",
                memories,
                listOf(StoredTurn(2L, "user", "Kaif mentioned the supplier")),
            ),
        )

        val delete = router.match("delete the memory about Kaif")!!.command as LocalCommand.DeleteMemory
        assertEquals("Kaif", delete.query)
        assertFalse(delete.all)
        assertEquals(memories.first(), bestMemory(memories, delete.query))

        val edit = router.match("change the memory about Kaif to my name is Mohammed Kaif")!!
            .command as LocalCommand.EditMemory
        assertEquals("Kaif", edit.query)
        assertEquals("my name is Mohammed Kaif", edit.text)
        assertTrue(edit.staysOnDevice())
    }

    @Test
    fun secretsAreNotStoredUnlessAsked() {
        assertTrue(containsSecret("my password is hunter2"))
        assertFalse(shouldPersistTurn("my password is hunter2", allowSecret = false))
        assertTrue(shouldPersistTurn("my password is hunter2", allowSecret = true))
        assertFalse(containsSecret("my name is Kaif"))
        val asked = parseMemoryRequest("remember that my password is hunter2") as MemoryRequest.Save
        assertEquals("my password is hunter2", asked.text)
        assertEquals(
            "I'll remember that. It's saved only on this phone.",
            formatRememberReply(asked.text),
        )
        assertNull(parseMemoryRequest("the supplier is late"))
        assertNull(router.match("set an alarm for 6:27 p.m.")!!.command as? LocalCommand.SaveMemory)
    }

    @Test
    fun recentTalkAndListStayLocal() {
        val recent = router.match("what did we talk about")!!.command as LocalCommand.RecallMemory
        assertEquals(RecallMode.Recent, recent.mode)
        assertEquals(
            "You said: call the supplier.",
            formatRecentReply(listOf(StoredTurn(1L, "user", "call the supplier"))),
        )
        val list = router.match("what do you remember")!!.command as LocalCommand.RecallMemory
        assertEquals(RecallMode.List, list.mode)
        assertTrue(list.staysOnDevice())
        assertEquals(
            "I remember: my name is Kaif.",
            formatListReply(listOf(SavedMemory(1, "my name is Kaif", MemoryKind.Fact, 1L))),
        )
    }
}
