package app.nastavnik.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningTest {
    private val day = 24 * 3_600_000L
    private val now = 100 * day

    @Test
    fun dangerousTopicsAreConfidentMistakes() {
        val events = listOf(
            Event(Learning.KIND_CALIB, "Пределы", 1, now - day),
            Event(Learning.KIND_CALIB, "Пределы", 1, now - 2 * day),
            Event(Learning.KIND_CALIB, "Матрицы", 1, now - day),
            Event(Learning.KIND_CALIB, "Ряды", 0, now - day), // уверен и прав — не опасно
            Event(Learning.KIND_CALIB, "Старое", 1, now - 40 * day), // давно
            Event(Progress.KIND_CARD, "Интегралы", 1, now), // обычная ошибка — не калибровка
        )
        assertEquals(listOf("Пределы" to 2, "Матрицы" to 1), Learning.dangerous(events, now))
        assertTrue(Learning.overconfident(Learning.Confidence.SURE, remembered = false))
        assertFalse(Learning.overconfident(Learning.Confidence.UNSURE, remembered = false))
        assertFalse(Learning.overconfident(Learning.Confidence.SURE, remembered = true))
    }

    @Test
    fun orderPutsDangerFirstAndInterleaves() {
        val cards = listOf("a1" to "А", "a2" to "А", "a3" to "А", "b1" to "Б", "b2" to "Б", "c1" to "В")
        val ordered = Learning.order(cards, { it.second }, setOf("В")).map { it.first }
        assertEquals(listOf("c1", "a1", "b1", "a2", "b2", "a3"), ordered)
        // Ничего не теряется и не дублируется.
        assertEquals(cards.map { it.first }.sorted(), ordered.sorted())
    }

    @Test
    fun levelsAndUpgrade() {
        assertEquals(1, Learning.level("Что такое предел?"))
        assertEquals(2, Learning.level(Learning.APPLY_PREFIX + "найди предел"))
        assertEquals(3, Learning.level(Learning.EXPLAIN_PREFIX + "почему"))
        // Стала выученной — усложняем.
        assertTrue(Learning.shouldUpgrade("Что такое предел?", oldBox = 2, newBox = 3))
        assertTrue(Learning.shouldUpgrade(Learning.APPLY_PREFIX + "x", 2, 3))
        // Уже выучена раньше, высший уровень, задача — нет.
        assertFalse(Learning.shouldUpgrade("Что такое предел?", 3, 4))
        assertFalse(Learning.shouldUpgrade(Learning.EXPLAIN_PREFIX + "x", 2, 3))
        assertFalse(Learning.shouldUpgrade(Learning.PROBLEM_PREFIX + "реши", 2, 3))
    }

    @Test
    fun checkpointEveryTwoWeeks() {
        assertTrue(Learning.checkpointDue(emptyList(), now))
        val e = listOf(Event(Learning.KIND_CHECKPOINT, "7/10", 3, now - 10 * day))
        assertFalse(Learning.checkpointDue(e, now))
        assertTrue(Learning.checkpointDue(e, now + 4 * day))
        val history = Learning.checkpoints(e + Event(Learning.KIND_CHECKPOINT, "9/10", 1, now) + Event(Learning.KIND_CHECKPOINT, "мусор", 0, now))
        assertEquals(listOf(70, 90), history.map { it.second })
    }

    @Test
    fun noHintShare() {
        assertNull(Learning.noHintShare(emptyList(), now))
        val e = listOf(
            Event(Learning.KIND_TASK, "Пределы", 0, now - day),
            Event(Learning.KIND_TASK, "Пределы", 2, now - day),
            Event(Learning.KIND_TASK, "Пределы", 0, now - day),
            Event(Learning.KIND_TASK, "Пределы", 1, now - 20 * day), // старая
        )
        assertEquals(66, Learning.noHintShare(e, now))
    }

    @Test
    fun summaryIsShortAndSkipsEmpty() {
        val text = Learning.summary(
            dangerous = listOf("Пределы"), weak = listOf("Матрицы", "Ряды"), due = 12, problems = 2, noHint = 50,
            checkpoints = listOf(60, 70, 80), checkpointDue = false, tomorrow = listOf("Математический анализ"),
            screen = listOf("TikTok" to 140, "Telegram" to 60),
        )
        assertTrue(text, "Пределы" in text && "Матрицы" in text && "TikTok — 140 мин" in text && "60 → 70 → 80" in text)
        assertTrue("сводка длинная: ${text.length}", text.length < 1200)
        val empty = Learning.summary(emptyList(), emptyList(), 0, 0, null, emptyList(), true, emptyList(), emptyList())
        assertEquals("Карточек к повторению сейчас: 0.\nПора пройти контрольный срез.", empty)
    }
}
