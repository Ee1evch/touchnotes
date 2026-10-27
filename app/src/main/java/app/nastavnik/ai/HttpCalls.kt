package app.nastavnik.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Общие для всех провайдеров HTTP-вызовы: отмена корутины рвёт соединение, сеть → [AiException]. */
internal object HttpCalls {
    val JSON_MEDIA = "application/json".toMediaType()
    val json = Json { ignoreUnknownKeys = true }

    /** Пометка «текст уже начал печататься» — такие ошибки не повторяем автоматически. */
    class StreamStarted : Exception()

    /**
     * Потоковый запрос: каждую SSE-строку отдаёт в [onEvent] (через [SseParser]).
     * [onEvent] возвращает true, если в событии был текст ответа.
     */
    suspend fun stream(
        client: OkHttpClient,
        request: Request,
        errorOf: (Response) -> AiException,
        onEvent: (SseEvent) -> Boolean,
    ) = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        var printed = false
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw errorOf(resp)
                val source = resp.body?.source() ?: throw AiErrors.badResponse("пустой ответ")
                val parser = SseParser()
                fun handle(ev: SseEvent) {
                    try {
                        if (onEvent(ev)) printed = true
                    } catch (e: AiException) {
                        throw if (printed) AiException(e.kind, e.userMessage, e.httpCode, StreamStarted()) else e
                    }
                }
                while (true) {
                    ensureActive()
                    val line = source.readUtf8Line() ?: break
                    parser.feedLine(line)?.let(::handle)
                }
                parser.finish()?.let(::handle)
            }
        } catch (e: IOException) {
            val err = AiErrors.fromIo(e)
            throw if (printed) AiException(err.kind, err.userMessage, null, StreamStarted()) else err
        } finally {
            handle.dispose()
        }
    }

    /** Обычный запрос; длинный таймаут — чистовик может готовиться минуты. */
    suspend fun plain(client: OkHttpClient, request: Request, errorOf: (Response) -> AiException): String =
        withContext(Dispatchers.IO) {
            val long = client.newBuilder().readTimeout(10, TimeUnit.MINUTES).callTimeout(12, TimeUnit.MINUTES).build()
            val call = long.newCall(request)
            val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw errorOf(resp)
                    resp.body?.string() ?: throw AiErrors.badResponse("пустой ответ")
                }
            } catch (e: IOException) {
                throw AiErrors.fromIo(e)
            } finally {
                handle.dispose()
            }
        }

    /** Один повтор при перегрузке/5xx, если ещё ничего не напечатано. */
    suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: AiException) {
                if (e.retryable && attempt == 0 && e.cause !is StreamStarted) {
                    attempt++
                    delay(1500)
                    continue
                }
                throw e
            }
        }
    }

    /**
     * Разбор тела ошибки: `{"error":{"type"|"status"|"code", "message"}}`
     * (Anthropic, OpenAI и Google пишут по-разному).
     */
    fun errorFromResponse(resp: Response, provider: AiProvider): AiException {
        val raw = runCatching { resp.body?.string() }.getOrNull().orEmpty()
        val err = runCatching { json.parseToJsonElement(raw).jsonObject["error"] as? JsonObject }.getOrNull()
        fun str(k: String) = (err?.get(k) as? JsonPrimitive)?.contentOrNull
        val type = str("type") ?: str("status") ?: str("code")
        val message = str("message") ?: raw.take(200).takeIf { err == null && it.isNotBlank() }
        val retryAfter = resp.header("retry-after")?.toDoubleOrNull()?.toLong()
        return AiErrors.fromHttp(resp.code, type, message, retryAfter, provider)
    }

    fun str(obj: JsonObject?, key: String): String? = (obj?.get(key) as? JsonPrimitive)?.contentOrNull
}
