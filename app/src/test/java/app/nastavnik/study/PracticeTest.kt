package app.nastavnik.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PracticeTest {
    private val msk = TimeZone.getTimeZone("Europe/Moscow")
    private fun at(month: Int, day: Int, hour: Int) = Calendar.getInstance(msk).apply { clear(); set(2026, month, day, hour, 0) }.timeInMillis

    @Test
    fun tasksAndQuizAreParsedTolerantly() {
        val raw = "Вот вариант:\n```json\n[{\"task\":\"Найди ${'$'}x${'$'}: ${'$'}2x=4${'$'}\",\"answer\":\"2\",\"level\":1}," +
            "{\"task\":\"\",\"answer\":\"1\"},{\"task\":\"Без ответа\"},{\"task\":\"Сложная\",\"answer\":\"0\",\"level\":7}]\n```"
        val t = StudyJson.tasks(raw)
        assertEquals(2, t.size)
        assertEquals(3, t[1].level)

        val q = StudyJson.quiz("[{\"q\":\"Производная x^2?\",\"options\":[\"2x\",\"x\",\" \"],\"correct\":0,\"why\":\"степень\"}," +
            "{\"q\":\"Плохой\",\"options\":[\"a\"],\"correct\":0},{\"q\":\"Номер мимо\",\"options\":[\"a\",\"b\"],\"correct\":5}]")
        assertEquals(1, q.size)
        assertEquals(listOf("2x", "x"), q[0].options)
        assertTrue(StudyJson.tasks("нет json").isEmpty())
        assertEquals("Что такое предел?" to "Число…", StudyJson.card("{\"q\":\"Что такое предел?\",\"a\":\"Число…\"}"))
        assertNull(StudyJson.card("{\"q\":\"\",\"a\":\"x\"}"))
    }

    @Test
    fun cardsComeBackBeforeTheExam() {
        val now = at(Calendar.NOVEMBER, 1, 12)
        val exam = at(Calendar.NOVEMBER, 10, 9)
        // Повторение через 16 дней уехало бы за экзамен — переносится на утро накануне.
        val far = now + 16L * 24 * 3600 * 1000
        assertEquals(at(Calendar.NOVEMBER, 9, 4), ExamPlan.capDue(far, now, exam, msk))
        // Ближе экзамена — не трогаем; экзамен прошёл — тоже.
        val near = now + 2L * 24 * 3600 * 1000
        assertEquals(near, ExamPlan.capDue(near, now, exam, msk))
        assertEquals(far, ExamPlan.capDue(far, now, null, msk))
        assertEquals(9, ExamPlan.daysLeft(exam, now, msk))
        val exams = listOf(Exam("Математический анализ", exam), Exam("Линейная алгебра", at(Calendar.DECEMBER, 1, 9)))
        assertEquals(exam, ExamPlan.examFor(exams, "математический анализ", now)?.at)
        assertEquals(exams, ExamPlan.decode(ExamPlan.encode(exams)))
    }

    @Test
    fun promptsCarryMaterialAndHideAnswersFromStudent() {
        assertTrue(PracticePrompts.variantSystem(5).contains("из 5 задач"))
        assertTrue(PracticePrompts.quizSystem(8).contains("из 8 вопросов"))
        assertTrue(PracticePrompts.CARD_SYSTEM.contains("\$…\$"))
        val long = "а".repeat(PracticePrompts.MATERIAL_LIMIT + 50)
        val m = PracticePrompts.material("Пределы", long)
        assertTrue(m.startsWith("Тема: Пределы\nМатериал:\n"))
        assertTrue(m.endsWith("…"))
        assertEquals("Тема: Ряды", PracticePrompts.material("  Ряды ", "  "))
        assertNull(PracticePrompts.variantForMentor(emptyList()))
        val v = PracticePrompts.variantForMentor(listOf(PracticeTask("Найти \$x\$", "2"), PracticeTask("Вторая", "5")))!!
        assertTrue(v.contains("1. Найти \$x\$\n   Ответ: 2"))
        assertTrue(v.contains("2. Вторая"))
    }

    @Test
    fun noteBecomesPlainText() {
        val note = app.nastavnik.notes.CleanNote(
            title = "Производная",
            blocks = listOf(
                app.nastavnik.notes.NoteBlock(type = "def", title = "Определение", text = "Предел отношения", tex = "f'(x)"),
                app.nastavnik.notes.NoteBlock(type = "list", items = listOf("линейность", "произведение")),
            ),
            summary = listOf("Главное правило"),
        )
        val t = NoteText.plain(note)
        assertTrue(t.startsWith("Производная\n\nОпределение: Предел отношения \$f'(x)\$"))
        assertTrue(t.contains("- линейность"))
        assertTrue(t.endsWith("Главное:\n- Главное правило"))
    }
}
