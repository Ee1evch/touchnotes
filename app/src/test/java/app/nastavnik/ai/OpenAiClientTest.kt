package app.nastavnik.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Адрес вставлен «целиком» — с /chat/completions и слешем: клиент должен это поправить.
        client = OpenAiClient(ClaudeClient.defaultHttp(), server.url("/api/v1/chat/completions/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun req() = ChatRequest(
        modelId = "openai/gpt-5-mini",
        system = "Ты — наставник",
        turns = listOf(
            ChatTurn(Role.USER, "Подскажи", hadImage = true),
            ChatTurn(Role.ASSISTANT, "Вспомни определение."),
            ChatTurn(Role.USER, "Проверь", hadImage = true),
        ),
        images = listOf(ImagePart(byteArrayOf(1, 2, 3))),
        maxTokens = 1000,
    )

    @Test
    fun bodyHasSystemFirstAndDataUrlImageInLastMessage() {
        val body = OpenAiClient.buildBody(req(), stream = true)
        val msgs = body["messages"]!!.jsonArray
        assertEquals(listOf("system", "user", "assistant", "user"), msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertTrue((msgs[1].jsonObject["content"] as JsonPrimitive).content.startsWith(Prompts.PAST_IMAGE_NOTE))
        val last = msgs[3].jsonObject["content"] as JsonArray
        assertEquals("Проверь", last[0].jsonObject["text"]!!.jsonPrimitive.content)
        val url = last[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertNull(body["max_tokens"])
    }

    @Test
    fun streamsDeltasUntilDone() = runTest {
        val sse = """
            data: {"choices":[{"delta":{"role":"assistant","content":""}}]}

            : OPENROUTER PROCESSING

            data: {"choices":[{"delta":{"reasoning":"хм"}}]}

            data: {"choices":[{"delta":{"content":"Вспомни "}}]}

            data: {"choices":[{"delta":{"content":"формулу."},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()
        server.enqueue(MockResponse().setHeader("content-type", "text/event-stream").setBody(sse))
        val sb = StringBuilder()
        var thinking = false
        val stop = client.stream("sk-or-test", req()) {
            when (it) {
                is StreamUpdate.Text -> sb.append(it.delta)
                StreamUpdate.Thinking -> thinking = true
                else -> Unit
            }
        }
        assertEquals("Вспомни формулу.", sb.toString())
        assertEquals(StopReasons.END, stop)
        assertTrue(thinking)
        val r = server.takeRequest()
        assertEquals("/api/v1/chat/completions", r.path)
        assertEquals("Bearer sk-or-test", r.getHeader("authorization"))
        val sent = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("openai/gpt-5-mini", sent["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorsAreMapped() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"Incorrect API key provided","type":"invalid_request_error","code":"invalid_api_key"}}"""),
        )
        try {
            client.complete("bad", req())
            fail()
        } catch (e: AiException) {
            assertEquals(AiException.Kind.AUTH, e.kind)
        }
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setBody("""{"error":{"message":"You exceeded your current quota","type":"insufficient_quota"}}"""),
        )
        try {
            client.complete("k", req())
            fail()
        } catch (e: AiException) {
            assertEquals(AiException.Kind.FORBIDDEN, e.kind)
        }
    }

    @Test
    fun completeReadsMessageAndLengthStop() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"title\":\"T\"}"},"finish_reason":"length"}]}"""))
        val c = client.complete("k", req())
        assertEquals("{\"title\":\"T\"}", c.text)
        assertEquals(StopReasons.MAX_TOKENS, c.stopReason)
    }

    @Test
    fun listModelsAndCheckKey() = runTest {
        val models = """{"data":[{"id":"openai/gpt-5-mini"},{"id":"google/gemini-flash-latest"}]}"""
        server.enqueue(MockResponse().setBody(models))
        assertEquals(listOf("google/gemini-flash-latest", "openai/gpt-5-mini"), client.listModels("k"))
        server.enqueue(MockResponse().setBody(models))
        assertEquals(KeyCheck.Ok, client.checkKey("k", "openai/gpt-5-mini"))
        server.enqueue(MockResponse().setBody(models))
        assertEquals(KeyCheck.ModelMissing("nope"), client.checkKey("k", "nope"))
    }

    @Test
    fun noModelIsSettingsError() = runTest {
        try {
            client.stream("k", req().copy(modelId = " ")) {}
            fail()
        } catch (e: AiException) {
            assertTrue(e.needsSettings)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun fastAnswerLowersReasoningOnlyForReasoningModels() {
        assertEquals("\"low\"", OpenAiClient.buildBody(req().copy(modelId = "gpt-5-mini", fast = true), stream = true)["reasoning_effort"].toString())
        assertEquals("\"low\"", OpenAiClient.buildBody(req().copy(modelId = "openai/o4-mini", fast = true), stream = true)["reasoning_effort"].toString())
        assertEquals(null, OpenAiClient.buildBody(req().copy(modelId = "llama-3.3-70b", fast = true), stream = true)["reasoning_effort"])
        assertEquals(null, OpenAiClient.buildBody(req().copy(modelId = "gpt-5-mini"), stream = true)["reasoning_effort"])
    }
}
