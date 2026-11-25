package com.kai.assistant.business

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/** Where enquiries are kept. The engine only needs this much, so tests use memory. */
interface EnquiryRepository {
    fun all(): List<Enquiry>
    fun get(chatKey: String): Enquiry?
    fun put(enquiry: Enquiry)

    /** Applies [change] to the stored chat under the repository's lock. No-op when it is unknown. */
    fun update(chatKey: String, change: (Enquiry) -> Enquiry): Enquiry?
}

class InMemoryEnquiryRepository : EnquiryRepository {
    private val items = LinkedHashMap<String, Enquiry>()

    @Synchronized override fun all(): List<Enquiry> = items.values.toList()

    @Synchronized override fun get(chatKey: String): Enquiry? = items[chatKey]

    @Synchronized override fun put(enquiry: Enquiry) {
        items[enquiry.chatKey] = enquiry
    }

    @Synchronized override fun update(chatKey: String, change: (Enquiry) -> Enquiry): Enquiry? {
        val current = items[chatKey] ?: return null
        return change(current).also { items[chatKey] = it }
    }
}

/**
 * Enquiries on this phone. The file sits in the no-backup folder so customer names
 * and messages are not copied to cloud backup.
 */
class EnquiryStore internal constructor(private val file: File) : EnquiryRepository {
    private val lock = Any()
    private val _enquiries = MutableStateFlow(load())
    val enquiries: StateFlow<List<Enquiry>> = _enquiries.asStateFlow()

    override fun all(): List<Enquiry> = _enquiries.value

    override fun get(chatKey: String): Enquiry? = _enquiries.value.firstOrNull { it.chatKey == chatKey }

    override fun put(enquiry: Enquiry) = synchronized(lock) {
        val others = _enquiries.value.filterNot { it.chatKey == enquiry.chatKey }
        save(trim(others + enquiry))
    }

    override fun update(chatKey: String, change: (Enquiry) -> Enquiry): Enquiry? = synchronized(lock) {
        val current = _enquiries.value.firstOrNull { it.chatKey == chatKey } ?: return null
        val changed = change(current)
        save(_enquiries.value.map { if (it.chatKey == chatKey) changed else it })
        changed
    }

    fun delete(chatKey: String) = synchronized(lock) {
        save(_enquiries.value.filterNot { it.chatKey == chatKey })
    }

    fun deleteAll() = synchronized(lock) { save(emptyList()) }

    private fun trim(items: List<Enquiry>): List<Enquiry> {
        if (items.size <= MAX_ENQUIRIES) return items
        return items.sortedByDescending { it.lastCustomerAtMs }.take(MAX_ENQUIRIES)
    }

    private fun save(items: List<Enquiry>) {
        _enquiries.value = items
        runCatching {
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(file)) {
                file.writeText(array.toString())
                temp.delete()
            }
        }
    }

    private fun load(): List<Enquiry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { Enquiry.fromJson(it) }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val MAX_ENQUIRIES = 300
        private const val FILE_NAME = "kai_enquiries.json"

        @Volatile private var instance: EnquiryStore? = null

        fun get(context: Context): EnquiryStore =
            instance ?: synchronized(this) {
                instance ?: EnquiryStore(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
                    .also { instance = it }
            }
    }
}
