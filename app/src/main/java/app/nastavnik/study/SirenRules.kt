package app.nastavnik.study

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Учебная сирена — правила без Android (тесты `SirenRulesTest`).
 *
 * В день с парами к 20:00 нужно решить хотя бы одну основную задачу по слабым темам. Не решил — с 20:00 звучит
 * сирена (сначала «wake up», потом сигнал по кругу), убрать её можно только мини-задачей: это 5 минут тишины на
 * основную. Решил основную — тишина до завтра. «День» длится до 5 утра (как у учебного замка).
 */
object SirenRules {
    const val RING_HOUR = 20
    const val QUIET_MINUTES = 5L

    /** Когда в учебный день [date] начинается сирена. */
    fun start(date: LocalDate): LocalDateTime = date.atTime(RING_HOUR, 0)

    /**
     * Звонить ли сейчас. [hasLessons] — в учебный день [StudyLockRules.sessionDate] были пары;
     * [solved] — сколько основных задач решено за этот день; [quietUntil] — до когда тишина после мини-задачи.
     */
    fun shouldRing(now: LocalDateTime, hasLessons: Boolean, solved: Int, quietUntil: LocalDateTime?): Boolean {
        if (!hasLessons || solved > 0) return false
        val date = StudyLockRules.sessionDate(now)
        if (now.isBefore(start(date))) return false
        return quietUntil == null || !now.isBefore(quietUntil)
    }

    private val spaces = Regex("""\s+""")
    private val frac = Regex("""\\[dt]?frac\{([^{}]*)\}\{([^{}]*)\}""")

    /** Ответ без оформления: «$\frac{1}{2}$» → «1/2», «x = 0,5» → «0.5», регистр и пробелы не важны. */
    fun normalize(s: String): String {
        var t = s.lowercase().replace('ё', 'е').replace("$", "").replace("\\left", "").replace("\\right", "")
            .replace("\\cdot", "*").replace("×", "*").replace("·", "*").replace("−", "-").replace("–", "-")
            .replace("\\,", "").trim()
        t = frac.replace(t) { "(${it.groupValues[1]})/(${it.groupValues[2]})" }
        t = t.replace(spaces, "").replace(',', '.').trimEnd('.')
        // «x=5», «ответ:5» → «5».
        t = t.substringAfterLast('=').substringAfterLast(':')
        // Скобки вокруг простых чисел в дробях: «(1)/(2)» → «1/2».
        t = t.replace(Regex("""\((-?[0-9.]+)\)"""), "$1")
        return t
    }

    /** Число из ответа: «0.5», «1/2», «-3», «2.50» (иначе null). */
    fun number(s: String): Double? {
        val t = normalize(s)
        t.toDoubleOrNull()?.let { return it }
        val parts = t.split('/')
        if (parts.size == 2) {
            val a = parts[0].toDoubleOrNull() ?: return null
            val b = parts[1].toDoubleOrNull() ?: return null
            if (b != 0.0) return a / b
        }
        return null
    }

    /** Совпадает ли введённый ответ с верным: как число (с точностью) или как текст без оформления. */
    fun sameAnswer(typed: String, expected: String): Boolean {
        if (typed.isBlank()) return false
        val a = number(typed)
        val b = number(expected)
        if (a != null && b != null) return kotlin.math.abs(a - b) <= 1e-6 + 1e-3 * kotlin.math.abs(b)
        return normalize(typed) == normalize(expected)
    }
}
