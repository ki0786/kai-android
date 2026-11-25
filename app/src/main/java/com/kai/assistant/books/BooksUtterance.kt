package com.kai.assistant.books

import com.kai.assistant.business.MaterialRequestParser
import java.time.ZonedDateTime

/**
 * Turns a books sentence into a ledger entry. Returns null when the sentence is not books,
 * so calls, messages and other phone commands keep their own matchers.
 */
object BooksUtterance {
    private const val AMOUNT = """(?<amount>\d+(?:\.\d+)?)(?:\s*(?<unit>k|thousand|lakh|lakhs|lac|lacs))?"""
    private const val ADVANCE =
        """(?:\s+advance(?:\s+of)?\s+(?<advance>\d+(?:\.\d+)?)(?:\s*(?<advanceUnit>k|thousand|lakh|lakhs|lac|lacs))?)?"""
    private const val DAY = """(?<day>\d{1,2})(?:st|nd|rd|th)?"""
    private const val BILL_KIND = """(?<kind>rent|electricity|electric|power|current|water|bescom)"""

    fun parse(raw: String, now: ZonedDateTime = ZonedDateTime.now()): BooksAction? {
        val text = prepare(raw)
        if (text.isBlank()) return null
        summary(text)?.let { return it }
        markBill(text)?.let { return it }
        bill(text, now)?.let { return it }
        order(text)?.let { return it }
        labour(text, now)?.let { return it }
        labourMaterial(text)?.let { return it }
        material(text)?.let { return it }
        receivable(text)?.let { return it }
        debt(text, now)?.let { return it }
        vendor(text, now)?.let { return it }
        money(text)?.let { return it }
        return null
    }

    private fun prepare(raw: String): String = raw.lowercase()
        .replace("₹", " ")
        .replace(Regex("""\b(?:rs\.?|rupees?|inr)\b"""), " ")
        .replace(Regex("""\blabor\b"""), "labour")
        .replace(",", "")
        .replace(Regex("""[?.!]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun summary(text: String): BooksAction.Summary? {
        val all = setOf(
            "business summary",
            "books summary",
            "how are the books",
            "how are my books",
            "how are the business books",
            "how's the business",
            "hows the business",
            "how is the business",
            "show my books",
            "show the books",
            "show business books",
            "open books",
            "open the books",
            "open business books",
            "open my books",
            "business books",
            "my books",
        )
        if (text in all || text.startsWith("how are the books") || text.startsWith("how are my books")) {
            return BooksAction.Summary(SummaryFocus.ALL)
        }
        if (text in setOf("what do i owe", "what i owe", "how much do i owe", "who do i owe", "what's my debt", "whats my debt", "what is my debt", "my debt") ||
            text.startsWith("what do i owe") || text.startsWith("how much do i owe")
        ) {
            return BooksAction.Summary(SummaryFocus.I_OWE)
        }
        if (text in setOf("who owes me", "who owes us", "what's owed to me", "whats owed to me", "what is owed to me", "customer pending", "pending money")) {
            return BooksAction.Summary(SummaryFocus.OWED_TO_ME)
        }
        if (text in setOf("sales today", "today's sales", "todays sales", "sales this month", "how much did i sell", "what's my sales", "whats my sales", "what are my sales")) {
            return BooksAction.Summary(SummaryFocus.SALES)
        }
        if (text in setOf("money in today", "money out today", "cash today")) {
            return BooksAction.Summary(SummaryFocus.CASH_TODAY)
        }
        if (text in setOf("money this week", "cash this week")) {
            return BooksAction.Summary(SummaryFocus.CASH_WEEK)
        }
        if (text in setOf("money this month", "cash this month")) {
            return BooksAction.Summary(SummaryFocus.CASH_MONTH)
        }
        return null
    }

    private fun markBill(text: String): BooksAction? {
        val patterns = listOf(
            Regex("""^(?:mark\s+)?(?:the\s+)?$BILL_KIND\s+bill\s+(?:as\s+)?paid$"""),
            Regex("""^paid\s+(?:the\s+)?$BILL_KIND\s+bill$"""),
            Regex("""^(?:mark\s+)?(?:the\s+)?$BILL_KIND\s+paid$"""),
        )
        val match = patterns.firstNotNullOfOrNull { it.matchEntire(text) } ?: return null
        return BooksAction.MarkBillPaid(kind = billKind(match.groups["kind"]?.value.orEmpty()))
    }

    private fun bill(text: String, now: ZonedDateTime): BooksAction? {
        val specific = Regex("""^$BILL_KIND(?:\s+bill)?\s+$AMOUNT\s+due\s+on\s+(?:the\s+)?$DAY$""").matchEntire(text)
        if (specific != null) {
            val amount = amount(specific) ?: return null
            val due = dueDay(specific, now) ?: return null
            val kind = billKind(specific.groups["kind"]?.value.orEmpty())
            return BooksAction.Bill(kind, kind.label, amount, due)
        }
        val other = Regex(
            """^(?:other|misc|miscellaneous)\s+bill\s+(?<label>.+?)\s+$AMOUNT\s+due\s+on\s+(?:the\s+)?$DAY$""",
        ).matchEntire(text)
        if (other != null) {
            val amount = amount(other) ?: return null
            val due = dueDay(other, now) ?: return null
            return BooksAction.Bill(BillKind.OTHER, titleParty(other.groups["label"]?.value.orEmpty()), amount, due)
        }
        val named = Regex("""^(?<label>.+?)\s+bill\s+$AMOUNT\s+due\s+on\s+(?:the\s+)?$DAY$""").matchEntire(text)
        if (named != null) {
            val amount = amount(named) ?: return null
            val due = dueDay(named, now) ?: return null
            return BooksAction.Bill(BillKind.OTHER, titleParty(named.groups["label"]?.value.orEmpty()), amount, due)
        }
        return bareBill(text)
    }

    /** "rent 15000" and "demo rent 15000" when no due day was said. */
    private fun bareBill(text: String): BooksAction.Bill? {
        val match = Regex("""^(?:(?<demo>demo)\s+)?$BILL_KIND(?:\s+(?<bill>bill))?\s+$AMOUNT$""").matchEntire(text)
            ?: return null
        val token = match.groups["kind"]?.value.orEmpty()
        val needsBillWord = token in setOf("electric", "power", "current", "bescom")
        if (needsBillWord && match.groups["bill"] == null) return null
        val rupees = amount(match) ?: return null
        val kind = billKind(token)
        val label = if (match.groups["demo"] != null) "demo ${kind.label}" else kind.label
        return BooksAction.Bill(kind, titleParty(label), rupees, dueEpochMs = null)
    }

    private fun order(text: String): BooksAction? {
        Regex(
            """^(?:(?:you|i|we)\s+)?(?:recorded\s+)?(?:selling|sold)\s+(?:a|an|the)\s+(?<item>.+?)\s+for\s+$AMOUNT$ADVANCE$""",
        ).matchEntire(text)?.let { match ->
            val amount = amount(match) ?: return null
            return BooksAction.Order(
                customer = "",
                item = titleParty(match.groups["item"]?.value.orEmpty()),
                amount = amount,
                advance = advance(match),
            )
        }
        Regex("""^sold\s+(?<customer>.+?)\s+(?:a|an|the)\s+(?<item>.+?)\s+for\s+$AMOUNT$ADVANCE$""")
            .matchEntire(text)
            ?.let { match ->
                val amount = amount(match) ?: return null
                return BooksAction.Order(
                    customer = titleParty(match.groups["customer"]?.value.orEmpty()),
                    item = titleParty(match.groups["item"]?.value.orEmpty()),
                    amount = amount,
                    advance = advance(match),
                )
            }
        Regex("""^sold\s+(?:a|an|the)\s+(?<item>.+?)\s+for\s+$AMOUNT$ADVANCE$""").matchEntire(text)?.let { match ->
            val amount = amount(match) ?: return null
            return BooksAction.Order(
                customer = "",
                item = titleParty(match.groups["item"]?.value.orEmpty()),
                amount = amount,
                advance = advance(match),
            )
        }
        Regex("""^(?<customer>.+?)\s+ordered\s+(?:a|an|the)\s+(?<item>.+?)\s+for\s+$AMOUNT$ADVANCE$""")
            .matchEntire(text)
            ?.let { match ->
                val amount = amount(match) ?: return null
                return BooksAction.Order(
                    customer = titleParty(match.groups["customer"]?.value.orEmpty()),
                    item = titleParty(match.groups["item"]?.value.orEmpty()),
                    amount = amount,
                    advance = advance(match),
                )
            }
        Regex(
            """^(?:new\s+)?order\s+from\s+(?<customer>.+?)\s+(?:for\s+)?(?:a\s+|an\s+|the\s+)?(?<item>.+?)\s+(?:for\s+)?$AMOUNT$ADVANCE$""",
        ).matchEntire(text)?.let { match ->
            val amount = amount(match) ?: return null
            return BooksAction.Order(
                customer = titleParty(match.groups["customer"]?.value.orEmpty()),
                item = titleParty(match.groups["item"]?.value.orEmpty()),
                amount = amount,
                advance = advance(match),
            )
        }
        return null
    }

    private fun labour(text: String, now: ZonedDateTime): BooksAction? {
        val patterns = listOf(
            Regex("""^labour\s+paid\s+(?:to\s+)?(?<worker>.+?)\s+$AMOUNT(?:\s+(?:for\s+)?(?<when>today|yesterday))?$""") to true,
            Regex("""^paid\s+labour\s+(?:to\s+)?(?<worker>.+?)\s+$AMOUNT(?:\s+(?:for\s+)?(?<when>today|yesterday))?$""") to true,
            Regex("""^labour\s+due\s+(?:to\s+)?(?<worker>.+?)\s+$AMOUNT(?:\s+(?:for\s+)?(?<when>today|yesterday))?$""") to false,
            Regex("""^(?<worker>.+?)\s+labour\s+$AMOUNT\s+due(?:\s+(?:for\s+)?(?<when>today|yesterday))?$""") to false,
            Regex("""^paid\s+(?<worker>.+?)\s+$AMOUNT\s+(?:for\s+)?labour(?:\s+(?:for\s+)?(?<when>today|yesterday))?$""") to true,
            Regex("""^paid\s+(?<worker>.+?)\s+$AMOUNT\s+for\s+(?<when>today|yesterday)$""") to true,
        )
        for ((pattern, paid) in patterns) {
            val match = pattern.matchEntire(text) ?: continue
            val amount = amount(match) ?: return null
            val worker = match.groups["worker"]?.value.orEmpty()
            if (worker.any { it.isDigit() }) return null
            return BooksAction.Labour(
                worker = titleParty(worker),
                dayEpochMs = workDay(match.groups["when"]?.value, now),
                amount = amount,
                paid = paid,
            )
        }
        return null
    }

    /**
     * "labour needs 10 sheets of plywood" is a workshop material request, not a wage
     * and not a web search. The supplier is filled in from stock when the books are updated.
     */
    private fun labourMaterial(text: String): BooksAction? {
        val match = Regex(
            """^(?:that\s+|the\s+)?labour\s+(?:needs|need|needed|requires|required|wants|wanted|asked\s+for)\s+(?<rest>.+)$""",
        ).matchEntire(text) ?: return null
        val lines = MaterialRequestParser.parse(match.groups["rest"]?.value.orEmpty())
        if (lines.isEmpty()) return null
        return BooksAction.PurchaseOrder(
            supplier = "",
            requestedBy = "Labour",
            lines = MaterialRequestParser.describe(lines),
        )
    }

    private fun material(text: String): BooksAction? {
        val stock = Regex(
            """^(?<item>.+?)\s+stock\s+(?<qty>\d+(?:\.\d+)?)\s*(?<stockUnit>sheets|pcs|pieces|kg|kgs|feet|ft|litres?|ltr|units?)?\s+reorder(?:\s+(?:level|at))?\s+(?<reorder>\d+(?:\.\d+)?)(?:\s+(?:from|supplier)\s+(?<supplier>.+))?$""",
        ).matchEntire(text)
        if (stock != null) {
            return materialAction(stock)
        }
        val added = Regex(
            """^(?:add\s+)?(?:raw\s+)?material\s+(?<item>.+?)\s+(?:qty\s+)?(?<qty>\d+(?:\.\d+)?)\s*(?<stockUnit>sheets|pcs|pieces|kg|kgs|feet|ft|litres?|ltr|units?)?\s+supplier\s+(?<supplier>.+?)\s+reorder(?:\s+(?:level|at))?\s+(?<reorder>\d+(?:\.\d+)?)$""",
        ).matchEntire(text) ?: return null
        return materialAction(added)
    }

    private fun materialAction(match: MatchResult): BooksAction.Material? {
        val qty = match.groups["qty"]?.value?.toDoubleOrNull() ?: return null
        val reorder = match.groups["reorder"]?.value?.toDoubleOrNull() ?: return null
        return BooksAction.Material(
            item = titleParty(match.groups["item"]?.value.orEmpty()),
            qty = qty,
            unit = match.groups["stockUnit"]?.value.orEmpty(),
            supplier = titleParty(match.groups["supplier"]?.value.orEmpty()),
            reorderLevel = reorder,
        )
    }

    private fun receivable(text: String): BooksAction? {
        val patterns = listOf(
            Regex("""^(?<customer>.+?)\s+owes\s+(?:me|us)\s+$AMOUNT$"""),
            Regex("""^(?:pending|due)\s+from\s+(?<customer>.+?)\s+$AMOUNT$"""),
            Regex("""^customer\s+pending\s+(?<customer>.+?)\s+$AMOUNT$"""),
        )
        val match = patterns.firstNotNullOfOrNull { it.matchEntire(text) } ?: return null
        val amount = amount(match) ?: return null
        return BooksAction.Receivable(titleParty(match.groups["customer"]?.value.orEmpty()), amount)
    }

    private fun debt(text: String, now: ZonedDateTime): BooksAction? {
        val loan = Regex(
            """^(?:a\s+)?(?:(?<demo>demo)\s+)?bank\s+loan\s+$AMOUNT(?:\s+due\s+on\s+(?:the\s+)?$DAY)?$""",
        ).matchEntire(text) ?: Regex(
            """^(?:i\s+)?(?:received|got)\s+$AMOUNT\s+(?:as\s+)?(?:a\s+)?(?:(?<demo>demo)\s+)?bank\s+loan(?:\s+due\s+on\s+(?:the\s+)?$DAY)?$""",
        ).matchEntire(text)
        if (loan != null) {
            val amount = amount(loan) ?: return null
            val lender = if (loan.groups["demo"] != null) "demo bank" else "bank"
            return BooksAction.OwnerDebt(titleParty(lender), amount, dueDayOrNull(loan, now))
        }
        val explicit = listOf(
            Regex("""^(?:my\s+)?owner\s+debt\s+(?:to\s+)?(?<party>.+?)\s+(?:is\s+)?$AMOUNT(?:\s+due\s+on\s+(?:the\s+)?$DAY)?$"""),
            Regex("""^my\s+debt\s+(?:to\s+)?(?<party>.+?)\s+(?:is\s+)?$AMOUNT(?:\s+due\s+on\s+(?:the\s+)?$DAY)?$"""),
            Regex("""^loan\s+from\s+(?<party>.+?)\s+$AMOUNT(?:\s+due\s+on\s+(?:the\s+)?$DAY)?$"""),
            Regex("""^i\s+borrowed\s+$AMOUNT\s+from\s+(?<party>.+)$"""),
        )
        for (pattern in explicit) {
            val match = pattern.matchEntire(text) ?: continue
            val amount = amount(match) ?: return null
            val party = titleParty(match.groups["party"]?.value.orEmpty())
            if (party.isBlank()) return null
            return BooksAction.OwnerDebt(party, amount, dueDayOrNull(match, now))
        }
        return null
    }

    private fun vendor(text: String, now: ZonedDateTime): BooksAction? {
        val patterns = listOf(
            Regex("""^i\s+owe\s+(?:the\s+)?(?<party>.+?)\s+$AMOUNT(?:\s+due\s+on\s+(?:the\s+)?$DAY)?$"""),
            Regex("""^vendor\s+payment\s+$AMOUNT\s+to\s+(?:the\s+)?(?<party>.+?)(?:\s+due\s+on\s+(?:the\s+)?$DAY)?$"""),
            Regex("""^vendor\s+(?:payment\s+)?(?:due\s+)?(?:to\s+)?(?<party>.+?)\s+$AMOUNT(?:\s+due\s+on\s+(?:the\s+)?$DAY)?$"""),
        )
        val match = patterns.firstNotNullOfOrNull { it.matchEntire(text) } ?: return null
        val amount = amount(match) ?: return null
        val party = titleParty(match.groups["party"]?.value.orEmpty())
        if (party.isBlank()) return null
        val due = dueDayOrNull(match, now)
        if (isOwnerDebtParty(party)) return BooksAction.OwnerDebt(party, amount, due)
        return BooksAction.VendorDue(party, amount, due)
    }

    private fun money(text: String): BooksAction? {
        Regex("""^(?:i\s+)?(?:paid\s+)?(?:an\s+)?advance(?:\s+of)?\s+$AMOUNT\s+to\s+(?:the\s+)?(?<party>.+)$""")
            .matchEntire(text)
            ?.let { match ->
                val amount = amount(match) ?: return null
                val party = titleParty(match.groups["party"]?.value.orEmpty())
                if (party.isBlank()) return null
                return BooksAction.Money(
                    direction = MoneyDirection.OUT,
                    scope = MoneyScope.BUSINESS,
                    amount = amount,
                    party = party,
                    note = "Advance to $party",
                )
            }
        Regex("""^(?:a\s+)?(?:(?<demo>demo)\s+)?personal\s+(?<party>.+?)\s+$AMOUNT$""").matchEntire(text)?.let { match ->
            val rupees = amount(match) ?: return null
            val party = match.groups["party"]?.value.orEmpty()
            val named = if (match.groups["demo"] != null && !party.startsWith("demo")) "demo $party" else party
            return BooksAction.Money(MoneyDirection.OUT, MoneyScope.PERSONAL, rupees, titleParty(named))
        }
        Regex("""^(?<scope>personal|business)\s+expense\s+$AMOUNT(?:\s+(?:for|on)\s+(?<party>.+))?$""")
            .matchEntire(text)
            ?.let { match ->
                val amount = amount(match) ?: return null
                val scope = if (match.groups["scope"]?.value == "personal") MoneyScope.PERSONAL else MoneyScope.BUSINESS
                return BooksAction.Money(MoneyDirection.OUT, scope, amount, titleParty(match.groups["party"]?.value.orEmpty()))
            }
        Regex("""^(?:i\s+)?(?:received|got)\s+$AMOUNT\s+from\s+(?:the\s+)?(?<party>.+)$""").matchEntire(text)?.let { match ->
            val amount = amount(match) ?: return null
            return BooksAction.Money(MoneyDirection.IN, MoneyScope.BUSINESS, amount, titleParty(match.groups["party"]?.value.orEmpty()))
        }
        Regex("""^money\s+in\s+$AMOUNT(?:\s+from\s+(?:the\s+)?(?<party>.+))?$""").matchEntire(text)?.let { match ->
            val amount = amount(match) ?: return null
            return BooksAction.Money(MoneyDirection.IN, MoneyScope.BUSINESS, amount, titleParty(match.groups["party"]?.value.orEmpty()))
        }
        Regex("""^(?:i\s+)?paid\s+$AMOUNT\s+(?:for|on)\s+(?<party>.+)$""").matchEntire(text)?.let { match ->
            val rupees = amount(match) ?: return null
            val party = match.groups["party"]?.value.orEmpty()
            val scope = if (Regex("""\bpersonal\b""").containsMatchIn(party)) MoneyScope.PERSONAL else MoneyScope.BUSINESS
            return BooksAction.Money(MoneyDirection.OUT, scope, rupees, titleParty(party))
        }
        Regex("""^(?:i\s+)?paid\s+$AMOUNT\s+to\s+(?:the\s+)?(?<party>.+)$""").matchEntire(text)?.let { match ->
            val amount = amount(match) ?: return null
            val party = match.groups["party"]?.value.orEmpty()
            val scope = if (party.contains("personal")) MoneyScope.PERSONAL else MoneyScope.BUSINESS
            return BooksAction.Money(MoneyDirection.OUT, scope, amount, titleParty(party))
        }
        Regex("""^(?:i\s+)?spent\s+$AMOUNT\s+on\s+(?<party>.+)$""").matchEntire(text)?.let { match ->
            val amount = amount(match) ?: return null
            val party = match.groups["party"]?.value.orEmpty()
            val scope = if (Regex("""\bpersonal\b""").containsMatchIn(party)) MoneyScope.PERSONAL else MoneyScope.BUSINESS
            return BooksAction.Money(MoneyDirection.OUT, scope, amount, titleParty(party))
        }
        Regex("""^money\s+out\s+$AMOUNT(?:\s+(?:for|on)\s+(?<party>.+))?$""").matchEntire(text)?.let { match ->
            val amount = amount(match) ?: return null
            return BooksAction.Money(MoneyDirection.OUT, MoneyScope.BUSINESS, amount, titleParty(match.groups["party"]?.value.orEmpty()))
        }
        return null
    }

    private fun amount(match: MatchResult, number: String = "amount", unit: String = "unit"): Long? =
        parseAmount(match.groups[number]?.value.orEmpty(), match.groups[unit]?.value)

    private fun advance(match: MatchResult): Long =
        parseAmount(match.groups["advance"]?.value.orEmpty(), match.groups["advanceUnit"]?.value) ?: 0

    private fun dueDay(match: MatchResult, now: ZonedDateTime): Long? {
        val day = match.groups["day"]?.value?.toIntOrNull() ?: return null
        return dueOnDayOfMonth(day, now)
    }

    private fun dueDayOrNull(match: MatchResult, now: ZonedDateTime): Long? {
        val day = named(match, "day") ?: return null
        return dueOnDayOfMonth(day.toIntOrNull() ?: return null, now)
    }

    private fun named(match: MatchResult, name: String): String? = try {
        match.groups[name]?.value?.ifBlank { null }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun workDay(token: String?, now: ZonedDateTime): Long {
        val date = if (token == "yesterday") now.toLocalDate().minusDays(1) else now.toLocalDate()
        return date.atStartOfDay(now.zone).toInstant().toEpochMilli()
    }

    private fun billKind(token: String): BillKind = when (token) {
        "rent" -> BillKind.RENT
        "water" -> BillKind.WATER
        "electricity", "electric", "power", "current", "bescom" -> BillKind.ELECTRICITY
        else -> BillKind.OTHER
    }

    private fun isOwnerDebtParty(party: String): Boolean =
        Regex("""\b(bank|loan|lender|emi|finance|nbfc)\b""", RegexOption.IGNORE_CASE).containsMatchIn(party)
}
