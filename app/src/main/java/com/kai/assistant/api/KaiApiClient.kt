package com.kai.assistant.api

import com.kai.assistant.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP stub for the sibling FastAPI backend (`kai-backend`).
 * Base URL comes from BuildConfig / local.properties (`KAI_API_BASE_URL`).
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

    data class ChatStubResult(
        val ok: Boolean,
        val reply: String,
        val code: Int?,
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
     * Placeholder chat call. Real schema lands when the backend repo is ready.
     */
    fun sendChatStub(sessionId: String, message: String): ChatStubResult {
        val json = """
            {"session_id":${sessionId.quoteJson()},"message":${message.quoteJson()}}
        """.trimIndent()
        val request = Request.Builder()
            .url("$root/v1/chat")
            .post(json.toRequestBody(JSON))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                ChatStubResult(
                    ok = response.isSuccessful,
                    reply = if (response.isSuccessful) body.ifBlank { "(empty response)" }
                    else "Backend ${response.code}: ${body.ifBlank { "unavailable" }}",
                    code = response.code,
                )
            }
        }.getOrElse { error ->
            ChatStubResult(
                ok = false,
                reply = "Offline stub: backend not reachable (${error.message}). " +
                    "Set KAI_API_BASE_URL in local.properties when kai-backend is up.",
                code = null,
            )
        }
    }

    fun baseUrl(): String = root

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()
        }

        private fun String.quoteJson(): String =
            "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
