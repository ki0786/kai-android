package com.kai.assistant.books

/** Cash the owner recorded. Amounts are whole rupees. */
enum class MoneyDirection { IN, OUT }

enum class MoneyScope { BUSINESS, PERSONAL }

data class MoneyEntry(
    val id: String,
    val direction: MoneyDirection,
    val scope: MoneyScope,
    val amount: Long,
    val party: String,
    val note: String,
    val atEpochMs: Long,
)

enum class OrderStatus(val label: String) {
    QUOTE("Quote"),
    ORDERED("Ordered"),
    IN_PRODUCTION("In production"),
    DELIVERED("Delivered"),
    PAID("Paid"),
    CANCELLED("Cancelled"),
}

data class FurnitureOrder(
    val id: String,
    val customer: String,
    val item: String,
    val amount: Long,
    val advance: Long,
    val status: OrderStatus,
    val atEpochMs: Long,
)

data class LabourEntry(
    val id: String,
    val worker: String,
    val dayEpochMs: Long,
    val amount: Long,
    val paid: Boolean,
)

data class MaterialStock(
    val id: String,
    val item: String,
    val qty: Double,
    val unit: String,
    val supplier: String,
    val reorderLevel: Double,
)

data class VendorDue(
    val id: String,
    val vendor: String,
    val amount: Long,
    val dueEpochMs: Long?,
    val paid: Boolean,
)

data class Receivable(
    val id: String,
    val customer: String,
    val amount: Long,
    val settled: Boolean,
)

data class OwnerDebt(
    val id: String,
    val lender: String,
    val balance: Long,
    val dueEpochMs: Long?,
)

enum class BillKind(val label: String) {
    RENT("Rent"),
    ELECTRICITY("Electricity"),
    WATER("Water"),
    OTHER("Other"),
}

data class Bill(
    val id: String,
    val kind: BillKind,
    val label: String,
    val amount: Long,
    val dueEpochMs: Long?,
    val paid: Boolean,
)

/** A material order KAI sent to a supplier the owner already tagged. No price is stored. */
data class PurchaseOrderRecord(
    val id: String,
    val supplier: String,
    val requestedBy: String,
    val lines: String,
    val atEpochMs: Long,
)

/** A supplier's total, the advance already in the books, and what is still unpaid. */
data class SupplierBillRecord(
    val id: String,
    val supplier: String,
    val billAmount: Long,
    val advance: Long,
    val payable: Long,
    val atEpochMs: Long,
)

data class BooksSnapshot(
    val money: List<MoneyEntry> = emptyList(),
    val orders: List<FurnitureOrder> = emptyList(),
    val labour: List<LabourEntry> = emptyList(),
    val materials: List<MaterialStock> = emptyList(),
    val vendorDues: List<VendorDue> = emptyList(),
    val receivables: List<Receivable> = emptyList(),
    val debts: List<OwnerDebt> = emptyList(),
    val bills: List<Bill> = emptyList(),
    val purchaseOrders: List<PurchaseOrderRecord> = emptyList(),
    val supplierBills: List<SupplierBillRecord> = emptyList(),
)

enum class SummaryFocus { ALL, OWED_TO_ME, I_OWE, SALES, CASH_TODAY, CASH_WEEK, CASH_MONTH }

enum class DueState { PAID, UPCOMING, DUE_TODAY, OVERDUE }

/** A stock line at or below its reorder level. KAI only reminds; it never contacts the supplier. */
data class ReorderReminder(
    val item: String,
    val supplier: String,
    val qty: Double,
    val unit: String,
    val reorderLevel: Double,
)

sealed class BooksAction {
    data class Money(
        val direction: MoneyDirection,
        val scope: MoneyScope,
        val amount: Long,
        val party: String,
        /** When this contains "advance", the payment is a supplier advance, not a bill settlement. */
        val note: String = "",
    ) : BooksAction()

    data class Order(
        val customer: String,
        val item: String,
        val amount: Long,
        val advance: Long,
    ) : BooksAction()

    data class Labour(
        val worker: String,
        val dayEpochMs: Long,
        val amount: Long,
        val paid: Boolean,
    ) : BooksAction()

    data class Material(
        val item: String,
        val qty: Double,
        val unit: String,
        val supplier: String,
        val reorderLevel: Double,
    ) : BooksAction()

    data class VendorDue(
        val vendor: String,
        val amount: Long,
        val dueEpochMs: Long?,
    ) : BooksAction()

    data class Receivable(
        val customer: String,
        val amount: Long,
    ) : BooksAction()

    data class OwnerDebt(
        val lender: String,
        val amount: Long,
        val dueEpochMs: Long?,
    ) : BooksAction()

    data class Bill(
        val kind: BillKind,
        val label: String,
        val amount: Long,
        val dueEpochMs: Long?,
    ) : BooksAction()

    data class MarkBillPaid(
        val kind: BillKind? = null,
        val id: String? = null,
    ) : BooksAction()

    data class MarkVendorPaid(val id: String) : BooksAction()

    data class SettleReceivable(val id: String) : BooksAction()

    data class SetOrderStatus(val id: String, val status: OrderStatus) : BooksAction()

    data class Summary(val focus: SummaryFocus) : BooksAction()

    /** Workshop order. The lines are quantities only — this action has no price. */
    data class PurchaseOrder(
        val supplier: String,
        val requestedBy: String,
        val lines: String,
    ) : BooksAction()

    /** Supplier's stated total. The payable amount is computed from advances already in the books. */
    data class SupplierBill(
        val supplier: String,
        val billAmount: Long,
    ) : BooksAction()
}
