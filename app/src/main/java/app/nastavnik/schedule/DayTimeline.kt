package app.nastavnik.schedule

import java.time.Duration
import java.time.LocalDateTime

/**
 * Учебный день одной полосой — для живого уведомления (тесты `DayTimelineTest`).
 * Пары по порядку, между ними перемены; где ты сейчас: до пар, на паре, на перемене, после.
 */
object DayTimeline {
    enum class Phase { BEFORE, LESSON, BREAK, AFTER }

    /** Кусок дня: пара ([lesson] не null) или перемена. */
    data class Part(val minutes: Int, val lesson: Lesson?)

    data class State(
        val phase: Phase,
        val parts: List<Part>,
        /** Сколько минут дня прошло (от начала первой пары), 0…[total]. */
        val progress: Int,
        val total: Int,
        /** Идущая пара (на паре) или следующая (до пар, на перемене). */
        val focus: Lesson?,
        /** Пара после [focus]. */
        val after: Lesson?,
        /** Номер [focus] среди пар дня (с 1) и сколько их. */
        val number: Int,
        val count: Int,
    )

    /** Пары дня по времени; пересекающиеся (параллельные группы) — только первая. */
    fun lessons(day: List<Lesson>): List<Lesson> {
        val out = mutableListOf<Lesson>()
        for (l in day.filter { it.startsAt() != null }.sortedBy { it.startsAt() }) {
            val prevEnd = out.lastOrNull()?.let { it.endsAt() ?: it.startsAt()!!.plusMinutes(90) }
            if (prevEnd == null || !l.startsAt()!!.isBefore(prevEnd)) out += l
        }
        return out
    }

    fun at(day: List<Lesson>, now: LocalDateTime): State? {
        val ls = lessons(day)
        if (ls.isEmpty()) return null
        fun end(l: Lesson) = l.endsAt() ?: l.startsAt()!!.plusMinutes(90)
        val first = ls.first().startsAt()!!
        val parts = mutableListOf<Part>()
        ls.forEachIndexed { i, l ->
            if (i > 0) {
                val gap = Duration.between(end(ls[i - 1]), l.startsAt()!!).toMinutes().toInt()
                if (gap > 0) parts += Part(gap, null)
            }
            parts += Part(Duration.between(l.startsAt()!!, end(l)).toMinutes().toInt().coerceAtLeast(1), l)
        }
        val total = parts.sumOf { it.minutes }
        val progress = Duration.between(first, now).toMinutes().toInt().coerceIn(0, total)
        val currentIdx = ls.indexOfFirst { !now.isBefore(it.startsAt()!!) && now.isBefore(end(it)) }
        val nextIdx = ls.indexOfFirst { it.startsAt()!!.isAfter(now) }
        val (phase, idx) = when {
            currentIdx >= 0 -> Phase.LESSON to currentIdx
            now.isBefore(first) -> Phase.BEFORE to 0
            nextIdx >= 0 -> Phase.BREAK to nextIdx
            else -> Phase.AFTER to -1
        }
        return State(
            phase = phase,
            parts = parts,
            progress = progress,
            total = total,
            focus = ls.getOrNull(idx),
            after = if (idx >= 0) ls.getOrNull(idx + 1) else null,
            number = idx + 1,
            count = ls.size,
        )
    }
}
