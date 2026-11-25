package com.kai.assistant.business

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId

class EnquiryEngineTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val info = BusinessInfo(
        businessName = "Sagar Furniture",
        address = "123 MG Road, Bengaluru",
        hours = OpeningHours(
            DayOfWeek.entries.associateWith { DayHours(10 * 60, 20 * 60) },
        ),
        phone = "9876543210",
        website = "sagarfurniture.com",
        products = listOf(Product("Sofa", "25000-80000")),
    )

    private fun engine(
        brain: EnquiryBrain? = null,
        repo: EnquiryRepository = InMemoryEnquiryRepository(),
        nowMs: Long = 1_700_000_000_000L,
        enabledAt: Long = 0L,
    ) = EnquiryEngine(
        infoStore = { info },
        repository = repo,
        brain = brain,
        zone = zone,
        clock = { nowMs },
        enabledAtMs = { enabledAt },
    )

    private fun customerFacts(text: String, at: Long = 1_700_000_000_500L) = NotificationFacts(
        packageName = WHATSAPP_BUSINESS_PACKAGE,
        title = "Ravi",
        text = text,
        postedAtMs = at,
        category = "msg",
        ids = listOf("919999999999@s.whatsapp.net"),
        hasReplyAction = true,
        messages = listOf(NotifiedMessage(text, at, sender = "Ravi")),
    )

    @Test
    fun offlineAnswersAddress() = runBlocking {
        val repo = InMemoryEnquiryRepository()
        val action = engine(brain = null, repo = repo).handle(customerFacts("What is your address?"))
        assertTrue(action is EngineAction.Reply)
        action as EngineAction.Reply
        assertTrue(action.text.contains("123 MG Road"))
        assertTrue(!action.alertOwner)
        assertEquals(EnquiryStatus.AnsweredByKai, repo.get(action.chatKey)!!.status(1_700_000_000_600L, zone))
    }

    @Test
    fun offlineHoldsAndAlertsForPrice() = runBlocking {
        val action = engine(brain = null).handle(customerFacts("sofa price?"))
        assertTrue(action is EngineAction.Reply)
        action as EngineAction.Reply
        assertTrue(action.alertOwner)
        assertTrue(action.text.contains("shortly") || action.text.contains("jald"))
    }

    @Test
    fun ownerTakeoverStopsReplies() = runBlocking {
        val repo = InMemoryEnquiryRepository()
        val eng = engine(repo = repo)
        eng.handle(customerFacts("address?"))
        val key = repo.all().first().chatKey
        // Owner writes in the chat (not an echo of KAI).
        eng.handle(
            NotificationFacts(
                packageName = WHATSAPP_BUSINESS_PACKAGE,
                title = "Ravi",
                text = "I will call you",
                postedAtMs = 1_700_000_001_000L,
                category = "msg",
                ids = listOf("919999999999@s.whatsapp.net"),
                hasReplyAction = true,
                messages = listOf(
                    NotifiedMessage("I will call you", 1_700_000_001_000L, sender = "Sagar Furniture"),
                ),
                selfName = "Sagar Furniture",
            ),
        )
        assertTrue(repo.get(key)!!.ownerHandling)
        val next = eng.handle(customerFacts("still there?", at = 1_700_000_002_000L))
        assertTrue(next is EngineAction.AlertOnly)
    }

    @Test
    fun brainCommonQuestionUsesTemplateWhenUnguarded() = runBlocking {
        val brain = EnquiryBrain {
            BrainAnswer(
                category = EnquiryCategory.CommonQuestion,
                topics = setOf(Topic.Address),
                language = ReplyLanguage.English,
                reply = "Come tomorrow, sofa is 999 only!",
            )
        }
        val action = engine(brain = brain).handle(customerFacts("address please"))
        assertTrue(action is EngineAction.Reply)
        action as EngineAction.Reply
        assertTrue(action.text.contains("123 MG Road"))
        assertTrue(!action.text.contains("999"))
    }

    @Test
    fun unreachableBrainFallsBackToLocalAddress() = runBlocking {
        val brain = EnquiryBrain { null }
        val action = engine(brain = brain).handle(customerFacts("What is your address?"))
        assertTrue(action is EngineAction.Reply)
        action as EngineAction.Reply
        assertTrue(action.text.contains("123 MG Road"))
        assertTrue(!action.alertOwner)
    }
}
