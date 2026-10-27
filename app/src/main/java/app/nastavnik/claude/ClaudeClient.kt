package app.nastavnik.claude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Итог обычного (не потокового) запроса. */
data class Completion(val text: String, val stopReason: String?)

/** Результат проверки ключа в настройках. */
sealed interface KeyCheck {
    data object Ok : KeyCheck
    data class ModelMissing(val modelId: String) : KeyCheck
    data class Failed(val message: String) : KeyCheck
}

/**
 * Клиент Messages API поверх OkHttp. Ключ передаётся в каждый вызов и нигде не логируется.
 */
class ClaudeClient(
    private val http: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Если сервер однажды отверг параметр `fallbacks`, больше его не шлём. */
    @Volatile
    private var fallbacksRejected = false

    /**
     * Потоковый запрос. Каждое обновление уходит в [onUpdate] (вызывается в фоновом потоке).
     * Возвращает stop_reason. Бросает [ClaudeException].
     */
    suspend fun stream(apiKey: String, request: MessageRequest, onUpdate: (StreamUpdate) -> Unit): String? {
        val req = request.copy(stream = true)
        return withRetries(apiKey, req) { effective ->
            var anyOutput = false
            val stopReason = executeStreaming(apiKey, effective) { update ->
                if (update is StreamUpdate.Text) anyOutput = true
                onUpdate(update)
            }
            if (stopReason == "refusal") throw ClaudeErrors.refusal()
            stopReason to anyOutput
        }
    }

    /** Обычный запрос без стрима (для чистовика). */
    suspend fun complete(apiKey: String, request: MessageRequest): Completion {
        val req = request.copy(stream = false)
        return withRetries(apiKey, req) { effective ->
            val body = executePlain(apiKey, effective)
            val completion = parseCompletion(body)
            if (completion.stopReason == "refusal") throw ClaudeErrors.refusal()
            completion to false
        }
    }

    /** Бесплатная проверка: GET /v1/models/{id} проверяет и ключ, и доступность модели. */
    suspend fun checkKey(apiKey: String, modelId: String): KeyCheck = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext KeyCheck.Failed(ClaudeErrors.noKey().userMessage)
        val request = Request.Builder()
            .url("$baseUrl/v1/models/$modelId")
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .get()
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> KeyCheck.Ok
                    resp.code == 404 -> KeyCheck.ModelMissing(modelId)
                    else -> KeyCheck.Failed(httpError(resp).userMessage)
                }
            }
        } catch (e: IOException) {
            KeyCheck.Failed(ClaudeErrors.fromIo(e).userMessage)
        }
    }

    /**
     * Общая логика повторов: один повтор при перегрузке/5xx (если ещё ничего не напечатано)
     * и повтор без `fallbacks`, если сервер не принял этот параметр.
     */
    private suspend fun <T> withRetries(
        apiKey: String,
        request: MessageRequest,
        block: suspend (MessageRequest) -> Pair<T, Boolean>,
    ): T {
        if (apiKey.isBlank()) throw ClaudeErrors.noKey()
        var req = if (fallbacksRejected) request.copy(useFallbacks = false) else request
        var attempt = 0
        while (true) {
            try {
                return block(req).first
            } catch (e: ClaudeException) {
                if (e.kind == ClaudeException.Kind.BAD_REQUEST && req.useFallbacks &&
                    e.userMessage.contains("fallback", ignoreCase = true)
                ) {
                    fallbacksRejected = true
                    req = req.copy(useFallbacks = false)
                    continue
                }
                if (e.retryable && attempt == 0 && e.cause !is StreamStartedMarker) {
                    attempt++
                    delay(1500)
                    continue
                }
                throw e
            }
        }
    }

    private suspend fun executeStreaming(
        apiKey: String,
        req: MessageRequest,
        onUpdate: (StreamUpdate) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val call = newCall(apiKey, req, http)
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        var printed = false
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp)
                val source = resp.body?.source() ?: throw ClaudeErrors.badResponse("пустой ответ")
                val parser = SseParser()
                var stopReason: String? = null
                fun handle(ev: SseEvent) {
                    when (val update = StreamDecoder.decode(ev) ?: return) {
                        is StreamUpdate.Error -> {
                            val err = ClaudeErrors.fromStream(update.type, update.message)
                            // Если текст уже печатался, повторять запрос нельзя — пусть решает пользователь.
                            throw if (printed) ClaudeException(err.kind, err.userMessage, err.httpCode, StreamStartedMarker()) else err
                        }
                        is StreamUpdate.Stop -> {
                            if (update.stopReason != null) stopReason = update.stopReason
                            onUpdate(update)
                        }
                        is StreamUpdate.Text -> {
                            printed = true
                            onUpdate(update)
                        }
                        else -> onUpdate(update)
                    }
                }
                while (true) {
                    ensureActive()
                    val line = source.readUtf8Line() ?: break
                    parser.feedLine(line)?.let(::handle)
                }
                parser.finish()?.let(::handle)
                stopReason
            }
        } catch (e: IOException) {
            val err = ClaudeErrors.fromIo(e)
            throw if (printed) ClaudeException(err.kind, err.userMessage, null, StreamStartedMarker()) else err
        } finally {
            cancelHandle.dispose()
        }
    }

    private suspend fun executePlain(apiKey: String, req: MessageRequest): String = withContext(Dispatchers.IO) {
        val client = http.newBuilder().readTimeout(10, TimeUnit.MINUTES).callTimeout(12, TimeUnit.MINUTES).build()
        val call = newCall(apiKey, req, client)
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp)
                resp.body?.string() ?: throw ClaudeErrors.badResponse("пустой ответ")
            }
        } catch (e: IOException) {
            throw ClaudeErrors.fromIo(e)
        } finally {
            cancelHandle.dispose()
        }
    }

    private fun newCall(apiKey: String, req: MessageRequest, client: OkHttpClient): Call {
        val body = RequestBuilder.build(req).toString().toRequestBody(JSON_MEDIA)
        val builder = Request.Builder().url("$baseUrl/v1/messages").post(body)
        RequestBuilder.headers(apiKey, req).forEach { (k, v) -> builder.header(k, v) }
        return client.newCall(builder.build())
    }

    internal fun parseCompletion(body: String): Completion {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw ClaudeErrors.badResponse()
        val content = obj["content"] as? JsonArray ?: JsonArray(emptyList())
        val sb = StringBuilder()
        for (el in content) {
            val block = el as? JsonObject ?: continue
            when ((block["type"] as? JsonPrimitive)?.contentOrNull) {
                // После переключения на запасную модель берём только её ответ.
                "fallback" -> sb.setLength(0)
                "text" -> sb.append((block["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            }
        }
        val stop = (obj["stop_reason"] as? JsonPrimitive)?.contentOrNull
        return Completion(sb.toString(), stop)
    }

    private fun httpError(resp: Response): ClaudeException {
        val raw = runCatching { resp.body?.string() }.getOrNull().orEmpty()
        val err = runCatching { json.parseToJsonElement(raw).jsonObject["error"] as? JsonObject }.getOrNull()
        val type = (err?.get("type") as? JsonPrimitive)?.contentOrNull
        val message = (err?.get("message") as? JsonPrimitive)?.contentOrNull
        val retryAfter = resp.header("retry-after")?.toDoubleOrNull()?.toLong()
        return ClaudeErrors.fromHttp(resp.code, type, message, retryAfter)
    }

    /** Пометка «текст уже начал печататься» — такие ошибки не повторяем автоматически. */
    private class StreamStartedMarker : Exception()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val API_VERSION = "2023-06-01"
        private val JSON_MEDIA = "application/json".toMediaType()

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
