package app.nastavnik.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SirenRulesTest {
    private val mon = LocalDate.of(2026, 11, 2)

    @Test
    fun ringsFromTenPmOnLessonDaysUntilSolved() {
        // До 22:00 — тихо.
        assertFalse(SirenRules.shouldRing(mon.atTime(21, 59), hasLessons = true, solved = 0, quietUntil = null))
        // С 22:00 — звонит, и ночью тоже (это ещё понедельник).
        assertTrue(SirenRules.shouldRing(mon.atTime(22, 0), true, 0, null))
        assertTrue(SirenRules.shouldRing(mon.plusDays(1).atTime(2, 30), true, 0, null))
        // С 5 утра — новый день, до его 22:00 тихо.
        assertFalse(SirenRules.shouldRing(mon.plusDays(1).atTime(5, 0), true, 0, null))
        // Решил основную — тихо.
        assertFalse(SirenRules.shouldRing(mon.atTime(23, 0), true, 1, null))
        // День без пар — тихо.
        assertFalse(SirenRules.shouldRing(mon.atTime(23, 0), false, 0, null))
    }

    @Test
    fun miniTaskGivesFiveQuietMinutes() {
        val solvedMini = mon.atTime(22, 10)
        val quiet = solvedMini.plusMinutes(SirenRules.QUIET_MINUTES)
        assertFalse(SirenRules.shouldRing(mon.atTime(22, 14), true, 0, quiet))
        // Не решил основную за 5 минут — снова звонит.
        assertTrue(SirenRules.shouldRing(mon.atTime(22, 15), true, 0, quiet))
    }

    @Test
    fun answersCompareWithoutFormatting() {
        assertTrue(SirenRules.sameAnswer("0,5", "\$\\frac{1}{2}\$"))
        assertTrue(SirenRules.sameAnswer("1/2", "0.5"))
        assertTrue(SirenRules.sameAnswer("x = -3", "-3"))
        assertTrue(SirenRules.sameAnswer(" 12 ", "\$12\$"))
        assertTrue(SirenRules.sameAnswer("2.50", "2,5"))
        assertTrue(SirenRules.sameAnswer("Производная", "производная"))
        assertTrue(SirenRules.sameAnswer("ёлка", "Елка"))
        assertFalse(SirenRules.sameAnswer("3", "-3"))
        assertFalse(SirenRules.sameAnswer("", "0"))
        assertFalse(SirenRules.sameAnswer("интеграл", "производная"))
        assertEquals(0.75, SirenRules.number("\$\\dfrac{3}{4}\$")!!, 1e-9)
    }
}
