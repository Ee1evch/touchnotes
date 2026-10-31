package app.nastavnik.study

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.TimeZone

/** Задача тренировочного варианта: условие и ответ (ответ студент не видит — по нему проверяет наставник). */
@Serializable
data class PracticeTask(val task: String = "", val answer: String = "", val level: Int = 1)

/** Вопрос мини-теста: варианты, номер верного и почему так. */
@Serializable
data class QuizQuestion(val q: String = "", val options: List<String> = emptyList(), val correct: Int = 0, val why: String = "")

/** Ответы модели в JSON — разбираем терпимо: вырезаем от первой скобки до последней, лишнее отбрасываем. */
object StudyJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private fun slice(raw: String, open: Char, close: Char): String? {
        val a = raw.indexOf(open)
        val b = raw.lastIndexOf(close)
        return if (a < 0 || b <= a) null else raw.substring(a, b + 1)
    }

    /** Задачи варианта: массив или объект с полем tasks. Пустые и без ответа выкидываем. */
    fun tasks(raw: String): List<PracticeTask> {
        val body = slice(raw, '[', ']') ?: return emptyList()
        return runCatching { json.decodeFromString<List<PracticeTask>>(body) }.getOrDefault(emptyList())
            .map { PracticeTask(it.task.trim(), it.answer.trim(), it.level.coerceIn(1, 3)) }
            .filter { it.task.isNotEmpty() && it.answer.isNotEmpty() }
            .take(8)
    }

    /** Вопросы теста: только с 2–6 вариантами и верным номером в пределах. */
    fun quiz(raw: String): List<QuizQuestion> {
        val body = slice(raw, '[', ']') ?: return emptyList()
        return runCatching { json.decodeFromString<List<QuizQuestion>>(body) }.getOrDefault(emptyList())
            .map { q -> q.copy(q = q.q.trim(), options = q.options.map { it.trim() }.filter { it.isNotEmpty() }) }
            .filter { it.q.isNotEmpty() && it.options.size in 2..6 && it.correct in it.options.indices }
            .take(12)
    }

    /** Карточка из заметки: {"q":…,"a":…}. */
    fun card(raw: String): Pair<String, String>? {
        val body = slice(raw, '{', '}') ?: return null
        val c = runCatching { json.decodeFromString<CardJson>(body) }.getOrNull() ?: return null
        return if (c.q.isBlank() || c.a.isBlank()) null else c.q.trim() to c.a.trim()
    }

    @Serializable
    private data class CardJson(val q: String = "", val a: String = "")
}

/** Экзамен: предмет и дата. */
@Serializable
data class Exam(val subject: String, val at: Long)

/**
 * Подготовка к экзамену: карточки предмета не должны «уехать» за дату экзамена —
 * последнее повторение будет накануне, а сегодняшний план виден на главном экране.
 */
object ExamPlan {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String?): List<Exam> = raw?.let { runCatching { json.decodeFromString<List<Exam>>(it) }.getOrNull() }.orEmpty()
    fun encode(list: List<Exam>): String = json.encodeToString(list)

    /** Ближайший будущий экзамен по предмету (по названию, без учёта регистра). */
    fun examFor(exams: List<Exam>, subject: String, now: Long): Exam? =
        exams.filter { it.at > now && same(it.subject, subject) }.minByOrNull { it.at }

    /**
     * Срок следующего повторения с учётом экзамена: не позже утра накануне экзамена
     * (но и не раньше завтрашнего утра, если до экзамена ещё есть время).
     */
    fun capDue(due: Long, now: Long, examAt: Long?, zone: TimeZone = TimeZone.getDefault()): Long {
        if (examAt == null || examAt <= now) return due
        val eve = Calendar.getInstance(zone).apply {
            timeInMillis = examAt
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 4); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return if (due > eve) maxOf(eve, now) else due
    }

    /** Сколько целых дней до экзамена (сегодня — 0). */
    fun daysLeft(examAt: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Int =
        (Streaks.day(examAt, zone) - Streaks.day(now, zone)).toInt().coerceAtLeast(0)

    private fun same(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true) ||
        (a.length > 3 && b.length > 3 && (a.contains(b, true) || b.contains(a, true)))
}
