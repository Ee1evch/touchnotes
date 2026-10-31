package app.nastavnik.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.ceil
import kotlin.math.min

/** Токены одного запроса: сколько ушло в модель и сколько она написала. */
data class Usage(val input: Int, val output: Int)

/**
 * Примерные цены за миллион токенов (вход / выход), в долларах. У Claude — официальные цены API,
 * у остальных — ориентир по похожим моделям: прокси и сервисы считают по-своему.
 */
object Pricing {
    data class Price(val input: Double, val output: Double)

    private val KNOWN = listOf(
        "haiku-5-5" to Price(0.10, 0.50),
        "sonnet-5-5" to Price(2.0, 10.0),
        "opus-5-5" to Price(4.0, 20.0),
        "fable" to Price(10.0, 50.0),
        "haiku" to Price(1.0, 5.0),
        "sonnet" to Price(3.0, 15.0),
        "opus" to Price(5.0, 25.0),
        "flash-lite" to Price(0.10, 0.40),
        "flash" to Price(0.30, 2.50),
        "gemini" to Price(1.25, 10.0),
        "gpt-5-nano" to Price(0.05, 0.40),
        "gpt-5-mini" to Price(0.25, 2.0),
        "mini" to Price(0.25, 2.0),
        "gpt" to Price(1.25, 10.0),
    )

    /** Неизвестная модель — средняя цена, чтобы лимит всё равно работал. */
    val DEFAULT = Price(1.0, 5.0)

    fun of(model: String): Price {
        val m = model.lowercase()
        return KNOWN.firstOrNull { m.contains(it.first) }?.second ?: DEFAULT
    }

    fun cost(model: String, u: Usage): Double {
        val p = of(model)
        return (u.input * p.input + u.output * p.output) / 1_000_000.0
    }
}

/** Оценка токенов, когда сервис не сообщает их сам (Gemini, OpenAI-совместимые без usage). */
object TokenEstimate {
    /** Русский текст — примерно 3 символа на токен (латиница и формулы — около 4). */
    fun text(s: String): Int = chars(s.length)

    fun chars(n: Int): Int = ceil(n / 3.0).toInt()

    /** Картинка: как считает Claude — ширина × высота / 750, не больше ~1600 токенов на снимок до 1,15 Мп. */
    fun image(jpeg: ByteArray): Int {
        val (w, h) = JpegSize.of(jpeg) ?: return 1200
        return min(ceil(w.toDouble() * h / 750.0).toInt(), 6000)
    }

    fun request(r: ChatRequest): Int =
        text(r.system) + r.turns.sumOf { text(it.text) + 4 } + r.images.sumOf { image(it.bytes) }
}

/** Размер JPEG по заголовку SOF — без декодирования картинки. */
object JpegSize {
    fun of(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return null
        var i = 2
        while (i + 9 < b.size) {
            if ((b[i].toInt() and 0xFF) != 0xFF) return null
            val marker = b[i + 1].toInt() and 0xFF
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            val sof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (sof) {
                val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                return w to h
            }
            i += 2 + len
        }
        return null
    }
}

/** Расходы за один день. */
@Serializable
data class DayUsage(val day: Long, val requests: Int = 0, val input: Long = 0, val output: Long = 0, val usd: Double = 0.0)

/** Журнал расходов по дням (хранится в настройках как JSON, последние [KEEP_DAYS] дней). */
object UsageLog {
    const val KEEP_DAYS = 60
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String?): List<DayUsage> = raw?.let { runCatching { json.decodeFromString<List<DayUsage>>(it) }.getOrNull() }.orEmpty()
    fun encode(list: List<DayUsage>): String = json.encodeToString(list)

    fun add(list: List<DayUsage>, day: Long, u: Usage, usd: Double): List<DayUsage> {
        val cur = list.firstOrNull { it.day == day } ?: DayUsage(day)
        val next = cur.copy(requests = cur.requests + 1, input = cur.input + u.input, output = cur.output + u.output, usd = cur.usd + usd)
        return (list.filter { it.day != day } + next).filter { it.day > day - KEEP_DAYS }.sortedBy { it.day }
    }

    fun today(list: List<DayUsage>, day: Long): DayUsage = list.firstOrNull { it.day == day } ?: DayUsage(day)

    /** Сумма за последние [days] дней, включая сегодня. */
    fun total(list: List<DayUsage>, day: Long, days: Int): Double = list.filter { it.day > day - days && it.day <= day }.sumOf { it.usd }

    /** «$0.04», «$1.20», «меньше цента». */
    fun money(usd: Double): String = when {
        usd <= 0.0 -> "$0"
        usd < 0.01 -> "меньше цента"
        usd < 10 -> "$" + String.format(java.util.Locale.US, "%.2f", usd)
        else -> "$" + String.format(java.util.Locale.US, "%.1f", usd)
    }
}
