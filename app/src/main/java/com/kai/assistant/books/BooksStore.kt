package com.kai.assistant.books

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Sagar Furniture's books, on this phone only.
 * The file is in the no-backup folder, so it is not copied to cloud backup.
 * There are no API keys here. Saving an entry does not pay anyone or message a supplier.
 */
class BooksStore internal constructor(
    private val file: File,
    private val engine: BooksEngine = BooksEngine(),
) {
    private val lock = Any()
    private val _books = MutableStateFlow(load())
    val books: StateFlow<BooksSnapshot> = _books.asStateFlow()

    fun apply(action: BooksAction, nowMs: Long = System.currentTimeMillis()): BooksOutcome = synchronized(lock) {
        val outcome = engine.apply(_books.value, action, nowMs)
        save(trim(outcome.snapshot))
        outcome.copy(snapshot = _books.value)
    }

    private fun trim(snapshot: BooksSnapshot) = snapshot.copy(
        money = snapshot.money.takeLast(MAX_EACH),
        orders = snapshot.orders.takeLast(MAX_EACH),
        labour = snapshot.labour.takeLast(MAX_EACH),
        materials = snapshot.materials.takeLast(MAX_EACH),
        vendorDues = snapshot.vendorDues.takeLast(MAX_EACH),
        receivables = snapshot.receivables.takeLast(MAX_EACH),
        debts = snapshot.debts.takeLast(MAX_EACH),
        bills = snapshot.bills.takeLast(MAX_EACH),
        purchaseOrders = snapshot.purchaseOrders.takeLast(MAX_EACH),
        supplierBills = snapshot.supplierBills.takeLast(MAX_EACH),
    )

    private fun save(snapshot: BooksSnapshot) {
        _books.value = snapshot
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(snapshot.toJson().toString())
            if (!temp.renameTo(file)) {
                file.writeText(snapshot.toJson().toString())
                temp.delete()
            }
        }
    }

    private fun load(): BooksSnapshot {
        if (!file.exists()) return BooksSnapshot()
        return runCatching { booksSnapshotFromJson(file.readText()) }.getOrDefault(BooksSnapshot())
    }

    companion object {
        private const val MAX_EACH = 1000
        private const val FILE_NAME = "kai_books.json"

        @Volatile private var instance: BooksStore? = null

        fun get(context: Context): BooksStore =
            instance ?: synchronized(this) {
                instance ?: BooksStore(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
                    .also { instance = it }
            }
    }
}
