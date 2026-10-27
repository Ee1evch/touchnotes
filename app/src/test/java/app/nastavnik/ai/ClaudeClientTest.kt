package app.nastavnik.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Клиент против локального MockWebServer — без настоящей сети. */
class ClaudeClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ClaudeClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ClaudeClient(ClaudeClient.defaultHttp(), server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun req() = MessageRequest(
        model = ClaudeModel.SONNET.id,
        system = "s",
        turns = listOf(ChatTurn(Role.USER, "Подскажи", hadImage = true)),
        images = listOf(ImagePart(byteArrayOf(1, 2, 3))),
        maxTokens = 1000,
        stream = true,
    )

    private fun sse(vararg texts: String, stop: String = "end_turn"): String = buildString {
        append("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m\"}}\n\n")
        append("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n")
        texts.forEach {
            append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"$it\"}}\n\n")
        }
        append("event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"$stop\"}}\n\n")
        append("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
    }

    @Test
    fun streamsTextAndSendsCorrectRequest() = runTest {
        server.enqueue(MockResponse().setHeader("content-type", "text/event-stream").setBody(sse("Привет, ", "мир")))
        val sb = StringBuilder()
        val stop = client.stream("sk-secret", req()) { if (it is StreamUpdate.Text) sb.append(it.delta) }
        assertEquals("end_turn", stop)
        assertEquals("Привет, мир", sb.toString())

        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("sk-secret", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("claude-sonnet-5-5", body["model"]!!.jsonPrimitive.content)
        assertTrue(body.toString().contains("\"type\":\"image\""))
    }

    @Test
    fun maps401ToRussianMessage() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"),
        )
        try {
            client.stream("bad", req()) {}
            fail()
        } catch (e: AiException) {
            assertEquals(AiException.Kind.AUTH, e.kind)
            assertTrue(e.userMessage.contains("Неверный API-ключ"))
            assertFalse(e.userMessage.contains("bad"))
            assertTrue(e.needsSettings)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun retriesOnceOn529() = runTest {
        server.enqueue(MockResponse().setResponseCode(529).setBody("{\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"))
        server.enqueue(MockResponse().setBody(sse("ок")))
        val sb = StringBuilder()
        client.stream("k", req()) { if (it is StreamUpdate.Text) sb.append(it.delta) }
        assertEquals("ок", sb.toString())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun rateLimitIsNotRetriedAndMentionsWait() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "20").setBody("{}"))
        try {
            client.stream("k", req()) {}
            fail()
        } catch (e: AiException) {
            assertEquals(AiException.Kind.RATE_LIMIT, e.kind)
            assertTrue(e.userMessage.contains("20"))
        }
    }

    @Test
    fun refusalBecomesError() = runTest {
        server.enqueue(MockResponse().setBody(sse(stop = "refusal")))
        try {
            client.stream("k", req()) {}
            fail()
        } catch (e: AiException) {
            assertEquals(AiException.Kind.REFUSAL, e.kind)
        }
    }

    @Test
    fun retriesWithoutFallbacksWhenServerRejectsThem() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"fallbacks: unknown field\"}}"),
        )
        server.enqueue(MockResponse().setBody(sse("ok")))
        client.stream("k", req()) {}
        server.takeRequest()
        val second = server.takeRequest()
        assertEquals(null, second.getHeader("anthropic-beta"))
        assertFalse(second.body.readUtf8().contains("fallbacks"))
    }

    @Test
    fun completeParsesTextAfterFallbackBlock() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"частичный"},{"type":"fallback","model":"x"},{"type":"text","text":"{\"title\":\"T\"}"}],"stop_reason":"end_turn"}""",
            ),
        )
        val c = client.complete("k", req())
        assertEquals("{\"title\":\"T\"}", c.text)
    }

    @Test
    fun noKeyFailsWithoutRequest() = runTest {
        try {
            client.stream("", req()) {}
            fail()
        } catch (e: AiException) {
            assertEquals(AiException.Kind.NO_KEY, e.kind)
        }
        assertEquals(0, server.requestCount)
    }
}
