package app.nastavnik.study

import app.nastavnik.study.HomeworkRules.PageRange
import app.nastavnik.study.HomeworkRules.Probe
import app.nastavnik.study.HomeworkRules.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HomeworkRulesTest {
    @Test fun parsesProblemNumbers() {
        assertEquals(listOf("245", "247", "248", "249", "250", "251", "260а"), HomeworkRules.parseNumbers("245, 247–251, 260А"))
        assertEquals(listOf("247", "248", "249"), HomeworkRules.parseNumbers("№247 - 249"))
        assertEquals(listOf("1.12", "1.13", "2.1"), HomeworkRules.parseNumbers("1.12; 1.13\n2.1"))
        assertEquals(listOf("5", "6"), HomeworkRules.parseNumbers("5, 6, 5"))
        // Неправдоподобный диапазон не разворачиваем.
        assertEquals(listOf("1-500"), HomeworkRules.parseNumbers("1-500"))
        assertEquals(emptyList<String>(), HomeworkRules.parseNumbers(" , ;"))
    }

    @Test fun ordersLikeAProblemBook() {
        assertTrue(HomeworkRules.compare("245", "1001") < 0)
        assertTrue(HomeworkRules.compare("260", "260а") < 0)
        assertTrue(HomeworkRules.compare("260а", "260б") < 0)
        assertTrue(HomeworkRules.compare("260б", "261") < 0)
        assertTrue(HomeworkRules.compare("1.12", "1.13") < 0)
        assertTrue(HomeworkRules.compare("1.13", "2.1") < 0)
        assertEquals(0, HomeworkRules.compare("245", "245"))
    }

    /** Учебник на 400 страниц, на странице p — задачи 10p…10p+9 (страницы 0–19 — теория без задач). */
    private fun book(p: Int): PageRange? = if (p < 20) null else PageRange("${10 * p}", "${10 * p + 9}")

    private fun find(target: String, pages: Int = 400): Pair<Int?, Int> {
        val known = HashMap<Int, PageRange?>()
        repeat(40) { step ->
            when (val p = HomeworkRules.nextProbe(known, target, pages)) {
                is Probe.Found -> return p.page to step
                is Probe.Look -> known[p.page] = book(p.page)
                Probe.NotFound -> return null to step
            }
        }
        return null to 40
    }

    @Test fun findsProblemByHalving() {
        val (page, steps) = find("2457")
        assertEquals(245, page)
        assertTrue("слишком много шагов: $steps", steps <= 10)
        assertEquals(20, find("200").first)
        assertEquals(399, find("3999").first)
        // Номера нет в учебнике — не нашли, а не зациклились.
        assertEquals(null, find("9999").first)
        assertEquals(null, find("5").first)
    }

    @Test fun knownPageIsFoundAtOnce() {
        val known = mapOf(245 to PageRange("2450", "2459"))
        assertEquals(Probe.Found(245), HomeworkRules.nextProbe(known, "2455", 400))
    }

    @Test fun hundredPercentAndOneDisputed() {
        assertTrue(HomeworkRules.complete(listOf(Status.DONE, Status.DONE)))
        assertTrue(HomeworkRules.complete(listOf(Status.DONE, Status.DISPUTED)))
        assertFalse(HomeworkRules.complete(listOf(Status.DISPUTED, Status.DISPUTED)))
        assertFalse(HomeworkRules.complete(listOf(Status.DONE, Status.WRONG)))
        assertFalse(HomeworkRules.complete(emptyList()))
        assertEquals(2, HomeworkRules.done(listOf(Status.DONE, Status.DISPUTED, Status.TODO)))
    }

    @Test fun disputeOnlyAfterTwoFailedChecks() {
        val all = listOf(Status.WRONG, Status.DONE)
        assertFalse(HomeworkRules.canDispute(Status.WRONG, 1, all))
        assertTrue(HomeworkRules.canDispute(Status.WRONG, 2, all))
        assertTrue(HomeworkRules.canDispute(Status.INCOMPLETE, 3, all))
        assertFalse(HomeworkRules.canDispute(Status.TODO, 5, all))
        assertFalse(HomeworkRules.canDispute(Status.WRONG, 2, listOf(Status.WRONG, Status.DISPUTED)))
    }

    @Test fun dueIsNextLessonOfSubject() {
        val today = LocalDate.of(2026, 11, 2)
        val lessons = listOf(
            today to "Математический анализ",
            today.plusDays(2) to "Линейная алгебра",
            today.plusDays(3) to "Математический анализ",
            today.plusDays(10) to "Математический анализ",
        )
        val same = { a: String, b: String -> a.equals(b, ignoreCase = true) }
        assertEquals(today.plusDays(3), HomeworkRules.defaultDue("математический анализ", today, lessons, same))
        assertEquals(today.plusWeeks(1), HomeworkRules.defaultDue("Физика", today, lessons, same))
    }

    @Test fun openForTheEvening() {
        val session = LocalDate.of(2026, 11, 2)
        data class Hw(val due: LocalDate, val st: List<Status>)
        val items = listOf(
            Hw(session.plusDays(1), listOf(Status.TODO)), // на завтра, не сделано — да
            Hw(session.plusDays(1), listOf(Status.DONE)), // сделано — нет
            Hw(session, listOf(Status.WRONG)), // просрочено — да
            Hw(session.plusDays(3), listOf(Status.TODO)), // ещё не скоро — нет
        )
        assertEquals(listOf(items[0], items[2]), HomeworkRules.openFor(session, items, { it.due }, { it.st }))
    }
}
