package app.nastavnik.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RequestBuilderTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)

    private fun request(model: ClaudeModel = ClaudeModel.SONNET, stream: Boolean = true) = MessageRequest(
        model = model.id,
        system = Prompts.mentorSystem(Strictness.HINTS),
        turns = listOf(
            ChatTurn(Role.USER, "Подскажи следующий шаг", hadImage = true),
            ChatTurn(Role.ASSISTANT, "Вспомни правило Крамера."),
            ChatTurn(Role.USER, "Проверь, где ошибка", hadImage = true),
        ),
        images = listOf(ImagePart(jpeg)),
        maxTokens = 4096,
        stream = stream,
    )

    @Test
    fun imageOnlyInLastMessageAndOldImagesBecomeText() {
        val body = RequestBuilder.build(request())
        val messages = body["messages"]!!.jsonArray
        assertEquals(3, messages.size)

        val first = messages[0].jsonObject
        assertEquals("user", first["role"]!!.jsonPrimitive.content)
        val firstContent = first["content"] as JsonPrimitive
        assertTrue(firstContent.content.startsWith(Prompts.PAST_IMAGE_NOTE))

        val last = messages[2].jsonObject["content"] as JsonArray
        assertEquals(2, last.size)
        val image = last[0].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        val source = image["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", source["media_type"]!!.jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(jpeg), source["data"]!!.jsonPrimitive.content)
        val text = last[1].jsonObject
        assertEquals("text", text["type"]!!.jsonPrimitive.content)
        assertEquals("Проверь, где ошибка", text["text"]!!.jsonPrimitive.content)
        // Только одна картинка на весь запрос.
        assertEquals(1, body.toString().split("\"type\":\"image\"").size - 1)
    }

    @Test
    fun topLevelFields() {
        val body = RequestBuilder.build(request())
        assertEquals("claude-sonnet-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(4096, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertTrue(body["system"]!!.jsonPrimitive.content.contains("Ты — наставник"))
        assertEquals("medium", (body["output_config"] as JsonObject)["effort"]!!.jsonPrimitive.content)
        assertNull(body["temperature"])
    }

    @Test
    fun haikuHasNoFallbacksAndNoStreamFlagWhenPlain() {
        val req = request(ClaudeModel.HAIKU, stream = false)
        val body = RequestBuilder.build(req)
        assertNull(body["fallbacks"])
        assertNull(body["stream"])
        assertFalse(RequestBuilder.headers("k", req).containsKey("anthropic-beta"))
    }

    @Test
    fun headers() {
        val h = RequestBuilder.headers("sk-test", request())
        assertEquals("sk-test", h["x-api-key"])
        assertEquals("2023-06-01", h["anthropic-version"])
        assertEquals("application/json", h["content-type"])
        assertEquals(RequestBuilder.FALLBACK_BETA, h["anthropic-beta"])
    }

    @Test
    fun normalizeMergesSameRoleAndDropsEmptyAssistant() {
        val turns = RequestBuilder.normalize(
            listOf(
                ChatTurn(Role.ASSISTANT, "приветствие без вопроса"),
                ChatTurn(Role.USER, "первый"),
                ChatTurn(Role.ASSISTANT, ""),
                ChatTurn(Role.USER, "второй"),
            ),
        )
        assertEquals(1, turns.size)
        assertEquals(Role.USER, turns[0].role)
        assertEquals("первый\n\nвторой", turns[0].text)
    }

    @Test
    fun strictnessAndSubjectInSystemPrompt() {
        val s = Prompts.mentorSystem(Strictness.QUESTIONS_ONLY)
        // Предмет модель определяет сама — выбирать его в настройках не нужно.
        assertTrue(s.contains("Предмет определи сам"))
        assertTrue(s.contains("Строгость: только наводящие вопросы"))
        assertTrue(s.contains("последней строкой напиши ровно [[ГОТОВО]]."))
        val clean = Prompts.cleanSystem()
        assertTrue(clean.contains("Предмет определи сам"))
        assertTrue(clean.contains("\$формулами\$"))
    }

    @Test
    fun fastAnswersLowerEffortAndSwitchThinkingOffWherePossible() {
        val sonnet = RequestBuilder.build(request().copy(fast = true))
        assertEquals("low", (sonnet["output_config"] as JsonObject)["effort"]!!.jsonPrimitive.content)
        assertEquals("between_tools", (sonnet["thinking"] as JsonObject)["type"]!!.jsonPrimitive.content)
        // С выключенными размышлениями серверный резерв не шлём — он может их не принять.
        val fastReq = MessageRequest(ClaudeModel.SONNET.id, "s", listOf(ChatTurn(Role.USER, "x")), maxTokens = 10, stream = true, fast = true)
        assertFalse(fastReq.useFallbacks)
        assertNull(RequestBuilder.build(fastReq)["fallbacks"])
        assertEquals("disabled", RequestBuilder.thinkingOff("claude-haiku-5-5", fast = true, compat = false))
        assertNull(RequestBuilder.thinkingOff("claude-opus-5-5", fast = true, compat = false))
        assertNull(RequestBuilder.thinkingOff("claude-sonnet-5-5", fast = false, compat = false))
        assertNull(RequestBuilder.thinkingOff("claude-sonnet-5-5", fast = true, compat = true))
        // Обычный режим — как раньше.
        val normal = RequestBuilder.build(request())
        assertNull(normal["thinking"])
        assertEquals("medium", (normal["output_config"] as JsonObject)["effort"]!!.jsonPrimitive.content)
    }
}
