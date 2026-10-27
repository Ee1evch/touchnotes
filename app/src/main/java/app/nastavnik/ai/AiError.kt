package app.nastavnik.ai

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Ошибка обращения к нейросети с готовым русским текстом для пользователя. */
class AiException(
    val kind: Kind,
    val userMessage: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : Exception(userMessage, cause) {
    enum class Kind { NO_KEY, AUTH, FORBIDDEN, NOT_FOUND, BAD_REQUEST, RATE_LIMIT, OVERLOADED, SERVER, NETWORK, REFUSAL, BAD_RESPONSE }

    /** Есть смысл молча повторить запрос ещё раз. */
    val retryable: Boolean get() = kind == Kind.OVERLOADED || kind == Kind.SERVER

    /** Ошибка про ключ или модель — в панели показываем кнопку «Открыть настройки». */
    val needsSettings: Boolean get() = kind == Kind.NO_KEY || kind == Kind.AUTH || kind == Kind.FORBIDDEN || kind == Kind.NOT_FOUND
}

object AiErrors {
    fun noKey(p: AiProvider = AiProvider.ANTHROPIC) = AiException(
        AiException.Kind.NO_KEY,
        "Не задан API-ключ (${p.shortTitle}). Открой настройки и вставь ключ — его выдают на ${p.keySite}.",
    )

    fun noModel() = AiException(
        AiException.Kind.NOT_FOUND,
        "Не выбрана модель. Открой настройки → «Модель» и выбери её из списка.",
    )

    fun badBaseUrl(url: String) = AiException(
        AiException.Kind.NOT_FOUND,
        "Неверный адрес сервера: «$url». Он должен начинаться с http:// или https://.",
    )

    fun refusal() = AiException(
        AiException.Kind.REFUSAL,
        "Модель отказалась отвечать на этот запрос. Попробуй переформулировать вопрос.",
    )

    fun badResponse(detail: String? = null) = AiException(
        AiException.Kind.BAD_RESPONSE,
        "Сервер вернул непонятный ответ" + (detail?.let { ": $it" } ?: ".") + " Попробуй ещё раз.",
    )

    /**
     * HTTP-ошибка API. [apiType] и [apiMessage] — из тела ответа
     * (`error.type` у Anthropic и OpenAI, `error.status` у Google).
     */
    fun fromHttp(
        code: Int,
        apiType: String?,
        apiMessage: String?,
        retryAfterSec: Long? = null,
        p: AiProvider = AiProvider.ANTHROPIC,
    ): AiException {
        val msg = apiMessage.orEmpty()
        val lower = msg.lowercase()
        val type = apiType.orEmpty().lowercase()
        return when {
            code == 401 || type == "authentication_error" || type == "unauthenticated" ||
                lower.contains("api key not valid") || lower.contains("invalid api key") || lower.contains("incorrect api key") -> AiException(
                AiException.Kind.AUTH,
                "Неверный API-ключ ($code). Проверь ключ в настройках — его выдают на ${p.keySite}.",
                code,
            )
            lower.contains("credit balance") || lower.contains("billing") || type == "insufficient_quota" ||
                lower.contains("exceeded your current quota") || lower.contains("insufficient credits") || code == 402 -> AiException(
                AiException.Kind.FORBIDDEN,
                "На балансе закончились средства или не подключена оплата. Проверь баланс на ${p.keySite}.",
                code,
            )
            code == 403 || type == "permission_error" || type == "permission_denied" -> AiException(
                AiException.Kind.FORBIDDEN,
                "Доступ запрещён (403): у ключа нет прав на эту модель или сервис недоступен в регионе. Выбери другую модель в настройках.",
                code,
            )
            code == 404 || type == "not_found_error" || type == "not_found" -> AiException(
                AiException.Kind.NOT_FOUND,
                "Модель не найдена (404). В настройках нажми «Загрузить список моделей» и выбери из списка.",
                code,
            )
            code == 413 || type == "request_too_large" -> AiException(
                AiException.Kind.BAD_REQUEST,
                "Запрос слишком большой: попробуй выделить область поменьше или начни новую задачу.",
                code,
            )
            code == 429 || type == "rate_limit_error" || type == "resource_exhausted" -> AiException(
                AiException.Kind.RATE_LIMIT,
                "Слишком много запросов или исчерпан лимит (429). " +
                    (retryAfterSec?.let { "Подожди примерно $it с и попробуй снова." } ?: "Подожди минуту и попробуй снова."),
                code,
            )
            code == 529 || code == 503 || type == "overloaded_error" || type == "unavailable" -> AiException(
                AiException.Kind.OVERLOADED,
                "Серверы ${p.shortTitle} сейчас перегружены ($code). Попробуй через минуту.",
                code,
            )
            code >= 500 || type == "api_error" || type == "internal" -> AiException(
                AiException.Kind.SERVER,
                "Ошибка на стороне сервера ${p.shortTitle} (код $code). Попробуй ещё раз.",
                code,
            )
            else -> AiException(
                AiException.Kind.BAD_REQUEST,
                "Запрос отклонён (код $code)" + (if (msg.isNotBlank()) ": ${msg.take(200)}" else "."),
                code,
            )
        }
    }

    /** Ошибка из потока SSE (`event: error` у Anthropic). */
    fun fromStream(type: String?, message: String?, p: AiProvider = AiProvider.ANTHROPIC): AiException = when (type) {
        "overloaded_error" -> fromHttp(529, type, message, p = p)
        "rate_limit_error" -> fromHttp(429, type, message, p = p)
        "api_error" -> fromHttp(500, type, message, p = p)
        else -> fromHttp(400, type, message, p = p)
    }

    fun fromIo(e: IOException): AiException = when (e) {
        is UnknownHostException -> AiException(
            AiException.Kind.NETWORK,
            "Нет подключения к интернету. Проверь Wi‑Fi или мобильную сеть.",
            cause = e,
        )
        is ConnectException -> AiException(
            AiException.Kind.NETWORK,
            "Не удалось подключиться к серверу. Проверь интернет (и адрес сервера в настройках, если он свой).",
            cause = e,
        )
        is SocketTimeoutException, is InterruptedIOException -> AiException(
            AiException.Kind.NETWORK,
            "Сервер слишком долго не отвечает. Проверь интернет и попробуй ещё раз.",
            cause = e,
        )
        is SSLException -> AiException(
            AiException.Kind.NETWORK,
            "Ошибка защищённого соединения. Проверь дату и время на планшете и сеть.",
            cause = e,
        )
        else -> AiException(
            AiException.Kind.NETWORK,
            "Ошибка сети. Проверь интернет и попробуй ещё раз.",
            cause = e,
        )
    }
}
