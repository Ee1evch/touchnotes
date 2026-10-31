package app.nastavnik.study

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Ошибка, которую нашёл наставник: короткая подсказка, тема и когда. */
@Serializable
data class Mistake(val text: String, val topic: String, val at: Long)

/**
 * Память о типичных ошибках студента: последние ошибки уходят наставнику в системный промт,
 * и он мягко напоминает, если ошибка повторяется («опять потерял минус при раскрытии скобок»).
 */
object MistakeMemory {
    private val json = Json { ignoreUnknownKeys = true }
    const val KEEP = 30
    const val IN_PROMPT = 8

    fun decode(raw: String?): List<Mistake> =
        raw?.let { runCatching { json.decodeFromString<List<Mistake>>(it) }.getOrNull() }.orEmpty()

    fun encode(list: List<Mistake>): String = json.encodeToString(list)

    /** Добавить новые ошибки (повторы того же текста не копим — обновляем время). */
    fun add(list: List<Mistake>, new: List<Mistake>): List<Mistake> {
        val byText = LinkedHashMap<String, Mistake>()
        (list + new).forEach { m -> byText[normalize(m.text)] = m }
        return byText.values.sortedBy { it.at }.takeLast(KEEP)
    }

    /** Строки для промта: свежие ошибки, без формул-простыней. */
    fun forPrompt(list: List<Mistake>): List<String> =
        list.sortedByDescending { it.at }.take(IN_PROMPT).map { m ->
            val t = m.text.replace(Regex("""\s+"""), " ").trim().take(140)
            if (m.topic.isBlank()) t else "$t (тема: ${m.topic.take(60)})"
        }

    private fun normalize(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}

/** Текст подсказки для озвучки: без разметки, формулы — словами, как их читают вслух. */
object SpeakText {
    private val replacements = listOf(
        Regex("""\\frac\{([^{}]*)\}\{([^{}]*)\}""") to "$1 делить на $2",
        Regex("""\\sqrt\{([^{}]*)\}""") to "корень из $1",
        Regex("""\^\{?2\}?""") to " в квадрате",
        Regex("""\^\{?3\}?""") to " в кубе",
        Regex("""\^\{([^{}]*)\}""") to " в степени $1",
        Regex("""\^(\w)""") to " в степени $1",
        Regex("""_\{([^{}]*)\}""") to " $1",
        Regex("""_(\w)""") to " $1",
        Regex("""\\(?:cdot|times)""") to " умножить на ",
        Regex("""\\(?:le|leq)\b""") to " меньше или равно ",
        Regex("""\\(?:ge|geq)\b""") to " больше или равно ",
        Regex("""\\(?:ne|neq)\b""") to " не равно ",
        Regex("""\\to\b""") to " стремится к ",
        Regex("""\\infty""") to " бесконечность",
        Regex("""\\pi\b""") to " пи",
        Regex("""\\lim""") to " предел ",
        Regex("""\\sum""") to " сумма ",
        Regex("""\\int""") to " интеграл ",
        Regex("""\\pm""") to " плюс-минус ",
        Regex("""\\Rightarrow""") to " следовательно ",
        Regex("""\\[a-zA-Z]+""") to " ",
    )

    fun from(markdown: String): String {
        var s = markdown
        // Формулы: сначала содержимое $…$, потом общая чистка.
        s = s.replace(Regex("""\$\$?([^$]+)\$\$?""")) { m -> " " + mathToWords(m.groupValues[1]) + " " }
        s = s.replace(Regex("""[*_`#>]+"""), "")
        // Пробел перед знаком препинания — остаток от формулы.
        return s.replace(Regex("""\s+"""), " ").replace(Regex("""\s+([,.?!:;])"""), "$1").trim()
    }

    fun mathToWords(tex: String): String {
        var s = tex
        replacements.forEach { (re, to) -> s = s.replace(re, to) }
        s = s.replace("=", " равно ").replace("+", " плюс ").replace("−", " минус ").replace(Regex("""(?<=\s|^)-"""), " минус ")
            .replace("<", " меньше ").replace(">", " больше ").replace("/", " делить на ")
            .replace(Regex("""[{}()\\]"""), " ")
        return s.replace(Regex("""\s+"""), " ").trim()
    }
}
