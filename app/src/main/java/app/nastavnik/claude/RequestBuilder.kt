package app.nastavnik.claude

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

enum class Role(val wire: String) { USER("user"), ASSISTANT("assistant") }

/** Одна реплика разговора в том виде, в каком она хранится в истории (только текст). */
data class ChatTurn(val role: Role, val text: String, val hadImage: Boolean = false)

/** Картинка для последнего сообщения. */
class ImagePart(val bytes: ByteArray, val mediaType: String = "image/jpeg") {
    fun base64(): String = Base64.getEncoder().encodeToString(bytes)
}

/** Модели, которые можно выбрать в настройках. */
enum class ClaudeModel(val id: String, val label: String, val hint: String) {
    SONNET("claude-sonnet-5-5", "Sonnet 5.5", "по умолчанию: быстро и толково"),
    HAIKU("claude-haiku-5-5", "Haiku 5.5", "быстрее и дешевле"),
    OPUS("claude-opus-5-5", "Opus 5.5", "для сложных задач, дороже"),
    ;

    /** Серверный резерв на случай отказа модели есть у Sonnet и Opus, но не у Haiku. */
    val supportsFallbacks: Boolean get() = this != HAIKU

    companion object {
        fun byId(id: String?): ClaudeModel = entries.firstOrNull { it.id == id } ?: SONNET
    }
}

data class MessageRequest(
    val model: ClaudeModel,
    val system: String,
    val turns: List<ChatTurn>,
    val images: List<ImagePart> = emptyList(),
    val maxTokens: Int,
    val stream: Boolean,
    val effort: String = "medium",
    val useFallbacks: Boolean = model.supportsFallbacks,
)

object RequestBuilder {
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

    /**
     * Собирает тело запроса к /v1/messages.
     * - картинки уходят только в последнем сообщении пользователя, в прошлых — текстовая пометка;
     * - подряд идущие реплики одной роли склеиваются (API требует чередования);
     * - пустые ответы ассистента (например, после ошибки) выбрасываются.
     */
    fun build(req: MessageRequest): JsonObject {
        val turns = normalize(req.turns)
        require(turns.isNotEmpty() && turns.last().role == Role.USER) {
            "Последним должно быть сообщение пользователя"
        }
        val messages = buildJsonArray {
            turns.forEachIndexed { index, turn ->
                val isLast = index == turns.lastIndex
                addJsonObject {
                    put("role", turn.role.wire)
                    if (isLast && req.images.isNotEmpty()) {
                        put("content", buildJsonArray {
                            req.images.forEach { img ->
                                addJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", img.mediaType)
                                        put("data", img.base64())
                                    }
                                }
                            }
                            addJsonObject {
                                put("type", "text")
                                put("text", turn.text)
                            }
                        })
                    } else {
                        put("content", turn.text)
                    }
                }
            }
        }
        return buildJsonObject {
            put("model", req.model.id)
            put("max_tokens", req.maxTokens)
            put("system", req.system)
            put("messages", messages)
            putJsonObject("output_config") { put("effort", req.effort) }
            if (req.stream) put("stream", true)
            if (req.useFallbacks) put("fallbacks", "default")
        }
    }

    fun headers(apiKey: String, req: MessageRequest): Map<String, String> = buildMap {
        put("x-api-key", apiKey)
        put("anthropic-version", "2023-06-01")
        put("content-type", "application/json")
        if (req.useFallbacks) put("anthropic-beta", FALLBACK_BETA)
        if (req.stream) put("accept", "text/event-stream")
    }

    internal fun normalize(input: List<ChatTurn>): List<ChatTurn> {
        val out = ArrayList<ChatTurn>()
        for ((index, raw) in input.withIndex()) {
            val isLast = index == input.lastIndex
            var text = raw.text.trim()
            if (raw.role == Role.ASSISTANT && text.isEmpty()) continue
            // Картинка в прошлом сообщении пользователя: оставляем пометку вместо самой картинки.
            if (raw.role == Role.USER && raw.hadImage && !isLast) {
                text = if (text.isEmpty()) Prompts.PAST_IMAGE_NOTE else "${Prompts.PAST_IMAGE_NOTE}\n$text"
            }
            if (text.isEmpty()) text = "…"
            val prev = out.lastOrNull()
            if (prev != null && prev.role == raw.role) {
                out[out.lastIndex] = prev.copy(text = prev.text + "\n\n" + text, hadImage = prev.hadImage || raw.hadImage)
            } else {
                out += raw.copy(text = text)
            }
        }
        while (out.isNotEmpty() && out.first().role == Role.ASSISTANT) out.removeAt(0)
        return out
    }
}
