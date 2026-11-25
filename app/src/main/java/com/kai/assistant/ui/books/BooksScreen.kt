package com.kai.assistant.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kai.assistant.books.BillKind
import com.kai.assistant.books.BooksAction
import com.kai.assistant.books.BooksReminders
import com.kai.assistant.books.BooksSnapshot
import com.kai.assistant.books.BooksStore
import com.kai.assistant.books.DueState
import com.kai.assistant.books.MoneyDirection
import com.kai.assistant.books.MoneyScope
import com.kai.assistant.books.OrderStatus
import com.kai.assistant.books.dueOnDayOfMonth
import com.kai.assistant.books.dueState
import com.kai.assistant.books.formatDueDate
import com.kai.assistant.books.formatQty
import com.kai.assistant.books.formatRupees
import com.kai.assistant.books.isLowStock
import com.kai.assistant.books.orderBalance
import com.kai.assistant.books.periodTotals
import com.kai.assistant.books.summarize
import java.time.ZoneId
import java.time.ZonedDateTime

private enum class BooksSection(val label: String) {
    Summary("Summary"),
    Money("Money"),
    Orders("Orders"),
    Labour("Labour"),
    Materials("Materials"),
    Dues("Dues"),
    Bills("Bills"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BooksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { BooksStore.get(context) }
    val snapshot by store.books.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf(BooksSection.Summary) }
    var message by remember { mutableStateOf("") }
    val zone = remember { ZoneId.systemDefault() }
    val now = System.currentTimeMillis()
    val summary = remember(snapshot, now / 60_000) { summarize(snapshot, now, zone) }

    fun save(action: BooksAction) {
        val outcome = store.apply(action)
        BooksReminders.sync(context, outcome)
        message = outcome.spoken
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Business books") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Sagar Furniture. Saved on this phone only. KAI does not pay anyone, order from a supplier, or message them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SummaryGrid(
                sales = summary.salesThisMonth,
                moneyIn = summary.moneyInThisMonth,
                moneyOut = summary.moneyOutThisMonth,
                owedToYou = summary.owedToOwner,
                youOwe = summary.ownerOwes,
            )
            if (summary.lowStock.isNotEmpty()) {
                Text(
                    text = summary.lowStock.joinToString { "${it.item} (${formatQty(it.qty)})" } +
                        " at or below reorder. Reminder only — the supplier is not contacted.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (summary.overdueBills.isNotEmpty()) {
                Text(
                    text = summary.overdueBills.joinToString { it.label } + " overdue.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (message.isNotBlank()) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BooksSection.entries.forEach { item ->
                    FilterChip(
                        selected = section == item,
                        onClick = { section = item },
                        label = { Text(item.label) },
                    )
                }
            }
            when (section) {
                BooksSection.Summary -> SummaryDetail(snapshot, zone, now)
                BooksSection.Money -> MoneySection(snapshot, zone, now, ::save)
                BooksSection.Orders -> OrdersSection(snapshot, ::save)
                BooksSection.Labour -> LabourSection(snapshot, zone, ::save)
                BooksSection.Materials -> MaterialsSection(snapshot, ::save)
                BooksSection.Dues -> DuesSection(snapshot, zone, now, ::save)
                BooksSection.Bills -> BillsSection(snapshot, zone, now, ::save)
            }
        }
    }
}

@Composable
private fun SummaryGrid(sales: Long, moneyIn: Long, moneyOut: Long, owedToYou: Long, youOwe: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryTile("Sales", sales, "This month", Modifier.weight(1f))
            SummaryTile("Money in", moneyIn, "This month", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryTile("Money out", moneyOut, "This month", Modifier.weight(1f))
            SummaryTile("Owed to you", owedToYou, "Still open", Modifier.weight(1f))
        }
        SummaryTile(
            label = "You owe",
            amount = youOwe,
            caption = "Suppliers, bills, labour, debt",
            modifier = Modifier.fillMaxWidth(),
            emphasize = youOwe > 0,
        )
    }
}

@Composable
private fun SummaryTile(
    label: String,
    amount: Long,
    caption: String,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            formatRupees(amount),
            style = MaterialTheme.typography.titleLarge,
            color = if (emphasize) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
        )
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SummaryDetail(snapshot: BooksSnapshot, zone: ZoneId, now: Long) {
    val totals = periodTotals(snapshot.money, now, zone)
    Text("Today ${formatRupees(totals.today.moneyIn)} in, ${formatRupees(totals.today.moneyOut)} out.", style = MaterialTheme.typography.bodyLarge)
    Text("This week ${formatRupees(totals.week.moneyIn)} in, ${formatRupees(totals.week.moneyOut)} out.", style = MaterialTheme.typography.bodyLarge)
    Text(
        "Say: I paid 5000 to the wood supplier. Rahul owes me 12000. Electricity bill 2400 due on the 5th. Sold a sofa for 45000 advance 10000.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoneySection(
    snapshot: BooksSnapshot,
    zone: ZoneId,
    now: Long,
    save: (BooksAction) -> Unit,
) {
    val totals = periodTotals(snapshot.money, now, zone)
    Text("Today: in ${formatRupees(totals.today.moneyIn)}, out ${formatRupees(totals.today.moneyOut)}.")
    Text("This week: in ${formatRupees(totals.week.moneyIn)}, out ${formatRupees(totals.week.moneyOut)}.")
    Text("This month: in ${formatRupees(totals.month.moneyIn)}, out ${formatRupees(totals.month.moneyOut)}.")
    Text(
        "Business this month ${formatRupees(totals.monthBusiness.moneyOut)} out. Personal ${formatRupees(totals.monthPersonal.moneyOut)} out.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    var direction by remember { mutableStateOf(MoneyDirection.OUT) }
    var scope by remember { mutableStateOf(MoneyScope.BUSINESS) }
    var amount by remember { mutableStateOf("") }
    var party by remember { mutableStateOf("") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = direction == MoneyDirection.OUT, onClick = { direction = MoneyDirection.OUT }, label = { Text("Money out") })
        FilterChip(selected = direction == MoneyDirection.IN, onClick = { direction = MoneyDirection.IN }, label = { Text("Money in") })
        FilterChip(selected = scope == MoneyScope.BUSINESS, onClick = { scope = MoneyScope.BUSINESS }, label = { Text("Business") })
        FilterChip(selected = scope == MoneyScope.PERSONAL, onClick = { scope = MoneyScope.PERSONAL }, label = { Text("Personal") })
    }
    OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = party, onValueChange = { party = it }, label = { Text("Who or what") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        val rupees = amount.filter { it.isDigit() }.toLongOrNull()
        if (rupees == null || rupees <= 0) return@Button
        save(BooksAction.Money(direction, scope, rupees, party))
        amount = ""
        party = ""
    }) { Text("Save entry") }
    snapshot.money.asReversed().take(20).forEach { entry ->
        val sign = if (entry.direction == MoneyDirection.IN) "In" else "Out"
        Text("$sign ${formatRupees(entry.amount)} · ${entry.party} · ${entry.scope.name.lowercase()}")
    }
}

@Composable
private fun OrdersSection(snapshot: BooksSnapshot, save: (BooksAction) -> Unit) {
    var customer by remember { mutableStateOf("") }
    var item by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var advance by remember { mutableStateOf("") }
    OutlinedTextField(value = customer, onValueChange = { customer = it }, label = { Text("Customer") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = item, onValueChange = { item = it }, label = { Text("Item") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = advance, onValueChange = { advance = it }, label = { Text("Advance") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        val price = amount.filter { it.isDigit() }.toLongOrNull() ?: return@Button
        val paid = advance.filter { it.isDigit() }.toLongOrNull() ?: 0L
        save(BooksAction.Order(customer, item, price, paid))
        customer = ""
        item = ""
        amount = ""
        advance = ""
    }) { Text("Save order") }
    if (snapshot.orders.isEmpty()) {
        Text("No orders yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    snapshot.orders.asReversed().forEach { order ->
        val balance = orderBalance(order.amount, order.advance)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("${order.customer} · ${order.item}", style = MaterialTheme.typography.titleMedium)
            Text("${formatRupees(order.amount)} · advance ${formatRupees(order.advance)} · balance ${formatRupees(balance)} · ${order.status.label}")
            Row {
                if (order.status != OrderStatus.PAID && order.status != OrderStatus.CANCELLED) {
                    TextButton(onClick = { save(BooksAction.SetOrderStatus(order.id, nextStatus(order.status))) }) {
                        Text(nextStatus(order.status).label)
                    }
                    TextButton(onClick = { save(BooksAction.SetOrderStatus(order.id, OrderStatus.PAID)) }) { Text("Mark paid") }
                    TextButton(onClick = { save(BooksAction.SetOrderStatus(order.id, OrderStatus.CANCELLED)) }) { Text("Cancel") }
                }
            }
        }
        HorizontalDivider()
    }
}

private fun nextStatus(status: OrderStatus): OrderStatus = when (status) {
    OrderStatus.QUOTE -> OrderStatus.ORDERED
    OrderStatus.ORDERED -> OrderStatus.IN_PRODUCTION
    OrderStatus.IN_PRODUCTION -> OrderStatus.DELIVERED
    OrderStatus.DELIVERED -> OrderStatus.PAID
    OrderStatus.PAID -> OrderStatus.PAID
    OrderStatus.CANCELLED -> OrderStatus.CANCELLED
}

@Composable
private fun LabourSection(snapshot: BooksSnapshot, zone: ZoneId, save: (BooksAction) -> Unit) {
    var worker by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf(true) }
    OutlinedTextField(value = worker, onValueChange = { worker = it }, label = { Text("Worker") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount") }, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(if (paid) "Paid today" else "Due, not paid yet")
        Switch(checked = paid, onCheckedChange = { paid = it })
    }
    Button(onClick = {
        val rupees = amount.filter { it.isDigit() }.toLongOrNull() ?: return@Button
        val day = ZonedDateTime.now(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        save(BooksAction.Labour(worker, day, rupees, paid))
        worker = ""
        amount = ""
    }) { Text("Save labour") }
    snapshot.labour.asReversed().forEach { entry ->
        val day = formatDueDate(entry.dayEpochMs, zone)
        Text("${entry.worker} · $day · ${formatRupees(entry.amount)} · ${if (entry.paid) "paid" else "due"}")
    }
}

@Composable
private fun MaterialsSection(snapshot: BooksSnapshot, save: (BooksAction) -> Unit) {
    var item by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var supplier by remember { mutableStateOf("") }
    var reorder by remember { mutableStateOf("") }
    OutlinedTextField(value = item, onValueChange = { item = it }, label = { Text("Item") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = qty, onValueChange = { qty = it }, label = { Text("Quantity") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Unit") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = supplier, onValueChange = { supplier = it }, label = { Text("Supplier") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = reorder, onValueChange = { reorder = it }, label = { Text("Reorder level") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        val quantity = qty.toDoubleOrNull() ?: return@Button
        val level = reorder.toDoubleOrNull() ?: 0.0
        save(BooksAction.Material(item, quantity, unit, supplier, level))
        item = ""
        qty = ""
        unit = ""
        supplier = ""
        reorder = ""
    }) { Text("Save stock") }
    snapshot.materials.forEach { material ->
        val low = if (isLowStock(material)) " · reorder" else ""
        val supplierName = material.supplier.ifBlank { "no supplier" }
        Text(
            "${material.item}: ${formatQty(material.qty)} ${material.unit} · $supplierName · reorder ${formatQty(material.reorderLevel)}$low",
            color = if (isLowStock(material)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun DuesSection(
    snapshot: BooksSnapshot,
    zone: ZoneId,
    now: Long,
    save: (BooksAction) -> Unit,
) {
    Text("Vendor payments", style = MaterialTheme.typography.titleMedium)
    VendorForm(zone, save)
    snapshot.vendorDues.asReversed().forEach { due ->
        val state = dueState(due.dueEpochMs, due.paid, now, zone)
        val whenDue = due.dueEpochMs?.let { " · ${formatDueDate(it, zone)}" }.orEmpty()
        Text("${due.vendor} · ${formatRupees(due.amount)}$whenDue · ${state.name.lowercase().replace('_', ' ')}")
        if (!due.paid && due.amount > 0) {
            TextButton(onClick = { save(BooksAction.MarkVendorPaid(due.id)) }) { Text("Mark paid") }
        }
    }
    HorizontalDivider()
    Text("Customer pending", style = MaterialTheme.typography.titleMedium)
    ReceivableForm(save)
    snapshot.orders.filter { orderBalance(it.amount, it.advance) > 0 && it.status != OrderStatus.PAID && it.status != OrderStatus.CANCELLED && it.status != OrderStatus.QUOTE }
        .forEach { order ->
            Text("${order.customer} · ${order.item} balance ${formatRupees(orderBalance(order.amount, order.advance))}")
        }
    snapshot.receivables.filter { !it.settled && it.amount > 0 }.forEach { row ->
        Text("${row.customer} owes ${formatRupees(row.amount)}")
        TextButton(onClick = { save(BooksAction.SettleReceivable(row.id)) }) { Text("Mark collected") }
    }
    HorizontalDivider()
    Text("Your debt", style = MaterialTheme.typography.titleMedium)
    DebtForm(zone, save)
    snapshot.debts.filter { it.balance > 0 }.forEach { debt ->
        val whenDue = debt.dueEpochMs?.let { " · due ${formatDueDate(it, zone)}" }.orEmpty()
        val state = dueState(debt.dueEpochMs, paid = false, now, zone)
        Text("${debt.lender} · ${formatRupees(debt.balance)}$whenDue · ${state.name.lowercase().replace('_', ' ')}")
    }
}

@Composable
private fun VendorForm(zone: ZoneId, save: (BooksAction) -> Unit) {
    var vendor by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }
    OutlinedTextField(value = vendor, onValueChange = { vendor = it }, label = { Text("Supplier") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount due") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = day, onValueChange = { day = it }, label = { Text("Due day, optional") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        val rupees = amount.filter { it.isDigit() }.toLongOrNull() ?: return@Button
        val due = day.filter { it.isDigit() }.toIntOrNull()?.let { dueOnDayOfMonth(it, ZonedDateTime.now(zone)) }
        save(BooksAction.VendorDue(vendor, rupees, due))
        vendor = ""
        amount = ""
        day = ""
    }) { Text("Save vendor due") }
}

@Composable
private fun ReceivableForm(save: (BooksAction) -> Unit) {
    var customer by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    OutlinedTextField(value = customer, onValueChange = { customer = it }, label = { Text("Customer") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount they owe") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        val rupees = amount.filter { it.isDigit() }.toLongOrNull() ?: return@Button
        save(BooksAction.Receivable(customer, rupees))
        customer = ""
        amount = ""
    }) { Text("Save pending") }
}

@Composable
private fun DebtForm(zone: ZoneId, save: (BooksAction) -> Unit) {
    var lender by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }
    OutlinedTextField(value = lender, onValueChange = { lender = it }, label = { Text("Lender") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Balance") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = day, onValueChange = { day = it }, label = { Text("Due day, optional") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        val rupees = amount.filter { it.isDigit() }.toLongOrNull() ?: return@Button
        val due = day.filter { it.isDigit() }.toIntOrNull()?.let { dueOnDayOfMonth(it, ZonedDateTime.now(zone)) }
        save(BooksAction.OwnerDebt(lender, rupees, due))
        lender = ""
        amount = ""
        day = ""
    }) { Text("Save debt") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BillsSection(
    snapshot: BooksSnapshot,
    zone: ZoneId,
    now: Long,
    save: (BooksAction) -> Unit,
) {
    var kind by remember { mutableStateOf(BillKind.ELECTRICITY) }
    var label by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BillKind.entries.forEach { item ->
            FilterChip(selected = kind == item, onClick = { kind = item }, label = { Text(item.label) })
        }
    }
    if (kind == BillKind.OTHER) {
        OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("What bill") }, modifier = Modifier.fillMaxWidth())
    }
    OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = day, onValueChange = { day = it }, label = { Text("Due day") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        val rupees = amount.filter { it.isDigit() }.toLongOrNull() ?: return@Button
        val dueDay = day.filter { it.isDigit() }.toIntOrNull() ?: return@Button
        val due = dueOnDayOfMonth(dueDay, ZonedDateTime.now(zone)) ?: return@Button
        val name = if (kind == BillKind.OTHER) label else kind.label
        save(BooksAction.Bill(kind, name, rupees, due))
        amount = ""
        day = ""
        label = ""
    }) { Text("Save bill") }
    snapshot.bills.asReversed().forEach { bill ->
        val state = dueState(bill.dueEpochMs, bill.paid, now, zone)
        Text(
            "${bill.label} · ${formatRupees(bill.amount)} · ${formatDueDate(bill.dueEpochMs, zone)} · ${state.name.lowercase().replace('_', ' ')}",
            color = if (state == DueState.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
        )
        if (!bill.paid) {
            TextButton(onClick = { save(BooksAction.MarkBillPaid(id = bill.id)) }) { Text("Mark paid") }
        }
    }
}
