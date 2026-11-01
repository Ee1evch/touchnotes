package app.nastavnik.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SleepPlanTest {
    private fun t(h: Int, m: Int, day: Int = 2) = LocalDateTime.of(2026, 11, day, h, m)

    @Test
    fun latestWakeLeavesTimeToGetReadyAndTravel() {
        assertEquals(t(7, 30), SleepPlan.latestWake(t(8, 30), 45, 15))
    }

    @Test
    fun bedtimeIsFiveCyclesPlusFallingAsleep() {
        // 7:15 − 7 ч 30 мин − 15 мин = 23:30 накануне.
        assertEquals(t(23, 30, day = 1), SleepPlan.bedtime(t(7, 15)))
        val all = SleepPlan.bedtimes(t(7, 15))
        assertEquals(listOf(6, 5, 4), all.map { it.first })
        assertEquals(t(22, 0, day = 1), all[0].second)
        assertEquals(t(1, 0), all[2].second)
    }

    @Test
    fun smartWakeMovesToCycleEndWithinWindow() {
        // Лёг в 23:40 → уснул в 23:55 → концы циклов 1:25, 2:55, 4:25, 5:55, 7:25. Крайний подъём 7:40.
        assertEquals(t(7, 25), SleepPlan.smartWake(t(7, 40), t(23, 40, day = 1)))
    }

    @Test
    fun smartWakeNeverLaterThanLatest() {
        // Лёг в 23:30 → уснул 23:45 → конец 5-го цикла 7:15 ровно в крайний подъём.
        assertEquals(t(7, 15), SleepPlan.smartWake(t(7, 15), t(23, 30, day = 1)))
        for (m in 0 until 180 step 7) {
            val bed = t(22, 0, day = 1).plusMinutes(m.toLong())
            val w = SleepPlan.smartWake(t(7, 15), bed)
            assertTrue(!w.isAfter(t(7, 15)))
            assertTrue(!w.isBefore(t(7, 15).minusMinutes(SleepPlan.WINDOW_MIN)))
        }
    }

    @Test
    fun smartWakeFallsBackToLatestOutsideWindow() {
        // Уснул в 0:10 → концы 6:10 и 7:40; 6:10 на 50 минут раньше 7:00 — окно 30, будим в 7:00.
        assertEquals(t(7, 0), SleepPlan.smartWake(t(7, 0), t(23, 55, day = 1)))
        // Не знаем, когда лёг, — крайнее время.
        assertEquals(t(7, 0), SleepPlan.smartWake(t(7, 0), null))
        // Лёг почти перед подъёмом — тоже крайнее.
        assertEquals(t(7, 0), SleepPlan.smartWake(t(7, 0), t(6, 30)))
    }

    @Test
    fun cyclesAndDuration() {
        assertEquals(5, SleepPlan.cycles(t(23, 30, day = 1), t(7, 15)))
        assertEquals("7 ч 30 мин", SleepPlan.duration(450))
        assertEquals("8 ч", SleepPlan.duration(480))
        assertEquals("45 мин", SleepPlan.duration(45))
    }

    @Test
    fun taskAnswerMatchesText() {
        for (seed in 0L until 200L) {
            val (text, answer) = SleepPlan.task(seed)
            val v = text.replace("−", "-").replace("×", "*").split(" ")
            var acc = v[0].toInt()
            var i = 1
            while (i < v.size) {
                val n = v[i + 1].toInt()
                acc = when (v[i]) { "+" -> acc + n; "-" -> acc - n; else -> acc * n }
                i += 2
            }
            assertEquals(text, answer, acc)
            assertTrue(answer > 0)
        }
        assertEquals(SleepPlan.task(42), SleepPlan.task(42))
    }
}
