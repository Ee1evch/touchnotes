package app.nastavnik.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Любой сервис с OpenAI-совместимым Chat Completions API: OpenAI, OpenRouter, агрегаторы,
 * локальные серверы. Адрес задаётся в настройках (например, https://openrouter.ai/api/v1).
 */
class OpenAiClient(
    private val http: OkHttpClient,
    baseUrl: String,
) : AiBackend {
    override val provider = AiProvider.OPENAI_COMPAT

    private val base = normalizeBaseUrl(baseUrl)

    override suspend fun stream(apiKey: String, request: ChatRequest, onUpdate: (StreamUpdate) -> Unit): String? {
        validate(apiKey, request)
        return HttpCalls.withRetry {
            var stop: String? = null
            HttpCalls.stream(http, post(apiKey, request, stream = true), { HttpCalls.errorFromResponse(it, provider) }) { ev ->
                if (ev.data.trim() == "[DONE]") return@stream false
                val chunk = runCatching { HttpCalls.json.parseToJsonElement(ev.data).jsonObject }.getOrNull()
                    ?: return@stream false
                // Некоторые сервисы присылают ошибку прямо в потоке.
                (chunk["error"] as? JsonObject)?.let { err ->
                    throw AiErrors.fromHttp(500, HttpCalls.str(err, "type") ?: HttpCalls.str(err, "code"), HttpCalls.str(err, "message"), p = provider)
                }
                val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@stream false
                mapFinish(HttpCalls.str(choice, "finish_reason"))?.let { stop = it }
                val delta = choice["delta"] as? JsonObject
                val text = HttpCalls.str(delta, "content").orEmpty()
                val reasoning = HttpCalls.str(delta, "reasoning") ?: HttpCalls.str(delta, "reasoning_content")
                if (text.isEmpty() && !reasoning.isNullOrEmpty()) onUpdate(StreamUpdate.Thinking)
                if (text.isNotEmpty()) {
                    onUpdate(StreamUpdate.Text(text))
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
            val body = HttpCalls.plain(http, post(apiKey, request, stream = false)) { HttpCalls.errorFromResponse(it, provider) }
            val obj = runCatching { HttpCalls.json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw AiErrors.badResponse()
            (obj["error"] as? JsonObject)?.let { err ->
                throw AiErrors.fromHttp(500, HttpCalls.str(err, "type"), HttpCalls.str(err, "message"), p = provider)
            }
            val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw AiErrors.badResponse("нет choices")
            val stop = mapFinish(HttpCalls.str(choice, "finish_reason"))
            if (stop == StopReasons.REFUSAL) throw AiErrors.refusal()
            Completion(HttpCalls.str(choice["message"] as? JsonObject, "content").orEmpty(), stop)
        }
    }

    /** У OpenAI-совместимых сервисов проверяем ключ через список моделей. */
    override suspend fun checkKey(apiKey: String, modelId: String): KeyCheck = try {
        val models = listModels(apiKey)
        if (models.isEmpty() || modelId in models) KeyCheck.Ok else KeyCheck.ModelMissing(modelId)
    } catch (e: AiException) {
        KeyCheck.Failed(e.userMessage)
    }

    override suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AiErrors.noKey(provider)
        try {
            val req = Request.Builder().url("$base/models").header("authorization", "Bearer $apiKey").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpCalls.errorFromResponse(resp, provider)
                val obj = HttpCalls.json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                (obj["data"] as? JsonArray).orEmpty().mapNotNull { HttpCalls.str(it as? JsonObject, "id") }.sorted()
            }
        } catch (e: IOException) {
            throw AiErrors.fromIo(e)
        } catch (e: IllegalArgumentException) {
            throw AiException(AiException.Kind.BAD_REQUEST, "Неверный адрес сервера: $base")
        }
    }

    private fun validate(apiKey: String, request: ChatRequest) {
        if (apiKey.isBlank()) throw AiErrors.noKey(provider)
        if (request.modelId.isBlank()) throw AiErrors.noModel()
    }

    private fun post(apiKey: String, request: ChatRequest, stream: Boolean): Request {
        val url = "$base/chat/completions"
        val builder = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            throw AiException(AiException.Kind.BAD_REQUEST, "Неверный адрес сервера: $base")
        }
        return builder
            .header("authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            // OpenRouter показывает эти заголовки в статистике; другим сервисам они не мешают.
            .header("x-title", "Nastavnik")
            .post(buildBody(request, stream).toString().toRequestBody(HttpCalls.JSON_MEDIA))
            .build()
    }

    companion object {
        /** Убираем хвост «/chat/completions» и лишний «/», если адрес вставили целиком. */
        fun normalizeBaseUrl(url: String): String = url.trim()
            .removeSuffix("/")
            .removeSuffix("/chat/completions")
            .removeSuffix("/")
            .ifBlank { OpenAiCompatPresets.OPENAI }

        /**
         * Тело Chat Completions. Картинка — `image_url` с data-URL в последнем сообщении.
         * Лимит токенов не задаём: разные сервисы ждут разные поля (max_tokens / max_completion_tokens).
         */
        fun buildBody(request: ChatRequest, stream: Boolean): JsonObject {
            val turns = Turns.normalize(request.turns)
            return buildJsonObject {
                put("model", request.modelId.trim())
                put("messages", buildJsonArray {
                    addJsonObject {
                        put("role", "system")
                        put("content", request.system)
                    }
                    turns.forEachIndexed { i, t ->
                        addJsonObject {
                            put("role", t.role.wire)
                            if (i == turns.lastIndex && request.images.isNotEmpty()) {
                                put("content", buildJsonArray {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", t.text)
                                    }
                                    request.images.forEach { img ->
                                        addJsonObject {
                                            put("type", "image_url")
                                            putJsonObject("image_url") { put("url", "data:${img.mediaType};base64,${img.base64()}") }
                                        }
                                    }
                                })
                            } else {
                                put("content", t.text)
                            }
                        }
                    }
                })
                if (stream) put("stream", true)
            }
        }

        internal fun mapFinish(reason: String?): String? = when (reason) {
            null -> null
            "length" -> StopReasons.MAX_TOKENS
            "content_filter" -> StopReasons.REFUSAL
            else -> StopReasons.END
        }
    }
}
