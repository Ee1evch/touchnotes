package app.nastavnik.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DayTimelineTest {
    private val day = LocalDate.of(2026, 11, 2)
    private fun l(id: String, start: String, end: String, d: String = "Пара $id") =
        Lesson(id = id, date = day.toString(), start = start, end = end, discipline = d, type = "Лекция")
    private val lessons = listOf(
        l("2", "10:40", "12:10", "Матанализ"),
        l("1", "09:00", "10:30", "Физика"),
        l("3", "13:00", "14:30", "Линал"),
    )

    @Test
    fun partsAreLessonsWithBreaks() {
        val s = DayTimeline.at(lessons, day.atTime(8, 0))!!
        assertEquals(listOf(90, 10, 90, 50, 90), s.parts.map { it.minutes })
        assertEquals(listOf("Физика", null, "Матанализ", null, "Линал"), s.parts.map { it.lesson?.discipline })
        assertEquals(330, s.total)
    }

    @Test
    fun phasesThroughTheDay() {
        DayTimeline.at(lessons, day.atTime(8, 30))!!.run {
            assertEquals(DayTimeline.Phase.BEFORE, phase); assertEquals("Физика", focus?.discipline); assertEquals(0, progress); assertEquals(1, number)
        }
        DayTimeline.at(lessons, day.atTime(9, 45))!!.run {
            assertEquals(DayTimeline.Phase.LESSON, phase); assertEquals("Физика", focus?.discipline); assertEquals("Матанализ", after?.discipline)
            assertEquals(45, progress); assertEquals(1, number); assertEquals(3, count)
        }
        DayTimeline.at(lessons, day.atTime(12, 30))!!.run {
            assertEquals(DayTimeline.Phase.BREAK, phase); assertEquals("Линал", focus?.discipline); assertNull(after); assertEquals(3, number)
        }
        DayTimeline.at(lessons, day.atTime(15, 0))!!.run {
            assertEquals(DayTimeline.Phase.AFTER, phase); assertNull(focus); assertEquals(total, progress)
        }
    }

    @Test
    fun parallelGroupsKeepOneLesson() {
        val day2 = lessons + l("4", "10:40", "12:10", "Английский")
        assertEquals(3, DayTimeline.lessons(day2).size)
        assertNull(DayTimeline.at(emptyList(), day.atTime(9, 0)))
    }

}
