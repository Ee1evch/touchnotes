package app.nastavnik.study

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class StudyTest {
    private val msk = TimeZone.getTimeZone("Europe/Moscow")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance(msk).apply {
        clear()
        set(2026, Calendar.OCTOBER, day, hour, minute)
    }.timeInMillis

    @Test
    fun rememberedCardMovesUpAndComesBackInTheMorning() {
        val now = at(28, 22, 30)
        val n = Leitner.review(0, remembered = true, now = now, zone = msk)
        assertEquals(1, n.box)
        assertEquals(at(29, 4), n.dueAt)
        val n3 = Leitner.review(2, remembered = true, now = now, zone = msk)
        assertEquals(3, n3.box)
        assertEquals(at(28 + 7, 4), n3.dueAt)
        // Верхняя коробка — не выше пятой.
        assertEquals(Leitner.MAX_BOX, Leitner.review(5, remembered = true, now = now, zone = msk).box)
    }

    @Test
    fun forgottenCardStartsOverSoon() {
        val now = at(28, 10)
        val n = Leitner.review(4, remembered = false, now = now, zone = msk)
        assertEquals(0, n.box)
        assertEquals(now + Leitner.RELEARN_MS, n.dueAt)
    }

    @Test
    fun streakCountsLocalDaysAndSurvivesUntilTonight() {
        val days = setOf(at(25, 23, 50), at(26, 0, 10), at(27, 12), at(20, 9)).map { Streaks.day(it, msk) }.toSet()
        val today = Streaks.day(at(28, 8), msk)
        // 25, 26, 27 подряд; сегодня ещё не занимался — серия всё равно 3.
        assertEquals(3, Streaks.current(days, today))
        assertEquals(3, Streaks.best(days))
        // Пропустил вчера — серия сгорела.
        assertEquals(0, Streaks.current(days, today + 1))
    }

    @Test
    fun summaryCountsErrorsAndWeakTopics() {
        val now = at(28, 20)
        val events = listOf(
            Event(Progress.KIND_ASK, "Пределы", 2, at(28, 10)),
            Event(Progress.KIND_ASK, "Пределы", 1, at(27, 10)),
            Event(Progress.KIND_WATCH, "Матрицы", 1, at(27, 11)),
            Event(Progress.KIND_ASK, "Производная", 0, at(26, 10)),
            Event(Progress.KIND_CLEAN, "Пределы", 0, at(26, 12)),
            Event(Progress.KIND_CARD, "Матрицы", 1, at(28, 9)),
            Event(Progress.KIND_CARD, "Матрицы", 0, at(28, 9, 1)),
        )
        val s = Progress.summarize(events, now, msk)
        assertEquals(3, s.streak)
        assertEquals(3, s.questions)
        assertEquals(4, s.errorsFound)
        assertEquals(1, s.cleanNotes)
        assertEquals(2, s.cardsReviewed)
        assertEquals(listOf("Пределы" to 3, "Матрицы" to 2), s.weakTopics)
        assertEquals(14, s.days.size)
        assertEquals(3, s.days.last())
    }

    @Test
    fun russianPlural() {
        assertEquals("дней", ruPlural(11, "день", "дня", "дней"))
        assertEquals("день", ruPlural(21, "день", "дня", "дней"))
        assertEquals("дня", ruPlural(3, "день", "дня", "дней"))
        assertEquals("дней", ruPlural(5, "день", "дня", "дней"))
    }

    @Test
    fun reminderIsTodayOrTomorrow() {
        assertEquals(at(28, 18), nextReminderAt(at(28, 10), 18, msk))
        assertEquals(at(29, 18), nextReminderAt(at(28, 18), 18, msk))
        assertEquals(at(29, 18), nextReminderAt(at(28, 22), 18, msk))
    }
}
