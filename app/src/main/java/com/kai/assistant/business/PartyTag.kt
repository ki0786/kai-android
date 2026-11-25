package com.kai.assistant.business

import android.content.Context
import com.kai.assistant.books.namesMatch
import com.kai.assistant.books.normalizeParty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Who the owner says this WhatsApp Business chat is. Untagged chats are customers. */
enum class PartyRole { LABOUR, SUPPLIER }

data class PartyTag(
    val chatKey: String,
    val displayName: String,
    val role: PartyRole,
    val ownerHandling: Boolean = false,
    val lastSeenAtMs: Long = 0L,
    val lastText: String = "",
    val kaiReplyTimes: List<Long> = emptyList(),
)

interface PartyTagRepository {
    fun all(): List<PartyTag>
    fun find(chatKey: String, displayName: String): PartyTag?
    fun put(tag: PartyTag)
    fun remove(chatKey: String)
}

class InMemoryPartyTags : PartyTagRepository {
    private val items = LinkedHashMap<String, PartyTag>()

    @Synchronized override fun all(): List<PartyTag> = items.values.toList()

    @Synchronized override fun find(chatKey: String, displayName: String): PartyTag? =
        findTag(items.values.toList(), chatKey, displayName)

    @Synchronized override fun put(tag: PartyTag) {
        items.keys.filter { it != tag.chatKey && normalizeParty(items[it]?.displayName.orEmpty()) == normalizeParty(tag.displayName) }
            .toList()
            .forEach { items.remove(it) }
        items[tag.chatKey] = tag
    }

    @Synchronized override fun remove(chatKey: String) {
        items.remove(chatKey)
    }
}

/**
 * Labour and supplier tags on this phone. The file is in the no-backup folder.
 * A chat KAI has not been told about is never treated as labour or a supplier.
 */
class PartyTagStore internal constructor(private val file: File) : PartyTagRepository {
    private val lock = Any()
    private val _tags = MutableStateFlow(load())
    val tags: StateFlow<List<PartyTag>> = _tags.asStateFlow()

    override fun all(): List<PartyTag> = _tags.value

    override fun find(chatKey: String, displayName: String): PartyTag? = findTag(_tags.value, chatKey, displayName)

    override fun put(tag: PartyTag) = synchronized(lock) {
        val withoutSameName = _tags.value.filterNot { existing ->
            existing.chatKey != tag.chatKey &&
                normalizeParty(existing.displayName) == normalizeParty(tag.displayName)
        }
        save(withoutSameName.filterNot { it.chatKey == tag.chatKey } + tag)
    }

    override fun remove(chatKey: String) = synchronized(lock) {
        save(_tags.value.filterNot { it.chatKey == chatKey })
    }

    /** Tags this chat from now on. Messages already on screen are not replayed. */
    fun tag(chatKey: String, displayName: String, role: PartyRole, nowMs: Long = System.currentTimeMillis()) {
        val previous = find(chatKey, displayName)
        put(
            PartyTag(
                chatKey = chatKey,
                displayName = displayName.ifBlank { previous?.displayName.orEmpty() },
                role = role,
                ownerHandling = false,
                lastSeenAtMs = nowMs,
                lastText = "",
                kaiReplyTimes = emptyList(),
            ),
        )
    }

    private fun save(items: List<PartyTag>) {
        _tags.value = items
        runCatching {
            val array = JSONArray()
            items.forEach { tag ->
                array.put(
                    JSONObject()
                        .put("chatKey", tag.chatKey)
                        .put("displayName", tag.displayName)
                        .put("role", tag.role.name)
                        .put("ownerHandling", tag.ownerHandling)
                        .put("lastSeenAtMs", tag.lastSeenAtMs)
                        .put("lastText", tag.lastText)
                        .put("kaiReplyTimes", JSONArray(tag.kaiReplyTimes)),
                )
            }
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(file)) {
                file.writeText(array.toString())
                temp.delete()
            }
        }
    }

    private fun load(): List<PartyTag> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::tagFromJson) }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val FILE_NAME = "kai_party_tags.json"

        @Volatile private var instance: PartyTagStore? = null

        fun get(context: Context): PartyTagStore =
            instance ?: synchronized(this) {
                instance ?: PartyTagStore(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
                    .also { instance = it }
            }
    }
}

internal fun findTag(tags: List<PartyTag>, chatKey: String, displayName: String): PartyTag? {
    tags.firstOrNull { it.chatKey == chatKey }?.let { return it }
    val exact = tags.filter { normalizeParty(it.displayName) == normalizeParty(displayName) && displayName.isNotBlank() }
    return exact.singleOrNull()
}

private fun tagFromJson(json: JSONObject): PartyTag? {
    val chatKey = json.optString("chatKey")
    if (chatKey.isBlank()) return null
    val role = runCatching { PartyRole.valueOf(json.optString("role")) }.getOrNull() ?: return null
    val times = json.optJSONArray("kaiReplyTimes")
    val replyTimes = if (times == null) {
        emptyList()
    } else {
        (0 until times.length()).map { times.optLong(it) }
    }
    return PartyTag(
        chatKey = chatKey,
        displayName = json.optString("displayName"),
        role = role,
        ownerHandling = json.optBoolean("ownerHandling"),
        lastSeenAtMs = json.optLong("lastSeenAtMs"),
        lastText = json.optString("lastText"),
        kaiReplyTimes = replyTimes,
    )
}

/** Digits from a WhatsApp Business chat id. Group ids are not personal jids, so they yield nothing. */
fun whatsAppDigits(chatKey: String): String? {
    val match = Regex("""(?:id:)?(\d{8,15})@(?:s\.whatsapp\.net|c\.us|lid)""").find(chatKey) ?: return null
    return match.groupValues[1]
}

/** True when [saved] is the supplier named on a stock line. A loose extra person is not a match. */
fun supplierTagMatches(savedName: String, stockSupplier: String): Boolean {
    if (savedName.isBlank() || stockSupplier.isBlank()) return false
    return namesMatch(savedName, stockSupplier)
}
