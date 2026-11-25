package com.kai.assistant.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class BooksLedgerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 11, 25, 14, 30, 0, 0, zone)
    private val nowMs = now.toInstant().toEpochMilli()
    private var nextId = 0
    private val engine = BooksEngine(zone = zone, ids = { "id-${nextId++}" })

    @Test
    fun rupeeGrouping() {
        assertEquals("₹2,400", formatRupees(2400))
        assertEquals("₹5,000", formatRupees(5000))
        assertEquals("₹12,000", formatRupees(12000))
        assertEquals("₹45,000", formatRupees(45000))
        assertEquals("₹1,00,000", formatRupees(100000))
    }

    @Test
    fun todayWeekAndMonthTotalsUseCalendarBoundaries() {
        val entries = listOf(
            money(MoneyDirection.IN, 1_000, at(month = 11, day = 25)),
            money(MoneyDirection.OUT, 400, at(month = 11, day = 25), MoneyScope.PERSONAL),
            money(MoneyDirection.IN, 2_000, at(month = 11, day = 23)),
            money(MoneyDirection.OUT, 500, at(month = 11, day = 22)),
            money(MoneyDirection.IN, 8_000, at(month = 11, day = 1)),
            money(MoneyDirection.OUT, 3_000, at(month = 10, day = 31)),
        )
        val totals = periodTotals(entries, nowMs, zone)
        assertEquals(CashTotals(1_000, 400), totals.today)
        assertEquals(CashTotals(3_000, 400), totals.week)
        assertEquals(CashTotals(11_000, 900), totals.month)
        assertEquals(600, totals.today.net)
        assertEquals(CashTotals(11_000, 500), totals.monthBusiness)
        assertEquals(CashTotals(0, 400), totals.monthPersonal)
    }

    @Test
    fun dueAndOverdueFollowTheLocalDay() {
        val yesterday = at(month = 11, day = 24, hour = 23)
        val today = at(month = 11, day = 25, hour = 23)
        val tomorrow = at(month = 11, day = 26)
        assertEquals(DueState.OVERDUE, dueState(yesterday, paid = false, nowMs, zone))
        assertEquals(DueState.DUE_TODAY, dueState(today, paid = false, nowMs, zone))
        assertEquals(DueState.UPCOMING, dueState(tomorrow, paid = false, nowMs, zone))
        assertEquals(DueState.PAID, dueState(yesterday, paid = true, nowMs, zone))
        assertEquals(DueState.UPCOMING, dueState(dueEpochMs = null, paid = false, nowMs, zone))
        assertEquals(DueState.DUE_TODAY, dueState(dueOnDayOfMonth(25, now), paid = false, nowMs, zone))
        val fifth = dueOnDayOfMonth(5, now)!!
        assertEquals(5, java.time.Instant.ofEpochMilli(fifth).atZone(zone).dayOfMonth)
        assertEquals(12, java.time.Instant.ofEpochMilli(fifth).atZone(zone).monthValue)
        assertNull(dueOnDayOfMonth(31, now))
    }

    @Test
    fun saleAdvanceBalanceAndWhatIsOwed() {
        val sold = apply("sold a sofa for 45000 advance 10000")
        val order = sold.snapshot.orders.single()
        assertEquals(45_000, order.amount)
        assertEquals(10_000, order.advance)
        assertEquals(35_000, orderBalance(order.amount, order.advance))
        assertEquals(OrderStatus.ORDERED, order.status)
        assertEquals(10_000, sold.snapshot.money.single().amount)
        assertEquals(MoneyDirection.IN, sold.snapshot.money.single().direction)
        val summary = summarize(sold.snapshot, nowMs, zone)
        assertEquals(45_000, summary.salesThisMonth)
        assertEquals(10_000, summary.moneyInThisMonth)
        assertEquals(0, summary.moneyOutThisMonth)
        assertEquals(35_000, summary.owedToOwner)
        assertEquals(0, summary.ownerOwes)
        assertTrue(sold.spoken.contains("₹45,000"))
        assertTrue(sold.spoken.contains("₹35,000"))
        assertFalse(sold.spoken.contains("WhatsApp", ignoreCase = true))
    }

    @Test
    fun payingASupplierReducesTheOpenDueAndDoesNotClearADifferentOne() {
        var books = apply("i owe the wood supplier 15000").snapshot
        books = engine.apply(books, BooksUtterance.parse("I paid 5000 to the wood supplier")!!, nowMs).snapshot
        val due = books.vendorDues.single()
        assertEquals(10_000, due.amount)
        assertFalse(due.paid)
        assertEquals(5_000, books.money.single().amount)
        assertEquals(MoneyDirection.OUT, books.money.single().direction)
        assertEquals(10_000, ownerOwes(books))
        val cleared = engine.apply(books, BooksUtterance.parse("I paid 10000 to the wood supplier")!!, nowMs).snapshot
        assertTrue(cleared.vendorDues.single().paid)
        assertEquals(0, ownerOwes(cleared))
    }

    @Test
    fun customerPendingAndOwnerDebtStaySeparate() {
        var books = apply("Rahul owes me 12000").snapshot
        books = engine.apply(books, BooksUtterance.parse("loan from the bank 50000 due on the 5th", now)!!, nowMs).snapshot
        books = engine.apply(books, BooksUtterance.parse("labour due to Raju 800", now)!!, nowMs).snapshot
        assertEquals(12_000, owedToOwner(books))
        assertEquals(50_800, ownerOwes(books))
        val collected = engine.apply(books, BooksUtterance.parse("received 12000 from Rahul", now)!!, nowMs)
        assertEquals(0, owedToOwner(collected.snapshot))
        assertTrue(collected.snapshot.receivables.single().settled)
        assertEquals(50_800, ownerOwes(collected.snapshot))
    }

    @Test
    fun overdueBillCountsUntilItIsMarkedPaidOnce() {
        val saved = apply("electricity bill 2400 due on the 5th", now)
        val later = saved.snapshot
        val laterNow = ZonedDateTime.of(2026, 12, 6, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(DueState.OVERDUE, dueState(later.bills.single().dueEpochMs, paid = false, laterNow, zone))
        assertEquals(1, summarize(later, laterNow, zone).overdueBills.size)
        assertEquals(2_400, ownerOwes(later))
        val paid = engine.apply(later, BooksUtterance.parse("mark electricity bill paid")!!, laterNow)
        assertTrue(paid.snapshot.bills.single().paid)
        assertEquals(0, summarize(paid.snapshot, laterNow, zone).overdueBills.size)
        assertEquals(0, ownerOwes(paid.snapshot))
        assertEquals(1, paid.snapshot.money.size)
        val again = engine.apply(paid.snapshot, BooksAction.MarkBillPaid(kind = BillKind.ELECTRICITY), laterNow)
        assertEquals(1, again.snapshot.money.size)
        assertTrue(again.spoken.contains("don't have"))
    }

    @Test
    fun payingANamedBillClearsItWithoutASecondExpense() {
        var books = apply("electricity bill 2400 due on the 5th").snapshot
        val paid = engine.apply(books, BooksUtterance.parse("I paid 2400 to electricity", now)!!, nowMs)
        assertTrue(paid.snapshot.bills.single().paid)
        assertEquals(1, paid.snapshot.money.size)
        assertEquals(0, ownerOwes(paid.snapshot))
        assertTrue(paid.spoken.contains("marked paid"))
        assertTrue(paid.spoken.contains("did not send"))
    }

    @Test
    fun lowStockRemindsAtOrBelowReorderAndStopsAboveIt() {
        val level = apply("teak stock 5 reorder 5 from sharma")
        assertEquals(1, level.reorderReminders.size)
        assertTrue(reorderReminderText(level.reorderReminders.single()).contains("Do not contact the supplier"))
        assertFalse(reorderReminderText(level.reorderReminders.single()).contains("WhatsApp", ignoreCase = true))
        val below = engine.apply(BooksSnapshot(), BooksUtterance.parse("teak stock 4 reorder 5 supplier sharma")!!, nowMs)
        assertTrue(isLowStock(below.snapshot.materials.single()))
        val above = engine.apply(below.snapshot, BooksUtterance.parse("teak stock 6 reorder 5 supplier sharma")!!, nowMs)
        assertTrue(above.reorderReminders.isEmpty())
        assertEquals(listOf("Teak"), above.clearReorderFor)
        assertFalse(isLowStock(above.snapshot.materials.single()))
        val unset = engine.apply(BooksSnapshot(), BooksUtterance.parse("foam stock 0 reorder 0")!!, nowMs)
        assertTrue(unset.reorderReminders.isEmpty())
    }

    @Test
    fun advanceCannotExceedThePrice() {
        val rejected = engine.apply(
            BooksSnapshot(),
            BooksAction.Order(customer = "Rahul", item = "Sofa", amount = 10_000, advance = 12_000),
            nowMs,
        )
        assertTrue(rejected.snapshot.orders.isEmpty())
        assertTrue(rejected.snapshot.money.isEmpty())
    }

    @Test
    fun snapshotRoundTripsThroughJsonAndTheOnDeviceFile() {
        val outcome = apply("sold a sofa for 45000 advance 10000")
        val restored = booksSnapshotFromJson(outcome.snapshot.toJson().toString())
        assertEquals(outcome.snapshot, restored)
        val file = File.createTempFile("kai-books", ".json")
        val store = BooksStore(file, engine)
        store.apply(BooksUtterance.parse("Rahul owes me 12000")!!, nowMs)
        val again = BooksStore(file, BooksEngine(zone = zone, ids = { "unused" }))
        assertEquals("Rahul", again.books.value.receivables.single().customer)
        assertEquals(12_000, again.books.value.receivables.single().amount)
        file.delete()
    }

    private fun apply(phrase: String, clock: ZonedDateTime = now): BooksOutcome {
        val action = BooksUtterance.parse(phrase, clock) ?: error("Did not understand: $phrase")
        return engine.apply(BooksSnapshot(), action, clock.toInstant().toEpochMilli())
    }

    private fun money(
        direction: MoneyDirection,
        amount: Long,
        at: Long,
        scope: MoneyScope = MoneyScope.BUSINESS,
    ) = MoneyEntry("m-$at-$direction", direction, scope, amount, "note", "note", at)

    private fun at(month: Int, day: Int, hour: Int = 10): Long =
        ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
}
