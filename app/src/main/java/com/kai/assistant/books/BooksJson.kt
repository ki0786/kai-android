package com.kai.assistant.books

import org.json.JSONArray
import org.json.JSONObject

fun BooksSnapshot.toJson(): JSONObject = JSONObject()
    .put("version", 1)
    .put("money", money.toJson { it.toJson() })
    .put("orders", orders.toJson { it.toJson() })
    .put("labour", labour.toJson { it.toJson() })
    .put("materials", materials.toJson { it.toJson() })
    .put("vendorDues", vendorDues.toJson { it.toJson() })
    .put("receivables", receivables.toJson { it.toJson() })
    .put("debts", debts.toJson { it.toJson() })
    .put("bills", bills.toJson { it.toJson() })

fun booksSnapshotFromJson(raw: String): BooksSnapshot {
    val json = JSONObject(raw)
    return BooksSnapshot(
        money = json.optJSONArray("money").toList { moneyFromJson(it) },
        orders = json.optJSONArray("orders").toList { orderFromJson(it) },
        labour = json.optJSONArray("labour").toList { labourFromJson(it) },
        materials = json.optJSONArray("materials").toList { materialFromJson(it) },
        vendorDues = json.optJSONArray("vendorDues").toList { vendorFromJson(it) },
        receivables = json.optJSONArray("receivables").toList { receivableFromJson(it) },
        debts = json.optJSONArray("debts").toList { debtFromJson(it) },
        bills = json.optJSONArray("bills").toList { billFromJson(it) },
    )
}

private fun MoneyEntry.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("direction", direction.name)
    .put("scope", scope.name)
    .put("amount", amount)
    .put("party", party)
    .put("note", note)
    .put("at", atEpochMs)

private fun moneyFromJson(json: JSONObject): MoneyEntry? {
    val id = json.optString("id")
    if (id.isBlank()) return null
    val direction = enumOrNull<MoneyDirection>(json.optString("direction")) ?: return null
    val scope = enumOrNull<MoneyScope>(json.optString("scope")) ?: MoneyScope.BUSINESS
    return MoneyEntry(id, direction, scope, json.optLong("amount"), json.optString("party"), json.optString("note"), json.optLong("at"))
}

private fun FurnitureOrder.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("customer", customer)
    .put("item", item)
    .put("amount", amount)
    .put("advance", advance)
    .put("status", status.name)
    .put("at", atEpochMs)

private fun orderFromJson(json: JSONObject): FurnitureOrder? {
    val id = json.optString("id")
    if (id.isBlank()) return null
    val status = enumOrNull<OrderStatus>(json.optString("status")) ?: OrderStatus.ORDERED
    return FurnitureOrder(
        id = id,
        customer = json.optString("customer"),
        item = json.optString("item"),
        amount = json.optLong("amount"),
        advance = json.optLong("advance"),
        status = status,
        atEpochMs = json.optLong("at"),
    )
}

private fun LabourEntry.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("worker", worker)
    .put("day", dayEpochMs)
    .put("amount", amount)
    .put("paid", paid)

private fun labourFromJson(json: JSONObject): LabourEntry? {
    val id = json.optString("id")
    if (id.isBlank()) return null
    return LabourEntry(id, json.optString("worker"), json.optLong("day"), json.optLong("amount"), json.optBoolean("paid"))
}

private fun MaterialStock.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("item", item)
    .put("qty", qty)
    .put("unit", unit)
    .put("supplier", supplier)
    .put("reorder", reorderLevel)

private fun materialFromJson(json: JSONObject): MaterialStock? {
    val id = json.optString("id")
    if (id.isBlank()) return null
    return MaterialStock(
        id = id,
        item = json.optString("item"),
        qty = json.optDouble("qty"),
        unit = json.optString("unit"),
        supplier = json.optString("supplier"),
        reorderLevel = json.optDouble("reorder"),
    )
}

private fun VendorDue.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("vendor", vendor)
    .put("amount", amount)
    .put("due", dueEpochMs ?: JSONObject.NULL)
    .put("paid", paid)

private fun vendorFromJson(json: JSONObject): VendorDue? {
    val id = json.optString("id")
    if (id.isBlank()) return null
    val due = if (json.isNull("due")) null else json.optLong("due")
    return VendorDue(id, json.optString("vendor"), json.optLong("amount"), due, json.optBoolean("paid"))
}

private fun Receivable.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("customer", customer)
    .put("amount", amount)
    .put("settled", settled)

private fun receivableFromJson(json: JSONObject): Receivable? {
    val id = json.optString("id")
    if (id.isBlank()) return null
    return Receivable(id, json.optString("customer"), json.optLong("amount"), json.optBoolean("settled"))
}

private fun OwnerDebt.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("lender", lender)
    .put("balance", balance)
    .put("due", dueEpochMs ?: JSONObject.NULL)

private fun debtFromJson(json: JSONObject): OwnerDebt? {
    val id = json.optString("id")
    if (id.isBlank()) return null
    val due = if (json.isNull("due")) null else json.optLong("due")
    return OwnerDebt(id, json.optString("lender"), json.optLong("balance"), due)
}

private fun Bill.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("kind", kind.name)
    .put("label", label)
    .put("amount", amount)
    .put("due", dueEpochMs)
    .put("paid", paid)

private fun billFromJson(json: JSONObject): Bill? {
    val id = json.optString("id")
    if (id.isBlank()) return null
    val kind = enumOrNull<BillKind>(json.optString("kind")) ?: return null
    return Bill(id, kind, json.optString("label"), json.optLong("amount"), json.optLong("due"), json.optBoolean("paid"))
}

private inline fun <T> List<T>.toJson(encode: (T) -> JSONObject): JSONArray {
    val array = JSONArray()
    forEach { array.put(encode(it)) }
    return array
}

private inline fun <T> JSONArray?.toList(decode: (JSONObject) -> T?): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(decode) }
}

private inline fun <reified T : Enum<T>> enumOrNull(raw: String): T? =
    enumValues<T>().firstOrNull { it.name == raw }
