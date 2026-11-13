package app.nastavnik.grades

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LksExtractTest {
    @Test fun tableWithHeaders() {
        val page = LksExtract.Page(
            tables = listOf(
                LksExtract.Table(
                    headers = listOf("№", "Дисциплина", "Форма контроля", "Дата", "Оценка"),
                    rows = listOf(
                        listOf("1", "Математический  анализ", "Экзамен", "20.01.2026", "отлично"),
                        listOf("2", "Физика", "Зачёт", "", "зачтено"),
                        listOf("3", "История", "Экзамен", "", ""),
                    ),
                ),
            ),
        )
        val s = LksExtract.fromPage(page, 1)!!
        assertEquals(listOf("Математический анализ", "Физика", "История"), s.courses.map { it.name })
        assertEquals("отлично", s.courses[0].total)
        assertEquals(5.0, s.courses[0].totalRaw!!, 0.0)
        assertNull(s.courses[2].total)
        assertTrue(s.courses[0].items.any { it.name == "Форма контроля" && it.grade == "Экзамен" })
    }

    @Test fun headerInFirstRowAndJson() {
        val json = """{"url":"https://lks/x","tables":[{"headers":[],"rows":[["Предмет","Балл"],["Алгебра","87"],["Геометрия","4,5"]]},{"rows":[["меню","профиль"]]}]}"""
        val s = LksExtract.parse(json, 1)!!
        assertEquals(2, s.courses.size)
        assertEquals("87", s.courses[0].total)
        assertEquals(4.5, s.courses[1].totalRaw!!, 0.0)
        assertNull(LksExtract.parse("""{"tables":[]}"""))
    }
}
