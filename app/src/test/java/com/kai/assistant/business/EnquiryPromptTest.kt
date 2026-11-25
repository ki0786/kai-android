package com.kai.assistant.business

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class EnquiryPromptTest {
    @Test
    fun parsesStructuredEnquiryResponse() {
        val answer = EnquiryPrompt.parse(
            """
            {
              "category": "common",
              "topics": ["address", "hours"],
              "language": "english",
              "visit_date": null,
              "visit_time": null,
              "reply": "Our showroom is at 123 MG Road. We are open 10 AM to 8 PM.",
              "provider": "grok",
              "model": "grok-4",
              "latency_ms": 12.5
            }
            """.trimIndent(),
        )
        requireNotNull(answer)
        assertEquals(EnquiryCategory.CommonQuestion, answer.category)
        assertEquals(setOf(Topic.Address, Topic.Hours), answer.topics)
        assertEquals(ReplyLanguage.English, answer.language)
        assertTrue(answer.reply.contains("123 MG Road"))
        assertNull(answer.visitDate)
        assertNull(answer.visitTime)
    }

    @Test
    fun parsesVisitWithDateAndTime() {
        val answer = EnquiryPrompt.parse(
            """
            {
              "category": "visit",
              "topics": [],
              "language": "hinglish",
              "visit_date": "2026-11-26",
              "visit_time": "15:30",
              "reply": "Aap 26 Nov ko 3:30 PM aa sakte ho?"
            }
            """.trimIndent(),
        )
        requireNotNull(answer)
        assertEquals(EnquiryCategory.VisitRequest, answer.category)
        assertEquals(ReplyLanguage.Hinglish, answer.language)
        assertEquals(LocalDate.of(2026, 11, 26), answer.visitDate)
        assertEquals(LocalTime.of(15, 30), answer.visitTime)
    }

    @Test
    fun acceptsCategoryAliases() {
        assertEquals(
            EnquiryCategory.CommonQuestion,
            EnquiryPrompt.parse("""{"category":"common_question","topics":["phone"],"reply":"Call 987"}""")!!.category,
        )
        assertEquals(
            EnquiryCategory.VisitRequest,
            EnquiryPrompt.parse("""{"category":"visit_request","topics":[],"reply":""}""")!!.category,
        )
    }

    @Test
    fun commonWithEmptyTopicsBecomesOther() {
        val answer = EnquiryPrompt.parse(
            """{"category":"common","topics":[],"language":"hindi","reply":""}""",
        )
        requireNotNull(answer)
        assertEquals(EnquiryCategory.Other, answer.category)
        assertEquals(ReplyLanguage.Hindi, answer.language)
        assertTrue(answer.reply.isEmpty())
    }

    @Test
    fun commonWithUnknownTopicBecomesOther() {
        val answer = EnquiryPrompt.parse(
            """{"category":"common","topics":["warranty"],"language":null,"reply":""}""",
        )
        requireNotNull(answer)
        assertEquals(EnquiryCategory.Other, answer.category)
        assertNull(answer.language)
    }

    @Test
    fun nullLanguageIsAllowed() {
        val answer = EnquiryPrompt.parse(
            """{"category":"other","topics":[],"language":null,"visit_date":null,"visit_time":null,"reply":""}""",
        )
        requireNotNull(answer)
        assertEquals(EnquiryCategory.Other, answer.category)
        assertNull(answer.language)
        assertEquals("", answer.reply)
    }

    @Test
    fun rejectsUnparseablePayload() {
        assertNull(EnquiryPrompt.parse(""))
        assertNull(EnquiryPrompt.parse("not json"))
        assertNull(EnquiryPrompt.parse("""{"category":"mystery","topics":[]}"""))
        assertNull(EnquiryPrompt.parse("{broken"))
    }
}
