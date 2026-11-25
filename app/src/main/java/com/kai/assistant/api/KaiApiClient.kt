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
    // Constructor property is also named baseUrl; call it via invoke so we do not
    // recurse into the public baseUrl() method below.
    private val root: String get() = baseUrl.invoke().trimEnd('/')

    /**
     * One-shot chat without a session id. Prefer [enquire] for WhatsApp Business
     * classification — `/v1/chat` still prepends the phone-assistant system prompt.
     */
    suspend fun completeOnce(
        messages: List<ChatMessage>,
        forceCloud: Boolean = true,
    ): ChatResult {
        val payload = JSONObject().apply {
            put("messages", messages.toJsonArray())
            put("force_cloud", forceCloud)
        }
        return withRediscovery({ chatOffline(it) }) { base -> postChat(base, payload) }
    }

    /**
     * POST /v1/enquiry — structured classification for the enquiry inbox.
     * No session id, no planner, no phone-assistant prompt. Unparseable model
     * output is HTTP 502 (`enquiry_unparseable`).
     */
    suspend fun enquire(messages: List<ChatMessage>): EnquiryResult {
        val payload = JSONObject().put("messages", messages.toJsonArray())
        return withRediscovery({
            EnquiryResult(ok = false, offline = true)
        }) { base -> postEnquiry(base, payload) }
    }

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

    /**
     * Structured enquiry response. On success [body] is the JSON object
     * (`category`, `topics`, `language`, `visit_date`, `visit_time`, `reply`, …).
     */
    data class EnquiryResult(
        val ok: Boolean,
        val body: String = "",
        val code: Int? = null,
        val provider: String? = null,
        val model: String? = null,
        val offline: Boolean = false,
        val unparseable: Boolean = false,
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

    private fun postEnquiry(base: String, payload: JSONObject): EnquiryResult {
        val request = Request.Builder()
            .url("$base/v1/enquiry")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> {
                    val parsed = runCatching { JSONObject(body) }.getOrNull()
                        ?: return@use EnquiryResult(ok = false, body = body, code = response.code)
                    EnquiryResult(
                        ok = true,
                        body = body,
                        code = response.code,
                        provider = parsed.optString("provider").ifBlank { null },
                        model = parsed.optString("model").ifBlank { null },
                    )
                }
                response.code == 502 && isEnquiryUnparseable(body) -> EnquiryResult(
                    ok = false,
                    body = body,
                    code = 502,
                    unparseable = true,
                )
                else -> EnquiryResult(
                    ok = false,
                    body = body,
                    code = response.code,
                )
            }
        }
    }

    private fun isEnquiryUnparseable(body: String): Boolean {
        if (body.contains("enquiry_unparseable")) return true
        return runCatching {
            val detail = JSONObject(body).opt("detail")
            when (detail) {
                is String -> detail.contains("enquiry_unparseable")
                is JSONObject -> {
                    detail.optString("error").contains("enquiry_unparseable") ||
                        detail.optString("code").contains("enquiry_unparseable") ||
                        detail.optString("message").contains("enquiry_unparseable")
                }
                else -> false
            }
        }.getOrDefault(false)
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
        fun forDevice(
            context: android.content.Context,
            callTimeoutSeconds: Long = 60L,
        ): KaiApiClient {
            val endpoint = BackendEndpoint.get(context)
            val locator = BackendLocator(context.applicationContext, endpoint)
            return KaiApiClient(
                baseUrl = { endpoint.current() },
                client = defaultClient(callTimeoutSeconds),
                rediscover = { failed -> locator.rediscover(failed) },
            )
        }

        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(callTimeoutSeconds: Long = 60L): OkHttpClient {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            return OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()
        }
    }
}
