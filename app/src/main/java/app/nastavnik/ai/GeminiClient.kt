package app.nastavnik.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Google Gemini API (generativelanguage.googleapis.com, v1beta).
 * Ключ создаётся в Google AI Studio; подписка Google AI Pro сама по себе ключ API не даёт.
 */
class GeminiClient(
    private val http: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : AiBackend {
    override val provider = AiProvider.GEMINI

    override suspend fun stream(apiKey: String, request: ChatRequest, onUpdate: (StreamUpdate) -> Unit): String? {
        validate(apiKey, request)
        return HttpCalls.withRetry {
            var stop: String? = null
            val url = "$baseUrl/models/${model(request.modelId)}:streamGenerateContent?alt=sse"
            HttpCalls.stream(http, post(apiKey, url, request), { HttpCalls.errorFromResponse(it, provider) }) { ev ->
                val chunk = runCatching { HttpCalls.json.parseToJsonElement(ev.data).jsonObject }.getOrNull()
                    ?: return@stream false
                val parsed = parseChunk(chunk)
                parsed.stop?.let { stop = it }
                if (parsed.thinking && parsed.text.isEmpty()) onUpdate(StreamUpdate.Thinking)
                if (parsed.text.isNotEmpty()) {
                    onUpdate(StreamUpdate.Text(parsed.text))
                    true
                } else {
                    false
                }
            }
            if (stop == StopReasons.REFUSAL) throw AiErrors.refusal()
            onUpdate(StreamUpdate.Stop(stop))
            stop
        }
    }

    override suspend fun complete(apiKey: String, request: ChatRequest): Completion {
        validate(apiKey, request)
        return HttpCalls.withRetry {
            val url = "$baseUrl/models/${model(request.modelId)}:generateContent"
            val body = HttpCalls.plain(http, post(apiKey, url, request)) { HttpCalls.errorFromResponse(it, provider) }
            val obj = runCatching { HttpCalls.json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw AiErrors.badResponse()
            val parsed = parseChunk(obj)
            if (parsed.stop == StopReasons.REFUSAL) throw AiErrors.refusal()
            Completion(parsed.text, parsed.stop)
        }
    }

    override suspend fun checkKey(apiKey: String, modelId: String): KeyCheck = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext KeyCheck.Failed(AiErrors.noKey(provider).userMessage)
        try {
            http.newCall(get(apiKey, "$baseUrl/models/${model(modelId)}")).execute().use { resp ->
                when {
                    resp.isSuccessful -> KeyCheck.Ok
                    resp.code == 404 -> KeyCheck.ModelMissing(modelId)
                    else -> KeyCheck.Failed(HttpCalls.errorFromResponse(resp, provider).userMessage)
                }
            }
        } catch (e: IOException) {
            KeyCheck.Failed(AiErrors.fromIo(e).userMessage)
        }
    }

    /** Только модели, которые умеют generateContent (текст по картинке). */
    override suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AiErrors.noKey(provider)
        try {
            http.newCall(get(apiKey, "$baseUrl/models?pageSize=1000")).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpCalls.errorFromResponse(resp, provider)
                val obj = HttpCalls.json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                (obj["models"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val m = el as? JsonObject ?: return@mapNotNull null
                    val methods = (m["supportedGenerationMethods"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.content }
                    if ("generateContent" !in methods) return@mapNotNull null
                    HttpCalls.str(m, "name")?.removePrefix("models/")
                }.filter { "gemini" in it && "embedding" !in it && "tts" !in it && "image" !in it }
            }
        } catch (e: IOException) {
            throw AiErrors.fromIo(e)
        }
    }

    private fun validate(apiKey: String, request: ChatRequest) {
        if (apiKey.isBlank()) throw AiErrors.noKey(provider)
        if (request.modelId.isBlank()) throw AiErrors.noModel()
    }

    private fun model(id: String) = id.trim().removePrefix("models/")

    private fun get(apiKey: String, url: String) = Request.Builder().url(url).header("x-goog-api-key", apiKey).get().build()

    private fun post(apiKey: String, url: String, request: ChatRequest) = Request.Builder()
        .url(url)
        .header("x-goog-api-key", apiKey)
        .header("content-type", "application/json")
        .post(buildBody(request).toString().toRequestBody(HttpCalls.JSON_MEDIA))
        .build()

    internal data class Chunk(val text: String, val stop: String?, val thinking: Boolean)

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

        /** Тело запроса generateContent: роли user/model, картинка — inlineData в последнем сообщении. */
        fun buildBody(request: ChatRequest): JsonObject {
            val turns = Turns.normalize(request.turns)
            return buildJsonObject {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", request.system) } }
                }
                put("contents", buildJsonArray {
                    turns.forEachIndexed { i, t ->
                        addJsonObject {
                            put("role", if (t.role == Role.USER) "user" else "model")
                            putJsonArray("parts") {
                                if (i == turns.lastIndex) {
                                    request.images.forEach { img ->
                                        addJsonObject {
                                            putJsonObject("inlineData") {
                                                put("mimeType", img.mediaType)
                                                put("data", img.base64())
                                            }
                                        }
                                    }
                                }
                                addJsonObject { put("text", t.text) }
                            }
                        }
                    }
                })
                putJsonObject("generationConfig") {
                    put("maxOutputTokens", request.maxTokens)
                    if (request.jsonOutput) put("responseMimeType", "application/json")
                    // Быстрый ответ: Flash без размышлений, Pro — с минимальным бюджетом.
                    if (request.fast) {
                        val m = request.modelId.lowercase()
                        putJsonObject("thinkingConfig") { put("thinkingBudget", if (m.contains("pro")) 128 else 0) }
                    }
                }
            }
        }

        /** Разбор ответа/куска стрима: текст без «мыслей» и причина остановки. */
        internal fun parseChunk(obj: JsonObject): Chunk {
            val feedback = obj["promptFeedback"] as? JsonObject
            if (HttpCalls.str(feedback, "blockReason") != null) return Chunk("", StopReasons.REFUSAL, false)
            val cand = (obj["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return Chunk("", null, false)
            val parts = ((cand["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
            val sb = StringBuilder()
            var thinking = false
            for (p in parts) {
                val part = p as? JsonObject ?: continue
                if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) {
                    thinking = true
                    continue
                }
                HttpCalls.str(part, "text")?.let(sb::append)
            }
            val stop = when (HttpCalls.str(cand, "finishReason")) {
                null, "FINISH_REASON_UNSPECIFIED" -> null
                "STOP" -> StopReasons.END
                "MAX_TOKENS" -> StopReasons.MAX_TOKENS
                "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> StopReasons.REFUSAL
                else -> StopReasons.END
            }
            return Chunk(sb.toString(), stop, thinking)
        }
    }
}
