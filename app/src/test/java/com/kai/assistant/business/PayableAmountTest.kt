package com.kai.assistant.business

import com.kai.assistant.books.BooksAction
import com.kai.assistant.books.BooksEngine
import com.kai.assistant.books.BooksSnapshot
import com.kai.assistant.books.BooksUtterance
import com.kai.assistant.books.MoneyDirection
import com.kai.assistant.books.MoneyEntry
import com.kai.assistant.books.MoneyScope
import com.kai.assistant.books.payableRupees
import com.kai.assistant.books.recordedAdvanceRupees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PayableAmountTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 25, 14, 30, 0, 0, zone)

    @Test
    fun billMinusAdvance() {
        assertEquals(10_000L, payableRupees(15_000, 5_000))
        assertEquals(15_000L, payableRupees(15_000, 0))
        assertEquals(0L, payableRupees(15_000, 20_000))
        assertEquals(0L, payableRupees(0, 500))
    }

    @Test
    fun advancePhraseIsRecordedAndAPlainPaymentIsNot() {
        val advance = BooksUtterance.parse("advance 5000 to Sharma Timber", now)
        assertTrue(advance is BooksAction.Money)
        advance as BooksAction.Money
        assertEquals(MoneyDirection.OUT, advance.direction)
        assertEquals("Sharma Timber", advance.party)
        assertTrue(advance.note.contains("Advance"))

        val paid = BooksUtterance.parse("I paid 5000 to Sharma Timber", now) as BooksAction.Money
        assertTrue(!paid.note.contains("advance", ignoreCase = true))

        val engine = BooksEngine(zone) { "id" }
        val withAdvance = engine.apply(BooksSnapshot(), advance, 1L).snapshot
        val withBoth = engine.apply(withAdvance, paid, 2L).snapshot
        assertEquals(5_000L, recordedAdvanceRupees(withBoth, "Sharma Timber"))
    }

    @Test
    fun anAdvanceAlreadyAppliedIsNotUsedAgain() {
        val money = MoneyEntry("m", MoneyDirection.OUT, MoneyScope.BUSINESS, 8_000, "Sharma Timber", "Advance to Sharma Timber", 1L)
        val first = BooksEngine { "bill-1" }.apply(
            BooksSnapshot(money = listOf(money)),
            BooksAction.SupplierBill("Sharma Timber", 5_000),
            2L,
        )
        assertEquals(0L, first.snapshot.supplierBills.single().payable)
        assertEquals(5_000L, first.snapshot.supplierBills.single().advance)
        assertTrue(first.snapshot.vendorDues.isEmpty())
        assertTrue(first.spoken.contains("I did not pay"))
        assertTrue(!first.spoken.contains("UPI") || first.spoken.contains("did not send UPI"))

        assertEquals(3_000L, recordedAdvanceRupees(first.snapshot, "the Sharma Timber"))
        val second = BooksEngine { "bill-2" }.apply(
            first.snapshot,
            BooksAction.SupplierBill("Sharma Timber", 4_000),
            3L,
        )
        assertEquals(3_000L, second.snapshot.supplierBills.last().advance)
        assertEquals(1_000L, second.snapshot.supplierBills.last().payable)
        assertEquals(1_000L, second.snapshot.vendorDues.single().amount)
    }

    @Test
    fun supplierTotalsAreReadOnlyWhenOneAmountIsClear() {
        assertEquals(15_000L, SupplierBillParser.parse("total bill 15000"))
        assertEquals(15_000L, SupplierBillParser.parse("bill is ₹15,000"))
        assertEquals(12_500L, SupplierBillParser.parse("₹12,500"))
        assertEquals(15_000L, SupplierBillParser.parse("total 15000. UPI later, do not pay here"))
        assertNull(SupplierBillParser.parse("total 10000 or total 20000"))
        assertNull(SupplierBillParser.parse("10 or 20"))
        assertNull(SupplierBillParser.parse("pay 9999 to upi shop@ybl"))
        assertNull(SupplierBillParser.parse("total 10 sheets"))
    }
}
