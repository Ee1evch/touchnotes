package app.nastavnik.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SirenRulesTest {
    private val mon = LocalDate.of(2026, 11, 2)

    @Test
    fun ringsFromEightPmOnLessonDaysUntilSolved() {
        // До 20:00 — тихо.
        assertFalse(SirenRules.shouldRing(mon.atTime(19, 59), hasLessons = true, solved = 0, quietUntil = null))
        // С 20:00 — звонит, и ночью тоже (это ещё понедельник).
        assertTrue(SirenRules.shouldRing(mon.atTime(20, 0), true, 0, null))
        assertTrue(SirenRules.shouldRing(mon.plusDays(1).atTime(2, 30), true, 0, null))
        // С 5 утра — новый день, до его 20:00 тихо.
        assertFalse(SirenRules.shouldRing(mon.plusDays(1).atTime(5, 0), true, 0, null))
        // Решил основную — тихо.
        assertFalse(SirenRules.shouldRing(mon.atTime(21, 0), true, 1, null))
        // Основная задача решена, но ДЗ на завтра не на 100% — день не сделан, звонит.
        assertTrue(SirenRules.shouldRing(mon.atTime(21, 0), true, 1, null, homeworkOpen = true))
        // …но тишина после мини-задачи действует и тут.
        assertFalse(SirenRules.shouldRing(mon.atTime(21, 0), true, 1, mon.atTime(21, 4), homeworkOpen = true))
        // День без пар — тихо.
        assertFalse(SirenRules.shouldRing(mon.atTime(21, 0), false, 0, null))
    }

    @Test
    fun miniTaskGivesFiveQuietMinutes() {
        val solvedMini = mon.atTime(20, 10)
        val quiet = solvedMini.plusMinutes(SirenRules.QUIET_MINUTES)
        assertFalse(SirenRules.shouldRing(mon.atTime(20, 14), true, 0, quiet))
        // Не решил основную за 5 минут — снова звонит.
        assertTrue(SirenRules.shouldRing(mon.atTime(20, 15), true, 0, quiet))
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
