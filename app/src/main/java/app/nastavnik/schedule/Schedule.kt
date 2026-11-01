package app.nastavnik.schedule

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Занятие из расписания Сириуса. */
@Serializable
data class Lesson(
    val id: String,
    /** Дата в виде 2026-11-02. */
    val date: String,
    val start: String,
    val end: String,
    val pair: Int? = null,
    val discipline: String,
    /** «Лекция», «Практика», «Семинар»… */
    val type: String = "",
    val classroom: String? = null,
    val address: String? = null,
    val teachers: List<String> = emptyList(),
    val comment: String? = null,
    val online: String? = null,
) {
    val day: LocalDate get() = LocalDate.parse(date)
    val startTime: LocalTime? get() = parseTime(start)
    val endTime: LocalTime? get() = parseTime(end)
    fun startsAt(): LocalDateTime? = startTime?.let { day.atTime(it) }
    fun endsAt(): LocalDateTime? = endTime?.let { day.atTime(it) }

    /** «А. Иванов, Б. Петров». */
    val teachersShort: String? get() = teachers.map(::shortName).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString()

    companion object {
        fun parseTime(s: String): LocalTime? = runCatching {
            val (h, m) = s.trim().split(':').map { it.trim().toInt() }
            LocalTime.of(h, m)
        }.getOrNull()

        /** «Иванов Иван Иванович» → «И. Иванов»… на сайте фамилия идёт первой: «Иванов И. И.». */
        fun shortName(full: String): String {
            val parts = full.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (parts.size < 2) return full.trim()
            val initials = parts.drop(1).mapNotNull { p -> p.firstOrNull { it.isLetter() }?.uppercaseChar()?.let { "$it." } }
            return (listOf(parts[0]) + initials).joinToString(" ")
        }
    }
}

@Serializable
data class Week(
    val group: String,
    /** Понедельник недели, 2026-11-02. */
    val start: String,
    val lessons: List<Lesson>,
    /** Когда неделя загружена с сайта (мс). */
    val loadedAt: Long = 0,
) {
    fun on(date: LocalDate): List<Lesson> = lessons.filter { it.date == date.toString() }.sortedBy { it.start }
}

object ScheduleText {
    private val ru = java.util.Locale.forLanguageTag("ru")
    private val genericCampus = setOf("основной", "основное", "основная", "основн")
    private val campusInText = Regex("(?iu)[\\s,;:/\\-]*[(\\[]?основн[а-я]*[)\\]]?")
    private val spaces = Regex("""\s+""")

    fun monday(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

    fun eventType(type: String): String {
        val stripped = type.replace(campusInText, " ").replace(spaces, " ").trim()
        if (stripped.isEmpty()) return ""
        val lower = stripped.lowercase(ru)
        return when {
            "лекц" in lower -> "Лекция"
            "практ" in lower -> "Практика"
            "семин" in lower -> "Семинар"
            "лаб" in lower -> "Лабораторная"
            "внеучеб" in lower -> "Внеучебное"
            else -> stripped.replaceFirstChar { if (it.isLowerCase()) it.titlecase(ru) else it.toString() }
        }
    }

    fun address(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && !generic(it) }

    fun generic(value: String) = value.trim().trim('(', ')', '[', ']', '.', ',', ';').lowercase(ru) in genericCampus

    /** Сайт иногда повторяет текст дважды подряд: «Альфа 5.8Альфа 5.8». */
    fun undouble(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.length % 2 == 0) {
            val half = text.length / 2
            if (text.substring(0, half) == text.substring(half)) return text.substring(0, half)
        }
        return text
    }

    /** «Альфа 5.8 (Основной)» → «Альфа 5.8». */
    fun classroom(value: String?): String? = undouble(value?.replace(Regex("""\s*\((Основной|Университет)\)\s*$"""), ""))

    fun isForeignLanguage(discipline: String): Boolean {
        val name = discipline.lowercase().replace("ё", "е")
        return listOf("иностранн", "английск", "немецк", "французск", "иняз").any { name.contains(it) }
    }

    /** Языковые группы: оставляем пары только своего преподавателя иностранного. */
    fun forLanguageTeacher(lessons: List<Lesson>, teacher: String?): List<Lesson> =
        if (teacher.isNullOrBlank() || teacher == "*") lessons else lessons.filter { l ->
            !isForeignLanguage(l.discipline) || l.teachers.all { it.isBlank() } || l.teachers.any { it.trim().equals(teacher.trim(), ignoreCase = true) }
        }
}

/** Что сейчас и что дальше — для главного экрана и уведомлений. */
object ScheduleNow {
    data class State(val current: Lesson?, val next: Lesson?, val minutesToNext: Long?, val minutesLeft: Long?)

    fun at(lessons: List<Lesson>, now: LocalDateTime): State {
        val sorted = lessons.filter { it.startsAt() != null }.sortedBy { it.startsAt() }
        val current = sorted.firstOrNull { l -> val s = l.startsAt()!!; val e = l.endsAt() ?: s.plusMinutes(90); !now.isBefore(s) && now.isBefore(e) }
        val next = sorted.firstOrNull { it.startsAt()!!.isAfter(now) }
        return State(
            current = current,
            next = next,
            minutesToNext = next?.let { java.time.Duration.between(now, it.startsAt()).toMinutes() },
            minutesLeft = current?.let { java.time.Duration.between(now, it.endsAt() ?: it.startsAt()!!.plusMinutes(90)).toMinutes() },
        )
    }
}
