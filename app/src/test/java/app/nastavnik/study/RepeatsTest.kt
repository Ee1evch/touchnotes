package app.nastavnik.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class RepeatsTest {
    private val z: ZoneId = ZoneOffset.ofHours(3)
    private fun ms(t: LocalDateTime) = t.atZone(z).toInstant().toEpochMilli()
    private fun dt(ms: Long) = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), z)

    @Test fun futureStaysAndPastOnceIsGone() {
        val at = ms(LocalDateTime.of(2026, 11, 5, 9, 0))
        assertEquals(at, Repeats.next(at, Repeats.NONE, at - 1000, z))
        assertNull(Repeats.next(at, Repeats.NONE, at + 1000, z))
    }

    @Test fun dailyWeeklyAndWeekdays() {
        val fri = LocalDateTime.of(2026, 11, 6, 9, 0) // пятница
        assertEquals(DayOfWeek.FRIDAY, fri.dayOfWeek)
        val after = ms(fri) + 60_000
        assertEquals(fri.plusDays(1), dt(Repeats.next(ms(fri), Repeats.DAILY, after, z)!!))
        assertEquals(fri.plusWeeks(1), dt(Repeats.next(ms(fri), Repeats.WEEKLY, after, z)!!))
        assertEquals(fri.plusDays(3), dt(Repeats.next(ms(fri), Repeats.WEEKDAYS, after, z)!!))
        // Пропустил месяц — следующий раз всё равно ближайший, а не через месяц.
        val later = ms(fri.plusDays(30)) + 1
        assertEquals(fri.plusDays(31), dt(Repeats.next(ms(fri), Repeats.DAILY, later, z)!!))
        assertEquals(fri.plusWeeks(5), dt(Repeats.next(ms(fri), Repeats.WEEKLY, later, z)!!))
    }
}
