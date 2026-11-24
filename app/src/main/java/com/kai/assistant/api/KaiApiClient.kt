package com.kai.assistant.api

import com.kai.assistant.BuildConfig
import com.kai.assistant.data.ChatMessage
import com.kai.assistant.data.MessageRole
import com.kai.assistant.tools.ResearchSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the sibling FastAPI backend (`kai-backend`).
 * Base URL from [baseUrl], normally [BackendEndpoint]. When the backend can't be reached
 * at all, [rediscover] looks for it on the current Wi‑Fi and the request is retried once.
 * Provider API keys stay server-side — never embedded in this app.
 */
class KaiApiClient(
    private val baseUrl: () -> String = { BuildConfig.KAI_API_BASE_URL },
    private val client: OkHttpClient = defaultClient(),
    private val rediscover: suspend (failedUrl: String) -> String? = { null },
) {
    private val root: String get() = baseUrl().trimEnd('/')

    data class HealthResult(
        val ok: Boolean,
        val body: String,
        val code: Int?,
    )

    data class ResearchResult(
        val ok: Boolean,
        val answer: String,
        val sources: List<ResearchSource>,
        val code: Int?,
        val offline: Boolean = false,
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
    suspend fun sendChat(sessionId: String, messages: List<ChatMessage>): ChatResult {
        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("messages", messages.toJsonArray())
        }
        return withRediscovery({ chatOffline(it) }) { base -> postChat(base, payload) }
    }

    private fun chatOffline(error: Throwable) = ChatResult(
        ok = false,
        reply = "I'm offline right now — couldn't reach the backend " +
            "(${error.message ?: "network error"}). Local phone commands still work.",
        code = null,
        offline = true,
    )

    private fun postChat(base: String, payload: JSONObject): ChatResult {
        val request = Request.Builder()
            .url("$base/v1/chat")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        return run {
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
        }
    }

    /**
     * POST /v1/research. Sources are only those the backend returns.
     */
    suspend fun research(query: String): ResearchResult {
        val payload = JSONObject().put("query", query)
        return withRediscovery({ ResearchResult(ok = false, answer = "", sources = emptyList(), code = null, offline = true) }) { base ->
            postResearch(base, payload)
        }
    }

    private fun postResearch(base: String, payload: JSONObject): ResearchResult {
        val request = Request.Builder()
            .url("$base/v1/research")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return run {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use ResearchResult(
                        ok = false,
                        answer = "",
                        sources = emptyList(),
                        code = response.code,
                    )
                }
                val parsed = runCatching { JSONObject(body) }.getOrNull()
                    ?: return@use ResearchResult(
                        ok = false,
                        answer = "",
                        sources = emptyList(),
                        code = response.code,
                    )
                ResearchResult(
                    ok = true,
                    answer = parsed.optString("answer"),
                    sources = parseSources(parsed.optJSONArray("sources")),
                    code = response.code,
                )
            }
        }
    }

    /**
     * Runs [call] against the current base URL. Only a connection failure (no HTTP
     * answer at all) triggers discovery, and the call is retried once at the new address.
     */
    private suspend fun <T> withRediscovery(offline: (Throwable) -> T, call: (String) -> T): T {
        val first = root
        val error = try {
            return call(first)
        } catch (io: IOException) {
            io
        } catch (other: IllegalArgumentException) {
            return offline(other)
        }
        val found = try {
            rediscover(first)?.trimEnd('/')
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (found.isNullOrBlank() || found == first) return offline(error)
        return try {
            call(found)
        } catch (io: IOException) {
            offline(io)
        } catch (other: IllegalArgumentException) {
            offline(other)
        }
    }

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

    private fun parseSources(array: JSONArray?): List<ResearchSource> {
        if (array == null) return emptyList()
        val sources = mutableListOf<ResearchSource>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url").trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            val title = item.optString("title").trim()
            sources += ResearchSource(title = title, url = url)
        }
        return sources
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
        /** The app's client: shared saved address plus Wi‑Fi rediscovery. */
        fun forDevice(context: android.content.Context): KaiApiClient {
            val endpoint = BackendEndpoint.get(context)
            val locator = BackendLocator(context.applicationContext, endpoint)
            return KaiApiClient(
                baseUrl = { endpoint.current() },
                rediscover = { failed -> locator.rediscover(failed) },
            )
        }

        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            return OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()
        }
    }
}
