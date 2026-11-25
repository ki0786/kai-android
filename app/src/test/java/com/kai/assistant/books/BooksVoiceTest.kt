package com.kai.assistant.books

import com.kai.assistant.tools.LocalCommand
import com.kai.assistant.tools.LocalIntentRouter
import com.kai.assistant.tools.staysOnDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BooksVoiceTest {
    private val router = LocalIntentRouter()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 25, 14, 30, 0, 0, zone)

    @Test
    fun ownerPhrasesStayOnThePhone() {
        val phrases = listOf(
            "I paid 5000 to the wood supplier",
            "Hey KAI, I paid 5000 to the wood supplier",
            "Rahul owes me 12000",
            "electricity bill 2400 due on the 5th",
            "sold a sofa for 45000 advance 10000",
            "Hey KAI, sold a sofa for 45000 advance 10000 please",
        )
        phrases.forEach { phrase ->
            val match = router.match(phrase)
            assertNotNull("\"$phrase\" fell through to chat", match)
            assertTrue(match!!.command is LocalCommand.Books)
            assertTrue(match.command.staysOnDevice())
            assertFalse(match.requiresConfirmation)
            assertFalse(match.command is LocalCommand.SendWhatsApp)
            assertFalse(match.command is LocalCommand.CallContact)
            assertFalse(match.command is LocalCommand.SendSms)
        }
    }

    @Test
    fun paidToTheWoodSupplierIsMoneyOut() {
        val action = books("I paid 5000 to the wood supplier") as BooksAction.Money
        assertEquals(MoneyDirection.OUT, action.direction)
        assertEquals(MoneyScope.BUSINESS, action.scope)
        assertEquals(5_000, action.amount)
        assertEquals("Wood Supplier", action.party)
    }

    @Test
    fun rahulOwesMeIsAReceivable() {
        val action = books("Rahul owes me 12000") as BooksAction.Receivable
        assertEquals("Rahul", action.customer)
        assertEquals(12_000, action.amount)
    }

    @Test
    fun electricityBillLandsOnTheNextFifth() {
        val action = books("electricity bill 2400 due on the 5th") as BooksAction.Bill
        assertEquals(BillKind.ELECTRICITY, action.kind)
        assertEquals(2_400, action.amount)
        val due = java.time.Instant.ofEpochMilli(action.dueEpochMs).atZone(zone)
        assertEquals(5, due.dayOfMonth)
        assertEquals(12, due.monthValue)
        assertEquals(2026, due.year)
    }

    @Test
    fun sofaSaleCarriesAmountAndAdvance() {
        val action = books("sold a sofa for 45000 advance 10000") as BooksAction.Order
        assertEquals("Sofa", action.item)
        assertEquals(45_000, action.amount)
        assertEquals(10_000, action.advance)
        assertEquals("", action.customer)
    }

    @Test
    fun otherBooksPhrases() {
        val personal = books("personal expense 400 for lunch") as BooksAction.Money
        assertEquals(MoneyScope.PERSONAL, personal.scope)
        assertEquals(400, personal.amount)
        val labour = books("labour due to Raju 800") as BooksAction.Labour
        assertEquals("Raju", labour.worker)
        assertFalse(labour.paid)
        val summary = books("how are the books") as BooksAction.Summary
        assertEquals(SummaryFocus.ALL, summary.focus)
        assertTrue(books("what do i owe") is BooksAction.Summary)
        assertTrue(books("who owes me") is BooksAction.Summary)
        val debt = books("I owe the bank 200000") as BooksAction.OwnerDebt
        assertEquals("Bank", debt.lender)
        val vendor = books("I owe the wood supplier 15000 due on the 10th") as BooksAction.VendorDue
        assertEquals("Wood Supplier", vendor.vendor)
        assertEquals(10, java.time.Instant.ofEpochMilli(vendor.dueEpochMs!!).atZone(zone).dayOfMonth)
    }

    @Test
    fun callsAndMessagesAreNotBooks() {
        assertTrue(router.match("call the wood supplier")!!.command is LocalCommand.CallContact)
        assertFalse(router.match("whatsapp kaif the sofa is ready")!!.command is LocalCommand.Books)
        assertNull(BooksUtterance.parse("call the wood supplier", now))
        assertNull(BooksUtterance.parse("whatsapp kaif the sofa is ready", now))
        assertNull(BooksUtterance.parse("remind me to pay the electricity bill", now))
    }

    private fun books(phrase: String): BooksAction {
        val parsed = BooksUtterance.parse(phrase, now)
        assertNotNull(phrase, parsed)
        assertTrue(router.match(phrase)!!.command is LocalCommand.Books)
        return parsed!!
    }
}
