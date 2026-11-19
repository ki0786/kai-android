package com.kai.assistant.api

import com.kai.assistant.data.ChatMessage
import com.kai.assistant.data.MessageRole
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the sibling FastAPI backend (`kai-backend`).
 * Base URL from Settings (persisted) with device-aware defaults via [BackendUrlResolver].
 * Provider API keys stay server-side — never embedded in this app.
 */
class KaiApiClient(
    private val baseUrlProvider: () -> String,
    private val client: OkHttpClient = defaultClient(),
) {
    constructor(baseUrl: String, client: OkHttpClient = defaultClient()) : this(
        baseUrlProvider = { baseUrl },
        client = client,
    )

    private fun root(): String = baseUrlProvider().trim().trimEnd('/')

    data class HealthResult(
        val ok: Boolean,
        val body: String,
        val code: Int?,
    )

    data class ChatResult(
        val ok: Boolean,
        val reply: String,
        val code: Int?,
        val provider: String? = null,
        val offline: Boolean = false,
    )

    fun health(): HealthResult {
        val base = root()
        if (base.isBlank()) {
            return HealthResult(
                ok = false,
                body = BackendUrlResolver.offlineHint(""),
                code = null,
            )
        }
        val request = Request.Builder()
            .url("$base/health")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                HealthResult(ok = response.isSuccessful, body = body, code = response.code)
            }
        }.getOrElse { error ->
            HealthResult(ok = false, body = error.message ?: "unreachable", code = null)
        }
    }

    /**
     * POST /v1/chat with session transcript. Handles offline + 503 (no provider keys).
     */
    fun sendChat(sessionId: String, messages: List<ChatMessage>): ChatResult {
        val base = root()
        if (base.isBlank()) {
            return ChatResult(
                ok = false,
                reply = "Backend URL is not set. " +
                    "On a physical phone, open Settings → Backend URL and enter your PC's " +
                    "LAN IP on the same Wi‑Fi (e.g. http://192.168.1.10:8000). " +
                    "Do not use 10.0.2.2 — that only works on the Android emulator. " +
                    "Local phone commands still work.",
                code = null,
                offline = true,
            )
        }

        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("messages", messages.toJsonArray())
        }
        val request = Request.Builder()
            .url("$base/v1/chat")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val parsed = runCatching { JSONObject(body) }.getOrNull()
                        val content = parsed?.optString("content").orEmpty()
                            .ifBlank { body.ifBlank { "(empty response)" } }
                        ChatResult(
                            ok = true,
                            reply = content,
                            code = response.code,
                            provider = parsed?.optString("provider")?.ifBlank { null },
                        )
                    }
                    response.code == 503 -> ChatResult(
                        ok = false,
                        reply = friendly503(body),
                        code = 503,
                        offline = false,
                    )
                    else -> ChatResult(
                        ok = false,
                        reply = "Backend ${response.code}: ${
                            extractErrorMessage(body).ifBlank { "unavailable" }
                        }",
                        code = response.code,
                    )
                }
            }
        }.getOrElse { error ->
            ChatResult(
                ok = false,
                reply = BackendUrlResolver.offlineHint(base) +
                    " (${error.message ?: "network error"})",
                code = null,
                offline = true,
            )
        }
    }

    /** @deprecated Prefer [sendChat] with full transcript. */
    fun sendChatStub(sessionId: String, message: String): ChatResult =
        sendChat(
            sessionId = sessionId,
            messages = listOf(ChatMessage(role = MessageRole.User, text = message)),
        )

    fun baseUrl(): String = root()

    private fun List<ChatMessage>.toJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { message ->
            val role = when (message.role) {
                MessageRole.User -> "user"
                MessageRole.Assistant -> "assistant"
                MessageRole.System -> "system"
            }
            array.put(
                JSONObject()
                    .put("role", role)
                    .put("content", message.text),
            )
        }
        return array
    }

    private fun friendly503(body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("detail")?.optString("message")
        }.getOrNull()
        return detail?.takeIf { it.isNotBlank() }
            ?: "The AI backend has no provider keys configured (HTTP 503). " +
            "Local phone commands still work; set keys on kai-backend to enable chat replies."
    }

    private fun extractErrorMessage(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
            val json = JSONObject(body)
            json.optJSONObject("detail")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: json.optString("detail").takeIf { it.isNotBlank() }
                ?: body
        }.getOrDefault(body)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()
        }
    }
}
