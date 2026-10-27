package app.nastavnik.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Одно событие Server-Sent Events: имя (`event:`) и склеенные строки `data:`. */
data class SseEvent(val event: String?, val data: String)

/**
 * Построчный разбор SSE по спецификации WHATWG: событие заканчивается пустой строкой,
 * строки `data:` склеиваются через `\n`, строки-комментарии (`:`) пропускаются.
 */
class SseParser {
    private var eventName: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Скармливает одну строку без завершающего перевода строки. Возвращает событие, если оно завершено. */
    fun feedLine(rawLine: String): SseEvent? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> eventName = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    /** Завершает поток: отдаёт последнее событие, если сервер не прислал финальную пустую строку. */
    fun finish(): SseEvent? = flush()

    private fun flush(): SseEvent? {
        if (!hasData && eventName == null) return null
        val ev = SseEvent(eventName, data.toString())
        eventName = null
        data.setLength(0)
        hasData = false
        return if (ev.data.isEmpty() && ev.event == null) null else ev
    }

    companion object {
        fun parseAll(text: String): List<SseEvent> {
            val p = SseParser()
            val out = ArrayList<SseEvent>()
            text.split('\n').forEach { line -> p.feedLine(line)?.let(out::add) }
            p.finish()?.let(out::add)
            return out
        }
    }
}

/** Что интересно интерфейсу из потока Messages API. */
sealed interface StreamUpdate {
    /** Новый кусок текста ответа. */
    data class Text(val delta: String) : StreamUpdate

    /** Модель начала думать (adaptive thinking) — можно показать «Думаю…». */
    data object Thinking : StreamUpdate

    /** Сервер переключился на запасную модель: уже показанный текст нужно сбросить. */
    data object Restart : StreamUpdate

    data class Stop(val stopReason: String?) : StreamUpdate

    data class Error(val type: String?, val message: String?) : StreamUpdate
}

/** Превращает события SSE Messages API в [StreamUpdate]. Неизвестные события пропускаются. */
object StreamDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(ev: SseEvent): StreamUpdate? {
        if (ev.data.isBlank()) return null
        val obj = runCatching { json.parseToJsonElement(ev.data).jsonObject }.getOrNull() ?: return null
        val type = obj.str("type") ?: ev.event
        return when (type) {
            "content_block_start" -> {
                val block = obj["content_block"] as? JsonObject
                when (block?.str("type")) {
                    "thinking", "redacted_thinking" -> StreamUpdate.Thinking
                    "fallback" -> StreamUpdate.Restart
                    "text" -> block.str("text")?.takeIf { it.isNotEmpty() }?.let { StreamUpdate.Text(it) }
                    else -> null
                }
            }
            "content_block_delta" -> {
                val delta = obj["delta"] as? JsonObject
                when (delta?.str("type")) {
                    "text_delta" -> delta.str("text")?.let { StreamUpdate.Text(it) }
                    else -> null
                }
            }
            "message_delta" -> {
                val delta = obj["delta"] as? JsonObject
                val reason = delta?.str("stop_reason")
                if (reason != null) StreamUpdate.Stop(reason) else null
            }
            "message_stop" -> StreamUpdate.Stop(null)
            "error" -> {
                val err = obj["error"] as? JsonObject
                StreamUpdate.Error(err?.str("type"), err?.str("message"))
            }
            else -> null
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
