package app.nastavnik.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Модели Claude для быстрого выбора в настройках. */
enum class ClaudeModel(val id: String, val label: String, val hint: String) {
    SONNET("claude-sonnet-5-5", "Sonnet 5.5", "по умолчанию: быстро и толково"),
    HAIKU("claude-haiku-5-5", "Haiku 5.5", "быстрее и дешевле"),
    OPUS("claude-opus-5-5", "Opus 5.5", "для сложных задач, дороже"),
    ;

    companion object {
        /** Серверный резерв на случай отказа модели есть у Sonnet 5.5 и Opus 5.5, но не у Haiku. */
        fun supportsFallbacks(modelId: String): Boolean = modelId == SONNET.id || modelId == OPUS.id
    }
}

/** Запрос к Anthropic Messages API. */
data class MessageRequest(
    val model: String,
    val system: String,
    val turns: List<ChatTurn>,
    val images: List<ImagePart> = emptyList(),
    val maxTokens: Int,
    val stream: Boolean,
    val effort: String = "medium",
    val useFallbacks: Boolean = ClaudeModel.supportsFallbacks(model),
)

/** Тело и заголовки запроса к Anthropic `/v1/messages`. */
object RequestBuilder {
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

    fun build(req: MessageRequest): JsonObject {
        val turns = Turns.normalize(req.turns)
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
            put("model", req.model)
            put("max_tokens", req.maxTokens)
            put("system", req.system)
            put("messages", messages)
            // effort понимают модели Claude 4.6 и новее; для старых id не отправляем.
            if (supportsEffort(req.model)) putJsonObject("output_config") { put("effort", req.effort) }
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

    private val OLD_MODEL = Regex("""claude-(3|instant|2|haiku-4|sonnet-4-[0-5]|opus-4-[0-4])""")

    internal fun supportsEffort(modelId: String): Boolean = !OLD_MODEL.containsMatchIn(modelId)

    internal fun normalize(input: List<ChatTurn>): List<ChatTurn> = Turns.normalize(input)
}
