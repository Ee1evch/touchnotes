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

/** Текст для нейросети: из чего составлять вариант, тест или карточку. */
object PracticePrompts {
    /** Сколько символов материала отправляем — чтобы запрос оставался дешёвым. */
    const val MATERIAL_LIMIT = 9000

    private const val MATH = "Формулы пиши в LaTeX внутри \$…\$ (KaTeX), без markdown-заголовков."

    fun variantSystem(count: Int): String = """Ты — преподаватель, который готовит студента 1 курса ИТ-специальности к контрольной.
Составь тренировочный вариант из $count задач по присланной теме или материалу: как на настоящей контрольной, от простой к сложной (level 1 — разминка, 2 — типовая, 3 — с подвохом). Задачи должны решаться от руки за 5–15 минут, с однозначным проверяемым ответом. Не повторяй задачи из материала дословно — меняй числа и формулировки.
$MATH
Ответ — только JSON-массив без пояснений: [{"task": "условие", "answer": "краткий верный ответ", "level": 1}]"""

    fun quizSystem(count: Int): String = """Ты — преподаватель. Составь мини-тест из $count вопросов по присланному материалу для студента 1 курса ИТ-специальности: проверяй понимание, а не память на мелочи. У каждого вопроса 4 варианта ответа, ровно один верный; неверные варианты — типичные ошибки студентов. В поле why — одно-два предложения, почему верный ответ верный.
$MATH
Ответ — только JSON-массив без пояснений: [{"q": "вопрос", "options": ["…", "…", "…", "…"], "correct": 0, "why": "…"}] (correct — номер верного варианта, с нуля)."""

    val CARD_SYSTEM = """Ты помогаешь студенту повторять материал по карточкам. Из присланной подсказки наставника сделай одну карточку: на лицевой стороне — вопрос, который заставляет вспомнить главное (правило, формулу, типичную ошибку), на обороте — короткий точный ответ (до 2 предложений или одна формула).
$MATH
Ответ — только JSON без пояснений: {"q": "вопрос", "a": "ответ"}"""

    /** Сообщение студента: тема и, если есть, материал (конспект). */
    fun material(topic: String, text: String?): String = buildString {
        if (topic.isNotBlank()) append("Тема: ").append(topic.trim()).append('\n')
        val body = text?.trim().orEmpty()
        if (body.isNotEmpty()) {
            append("Материал:\n")
            append(if (body.length > MATERIAL_LIMIT) body.take(MATERIAL_LIMIT) + "…" else body)
        }
    }.trim()

    /**
     * Дополнение к промту наставника в тетради с вариантом: он знает условия и верные ответы,
     * но не выдаёт ответ, пока студент не решит сам или не попросит.
     */
    fun variantForMentor(tasks: List<PracticeTask>): String? {
        if (tasks.isEmpty()) return null
        return buildString {
            append("Студент решает в этой тетради тренировочный вариант. Условия и верные ответы (ответ не называй, пока студент сам не решит или прямо не попросит; при проверке сверяй с ним):\n")
            tasks.forEachIndexed { i, t -> append("${i + 1}. ${t.task}\n   Ответ: ${t.answer}\n") }
        }.trimEnd()
    }
}

/** Конспект как обычный текст — материал для варианта и теста. */
object NoteText {
    fun plain(note: app.nastavnik.notes.CleanNote): String = buildString {
        if (note.title.isNotBlank()) append(note.title.trim()).append("\n\n")
        for (b in note.blocks) {
            b.title?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append(": ") }
            b.text?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append(' ') }
            b.tex?.takeIf { it.isNotBlank() }?.let { append("$").append(it.trim()).append("$ ") }
            b.items?.forEach { append("\n- ").append(it.trim()) }
            b.head?.let { append("\n").append(it.joinToString(" | ")) }
            b.rows?.forEach { append("\n").append(it.joinToString(" | ")) }
            append('\n')
        }
        if (note.summary.isNotEmpty()) append("\nГлавное:\n").append(note.summary.joinToString("\n") { "- $it" })
    }.replace(Regex("\n{3,}"), "\n\n").trim()
}
