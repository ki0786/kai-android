package com.kai.assistant.api

import com.kai.assistant.business.BackendEnquiryBrain
import com.kai.assistant.business.BrainRequest
import com.kai.assistant.business.BusinessInfo
import com.kai.assistant.business.DayHours
import com.kai.assistant.business.EnquiryCategory
import com.kai.assistant.business.OpeningHours
import com.kai.assistant.business.ReplyLanguage
import com.kai.assistant.business.Topic
import com.kai.assistant.data.ChatMessage
import com.kai.assistant.data.MessageRole
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicReference

class KaiApiClientEnquiryTest {
    private val info = BusinessInfo(
        businessName = "Sagar Furniture",
        address = "123 MG Road, Bengaluru",
        hours = OpeningHours(DayOfWeek.entries.associateWith { DayHours(10 * 60, 20 * 60) }),
    )

    @Test
    fun enquirePostsMessagesOnlyToV1Enquiry() = runBlocking {
        val seen = AtomicReference<Pair<String, String>>()
        val api = client { request ->
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            seen.set(request.url.encodedPath to buffer.readUtf8())
            json(
                request,
                200,
                """
                {
                  "category": "common",
                  "topics": ["address"],
                  "language": "english",
                  "visit_date": null,
                  "visit_time": null,
                  "reply": "Our showroom is at 123 MG Road, Bengaluru.",
                  "provider": "grok",
                  "model": "grok-4",
                  "latency_ms": 8.2
                }
                """.trimIndent(),
            )
        }

        val result = api.enquire(
            listOf(
                ChatMessage(role = MessageRole.System, text = "system"),
                ChatMessage(role = MessageRole.User, text = "Where is the showroom?"),
            ),
        )

        assertTrue(result.ok)
        assertEquals("/v1/enquiry", seen.get().first)
        val payload = JSONObject(seen.get().second)
        assertTrue(payload.has("messages"))
        assertFalse(payload.has("session_id"))
        assertFalse(payload.has("force_cloud"))
        assertEquals(2, payload.getJSONArray("messages").length())
        assertEquals("grok", result.provider)
        assertEquals("grok-4", result.model)
        assertTrue(result.body.contains("\"category\""))
    }

    @Test
    fun enquireMarksUnparseable502() = runBlocking {
        val api = client { request ->
            json(
                request,
                502,
                """{"detail":{"error":"enquiry_unparseable","message":"model output was not valid enquiry JSON"}}""",
            )
        }
        val result = api.enquire(listOf(ChatMessage(role = MessageRole.User, text = "hi")))
        assertFalse(result.ok)
        assertTrue(result.unparseable)
        assertEquals(502, result.code)
    }

    @Test
    fun backendBrainReadsStructuredFields() = runBlocking {
        val api = client { request ->
            assertEquals("/v1/enquiry", request.url.encodedPath)
            json(
                request,
                200,
                """
                {
                  "category": "common",
                  "topics": ["hours"],
                  "language": "hindi",
                  "visit_date": null,
                  "visit_time": null,
                  "reply": "Hum 10 baje se 8 baje tak khule hain.",
                  "provider": "grok",
                  "model": "x",
                  "latency_ms": 1
                }
                """.trimIndent(),
            )
        }
        val answer = BackendEnquiryBrain(api).ask(
            BrainRequest(
                info = info,
                customerText = "kitne baje tak khula hai?",
                history = emptyList(),
                now = LocalDateTime.of(2026, 11, 25, 12, 0),
                nextSlot = null,
            ),
        )
        requireNotNull(answer)
        assertEquals(EnquiryCategory.CommonQuestion, answer.category)
        assertEquals(setOf(Topic.Hours), answer.topics)
        assertEquals(ReplyLanguage.Hindi, answer.language)
        assertTrue(answer.reply.contains("10 baje"))
    }

    @Test
    fun backendBrainReturnsNullOnUnparseable() = runBlocking {
        val api = client { request ->
            json(request, 502, """{"detail":{"error":"enquiry_unparseable"}}""")
        }
        val answer = BackendEnquiryBrain(api).ask(
            BrainRequest(
                info = info,
                customerText = "address?",
                history = emptyList(),
                now = LocalDateTime.of(2026, 11, 25, 12, 0),
                nextSlot = null,
            ),
        )
        assertNull(answer)
    }

    private fun client(
        respond: (okhttp3.Request) -> Response,
    ): KaiApiClient = KaiApiClient(
        baseUrl = { "http://enquiry.test" },
        client = OkHttpClient.Builder()
            .addInterceptor { chain -> respond(chain.request()) }
            .build(),
    )

    private fun json(request: okhttp3.Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
}
