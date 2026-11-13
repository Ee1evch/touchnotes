package app.nastavnik.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordStatsTest {

    @Test
    fun summaryCalculatesPercentages() {
        val s1 = CoordFeedbackLog.calculateSummary(total = 20, hits = 16, misses = 4, userMisses = 0)
        assertEquals(20f, s1.missPercent, 0.01f)
        assertEquals(80f, s1.hitPercent, 0.01f)

        // Добавляем 1 пользовательский промах «Не здесь»
        val s2 = CoordFeedbackLog.calculateSummary(total = 20, hits = 16, misses = 4, userMisses = 1)
        assertEquals(25f, s2.missPercent, 0.01f)
        assertEquals(75f, s2.hitPercent, 0.01f)

        // Пустая статистика
        val s0 = CoordFeedbackLog.calculateSummary(total = 0, hits = 0, misses = 0, userMisses = 0)
        assertEquals(0f, s0.missPercent, 0.01f)
        assertEquals(100f, s0.hitPercent, 0.01f)
    }

    @Test
    fun feedbackLogEncodesAndDecodes() {
        val entry1 = CoordMissFeedback(
            timestamp = 1000L,
            model = "gemini-pro",
            question = "Найди ошибку",
            box = "[100, 200, 300, 400]",
            line = 3,
            userReported = false,
            details = "Авто-промах мимо чернил",
        )
        val entry2 = CoordMissFeedback(
            timestamp = 2000L,
            model = "gemini-pro",
            question = "Найди ошибку",
            box = "[500, 600, 700, 800]",
            line = 4,
            userReported = true,
            details = "Студент нажал «Не здесь»",
        )
        val list = CoordFeedbackLog.add(listOf(entry1), entry2)
        assertEquals(2, list.size)

        val json = CoordFeedbackLog.encode(list)
        val decoded = CoordFeedbackLog.decode(json)
        assertEquals(2, decoded.size)
        assertEquals("gemini-pro", decoded[0].model)
        assertTrue(decoded[1].userReported)
        assertEquals("Студент нажал «Не здесь»", decoded[1].details)
    }

    @Test
    fun feedbackLogLimitsEntriesToMax() {
        var list = emptyList<CoordMissFeedback>()
        repeat(CoordFeedbackLog.MAX_FEEDBACK + 20) { i ->
            list = CoordFeedbackLog.add(
                list,
                CoordMissFeedback(
                    timestamp = i.toLong(),
                    model = "test-model",
                    line = i,
                ),
            )
        }
        assertEquals(CoordFeedbackLog.MAX_FEEDBACK, list.size)
        assertEquals(20L, list.first().timestamp)
    }
}
