package com.kai.assistant.books

import java.time.ZoneId

data class BooksOutcome(
    val snapshot: BooksSnapshot,
    val spoken: String,
    /** Stock that should get a local reminder. Empty when nothing is at the reorder level. */
    val reorderReminders: List<ReorderReminder> = emptyList(),
    /** Item names whose reorder reminder should be cleared because stock recovered. */
    val clearReorderFor: List<String> = emptyList(),
)

/**
 * Applies one books entry and speaks the result.
 * Nothing here sends money, places a supplier order, or messages anyone.
 */
class BooksEngine(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val ids: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    fun apply(snapshot: BooksSnapshot, action: BooksAction, nowMs: Long): BooksOutcome = when (action) {
        is BooksAction.Money -> recordMoney(snapshot, action, nowMs)
        is BooksAction.Order -> recordOrder(snapshot, action, nowMs)
        is BooksAction.Labour -> recordLabour(snapshot, action, nowMs)
        is BooksAction.Material -> recordMaterial(snapshot, action)
        is BooksAction.VendorDue -> recordVendor(snapshot, action)
        is BooksAction.Receivable -> recordReceivable(snapshot, action)
        is BooksAction.OwnerDebt -> recordDebt(snapshot, action)
        is BooksAction.Bill -> recordBill(snapshot, action)
        is BooksAction.MarkBillPaid -> markBillPaid(snapshot, action, nowMs)
        is BooksAction.MarkVendorPaid -> markVendorPaid(snapshot, action, nowMs)
        is BooksAction.SettleReceivable -> settleReceivable(snapshot, action, nowMs)
        is BooksAction.SetOrderStatus -> setOrderStatus(snapshot, action, nowMs)
        is BooksAction.Summary -> BooksOutcome(snapshot, speakBooks(snapshot, nowMs, zone, action.focus))
        is BooksAction.PurchaseOrder -> recordPurchaseOrder(snapshot, action, nowMs)
        is BooksAction.SupplierBill -> recordSupplierBill(snapshot, action, nowMs)
    }

    private fun recordMoney(snapshot: BooksSnapshot, action: BooksAction.Money, nowMs: Long): BooksOutcome {
        if (action.amount <= 0) return BooksOutcome(snapshot, "I need an amount greater than zero.")
        val party = titleParty(action.party).ifBlank { if (action.direction == MoneyDirection.IN) "Cash in" else "Cash out" }
        val note = action.note.ifBlank { party }
        val entry = MoneyEntry(
            id = ids(),
            direction = action.direction,
            scope = action.scope,
            amount = action.amount,
            party = party,
            note = note,
            atEpochMs = nowMs,
        )
        var next = snapshot.copy(money = snapshot.money + entry)
        val isAdvance = note.contains("advance", ignoreCase = true)
        val settlement = when {
            isAdvance -> Settlement(next, "")
            action.direction == MoneyDirection.OUT -> settleOutgoing(next, party, action.amount)
            else -> settleIncoming(next, party, action.amount)
        }
        next = settlement.snapshot
        val scopeWord = if (action.scope == MoneyScope.PERSONAL) "personal" else "business"
        val verb = if (action.direction == MoneyDirection.IN) "Money in" else "Paid"
        val spoken = buildString {
            append("Saved on this phone. ")
            if (note.contains("advance", ignoreCase = true) && action.direction == MoneyDirection.OUT) {
                append("Advance ${formatRupees(action.amount)} to $party. ")
            } else if (action.direction == MoneyDirection.IN) {
                append("$verb ${formatRupees(action.amount)} from $party. ")
            } else {
                append("$verb ${formatRupees(action.amount)} to $party. ")
            }
            append("That's a $scopeWord entry. ")
            if (settlement.note.isNotBlank()) append(settlement.note).append(' ')
            append("I did not send any money.")
        }
        return BooksOutcome(next, spoken)
    }

    private fun recordOrder(snapshot: BooksSnapshot, action: BooksAction.Order, nowMs: Long): BooksOutcome {
        if (action.amount <= 0) return BooksOutcome(snapshot, "I need the sale amount.")
        if (action.advance < 0 || action.advance > action.amount) {
            return BooksOutcome(snapshot, "The advance can't be more than the price. Nothing was saved.")
        }
        val item = titleParty(action.item).ifBlank { return BooksOutcome(snapshot, "I need the item.") }
        val customer = titleParty(action.customer).ifBlank { "Walk-in" }
        val order = FurnitureOrder(
            id = ids(),
            customer = customer,
            item = item,
            amount = action.amount,
            advance = action.advance,
            status = OrderStatus.ORDERED,
            atEpochMs = nowMs,
        )
        var money = snapshot.money
        if (action.advance > 0) {
            money = money + MoneyEntry(
                id = ids(),
                direction = MoneyDirection.IN,
                scope = MoneyScope.BUSINESS,
                amount = action.advance,
                party = customer,
                note = "Advance for $item",
                atEpochMs = nowMs,
            )
        }
        val balance = orderBalance(action.amount, action.advance)
        val spoken = "Saved on this phone. $item sold to $customer for ${formatRupees(action.amount)}, " +
            "advance ${formatRupees(action.advance)}, balance ${formatRupees(balance)}. " +
            (if (action.advance > 0) "The advance is money in. " else "") +
            "I did not message anyone."
        return BooksOutcome(snapshot.copy(orders = snapshot.orders + order, money = money), spoken)
    }

    private fun recordLabour(snapshot: BooksSnapshot, action: BooksAction.Labour, nowMs: Long): BooksOutcome {
        if (action.amount <= 0) return BooksOutcome(snapshot, "I need the labour amount.")
        val worker = titleParty(action.worker).ifBlank { return BooksOutcome(snapshot, "I need the worker's name.") }
        val entry = LabourEntry(
            id = ids(),
            worker = worker,
            dayEpochMs = action.dayEpochMs,
            amount = action.amount,
            paid = action.paid,
        )
        var money = snapshot.money
        if (action.paid) {
            money = money + MoneyEntry(
                id = ids(),
                direction = MoneyDirection.OUT,
                scope = MoneyScope.BUSINESS,
                amount = action.amount,
                party = worker,
                note = "Labour $worker",
                atEpochMs = nowMs,
            )
        }
        val state = if (action.paid) "paid" else "due"
        val spoken = "Saved on this phone. $worker, labour ${formatRupees(action.amount)} $state. " +
            "I did not message $worker."
        return BooksOutcome(snapshot.copy(labour = snapshot.labour + entry, money = money), spoken)
    }

    private fun recordMaterial(snapshot: BooksSnapshot, action: BooksAction.Material): BooksOutcome {
        if (action.item.isBlank()) return BooksOutcome(snapshot, "I need the material.")
        if (action.qty < 0 || action.reorderLevel < 0) {
            return BooksOutcome(snapshot, "Quantity can't be negative. Nothing was saved.")
        }
        val item = titleParty(action.item)
        val existing = snapshot.materials.firstOrNull { namesMatch(it.item, item) }
        val stock = MaterialStock(
            id = existing?.id ?: ids(),
            item = item,
            qty = action.qty,
            unit = action.unit.ifBlank { existing?.unit ?: "pcs" },
            supplier = titleParty(action.supplier).ifBlank { existing?.supplier.orEmpty() },
            reorderLevel = action.reorderLevel,
        )
        val materials = if (existing == null) {
            snapshot.materials + stock
        } else {
            snapshot.materials.map { if (it.id == existing.id) stock else it }
        }
        val reminder = reorderReminder(stock)
        val spoken = buildString {
            append("Saved on this phone. $item, ${formatQty(stock.qty)}")
            if (stock.unit.isNotBlank()) append(" ${stock.unit}")
            append(", reorder at ${formatQty(stock.reorderLevel)}")
            if (stock.supplier.isNotBlank()) append(", supplier ${stock.supplier}")
            append(". ")
            if (reminder != null) {
                append("Stock is at or below the reorder level. This is a reminder only. I will not contact the supplier.")
            } else {
                append("Stock is above the reorder level.")
            }
        }
        return BooksOutcome(
            snapshot = snapshot.copy(materials = materials),
            spoken = spoken,
            reorderReminders = listOfNotNull(reminder),
            clearReorderFor = if (reminder == null) listOf(item) else emptyList(),
        )
    }

    private fun recordVendor(snapshot: BooksSnapshot, action: BooksAction.VendorDue): BooksOutcome {
        if (action.amount <= 0) return BooksOutcome(snapshot, "I need the amount you owe.")
        val vendor = titleParty(action.vendor).ifBlank { return BooksOutcome(snapshot, "I need the supplier's name.") }
        val due = VendorDue(ids(), vendor, action.amount, action.dueEpochMs, paid = false)
        val whenDue = action.dueEpochMs?.let { " due on ${formatDueDate(it, zone)}" }.orEmpty()
        val spoken = "Saved on this phone. You owe $vendor ${formatRupees(action.amount)}$whenDue. " +
            "I will not pay them or message them."
        return BooksOutcome(snapshot.copy(vendorDues = snapshot.vendorDues + due), spoken)
    }

    private fun recordReceivable(snapshot: BooksSnapshot, action: BooksAction.Receivable): BooksOutcome {
        if (action.amount <= 0) return BooksOutcome(snapshot, "I need the amount they owe.")
        val customer = titleParty(action.customer).ifBlank { return BooksOutcome(snapshot, "I need who owes you.") }
        val row = Receivable(ids(), customer, action.amount, settled = false)
        val spoken = "Saved on this phone. $customer owes you ${formatRupees(action.amount)}. I did not message $customer."
        return BooksOutcome(snapshot.copy(receivables = snapshot.receivables + row), spoken)
    }

    private fun recordDebt(snapshot: BooksSnapshot, action: BooksAction.OwnerDebt): BooksOutcome {
        if (action.amount <= 0) return BooksOutcome(snapshot, "I need the debt amount.")
        val lender = titleParty(action.lender).ifBlank { return BooksOutcome(snapshot, "I need who you owe.") }
        val debt = OwnerDebt(ids(), lender, action.amount, action.dueEpochMs)
        val whenDue = action.dueEpochMs?.let { " due on ${formatDueDate(it, zone)}" }.orEmpty()
        val spoken = "Saved on this phone. Your debt to $lender is ${formatRupees(action.amount)}$whenDue. " +
            "I will not pay it."
        return BooksOutcome(snapshot.copy(debts = snapshot.debts + debt), spoken)
    }

    private fun recordBill(snapshot: BooksSnapshot, action: BooksAction.Bill): BooksOutcome {
        if (action.amount <= 0) return BooksOutcome(snapshot, "I need the bill amount.")
        val label = titleParty(action.label).ifBlank { action.kind.label }
        val bill = Bill(ids(), action.kind, label, action.amount, action.dueEpochMs, paid = false)
        val whenDue = action.dueEpochMs?.let { ", due on ${formatDueDate(it, zone)}" }.orEmpty()
        val spoken = "Saved on this phone. $label bill ${formatRupees(action.amount)}$whenDue. I will not pay it."
        return BooksOutcome(snapshot.copy(bills = snapshot.bills + bill), spoken)
    }

    private fun markBillPaid(snapshot: BooksSnapshot, action: BooksAction.MarkBillPaid, nowMs: Long): BooksOutcome {
        val index = snapshot.bills.indexOfLast { bill ->
            !bill.paid && (action.id == bill.id || (action.kind != null && bill.kind == action.kind))
        }
        if (index < 0) {
            val name = action.kind?.label ?: "that"
            return BooksOutcome(snapshot, "I don't have an unpaid $name bill on this phone.")
        }
        val bill = snapshot.bills[index].copy(paid = true)
        val bills = snapshot.bills.mapIndexed { i, row -> if (i == index) bill else row }
        val money = snapshot.money + MoneyEntry(
            id = ids(),
            direction = MoneyDirection.OUT,
            scope = MoneyScope.BUSINESS,
            amount = bill.amount,
            party = bill.label,
            note = "${bill.label} bill",
            atEpochMs = nowMs,
        )
        val spoken = "Saved on this phone. ${bill.label} bill ${formatRupees(bill.amount)} marked paid. " +
            "I did not pay it myself."
        return BooksOutcome(snapshot.copy(bills = bills, money = money), spoken)
    }

    private fun markVendorPaid(snapshot: BooksSnapshot, action: BooksAction.MarkVendorPaid, nowMs: Long): BooksOutcome {
        val due = snapshot.vendorDues.firstOrNull { it.id == action.id && !it.paid && it.amount > 0 }
            ?: return BooksOutcome(snapshot, "That supplier bill is already clear.")
        val dues = snapshot.vendorDues.map { if (it.id == due.id) it.copy(amount = 0, paid = true) else it }
        val money = snapshot.money + MoneyEntry(
            id = ids(),
            direction = MoneyDirection.OUT,
            scope = MoneyScope.BUSINESS,
            amount = due.amount,
            party = due.vendor,
            note = "Vendor payment ${due.vendor}",
            atEpochMs = nowMs,
        )
        val spoken = "Saved on this phone. ${due.vendor}'s ${formatRupees(due.amount)} marked paid. " +
            "I did not send the money."
        return BooksOutcome(snapshot.copy(vendorDues = dues, money = money), spoken)
    }

    private fun settleReceivable(snapshot: BooksSnapshot, action: BooksAction.SettleReceivable, nowMs: Long): BooksOutcome {
        val row = snapshot.receivables.firstOrNull { it.id == action.id && !it.settled && it.amount > 0 }
            ?: return BooksOutcome(snapshot, "That pending amount is already clear.")
        val rows = snapshot.receivables.map { if (it.id == row.id) it.copy(amount = 0, settled = true) else it }
        val money = snapshot.money + MoneyEntry(
            id = ids(),
            direction = MoneyDirection.IN,
            scope = MoneyScope.BUSINESS,
            amount = row.amount,
            party = row.customer,
            note = "Collected from ${row.customer}",
            atEpochMs = nowMs,
        )
        val spoken = "Saved on this phone. Collected ${formatRupees(row.amount)} from ${row.customer}."
        return BooksOutcome(snapshot.copy(receivables = rows, money = money), spoken)
    }

    private fun setOrderStatus(snapshot: BooksSnapshot, action: BooksAction.SetOrderStatus, nowMs: Long): BooksOutcome {
        val order = snapshot.orders.firstOrNull { it.id == action.id }
            ?: return BooksOutcome(snapshot, "I couldn't find that order.")
        if (order.status == action.status) return BooksOutcome(snapshot, "${order.item} is already ${order.status.label}.")
        var updated = order.copy(status = action.status)
        var money = snapshot.money
        if (action.status == OrderStatus.PAID) {
            val balance = orderBalance(order.amount, order.advance)
            if (balance > 0) {
                money = money + MoneyEntry(
                    id = ids(),
                    direction = MoneyDirection.IN,
                    scope = MoneyScope.BUSINESS,
                    amount = balance,
                    party = order.customer,
                    note = "Balance for ${order.item}",
                    atEpochMs = nowMs,
                )
                updated = updated.copy(advance = order.amount)
            }
        }
        val orders = snapshot.orders.map { if (it.id == order.id) updated else it }
        val spoken = if (action.status == OrderStatus.PAID) {
            "Saved on this phone. ${order.item} for ${order.customer} is paid. I did not message them."
        } else {
            "Saved on this phone. ${order.item} is now ${action.status.label}."
        }
        return BooksOutcome(snapshot.copy(orders = orders, money = money), spoken)
    }

    private fun recordPurchaseOrder(snapshot: BooksSnapshot, action: BooksAction.PurchaseOrder, nowMs: Long): BooksOutcome {
        val supplier = titleParty(action.supplier).ifBlank {
            return BooksOutcome(snapshot, "I need the supplier. Nothing was saved.")
        }
        val lines = action.lines.trim()
        if (lines.isEmpty()) return BooksOutcome(snapshot, "I need the materials. Nothing was saved.")
        val order = PurchaseOrderRecord(
            id = ids(),
            supplier = supplier,
            requestedBy = titleParty(action.requestedBy).ifBlank { "Labour" },
            lines = lines,
            atEpochMs = nowMs,
        )
        val spoken = "Saved on this phone. Order for $supplier: $lines. No price was added."
        return BooksOutcome(snapshot.copy(purchaseOrders = snapshot.purchaseOrders + order), spoken)
    }

    private fun recordSupplierBill(snapshot: BooksSnapshot, action: BooksAction.SupplierBill, nowMs: Long): BooksOutcome {
        if (action.billAmount <= 0) return BooksOutcome(snapshot, "I need the bill amount. Nothing was saved.")
        val supplier = titleParty(action.supplier).ifBlank {
            return BooksOutcome(snapshot, "I need the supplier. Nothing was saved.")
        }
        val advance = recordedAdvanceRupees(snapshot, supplier)
        val applied = minOf(advance, action.billAmount)
        val payable = payableRupees(action.billAmount, applied)
        val bill = SupplierBillRecord(
            id = ids(),
            supplier = supplier,
            billAmount = action.billAmount,
            advance = applied,
            payable = payable,
            atEpochMs = nowMs,
        )
        val dues = if (payable > 0) {
            snapshot.vendorDues + VendorDue(
                id = ids(),
                vendor = supplier,
                amount = payable,
                dueEpochMs = null,
                paid = false,
            )
        } else {
            snapshot.vendorDues
        }
        val advanceLine = if (applied > 0) {
            "Advance already recorded ${formatRupees(applied)}. "
        } else {
            "No advance is recorded for $supplier. "
        }
        val spoken = "Saved on this phone. Bill from $supplier is ${formatRupees(action.billAmount)}. " +
            advanceLine +
            "Pay ${formatRupees(payable)}. I did not pay, and I did not send UPI or a payment."
        return BooksOutcome(
            snapshot.copy(supplierBills = snapshot.supplierBills + bill, vendorDues = dues),
            spoken,
        )
    }

    private data class Settlement(val snapshot: BooksSnapshot, val note: String)

    private fun settleOutgoing(snapshot: BooksSnapshot, party: String, payment: Long): Settlement {
        val vendorIndex = snapshot.vendorDues.indexOfFirst {
            !it.paid && it.amount > 0 && namesMatch(it.vendor, party)
        }
        if (vendorIndex >= 0) {
            val due = snapshot.vendorDues[vendorIndex]
            val left = (due.amount - payment).coerceAtLeast(0)
            val updated = due.copy(amount = left, paid = left == 0L)
            val dues = snapshot.vendorDues.mapIndexed { index, row -> if (index == vendorIndex) updated else row }
            val note = if (left == 0L) {
                "${due.vendor}'s due is cleared."
            } else {
                "${due.vendor} still has ${formatRupees(left)} due."
            } + if (payment > due.amount) " The extra is only an expense." else ""
            return Settlement(snapshot.copy(vendorDues = dues), note)
        }
        val billIndex = snapshot.bills.indexOfFirst {
            !it.paid && it.amount > 0 && (namesMatch(it.label, party) || namesMatch(it.kind.label, party))
        }
        if (billIndex >= 0) {
            val bill = snapshot.bills[billIndex]
            val left = (bill.amount - payment).coerceAtLeast(0)
            val updated = bill.copy(amount = left, paid = left == 0L)
            val bills = snapshot.bills.mapIndexed { index, row -> if (index == billIndex) updated else row }
            val note = if (left == 0L) {
                "${bill.label} is marked paid."
            } else {
                "${bill.label} still has ${formatRupees(left)} unpaid."
            }
            return Settlement(snapshot.copy(bills = bills), note)
        }
        val debtIndex = snapshot.debts.indexOfFirst { it.balance > 0 && namesMatch(it.lender, party) }
        if (debtIndex >= 0) {
            val debt = snapshot.debts[debtIndex]
            val left = (debt.balance - payment).coerceAtLeast(0)
            val debts = snapshot.debts.mapIndexed { index, row ->
                if (index == debtIndex) row.copy(balance = left) else row
            }
            val note = if (left == 0L) "Your debt to ${debt.lender} is cleared." else "You still owe ${debt.lender} ${formatRupees(left)}."
            return Settlement(snapshot.copy(debts = debts), note)
        }
        return Settlement(snapshot, "")
    }

    private fun settleIncoming(snapshot: BooksSnapshot, party: String, payment: Long): Settlement {
        val receivableIndex = snapshot.receivables.indexOfFirst {
            !it.settled && it.amount > 0 && namesMatch(it.customer, party)
        }
        if (receivableIndex >= 0) {
            val row = snapshot.receivables[receivableIndex]
            val left = (row.amount - payment).coerceAtLeast(0)
            val updated = row.copy(amount = left, settled = left == 0L)
            val rows = snapshot.receivables.mapIndexed { index, item -> if (index == receivableIndex) updated else item }
            val note = if (left == 0L) "${row.customer}'s pending is cleared." else "${row.customer} still owes ${formatRupees(left)}."
            return Settlement(snapshot.copy(receivables = rows), note)
        }
        val orderIndex = snapshot.orders.indexOfFirst {
            it.status != OrderStatus.CANCELLED &&
                it.status != OrderStatus.PAID &&
                it.status != OrderStatus.QUOTE &&
                orderBalance(it.amount, it.advance) > 0 &&
                namesMatch(it.customer, party)
        }
        if (orderIndex >= 0) {
            val order = snapshot.orders[orderIndex]
            val room = orderBalance(order.amount, order.advance)
            val applied = minOf(payment, room)
            val updated = order.copy(advance = order.advance + applied)
            val orders = snapshot.orders.mapIndexed { index, item -> if (index == orderIndex) updated else item }
            val left = room - applied
            val note = if (left == 0L) {
                "${order.customer}'s balance on ${order.item} is cleared."
            } else {
                "${order.customer} still has ${formatRupees(left)} balance on ${order.item}."
            }
            return Settlement(snapshot.copy(orders = orders), note)
        }
        return Settlement(snapshot, "")
    }
}
