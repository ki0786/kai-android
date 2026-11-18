package com.kai.assistant.api

import com.kai.assistant.BuildConfig
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
 * Base URL from BuildConfig / local.properties (`KAI_API_BASE_URL`).
 * Provider API keys stay server-side — never embedded in this app.
 */
class KaiApiClient(
    baseUrl: String = BuildConfig.KAI_API_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) {
    private val root = baseUrl.trimEnd('/')

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
        val request = Request.Builder()
            .url("$root/health")
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
        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("messages", messages.toJsonArray())
        }
        val request = Request.Builder()
            .url("$root/v1/chat")
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
                reply = "I'm offline right now — couldn't reach the backend " +
                    "(${error.message ?: "network error"}). Local phone commands still work.",
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

    fun baseUrl(): String = root

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
