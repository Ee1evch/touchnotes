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

/**
 * Заметка наставника. Место задаётся номером строки ([lines]), «под строкой» ([after]) и/или рамкой [box]
 * (в долях присланного снимка). Приложение потом переводит всё это в точную рамку по чернилам.
 */
data class Mark(
    val kind: MarkKind,
    val box: NBox?,
    val text: String,
    val lines: IntRange? = null,
    val after: Int? = null,
)

data class ScreenAnswer(
    val marks: List<Mark>,
    /** `[[ГОТОВО]]` — решение верное и доведено до ответа. */
    val done: Boolean,
    /** `[[ВЕРНО]]` — тихая проверка не нашла ошибок, показывать нечего. */
    val allGood: Boolean,
    /** `@@ тема …` — короткое название задачи для истории. */
    val topic: String? = null,
)

/**
 * Разбирает ответ наставника вида
 * ```
 * @@ error стр 3
 * Проверь знак при раскрытии скобок.
 * @@ hint после 5
 * Что получится, если приравнять производную к нулю?
 * @@ error стр 4 : 410 520 980 590
 * ```
 * Понимает и голые координаты (`@@ error 410 520 980 590`). Работает на недописанном (потоковом) тексте.
 */
object ScreenAnswerParser {
    const val ALL_GOOD_MARKER = "[[ВЕРНО]]"

    private val header = Regex("""^\s*@@\s*([\p{L}_]+)(.*)$""")
    private val afterRe = Regex("""(?:после|под|after|below)\s*(?:стр\p{L}*\.?|line)?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val linesRe = Regex("""(?:стр\p{L}*\.?|lines?|L)\s*(\d+)(?:\s*[-–—]\s*(\d+))?""", RegexOption.IGNORE_CASE)
    private val rangeAtStart = Regex("""^[\s:\-]*(\d+)\s*[-–—]\s*(\d+)(?![\d.,])""")
    // Дробная часть — только через точку: запятая в «(10,10,20,20)» разделяет числа.
    private val numberRe = Regex("""-?\d+(?:\.\d+)?""")
    private val topicRe = Regex("""^\s*@@\s*(?:тема|topic)(?!\p{L})\s*[:\-—]?\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val allGoodRegex = Regex("""\[\[\s*ВЕРНО\s*\]\]""", RegexOption.IGNORE_CASE)

    private class Spec(val lines: IntRange?, val after: Int?, val coords: List<Float>?)

    /** Что после вида заметки: номера строк, «после N», координаты. */
    private fun parseSpec(restIn: String): Spec? {
        var rest = restIn
        var after: Int? = null
        var lines: IntRange? = null
        afterRe.find(rest)?.let { m ->
            after = m.groupValues[1].toInt()
            rest = rest.removeRange(m.range)
        }
        (linesRe.find(rest) ?: rangeAtStart.find(rest))?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toIntOrNull() ?: a
            lines = minOf(a, b)..maxOf(a, b)
            rest = rest.removeRange(m.range)
        }
        val nums = numberRe.findAll(rest).map { it.value.toFloat() }.toMutableList()
        // «@@ error 3» или «@@ error 3 410 520 980 590» — первое число тогда номер строки.
        if (lines == null && after == null && (nums.size == 1 || nums.size == 5)) {
            lines = nums.first().toInt()..nums.first().toInt()
            nums.removeAt(0)
        }
        val coords = if (nums.size >= 4) nums.takeLast(4) else null
        // В строке-метке не должно быть ничего, кроме этого; иначе это просто текст, начинающийся с «@@».
        val leftover = numberRe.replace(rest, "").replace(Regex("""[\s:;,\[\]()|xх×=-]+"""), "")
        if (leftover.isNotEmpty() && leftover.length > 3) return null
        return Spec(lines, after, coords)
    }

    fun parse(raw: String, mode: CoordMode, imageW: Int, imageH: Int, streaming: Boolean = false): ScreenAnswer {
        val done = DoneMarker.process(raw, streaming)
        val allGood = allGoodRegex.containsMatchIn(done.text)
        var text = allGoodRegex.replace(done.text, "")
        // Строка-метка, после которой ещё не пришёл перевод строки, может быть недописанной.
        if (streaming && !raw.endsWith("\n")) text = hidePartialHeader(text)

        val marks = mutableListOf<Mark>()
        var kind: MarkKind? = null
        var box: NBox? = null
        var lines: IntRange? = null
        var after: Int? = null
        val body = StringBuilder()
        var topic: String? = null
        fun flush() {
            val t = body.toString().trim()
            if (kind != null || t.isNotEmpty()) {
                if (t.isNotEmpty() || streaming) marks += Mark(kind ?: MarkKind.NOTE, box, t, lines, after)
            }
            body.setLength(0)
        }
        for (line in text.lines()) {
            val t = topicRe.matchEntire(line)
            if (t != null) {
                topic = t.groupValues[1].trim().trim('«', '»', '"', '.').take(80).ifBlank { null }
                continue
            }
            val m = header.matchEntire(line)
            val k = m?.let { MarkKind.parse(it.groupValues[1]) }
            val spec = if (k != null) parseSpec(m!!.groupValues[2]) else null
            if (k != null && spec != null) {
                flush()
                kind = k
                box = spec.coords?.let { toBox(it, mode, imageW, imageH) }
                lines = spec.lines
                after = spec.after
            } else {
                body.append(line).append('\n')
            }
        }
        flush()
        // Пустые заметки, кроме последней недописанной, не нужны.
        val cleaned = marks.filterIndexed { i, mk -> mk.text.isNotEmpty() || (streaming && i == marks.lastIndex) }
        return ScreenAnswer(cleaned, done.done, allGood && cleaned.none { it.kind == MarkKind.ERROR }, topic)
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
