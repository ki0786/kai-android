package com.kai.assistant.business

import com.kai.assistant.books.BooksSnapshot
import com.kai.assistant.books.MaterialStock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagerEngineTest {
    private val sharma = PartyTag(
        chatKey = "id:919800000001@s.whatsapp.net",
        displayName = "Sharma Timber",
        role = PartyRole.SUPPLIER,
    )
    private val raju = PartyTag(
        chatKey = "id:919811111111@s.whatsapp.net",
        displayName = "Raju",
        role = PartyRole.LABOUR,
    )
    private val stock = listOf(
        MaterialStock("1", "Teak", 5.0, "pcs", "Sharma Timber", 2.0),
        MaterialStock("2", "Plywood", 1.0, "sheet", "Nobody Saved", 1.0),
    )

    private fun facts(
        title: String,
        text: String,
        jid: String,
        at: Long,
        sender: String? = title,
        group: Boolean = false,
        pkg: String = WHATSAPP_BUSINESS_PACKAGE,
    ) = NotificationFacts(
        packageName = pkg,
        title = title,
        text = text,
        postedAtMs = at,
        category = "msg",
        isGroupConversation = group,
        ids = listOf(jid),
        hasReplyAction = true,
        messages = listOf(NotifiedMessage(text, at, sender = sender)),
    )

    private fun engine(
        tags: InMemoryPartyTags,
        books: BooksSnapshot = BooksSnapshot(materials = stock),
        nowMs: Long = 1_700_000_100_000L,
        policy: RatePolicy = RatePolicy(),
    ) = ManagerEngine(
        tags = tags,
        books = { books },
        clock = { nowMs },
        ratePolicy = policy,
    )

    @Test
    fun labourMaterialListOrdersTheTaggedSupplierWithoutAPrice() {
        val tags = InMemoryPartyTags().apply {
            put(raju)
            put(sharma)
        }
        val action = engine(tags).handle(
            facts("Raju", "need 10 teak", "919811111111@s.whatsapp.net", 1_700_000_100_500L),
        )
        assertTrue(action is ManagerAction.SendOrders)
        action as ManagerAction.SendOrders
        val order = action.orders.single()
        assertEquals("Sharma Timber", order.supplierName)
        assertEquals("919800000001", order.digits)
        assertEquals("id:919800000001@s.whatsapp.net", order.supplierChatKey)
        assertTrue(order.text.contains("Teak: 10"))
        assertTrue(orderTextIsSafe(order.text))
        assertTrue(!order.text.contains("₹"))
        assertEquals("Raju", order.record.requestedBy)
    }

    @Test
    fun unknownMaterialOrSupplierAlertsAndDoesNotMessage() {
        val tags = InMemoryPartyTags().apply {
            put(raju)
            put(sharma)
        }
        val unknown = engine(tags).handle(
            facts("Raju", "need 2 gold leaf", "919811111111@s.whatsapp.net", 1_700_000_100_500L),
        )
        assertTrue(unknown is ManagerAction.Alert)
        unknown as ManagerAction.Alert
        assertTrue(unknown.body.contains("did not message"))

        val tagsAgain = InMemoryPartyTags().apply {
            put(raju)
            put(sharma)
        }
        val unsaved = engine(tagsAgain).handle(
            facts("Raju", "plywood 4", "919811111111@s.whatsapp.net", 1_700_000_100_500L),
        )
        assertTrue(unsaved is ManagerAction.Alert)
        unsaved as ManagerAction.Alert
        assertTrue(unsaved.body.contains("not a tagged Supplier"))
    }

    @Test
    fun untaggedPersonalAndGroupChatsAreSkipped() {
        val tags = InMemoryPartyTags().apply { put(raju) }
        val untagged = engine(tags).handle(
            facts("Customer", "need 10 teak", "919822222222@s.whatsapp.net", 1_700_000_100_500L),
        )
        assertTrue(untagged is ManagerAction.Skip)

        val personal = engine(tags).handle(
            facts(
                "Raju",
                "need 10 teak",
                "919811111111@s.whatsapp.net",
                1_700_000_100_500L,
                pkg = "com.whatsapp",
            ),
        )
        assertTrue(personal is ManagerAction.Skip)

        val group = engine(tags).handle(
            facts(
                "Raju",
                "need 10 teak",
                "919811111111-1@g.us",
                1_700_000_100_500L,
                group = true,
            ),
        )
        assertTrue(group is ManagerAction.Skip)
    }

    @Test
    fun ownerReplyStopsTheNextOrder() {
        val tags = InMemoryPartyTags().apply {
            put(raju)
            put(sharma)
        }
        val eng = engine(tags)
        eng.handle(facts("Raju", "I will buy it", "919811111111@s.whatsapp.net", 1_700_000_100_400L, sender = "You"))
        val next = eng.handle(
            facts("Raju", "need 10 teak", "919811111111@s.whatsapp.net", 1_700_000_100_800L),
        )
        assertTrue(next is ManagerAction.Alert)
        next as ManagerAction.Alert
        assertTrue(next.body.contains("did not message a supplier"))
    }

    @Test
    fun supplierBillNotifiesThePayableAndDoesNotSend() {
        val tags = InMemoryPartyTags().apply { put(sharma) }
        val books = BooksSnapshot(
            money = listOf(
                com.kai.assistant.books.MoneyEntry(
                    "m",
                    com.kai.assistant.books.MoneyDirection.OUT,
                    com.kai.assistant.books.MoneyScope.BUSINESS,
                    5_000,
                    "Sharma Timber",
                    "Advance to Sharma Timber",
                    1L,
                ),
            ),
        )
        val action = engine(tags, books).handle(
            facts(
                "Sharma Timber",
                "total bill 15000. UPI shop@ybl",
                "919800000001@s.whatsapp.net",
                1_700_000_100_500L,
            ),
        )
        assertTrue(action is ManagerAction.NotifyPayable)
        action as ManagerAction.NotifyPayable
        assertEquals(15_000L, action.billRupees)
        assertEquals(5_000L, action.advanceRupees)
        assertEquals(10_000L, action.payableRupees)
        assertTrue(action.notice.contains("Pay ₹10,000"))
        assertTrue(action.notice.contains("I did not pay"))
        assertTrue(action.notice.contains("did not send UPI"))
        assertTrue(!action.notice.contains("shop@ybl"))
    }

    @Test
    fun aSupplierHelloDoesNotBecomeABill() {
        val tags = InMemoryPartyTags().apply { put(sharma) }
        val action = engine(tags).handle(
            facts("Sharma Timber", "hello", "919800000001@s.whatsapp.net", 1_700_000_100_500L),
        )
        assertTrue(action is ManagerAction.Skip)
        assertEquals("id:919800000001@s.whatsapp.net", tags.find("id:919800000001@s.whatsapp.net", "Sharma Timber")?.chatKey)
    }

    @Test
    fun ordersAreRateLimited() {
        val tags = InMemoryPartyTags().apply {
            put(raju)
            put(sharma)
        }
        val policy = RatePolicy(minGapMs = 8_000L, maxPerChatPerHour = 1)
        val eng = engine(tags, policy = policy)
        val first = eng.handle(facts("Raju", "need 10 teak", "919811111111@s.whatsapp.net", 1_700_000_100_500L))
        assertTrue(first is ManagerAction.SendOrders)
        val second = eng.handle(facts("Raju", "need 6 teak", "919811111111@s.whatsapp.net", 1_700_000_100_900L))
        assertTrue(second is ManagerAction.Alert)
    }

    @Test
    fun routeSendsOnlyTaggedChatsToTheManager() {
        assertEquals(IncomingRoute.Manager, routeWhatsAppBusiness(hasPartyTag = true, autoReplyOn = false, businessReady = false))
        assertEquals(IncomingRoute.Enquiry, routeWhatsAppBusiness(hasPartyTag = false, autoReplyOn = true, businessReady = true))
        assertEquals(IncomingRoute.Ignore, routeWhatsAppBusiness(hasPartyTag = false, autoReplyOn = false, businessReady = true))
        assertEquals(IncomingRoute.Ignore, routeWhatsAppBusiness(hasPartyTag = false, autoReplyOn = true, businessReady = false))
    }
}
