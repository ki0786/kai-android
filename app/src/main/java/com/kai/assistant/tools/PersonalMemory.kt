package com.kai.assistant.tools

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MemoryKind { Fact, Project }

enum class RecallMode { Search, List, Projects, Recent }

data class SavedMemory(
    val id: Long,
    val text: String,
    val kind: MemoryKind,
    val updatedAt: Long,
)

data class StoredTurn(
    val at: Long,
    val role: String,
    val text: String,
)

internal sealed class MemoryRequest {
    data class Save(val text: String, val kind: MemoryKind) : MemoryRequest()
    data class Delete(val query: String, val all: Boolean) : MemoryRequest()
    data class Edit(val query: String, val text: String) : MemoryRequest()
    data class Search(val query: String) : MemoryRequest()
    data object ListAll : MemoryRequest()
    data object ListProjects : MemoryRequest()
    data object RecentTalk : MemoryRequest()
}

private val secretPhrases = listOf(
    "password",
    "passcode",
    "passphrase",
    "pin code",
    "my pin",
    "one-time code",
    "one time code",
    "otp",
    "verification code",
    "api key",
    "api token",
    "secret key",
    "access token",
    "private key",
    "credit card",
    "card number",
    "social security",
    "ssn",
    "cvv",
    "cvc",
)

private val stopWords = setOf(
    "the", "a", "an", "about", "what", "did", "say", "said", "my", "i", "you",
    "to", "for", "of", "on", "and", "me", "your", "that", "this", "was", "were",
)

internal fun containsSecret(text: String): Boolean {
    val lower = text.lowercase()
    if (secretPhrases.any { lower.contains(it) }) return true
    if (Regex("""\b\d(?:[ -]?\d){12,18}\b""").containsMatchIn(lower)) return true
    if (Regex("""\bsk-[A-Za-z0-9]{8,}\b""").containsMatchIn(text)) return true
    return false
}

/** Conversation lines are stored only when this is true. Secrets need an explicit remember. */
internal fun shouldPersistTurn(text: String, allowSecret: Boolean): Boolean {
    if (text.isBlank()) return false
    if (containsSecret(text) && !allowSecret) return false
    return true
}

internal fun parseMemoryRequest(raw: String): MemoryRequest? {
    val text = raw.trim().replace(Regex("[?.!,]+$"), "").replace(Regex("""\s+"""), " ").trim()
    if (text.isBlank()) return null
    val lower = text.lowercase()

    if (lower in deleteAllPhrases) return MemoryRequest.Delete(query = "", all = true)
    parseEdit(text, lower)?.let { return it }
    parseDelete(text, lower)?.let { return it }
    if (lower in listPhrases) return MemoryRequest.ListAll
    if (lower in projectPhrases) return MemoryRequest.ListProjects
    if (lower in recentPhrases) return MemoryRequest.RecentTalk
    parseSearch(text, lower)?.let { return it }
    parseNameQuestion(text, lower)?.let { return it }
    return parseSave(text, lower)
}

internal fun matchesQuery(haystack: String, query: String): Boolean {
    val hay = haystack.lowercase()
    val q = query.lowercase().trim()
    if (q.isEmpty()) return false
    if (hay.contains(q)) return true
    val tokens = queryTokens(q)
    if (tokens.isEmpty()) return false
    return tokens.all { hay.contains(it) }
}

internal fun bestMemory(memories: List<SavedMemory>, query: String): SavedMemory? {
    return memories
        .filter { matchesQuery(it.text, query) }
        .maxWithOrNull(compareBy<SavedMemory> { matchScore(it.text, query) }.thenBy { it.updatedAt })
}

internal fun formatRememberReply(text: String, project: Boolean = false): String {
    if (containsSecret(text)) return "I'll remember that. It's saved only on this phone."
    if (project) return "I'll remember that you're working on $text."
    return "I'll remember that $text."
}

internal fun formatMemoryOffReply(): String =
    "Memory is off in Settings, so I didn't save that."

internal fun formatListReply(memories: List<SavedMemory>): String {
    if (memories.isEmpty()) return "I don't have any saved memories yet."
    return memories.joinToString(" ") { memory ->
        val prefix = if (memory.kind == MemoryKind.Project) "Project:" else "I remember:"
        "$prefix ${memory.text}."
    }
}

internal fun formatProjectsReply(memories: List<SavedMemory>): String {
    val projects = memories.filter { it.kind == MemoryKind.Project }
    if (projects.isEmpty()) return "I don't have any projects you asked me to remember."
    return "You're working on " + projects.joinToString(", ") { it.text.trimEnd('.') } + "."
}

internal fun formatRecentReply(turns: List<StoredTurn>): String {
    val lines = turns.filter { it.role == "user" }.takeLast(6)
    if (lines.isEmpty()) return "I don't have a recent conversation on this phone yet."
    return lines.joinToString(" ") { "You said: ${clip(it.text)}." }
}

internal fun formatSearchReply(
    query: String,
    memories: List<SavedMemory>,
    turns: List<StoredTurn>,
): String {
    val remembered = memories.filter { matchesQuery(it.text, query) }.take(3)
    val said = turns.filter { it.role == "user" && matchesQuery(it.text, query) }.takeLast(3)
    if (remembered.isEmpty() && said.isEmpty()) {
        return "I couldn't find anything about $query on this phone."
    }
    val parts = mutableListOf<String>()
    if (said.isNotEmpty()) {
        parts += said.joinToString(" ") { "You said: ${clip(it.text)}." }
    }
    if (remembered.isNotEmpty()) {
        parts += remembered.joinToString(" ") { "I remember: ${it.text}." }
    }
    return parts.joinToString(" ")
}

internal fun formatDeleteReply(query: String, removed: SavedMemory?, all: Boolean, count: Int): String {
    if (all) {
        return if (count == 0) "There were no saved memories to delete." else "I deleted every saved memory."
    }
    if (removed == null) return "I don't have a memory about $query."
    return "I forgot the memory: ${removed.text}."
}

internal fun formatEditReply(query: String, updated: SavedMemory?): String {
    if (updated == null) return "I don't have a memory about $query to edit."
    return "Updated the memory about $query. I now remember: ${updated.text}."
}

private fun parseSave(text: String, lower: String): MemoryRequest.Save? {
    val match = Regex(
        """^(?:please\s+)?remember(?:\s+that|\s+this)?\s+(.+)$""" +
            """|^(?:please\s+)?(?:don't forget|do not forget)(?:\s+that)?\s+(.+)$""",
    ).find(lower) ?: return null
    val captured = match.groups.drop(1).firstOrNull { it != null && it.value.isNotBlank() } ?: return null
    var body = slice(text, lower, captured.range)
    if (body.lowercase().startsWith("to ")) body = body.substring(3).trim()
    body = body.trim()
    if (body.length < 2) return null
    val kind = memoryKind(body)
    val stored = if (kind == MemoryKind.Project) projectText(body) else body
    return MemoryRequest.Save(stored, kind)
}

private fun parseDelete(text: String, lower: String): MemoryRequest.Delete? {
    val match = Regex(
        """^(?:please\s+)?(?:delete|remove|erase)\s+(?:the\s+)?(?:memory|memories|fact|note)(?:\s+about|\s+of|\s+on)?\s+(.+)$""" +
            """|^(?:please\s+)?forget\s+(?:the\s+)?(?:memory|fact|note)(?:\s+about|\s+of)?\s+(.+)$""" +
            """|^(?:please\s+)?forget\s+that\s+(.+)$""",
    ).find(lower) ?: return null
    val captured = match.groups.drop(1).firstOrNull { it != null && it.value.isNotBlank() } ?: return null
    val query = slice(text, lower, captured.range).trim()
    if (query.length < 2) return null
    return MemoryRequest.Delete(query, all = false)
}

private fun parseEdit(text: String, lower: String): MemoryRequest.Edit? {
    val match = Regex(
        """^(?:please\s+)?(?:edit|change|update|correct)\s+(?:the\s+)?(?:memory|fact|note)(?:\s+about)?\s+(.+?)\s+to\s+(.+)$""",
    ).find(lower) ?: return null
    val queryGroup = match.groups[1] ?: return null
    val textGroup = match.groups[2] ?: return null
    val query = slice(text, lower, queryGroup.range).trim()
    val updated = slice(text, lower, textGroup.range).trim()
    if (query.length < 2 || updated.length < 2) return null
    return MemoryRequest.Edit(query, updated)
}

private fun parseSearch(text: String, lower: String): MemoryRequest.Search? {
    val match = Regex(
        """^what did i say about\s+(.+)$""" +
            """|^what did i tell you about\s+(.+)$""" +
            """|^what do you remember about\s+(.+)$""" +
            """|^what have i said about\s+(.+)$""" +
            """|^(?:search|find)(?:\s+my)?\s+(?:memories|memory|chats|chat|conversation)(?:\s+for)?\s+(.+)$""",
    ).find(lower) ?: return null
    val captured = match.groups.drop(1).firstOrNull { it != null && it.value.isNotBlank() } ?: return null
    val query = slice(text, lower, captured.range).trim()
    if (query.length < 2) return null
    return MemoryRequest.Search(query)
}

private fun parseNameQuestion(text: String, lower: String): MemoryRequest.Search? {
    if (lower == "who am i") return MemoryRequest.Search("name")
    val match = Regex("""^what(?:'s| is) my\s+(.+)$""").find(lower) ?: return null
    val captured = match.groups[1] ?: return null
    val query = slice(text, lower, captured.range).trim()
    if (query.length < 2) return null
    return MemoryRequest.Search(query)
}

private fun memoryKind(body: String): MemoryKind {
    val lower = body.lowercase()
    val project = Regex(
        """\b(?:working on|project)\b""",
    ).containsMatchIn(lower)
    return if (project) MemoryKind.Project else MemoryKind.Fact
}

private fun projectText(body: String): String {
    val lower = body.lowercase()
    val working = Regex("""(?:i'm|i am|im)\s+(?:working on|building)\s+(.+)$""").find(lower)
    if (working != null) return slice(body, lower, working.groups[1]!!.range).trim()
    val named = Regex("""(?:the\s+|my\s+)?project\s+(?:is\s+|called\s+)?(.+)$""").find(lower)
    if (named != null) return slice(body, lower, named.groups[1]!!.range).trim()
    return body.trim()
}

private fun slice(text: String, lower: String, range: IntRange): String {
    if (text.length != lower.length) return lower.substring(range)
    return text.substring(range)
}

private fun queryTokens(query: String): List<String> =
    query.lowercase().split(Regex("""\s+""")).filter { it.length > 2 && it !in stopWords }

private fun matchScore(haystack: String, query: String): Int {
    val hay = haystack.lowercase()
    val q = query.lowercase().trim()
    var score = if (hay.contains(q)) 10 else 0
    score += queryTokens(q).count { hay.contains(it) }
    return score
}

private fun clip(text: String): String {
    val clean = text.trim()
    return if (clean.length <= 140) clean else clean.take(137).trimEnd() + "..."
}

private val deleteAllPhrases = setOf(
    "delete all memories",
    "delete my memories",
    "forget everything",
    "forget all memories",
    "erase all memories",
    "clear my memories",
)

private val listPhrases = setOf(
    "what do you remember",
    "what have you remembered",
    "list my memories",
    "list memories",
    "show my memories",
    "show memories",
    "my memories",
)

private val projectPhrases = setOf(
    "what am i working on",
    "what projects am i working on",
    "what are my projects",
    "what projects do you remember",
    "ongoing projects",
    "what are my ongoing projects",
    "recall my projects",
)

private val recentPhrases = setOf(
    "what did we talk about",
    "what did we just talk about",
    "what were we talking about",
    "recall our conversation",
    "recall the conversation",
    "recall our last conversation",
    "what did i just say",
    "what did we just say",
)

/**
 * Facts and recent chats stored only on this phone.
 * [writesEnabled] follows the Settings memory switch.
 */
class PersonalMemoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _memories: MutableStateFlow<List<SavedMemory>> = synchronized(lock) {
        shared ?: MutableStateFlow(readMemories()).also { shared = it }
    }
    val memories: StateFlow<List<SavedMemory>> = _memories.asStateFlow()

    @Volatile
    var writesEnabled: Boolean = true

    /** Conversation turns are kept only when the user turns chat history on in Settings. */
    @Volatile
    var historyEnabled: Boolean = false

    fun recordTurn(role: String, text: String, allowSecret: Boolean) {
        if (!writesEnabled || !historyEnabled) return
        if (!shouldPersistTurn(text, allowSecret)) return
        synchronized(lock) {
            val turns = readTurns().toMutableList()
            turns += StoredTurn(System.currentTimeMillis(), role, text.replace("\n", " ").take(500))
            writeTurns(if (turns.size > MAX_TURNS) turns.takeLast(MAX_TURNS) else turns)
        }
    }

    fun save(text: String, kind: MemoryKind): SavedMemory? = synchronized(lock) {
        if (!writesEnabled) return null
        val clean = text.trim()
        if (clean.isEmpty()) return null
        val items = readMemories().toMutableList()
        val saved = SavedMemory(
            id = (items.maxOfOrNull { it.id } ?: 0) + 1,
            text = clean,
            kind = kind,
            updatedAt = System.currentTimeMillis(),
        )
        items += saved
        writeMemories(items)
        return saved
    }

    fun update(id: Long, text: String): SavedMemory? = synchronized(lock) {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        var updated: SavedMemory? = null
        val items = readMemories().map { memory ->
            if (memory.id != id) memory
            else memory.copy(text = clean, updatedAt = System.currentTimeMillis()).also { updated = it }
        }
        if (updated == null) return null
        writeMemories(items)
        return updated
    }

    fun delete(id: Long) = synchronized(lock) {
        writeMemories(readMemories().filterNot { it.id == id })
    }

    fun deleteAll(): Int = synchronized(lock) {
        val count = readMemories().size
        writeMemories(emptyList())
        return count
    }

    fun deleteMatching(query: String): SavedMemory? = synchronized(lock) {
        val match = bestMemory(readMemories(), query) ?: return null
        delete(match.id)
        return match
    }

    fun editMatching(query: String, text: String): SavedMemory? = synchronized(lock) {
        val match = bestMemory(readMemories(), query) ?: return null
        return update(match.id, text)
    }

    fun all(): List<SavedMemory> = readMemories()

    fun projects(): List<SavedMemory> = readMemories().filter { it.kind == MemoryKind.Project }

    fun recentTurns(limit: Int = 40): List<StoredTurn> = readTurns().takeLast(limit)

    private fun readMemories(): List<SavedMemory> {
        val raw = prefs.getString(MEMORIES, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split("\t", limit = 4)
            if (parts.size < 4) return@mapNotNull null
            SavedMemory(
                id = parts[0].toLongOrNull() ?: return@mapNotNull null,
                kind = runCatching { MemoryKind.valueOf(parts[1]) }.getOrDefault(MemoryKind.Fact),
                updatedAt = parts[2].toLongOrNull() ?: 0L,
                text = unescape(parts[3]),
            )
        }.toList()
    }

    private fun writeMemories(items: List<SavedMemory>) {
        val raw = items.joinToString("\n") {
            "${it.id}\t${it.kind.name}\t${it.updatedAt}\t${escape(it.text)}"
        }
        prefs.edit().putString(MEMORIES, raw).apply()
        _memories.value = items
    }

    private fun readTurns(): List<StoredTurn> {
        val raw = prefs.getString(TURNS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split("\t", limit = 3)
            if (parts.size < 3) return@mapNotNull null
            StoredTurn(
                at = parts[0].toLongOrNull() ?: return@mapNotNull null,
                role = parts[1],
                text = unescape(parts[2]),
            )
        }.toList()
    }

    private fun writeTurns(items: List<StoredTurn>) {
        val raw = items.joinToString("\n") {
            "${it.at}\t${it.role}\t${escape(it.text)}"
        }
        prefs.edit().putString(TURNS, raw).apply()
    }

    private fun escape(text: String): String = text.replace("\t", " ").replace("\n", " ")

    private fun unescape(text: String): String = text

    companion object {
        private const val PREFS = "kai_personal_memory"
        private const val MEMORIES = "memories"
        private const val TURNS = "turns"
        private const val MAX_TURNS = 100

        /** Every instance reads and writes the same prefs; one lock and one list for all. */
        private val lock = Any()
        private var shared: MutableStateFlow<List<SavedMemory>>? = null
    }

    fun clearTurns() = synchronized(lock) { writeTurns(emptyList()) }
}
