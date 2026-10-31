package app.nastavnik.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Клиент Anthropic Messages API поверх OkHttp. Ключ передаётся в каждый вызов и нигде не логируется.
 * С [provider] = ANTHROPIC_COMPAT работает с Claude-совместимыми прокси (Claudex, CLIProxyAPI…):
 * свой адрес, ключ в выбранном заголовке и только стандартные поля запроса.
 */
class ClaudeClient(
    private val http: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
    override val provider: AiProvider = AiProvider.ANTHROPIC,
    private val authMode: AuthMode = if (provider == AiProvider.ANTHROPIC) AuthMode.X_API_KEY else AuthMode.BOTH,
) : AiBackend {
    private val compat = provider == AiProvider.ANTHROPIC_COMPAT
    private val baseUrl = normalizeBaseUrl(baseUrl)

    /** Подмена адреса API (только для тестов отладочной сборки). */
    var baseUrlOverride: () -> String? = { null }

    private val base: String get() = baseUrlOverride() ?: baseUrl

    /** Если сервер однажды отверг параметр `fallbacks`, больше его не шлём. */
    @Volatile
    private var fallbacksRejected = false

    override suspend fun stream(apiKey: String, request: ChatRequest, onUpdate: (StreamUpdate) -> Unit): String? =
        stream(apiKey, request.toMessageRequest(stream = true), onUpdate)

    override suspend fun complete(apiKey: String, request: ChatRequest): Completion =
        complete(apiKey, request.toMessageRequest(stream = false))

    private fun ChatRequest.toMessageRequest(stream: Boolean) = MessageRequest(
        model = modelId,
        system = system,
        turns = turns,
        images = images,
        maxTokens = maxTokens,
        stream = stream,
        compat = compat,
    )

    /** Потоковый запрос. Каждое обновление уходит в [onUpdate] (в фоновом потоке). Возвращает stop_reason. */
    suspend fun stream(apiKey: String, request: MessageRequest, onUpdate: (StreamUpdate) -> Unit): String? =
        withFallbackGuard(apiKey, request.copy(stream = true)) { req ->
            var stopReason: String? = null
            HttpCalls.stream(http, newRequest(apiKey, req), { HttpCalls.errorFromResponse(it, provider) }) { ev ->
                when (val update = StreamDecoder.decode(ev)) {
                    null -> false
                    is StreamUpdate.Error -> throw AiErrors.fromStream(update.type, update.message, provider)
                    is StreamUpdate.Stop -> {
                        if (update.stopReason != null) stopReason = update.stopReason
                        onUpdate(update)
                        false
                    }
                    is StreamUpdate.Text -> {
                        onUpdate(update)
                        true
                    }
                    else -> {
                        onUpdate(update)
                        false
                    }
                }
            }
            if (stopReason == StopReasons.REFUSAL) throw AiErrors.refusal()
            stopReason
        }

    /** Обычный запрос без стрима (для чистовика). */
    suspend fun complete(apiKey: String, request: MessageRequest): Completion =
        withFallbackGuard(apiKey, request.copy(stream = false)) { req ->
            val body = HttpCalls.plain(http, newRequest(apiKey, req)) { HttpCalls.errorFromResponse(it, provider) }
            val completion = parseCompletion(body)
            if (completion.stopReason == StopReasons.REFUSAL) throw AiErrors.refusal()
            completion
        }

    /** Бесплатная проверка: GET /v1/models/{id} проверяет и ключ, и доступность модели. */
    override suspend fun checkKey(apiKey: String, modelId: String): KeyCheck = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() && !compat) return@withContext KeyCheck.Failed(AiErrors.noKey(provider).userMessage)
        if (compat) {
            // Прокси не всегда умеют /v1/models/{id}: проверяем по общему списку.
            return@withContext try {
                val models = listModels(apiKey)
                if (models.isEmpty() || modelId in models) KeyCheck.Ok else KeyCheck.ModelMissing(modelId)
            } catch (e: AiException) {
                KeyCheck.Failed(e.userMessage)
            }
        }
        try {
            http.newCall(authGet(apiKey, "$base/v1/models/$modelId")).execute().use { resp ->
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

    override suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() && !compat) throw AiErrors.noKey(provider)
        try {
            http.newCall(authGet(apiKey, "$base/v1/models?limit=1000")).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpCalls.errorFromResponse(resp, provider)
                val obj = HttpCalls.json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                (obj["data"] as? JsonArray).orEmpty()
                    .mapNotNull { HttpCalls.str(it as? JsonObject, "id") }
            }
        } catch (e: IOException) {
            throw AiErrors.fromIo(e)
        }
    }

    private fun authGet(apiKey: String, url: String): Request {
        val b = try {
            Request.Builder().url(url)
        } catch (_: IllegalArgumentException) {
            throw AiErrors.badBaseUrl(baseUrl)
        }
        if (apiKey.isNotBlank()) {
            if (authMode != AuthMode.BEARER) b.header("x-api-key", apiKey)
            if (authMode != AuthMode.X_API_KEY) b.header("authorization", "Bearer $apiKey")
        }
        return b.header("anthropic-version", API_VERSION).get().build()
    }

    /** Повтор при перегрузке и повтор без `fallbacks`, если сервер не принял этот параметр. */
    private suspend fun <T> withFallbackGuard(apiKey: String, request: MessageRequest, block: suspend (MessageRequest) -> T): T {
        if (apiKey.isBlank() && !compat) throw AiErrors.noKey(provider)
        if (request.model.isBlank()) throw AiErrors.noModel()
        val req = if (fallbacksRejected) request.copy(useFallbacks = false) else request
        return try {
            HttpCalls.withRetry { block(req) }
        } catch (e: AiException) {
            if (e.kind == AiException.Kind.BAD_REQUEST && req.useFallbacks && e.userMessage.contains("fallback", ignoreCase = true)) {
                fallbacksRejected = true
                HttpCalls.withRetry { block(req.copy(useFallbacks = false)) }
            } else {
                throw e
            }
        }
    }

    private fun newRequest(apiKey: String, req: MessageRequest): Request {
        val body = RequestBuilder.build(req).toString().toRequestBody(HttpCalls.JSON_MEDIA)
        val builder = try {
            Request.Builder().url("$base/v1/messages").post(body)
        } catch (_: IllegalArgumentException) {
            throw AiErrors.badBaseUrl(baseUrl)
        }
        RequestBuilder.headers(apiKey, req, authMode).forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    internal fun parseCompletion(body: String): Completion {
        val obj = runCatching { HttpCalls.json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw AiErrors.badResponse()
        val content = obj["content"] as? JsonArray ?: JsonArray(emptyList())
        val sb = StringBuilder()
        for (el in content) {
            val block = el as? JsonObject ?: continue
            when (HttpCalls.str(block, "type")) {
                // После переключения на запасную модель берём только её ответ.
                "fallback" -> sb.setLength(0)
                "text" -> sb.append(HttpCalls.str(block, "text").orEmpty())
            }
        }
        val usage = (obj["usage"] as? JsonObject)?.let { u ->
            val input = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens").sumOf { HttpCalls.str(u, it)?.toIntOrNull() ?: 0 }
            Usage(input, HttpCalls.str(u, "output_tokens")?.toIntOrNull() ?: 0)
        }
        return Completion(sb.toString(), HttpCalls.str(obj, "stop_reason"), usage)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"

        /**
         * Адрес как в ANTHROPIC_BASE_URL: без «/v1/messages» на конце (клиент сам допишет его).
         * Вставленный целиком адрес тоже понимаем.
         */
        fun normalizeBaseUrl(url: String): String = url.trim()
            .removeSuffix("/")
            .removeSuffix("/messages")
            .removeSuffix("/v1")
            .removeSuffix("/")
            .ifBlank { DEFAULT_BASE_URL }
        const val API_VERSION = "2023-06-01"

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
