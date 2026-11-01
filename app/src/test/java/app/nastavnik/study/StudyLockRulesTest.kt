package app.nastavnik.study

import app.nastavnik.schedule.Lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class StudyLockRulesTest {
    private val mon = LocalDate.of(2026, 11, 2)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m)
    private val start20 = 20 * 60

    @Test
    fun dayLastsUntilFiveAm() {
        assertEquals(mon, StudyLockRules.sessionDate(at(mon, 23, 30)))
        assertEquals(mon, StudyLockRules.sessionDate(at(mon.plusDays(1), 1, 0)))
        assertEquals(mon.plusDays(1), StudyLockRules.sessionDate(at(mon.plusDays(1), 5, 0)))
    }

    @Test
    fun locksOnlyAfterStartTime() {
        assertEquals(emptyList<LocalDate>(), StudyLockRules.pending(at(mon, 19, 59), start20, mon, emptySet()))
        assertEquals(listOf(mon), StudyLockRules.pending(at(mon, 20, 0), start20, mon, emptySet()))
        // Ночью — всё ещё вечер понедельника.
        assertEquals(listOf(mon), StudyLockRules.pending(at(mon.plusDays(1), 2, 0), start20, mon, emptySet()))
        // Сделал — свободен.
        assertEquals(emptyList<LocalDate>(), StudyLockRules.pending(at(mon, 21, 0), start20, mon, setOf(mon.toString())))
    }

    @Test
    fun missedEveningKeepsLockNextDay() {
        val tue = mon.plusDays(1)
        // Вторник утром: понедельник не сделан — заблокировано.
        assertEquals(listOf(mon), StudyLockRules.pending(at(tue, 9, 0), start20, mon, emptySet()))
        // Вторник вечером — и понедельник, и вторник.
        assertEquals(listOf(mon, tue), StudyLockRules.pending(at(tue, 20, 30), start20, mon, emptySet()))
        // Копится не больше одного пропущенного вечера.
        assertEquals(listOf(tue), StudyLockRules.pending(at(tue.plusDays(1), 9, 0), start20, mon, emptySet()))
    }

    @Test
    fun notBeforeEnabled() {
        // Включил во вторник — за понедельник не спрашиваем.
        assertEquals(emptyList<LocalDate>(), StudyLockRules.pending(at(mon.plusDays(1), 9, 0), start20, mon.plusDays(1), emptySet()))
    }

    private fun lesson(d: String, type: String = "Лекция", start: String = "09:00") =
        Lesson(id = d + start, date = mon.toString(), start = start, end = "10:30", discipline = d, type = type)

    @Test
    fun summariesSkipSportAndExtracurricular() {
        val day = listOf(
            lesson("Физика", start = "12:00"),
            lesson("Математический анализ", start = "09:00"),
            lesson("Физическая культура и спорт", start = "14:00"),
            lesson("Кураторский час", type = "Внеучебное", start = "16:00"),
            lesson("Математический анализ", type = "Практика", start = "10:40"),
        )
        assertEquals(listOf("Математический анализ", "Физика"), StudyLockRules.disciplines(day))
    }

    @Test
    fun matchesCardsToSubject() {
        assertTrue(StudyLockRules.sameSubject("Матанализ, пределы", "Математический анализ") || StudyLockRules.sameSubject("Математический анализ", "Математический анализ"))
        assertTrue(StudyLockRules.sameSubject("математика: анализ функций", "Математический анализ"))
        assertTrue(StudyLockRules.sameSubject("Физика", "Физика"))
        assertFalse(StudyLockRules.sameSubject("Физика", "Математический анализ"))
        assertFalse(StudyLockRules.sameSubject("", "Физика"))
    }

    @Test
    fun emergencyOncePerWeek() {
        val now = 1_000_000_000_000L
        assertTrue(StudyLockRules.emergencyAvailable(0, now))
        assertFalse(StudyLockRules.emergencyAvailable(now - 3 * 24 * 3_600_000L, now))
        assertTrue(StudyLockRules.emergencyAvailable(now - 7 * 24 * 3_600_000L, now))
    }

    @Test
    fun allowedApps() {
        assertTrue(StudyLockRules.allowed("org.telegram.messenger", setOf("org.telegram.messenger"), setOf("com.android.dialer")))
        assertTrue(StudyLockRules.allowed("com.android.dialer", emptySet(), setOf("com.android.dialer")))
        assertFalse(StudyLockRules.allowed("com.zhiliaoapp.musically", setOf("org.telegram.messenger"), setOf("com.android.dialer")))
    }
}
