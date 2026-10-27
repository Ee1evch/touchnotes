package app.nastavnik.ai

/** Вид заметки на экране: от него зависят цвет рамки и подпись. */
enum class MarkKind(val label: String, val token: String) {
    ERROR("Ошибка", "error"),
    HINT("Подсказка", "hint"),
    OK("Верно", "ok"),
    THEORY("Теория", "theory"),
    NOTE("Наставник", "note");

    companion object {
        private val synonyms = mapOf(
            "error" to ERROR, "mistake" to ERROR, "err" to ERROR, "ошибка" to ERROR,
            "hint" to HINT, "next" to HINT, "step" to HINT, "подсказка" to HINT, "шаг" to HINT,
            "ok" to OK, "correct" to OK, "good" to OK, "верно" to OK,
            "theory" to THEORY, "теория" to THEORY,
            "note" to NOTE, "info" to NOTE, "заметка" to NOTE,
        )

        fun parse(word: String): MarkKind? = synonyms[word.lowercase()]
    }
}

/**
 * Как модель записывает координаты рамки.
 * Claude видит снимок в его настоящих пикселях; Gemini обучен на box_2d (y, x, нормировано на 1000);
 * у OpenAI-совместимых сервис может сам уменьшить картинку, поэтому для них тоже доли от 1000.
 */
enum class CoordMode { PIXELS_XY, NORM_XY, NORM_YX }

/** Рамка в долях снимка: 0…1 по ширине и высоте. */
data class NBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class Mark(val kind: MarkKind, val box: NBox?, val text: String)

data class ScreenAnswer(
    val marks: List<Mark>,
    /** `[[ГОТОВО]]` — решение верное и доведено до ответа. */
    val done: Boolean,
    /** `[[ВЕРНО]]` — тихая проверка не нашла ошибок, показывать нечего. */
    val allGood: Boolean,
)

/**
 * Разбирает ответ наставника вида
 * ```
 * @@ error 410 520 980 590
 * Проверь знак при раскрытии скобок.
 * @@ hint 400 600 1200 680
 * Что получится, если приравнять производную к нулю?
 * ```
 * Работает и на недописанном (потоковом) тексте.
 */
object ScreenAnswerParser {
    const val ALL_GOOD_MARKER = "[[ВЕРНО]]"

    private val num = """(-?\d+(?:[.,]\d+)?)"""
    private val sep = """[\s,;]+"""
    private val header = Regex(
        """^\s*@@\s*([\p{L}_]+)\s*[:\-]?\s*(?:[\[(]?\s*$num$sep$num$sep$num$sep$num\s*[\])]?)?\s*$""",
    )
    private val allGoodRegex = Regex("""\[\[\s*ВЕРНО\s*\]\]""", RegexOption.IGNORE_CASE)

    fun parse(raw: String, mode: CoordMode, imageW: Int, imageH: Int, streaming: Boolean = false): ScreenAnswer {
        val done = DoneMarker.process(raw, streaming)
        val allGood = allGoodRegex.containsMatchIn(done.text)
        var text = allGoodRegex.replace(done.text, "")
        // Строка-метка, после которой ещё не пришёл перевод строки, может быть недописанной.
        if (streaming && !raw.endsWith("\n")) text = hidePartialHeader(text)

        val marks = mutableListOf<Mark>()
        var kind: MarkKind? = null
        var box: NBox? = null
        val body = StringBuilder()
        fun flush() {
            val t = body.toString().trim()
            if (kind != null || t.isNotEmpty()) {
                if (t.isNotEmpty() || streaming) marks += Mark(kind ?: MarkKind.NOTE, box, t)
            }
            body.setLength(0)
        }
        for (line in text.lines()) {
            val m = header.matchEntire(line)
            val k = m?.let { MarkKind.parse(it.groupValues[1]) }
            if (m != null && k != null) {
                flush()
                kind = k
                box = if (m.groupValues[2].isNotEmpty()) {
                    toBox((2..5).map { m.groupValues[it].replace(',', '.').toFloat() }, mode, imageW, imageH)
                } else {
                    null
                }
            } else {
                body.append(line).append('\n')
            }
        }
        flush()
        // Пустые заметки, кроме последней недописанной, не нужны.
        val cleaned = marks.filterIndexed { i, mk -> mk.text.isNotEmpty() || (streaming && i == marks.lastIndex) }
        return ScreenAnswer(cleaned, done.done, allGood && cleaned.none { it.kind == MarkKind.ERROR })
    }

    /** Текст для истории и для следующих запросов: без координат, с подписями. */
    fun toPlain(answer: ScreenAnswer): String =
        answer.marks.joinToString("\n\n") { m ->
            if (m.kind == MarkKind.NOTE) m.text else "**${m.kind.label}.** ${m.text}"
        }

    /** Пока строка `@@ …` не дописана до конца, её не показываем. */
    private fun hidePartialHeader(text: String): String {
        val lastNl = text.lastIndexOf('\n')
        val last = text.substring(lastNl + 1)
        return if (last.trimStart().startsWith("@")) text.substring(0, (lastNl + 1).coerceAtLeast(0)) else text
    }

    internal fun toBox(v: List<Float>, mode: CoordMode, imageW: Int, imageH: Int): NBox? {
        if (imageW <= 0 || imageH <= 0) return null
        var (x0, y0, x1, y1) = when (mode) {
            CoordMode.NORM_YX -> listOf(v[1], v[0], v[3], v[2])
            else -> v
        }
        var effective = mode
        // Модель перепутала систему: прислала пиксели вместо долей от 1000.
        if (mode != CoordMode.PIXELS_XY && maxOf(x0, x1) > 1000.5f && maxOf(x0, x1) <= imageW + 1) {
            if (mode == CoordMode.NORM_YX) { x0 = v[0]; y0 = v[1]; x1 = v[2]; y1 = v[3] }
            effective = CoordMode.PIXELS_XY
        }
        val (sx, sy) = if (effective == CoordMode.PIXELS_XY) imageW.toFloat() to imageH.toFloat() else 1000f to 1000f
        var l = minOf(x0, x1) / sx
        var r = maxOf(x0, x1) / sx
        var t = minOf(y0, y1) / sy
        var b = maxOf(y0, y1) / sy
        if (r < 0f || b < 0f || l > 1f || t > 1f) return null
        l = l.coerceIn(0f, 1f); r = r.coerceIn(0f, 1f); t = t.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
        // Слишком тонкую рамку (точку, линию) расширяем до видимой.
        val minW = 0.04f
        val minH = 0.025f
        if (r - l < minW) { val c = (l + r) / 2; l = (c - minW / 2).coerceAtLeast(0f); r = (l + minW).coerceAtMost(1f) }
        if (b - t < minH) { val c = (t + b) / 2; t = (c - minH / 2).coerceAtLeast(0f); b = (t + minH).coerceAtMost(1f) }
        return NBox(l, t, r, b)
    }
}
