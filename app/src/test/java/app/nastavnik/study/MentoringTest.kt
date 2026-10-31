package app.nastavnik.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MentoringTest {
    @Test
    fun mistakesAreDedupedCappedAndFreshFirst() {
        var list = emptyList<Mistake>()
        list = MistakeMemory.add(list, listOf(Mistake("Проверь знак при раскрытии скобок.", "Уравнения", 1)))
        list = MistakeMemory.add(list, listOf(Mistake("проверь знак при раскрытии скобок", "Уравнения", 5)))
        assertEquals(1, list.size)
        assertEquals(5L, list[0].at)
        repeat(40) { i -> list = MistakeMemory.add(list, listOf(Mistake("Ошибка $i", "", 10L + i))) }
        assertEquals(MistakeMemory.KEEP, list.size)
        val prompt = MistakeMemory.forPrompt(list)
        assertEquals(MistakeMemory.IN_PROMPT, prompt.size)
        assertEquals("Ошибка 39", prompt[0])
        // Сохраняется и читается обратно; мусор — пустой список.
        assertEquals(list, MistakeMemory.decode(MistakeMemory.encode(list)))
        assertTrue(MistakeMemory.decode("не json").isEmpty())
    }

    @Test
    fun formulasAreReadAsWords() {
        assertEquals("Найди x в квадрате минус 4 равно 0", SpeakText.from("Найди \$x^2 - 4 = 0\$"))
        assertEquals("Чему равно 1 делить на x при x стремится к бесконечность?", SpeakText.from("Чему равно \$\\frac{1}{x}\$ при \$x \\to \\infty\$?"))
        assertEquals("Проверь корень из D", SpeakText.from("**Проверь** \$\\sqrt{D}\$"))
    }
}
