package app.nastavnik.claude

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Ошибка обращения к Claude с готовым русским текстом для пользователя. */
class ClaudeException(
    val kind: Kind,
    val userMessage: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : Exception(userMessage, cause) {
    enum class Kind { NO_KEY, AUTH, FORBIDDEN, NOT_FOUND, BAD_REQUEST, RATE_LIMIT, OVERLOADED, SERVER, NETWORK, REFUSAL, BAD_RESPONSE }

    /** Есть смысл молча повторить запрос ещё раз. */
    val retryable: Boolean get() = kind == Kind.OVERLOADED || kind == Kind.SERVER

    /** Ошибка про ключ — в панели показываем кнопку «Открыть настройки». */
    val needsSettings: Boolean get() = kind == Kind.NO_KEY || kind == Kind.AUTH || kind == Kind.FORBIDDEN || kind == Kind.NOT_FOUND
}

object ClaudeErrors {
    fun noKey() = ClaudeException(
        ClaudeException.Kind.NO_KEY,
        "Не задан API-ключ Anthropic. Открой настройки и вставь ключ с console.anthropic.com.",
    )

    fun refusal() = ClaudeException(
        ClaudeException.Kind.REFUSAL,
        "Модель отказалась отвечать на этот запрос. Попробуй переформулировать вопрос.",
    )

    fun badResponse(detail: String? = null) = ClaudeException(
        ClaudeException.Kind.BAD_RESPONSE,
        "Сервер вернул непонятный ответ" + (detail?.let { ": $it" } ?: ".") + " Попробуй ещё раз.",
    )

    /** HTTP-ошибка API. [apiType] и [apiMessage] — из тела ответа `{"error":{"type","message"}}`. */
    fun fromHttp(code: Int, apiType: String?, apiMessage: String?, retryAfterSec: Long? = null): ClaudeException {
        val msg = apiMessage.orEmpty()
        val lower = msg.lowercase()
        return when {
            code == 401 || apiType == "authentication_error" -> ClaudeException(
                ClaudeException.Kind.AUTH,
                "Неверный API-ключ (401). Проверь ключ в настройках — его можно создать на console.anthropic.com.",
                code,
            )
            lower.contains("credit balance") || lower.contains("billing") -> ClaudeException(
                ClaudeException.Kind.FORBIDDEN,
                "На балансе Anthropic закончились средства. Пополни баланс на console.anthropic.com → Billing.",
                code,
            )
            code == 403 || apiType == "permission_error" -> ClaudeException(
                ClaudeException.Kind.FORBIDDEN,
                "Доступ запрещён (403): у ключа нет прав на эту модель. Выбери другую модель в настройках.",
                code,
            )
            code == 404 || apiType == "not_found_error" -> ClaudeException(
                ClaudeException.Kind.NOT_FOUND,
                "Модель не найдена (404). Выбери другую модель в настройках.",
                code,
            )
            code == 413 || apiType == "request_too_large" -> ClaudeException(
                ClaudeException.Kind.BAD_REQUEST,
                "Запрос слишком большой: попробуй выделить область поменьше или начни новую задачу.",
                code,
            )
            code == 429 || apiType == "rate_limit_error" -> ClaudeException(
                ClaudeException.Kind.RATE_LIMIT,
                "Слишком много запросов (429). " +
                    (retryAfterSec?.let { "Подожди примерно $it с и попробуй снова." } ?: "Подожди минуту и попробуй снова."),
                code,
            )
            code == 529 || apiType == "overloaded_error" -> ClaudeException(
                ClaudeException.Kind.OVERLOADED,
                "Серверы Claude сейчас перегружены (529). Попробуй через минуту.",
                code,
            )
            code >= 500 || apiType == "api_error" -> ClaudeException(
                ClaudeException.Kind.SERVER,
                "Ошибка на стороне Anthropic (код $code). Попробуй ещё раз.",
                code,
            )
            else -> ClaudeException(
                ClaudeException.Kind.BAD_REQUEST,
                "Запрос отклонён (код $code)" + (if (msg.isNotBlank()) ": ${msg.take(200)}" else "."),
                code,
            )
        }
    }

    /** Ошибка из потока SSE (`event: error`). */
    fun fromStream(type: String?, message: String?): ClaudeException = when (type) {
        "overloaded_error" -> fromHttp(529, type, message)
        "rate_limit_error" -> fromHttp(429, type, message)
        "api_error" -> fromHttp(500, type, message)
        else -> fromHttp(400, type, message)
    }

    fun fromIo(e: IOException): ClaudeException = when (e) {
        is UnknownHostException -> ClaudeException(
            ClaudeException.Kind.NETWORK,
            "Нет подключения к интернету. Проверь Wi‑Fi или мобильную сеть.",
            cause = e,
        )
        is ConnectException -> ClaudeException(
            ClaudeException.Kind.NETWORK,
            "Не удалось подключиться к серверу Claude. Проверь интернет.",
            cause = e,
        )
        is SocketTimeoutException, is InterruptedIOException -> ClaudeException(
            ClaudeException.Kind.NETWORK,
            "Сервер слишком долго не отвечает. Проверь интернет и попробуй ещё раз.",
            cause = e,
        )
        is SSLException -> ClaudeException(
            ClaudeException.Kind.NETWORK,
            "Ошибка защищённого соединения. Проверь дату и время на планшете и сеть.",
            cause = e,
        )
        else -> ClaudeException(
            ClaudeException.Kind.NETWORK,
            "Ошибка сети. Проверь интернет и попробуй ещё раз.",
            cause = e,
        )
    }
}
