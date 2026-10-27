package app.nastavnik.ai

import kotlinx.coroutines.test.runTest
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

class GeminiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: GeminiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GeminiClient(ClaudeClient.defaultHttp(), server.url("/v1beta").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun req(json: Boolean = false) = ChatRequest(
        modelId = "gemini-flash-latest",
        system = "Ты — наставник",
        turns = listOf(
            ChatTurn(Role.USER, "Подскажи", hadImage = true),
            ChatTurn(Role.ASSISTANT, "Вспомни определение."),
            ChatTurn(Role.USER, "Проверь", hadImage = true),
        ),
        images = listOf(ImagePart(byteArrayOf(1, 2, 3))),
        maxTokens = 1000,
        jsonOutput = json,
    )

    @Test
    fun bodyHasSystemRolesAndImageOnlyInLastMessage() {
        val body = GeminiClient.buildBody(req(json = true))
        assertEquals("Ты — наставник", body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val contents = body["contents"]!!.jsonArray
        assertEquals(listOf("user", "model", "user"), contents.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val firstParts = contents[0].jsonObject["parts"]!!.jsonArray
        assertEquals(1, firstParts.size)
        assertTrue(firstParts[0].jsonObject["text"]!!.jsonPrimitive.content.startsWith(Prompts.PAST_IMAGE_NOTE))
        val lastParts = contents[2].jsonObject["parts"]!!.jsonArray
        assertEquals("image/jpeg", lastParts[0].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)
        assertEquals("Проверь", lastParts[1].jsonObject["text"]!!.jsonPrimitive.content)
        val cfg = body["generationConfig"]!!.jsonObject
        assertEquals("application/json", cfg["responseMimeType"]!!.jsonPrimitive.content)
        assertEquals(1000, cfg["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun streamsTextSkipsThoughtsAndSendsKeyHeader() = runTest {
        val sse = """
            data: {"candidates":[{"content":{"role":"model","parts":[{"text":"думаю","thought":true}]}}]}

            data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Вспомни "}]}}]}

            data: {"candidates":[{"content":{"role":"model","parts":[{"text":"теорему."}]},"finishReason":"STOP"}]}

        """.trimIndent()
        server.enqueue(MockResponse().setHeader("content-type", "text/event-stream").setBody(sse))
        val sb = StringBuilder()
        var thinking = false
        val stop = client.stream("AIza-test", req()) {
            when (it) {
                is StreamUpdate.Text -> sb.append(it.delta)
                StreamUpdate.Thinking -> thinking = true
                else -> Unit
            }
        }
        assertEquals("Вспомни теорему.", sb.toString())
        assertEquals(StopReasons.END, stop)
        assertTrue(thinking)
        val r = server.takeRequest()
        assertEquals("/v1beta/models/gemini-flash-latest:streamGenerateContent?alt=sse", r.path)
        assertEquals("AIza-test", r.getHeader("x-goog-api-key"))
        assertNull(r.requestUrl!!.queryParameter("key"))
    }

    @Test
    fun invalidKeyBecomesAuthError() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""",
            ),
        )
        try {
            client.stream("bad", req()) {}
            fail()
        } catch (e: AiException) {
            assertEquals(AiException.Kind.AUTH, e.kind)
            assertTrue(e.userMessage.contains("aistudio.google.com"))
        }
    }

    @Test
    fun quotaIsRateLimit() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""))
        try {
            client.complete("k", req())
            fail()
        } catch (e: AiException) {
            assertEquals(AiException.Kind.RATE_LIMIT, e.kind)
        }
    }

    @Test
    fun safetyBlockIsRefusal() = runTest {
        server.enqueue(MockResponse().setBody("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        try {
            client.complete("k", req())
            fail()
        } catch (e: AiException) {
            assertEquals(AiException.Kind.REFUSAL, e.kind)
        }
    }

    @Test
    fun completeAndListModels() = runTest {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"{\"title\":\"T\"}"}]},"finishReason":"STOP"}]}"""))
        assertEquals("{\"title\":\"T\"}", client.complete("k", req(json = true)).text)
        server.enqueue(
            MockResponse().setBody(
                """{"models":[
                  {"name":"models/gemini-flash-latest","supportedGenerationMethods":["generateContent","countTokens"]},
                  {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
                  {"name":"models/gemini-pro-latest","supportedGenerationMethods":["generateContent"]}
                ]}""",
            ),
        )
        assertEquals(listOf("gemini-flash-latest", "gemini-pro-latest"), client.listModels("k"))
    }
}
