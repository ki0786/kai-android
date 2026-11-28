package com.kai.assistant.books

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

data class CashTotals(val moneyIn: Long = 0, val moneyOut: Long = 0) {
    val net: Long get() = moneyIn - moneyOut
}

data class PeriodTotals(
    val today: CashTotals,
    val week: CashTotals,
    val month: CashTotals,
    val monthBusiness: CashTotals,
    val monthPersonal: CashTotals,
)

data class BooksSummary(
    val salesThisMonth: Long,
    val moneyInThisMonth: Long,
    val moneyOutThisMonth: Long,
    val owedToOwner: Long,
    val ownerOwes: Long,
    val overdueBills: List<Bill>,
    val lowStock: List<MaterialStock>,
)

fun orderBalance(amount: Long, advance: Long): Long = (amount - advance).coerceAtLeast(0)

/** What is still unpaid after the advance already saved against this supplier. Never negative. */
fun payableRupees(billAmount: Long, advance: Long): Long {
    if (billAmount <= 0) return 0
    return (billAmount - advance.coerceAtLeast(0)).coerceAtLeast(0)
}

/**
 * Business money already paid to [supplier] whose note says it is an advance,
 * minus advances already applied to earlier supplier bills.
 * A plain "I paid" entry is not an advance, so it is not subtracted from a new bill.
 */
fun recordedAdvanceRupees(snapshot: BooksSnapshot, supplier: String): Long {
    val noted = snapshot.money.filter { entry ->
        entry.direction == MoneyDirection.OUT &&
            entry.scope == MoneyScope.BUSINESS &&
            namesMatch(entry.party, supplier) &&
            entry.note.contains("advance", ignoreCase = true)
    }.sumOf { it.amount }
    val used = snapshot.supplierBills.filter { namesMatch(it.supplier, supplier) }.sumOf { it.advance }
    return (noted - used).coerceAtLeast(0)
}

fun formatRupees(amount: Long): String {
    val digits = abs(amount).toString()
    val grouped = if (digits.length <= 3) {
        digits
    } else {
        val tail = digits.takeLast(3)
        val head = digits.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        "$head,$tail"
    }
    return if (amount < 0) "-₹$grouped" else "₹$grouped"
}

fun formatQty(qty: Double): String =
    if (qty % 1.0 == 0.0) qty.toLong().toString() else qty.toString()

fun formatDueDate(epochMs: Long, zone: ZoneId): String {
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    return date.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
}

fun dueOnDayOfMonth(day: Int, now: ZonedDateTime): Long? {
    if (day !in 1..31) return null
    var month = now.toLocalDate().withDayOfMonth(1)
    if (now.dayOfMonth > day) month = month.plusMonths(1)
    if (day > month.lengthOfMonth()) return null
    return month.withDayOfMonth(day).atStartOfDay(now.zone).toInstant().toEpochMilli()
}

fun startOfDay(epochMs: Long, zone: ZoneId): Long {
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    return date.atStartOfDay(zone).toInstant().toEpochMilli()
}

fun dueState(dueEpochMs: Long?, paid: Boolean, nowEpochMs: Long, zone: ZoneId): DueState {
    if (paid) return DueState.PAID
    if (dueEpochMs == null) return DueState.UPCOMING
    val dueDay = Instant.ofEpochMilli(dueEpochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    return when {
        dueDay.isBefore(today) -> DueState.OVERDUE
        dueDay.isEqual(today) -> DueState.DUE_TODAY
        else -> DueState.UPCOMING
    }
}

fun isLowStock(material: MaterialStock): Boolean =
    material.reorderLevel > 0.0 && material.qty <= material.reorderLevel

fun reorderReminder(material: MaterialStock): ReorderReminder? {
    if (!isLowStock(material)) return null
    return ReorderReminder(
        item = material.item,
        supplier = material.supplier,
        qty = material.qty,
        unit = material.unit,
        reorderLevel = material.reorderLevel,
    )
}

fun reorderReminderText(reminder: ReorderReminder): String {
    val stock = listOf(formatQty(reminder.qty), reminder.unit).filter { it.isNotBlank() }.joinToString(" ")
    return "Reorder ${reminder.item}. Stock is $stock, reorder level is ${formatQty(reminder.reorderLevel)}. " +
        "Reminder only. Do not contact the supplier."
}

fun periodTotals(entries: List<MoneyEntry>, nowEpochMs: Long, zone: ZoneId): PeriodTotals {
    val now = Instant.ofEpochMilli(nowEpochMs).atZone(zone)
    val today = now.toLocalDate()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val monthStart = today.withDayOfMonth(1)
    return PeriodTotals(
        today = totalsBetween(entries, today, today.plusDays(1), zone),
        week = totalsBetween(entries, weekStart, weekStart.plusWeeks(1), zone),
        month = totalsBetween(entries, monthStart, monthStart.plusMonths(1), zone),
        monthBusiness = totalsBetween(entries, monthStart, monthStart.plusMonths(1), zone, MoneyScope.BUSINESS),
        monthPersonal = totalsBetween(entries, monthStart, monthStart.plusMonths(1), zone, MoneyScope.PERSONAL),
    )
}

fun summarize(snapshot: BooksSnapshot, nowEpochMs: Long, zone: ZoneId): BooksSummary {
    val totals = periodTotals(snapshot.money, nowEpochMs, zone)
    return BooksSummary(
        salesThisMonth = monthSales(snapshot.orders, nowEpochMs, zone),
        moneyInThisMonth = totals.month.moneyIn,
        moneyOutThisMonth = totals.month.moneyOut,
        owedToOwner = owedToOwner(snapshot),
        ownerOwes = ownerOwes(snapshot),
        overdueBills = snapshot.bills.filter {
            dueState(it.dueEpochMs, it.paid, nowEpochMs, zone) == DueState.OVERDUE
        },
        lowStock = snapshot.materials.filter(::isLowStock),
    )
}

fun owedToOwner(snapshot: BooksSnapshot): Long {
    val fromOrders = snapshot.orders
        .filter { it.status != OrderStatus.CANCELLED && it.status != OrderStatus.PAID && it.status != OrderStatus.QUOTE }
        .sumOf { orderBalance(it.amount, it.advance) }
    val fromPeople = snapshot.receivables.filter { !it.settled }.sumOf { it.amount }
    return fromOrders + fromPeople
}

fun ownerOwes(snapshot: BooksSnapshot): Long {
    val vendors = snapshot.vendorDues.filter { !it.paid }.sumOf { it.amount }
    val bills = snapshot.bills.filter { !it.paid }.sumOf { it.amount }
    val labour = snapshot.labour.filter { !it.paid }.sumOf { it.amount }
    val debts = snapshot.debts.sumOf { it.balance.coerceAtLeast(0) }
    return vendors + bills + labour + debts
}

fun monthSales(orders: List<FurnitureOrder>, nowEpochMs: Long, zone: ZoneId): Long {
    val now = Instant.ofEpochMilli(nowEpochMs).atZone(zone)
    val start = now.toLocalDate().withDayOfMonth(1)
    val end = start.plusMonths(1)
    return orders
        .filter { it.status != OrderStatus.CANCELLED && it.status != OrderStatus.QUOTE }
        .filter { inDates(it.atEpochMs, start, end, zone) }
        .sumOf { it.amount }
}

fun speakBooks(snapshot: BooksSnapshot, nowEpochMs: Long, zone: ZoneId, focus: SummaryFocus): String {
    val summary = summarize(snapshot, nowEpochMs, zone)
    val totals = periodTotals(snapshot.money, nowEpochMs, zone)
    val body = when (focus) {
        SummaryFocus.SALES -> "Sales this month are ${formatRupees(summary.salesThisMonth)}."
        SummaryFocus.OWED_TO_ME -> "Owed to you: ${formatRupees(summary.owedToOwner)}."
        SummaryFocus.I_OWE -> "You owe ${formatRupees(summary.ownerOwes)}."
        SummaryFocus.CASH_TODAY -> cashLine("Today", totals.today)
        SummaryFocus.CASH_WEEK -> cashLine("This week", totals.week)
        SummaryFocus.CASH_MONTH -> cashLine("This month", totals.month)
        SummaryFocus.ALL -> buildString {
            append("This month, sales ${formatRupees(summary.salesThisMonth)}, ")
            append("money in ${formatRupees(summary.moneyInThisMonth)}, ")
            append("money out ${formatRupees(summary.moneyOutThisMonth)}. ")
            append("Owed to you ${formatRupees(summary.owedToOwner)}. ")
            append("You owe ${formatRupees(summary.ownerOwes)}.")
        }
    }
    val extras = mutableListOf<String>()
    if (focus == SummaryFocus.ALL || focus == SummaryFocus.I_OWE) {
        if (summary.overdueBills.isNotEmpty()) {
            val names = summary.overdueBills.joinToString(", ") { it.label }
            extras += "${names} ${if (summary.overdueBills.size == 1) "is" else "are"} overdue."
        }
    }
    if (focus == SummaryFocus.ALL && summary.lowStock.isNotEmpty()) {
        val names = summary.lowStock.joinToString(", ") { it.item }
        extras += "$names ${if (summary.lowStock.size == 1) "is" else "are"} at or below the reorder level. " +
            "I'll remind you. I will not contact the supplier."
    }
    val tail = " This stays on this phone. I will not pay anyone or message a supplier."
    return (listOf(body) + extras).joinToString(" ") + tail
}

fun namesMatch(left: String, right: String): Boolean {
    val a = normalizeParty(left)
    val b = normalizeParty(right)
    if (a.isEmpty() || b.isEmpty()) return false
    if (a == b) return true
    if (a.length >= 3 && b.contains(a)) return true
    if (b.length >= 3 && a.contains(b)) return true
    return false
}

fun normalizeParty(raw: String): String =
    raw.lowercase().removePrefix("the ").trim().replace(Regex("""\s+"""), " ")

fun titleParty(raw: String): String {
    val cleaned = raw.trim().removePrefix("the ").removePrefix("The ").trim()
    if (cleaned.isEmpty()) return cleaned
    return cleaned.split(Regex("""\s+""")).joinToString(" ") { word ->
        word.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.ENGLISH) else char.toString()
        }
    }
}

private val leadingRupee = Regex(
    """^(\d+(?:\.\d+)?)(?:\s+(crores?|lakhs?|lacs?|thousands?|k)\b)?""",
    RegexOption.IGNORE_CASE,
)

/**
 * Reads a rupee amount from the start of [text], including "3 lakh 22000".
 * Returns the rupees and the unread remainder.
 */
internal fun parseLeadingRupees(text: String): Pair<Long, String>? {
    var rest = text.trim()
    var total = 0.0
    var consumed = false
    while (rest.isNotEmpty()) {
        val match = leadingRupee.find(rest) ?: break
        if (match.range.first != 0) break
        val number = match.groupValues[1].toDoubleOrNull() ?: break
        val unit = match.groupValues[2].lowercase()
        val scaled = when {
            unit.startsWith("crore") -> number * 10_000_000
            unit.startsWith("lakh") || unit.startsWith("lac") -> number * 100_000
            unit == "k" || unit.startsWith("thousand") -> number * 1_000
            unit.isEmpty() -> number
            else -> break
        }
        if (scaled < 0) return null
        total += scaled
        consumed = true
        rest = rest.substring(match.range.last + 1).trim().removePrefix("and ").trim()
        if (unit.isEmpty() || rest.isEmpty() || !rest.first().isDigit()) break
    }
    if (!consumed || total <= 0 || total > 100_000_000_000) return null
    return total.roundToLong() to rest
}

fun parseAmount(number: String, unit: String?): Long? {
    val value = number.toDoubleOrNull() ?: return null
    if (value < 0) return null
    val scaled = when (unit?.lowercase()) {
        null, "" -> value
        "k", "thousand" -> value * 1_000
        "lakh", "lakhs", "lac", "lacs" -> value * 100_000
        else -> return null
    }
    if (scaled > 100_000_000_000) return null
    return scaled.roundToLong().takeIf { it > 0 }
}

private fun cashLine(label: String, totals: CashTotals): String =
    "$label, money in ${formatRupees(totals.moneyIn)}, money out ${formatRupees(totals.moneyOut)}."

private fun totalsBetween(
    entries: List<MoneyEntry>,
    start: LocalDate,
    end: LocalDate,
    zone: ZoneId,
    scope: MoneyScope? = null,
): CashTotals {
    var moneyIn = 0L
    var moneyOut = 0L
    entries.forEach { entry ->
        if (scope != null && entry.scope != scope) return@forEach
        if (!inDates(entry.atEpochMs, start, end, zone)) return@forEach
        when (entry.direction) {
            MoneyDirection.IN -> moneyIn += entry.amount
            MoneyDirection.OUT -> moneyOut += entry.amount
        }
    }
    return CashTotals(moneyIn, moneyOut)
}

private fun inDates(epochMs: Long, start: LocalDate, end: LocalDate, zone: ZoneId): Boolean {
    val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    return !day.isBefore(start) && day.isBefore(end)
}
