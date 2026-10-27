package app.nastavnik.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAnswerParserTest {

    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 0.002f)

    @Test
    fun pixelsMarksAreParsedAndNormalized() {
        val raw = "@@ error 100 200 500 260\nПроверь знак: $-(x-3)$.\n\n@@ hint 100 300 900 360\nЧто будет, если приравнять к нулю?\n[[ГОТОВО]]"
        val a = ScreenAnswerParser.parse(raw, CoordMode.PIXELS_XY, 1000, 800)
        assertEquals(2, a.marks.size)
        assertTrue(a.done)
        val e = a.marks[0]
        assertEquals(MarkKind.ERROR, e.kind)
        assertEquals("Проверь знак: $-(x-3)$.", e.text)
        val b = e.box!!
        near(0.1f, b.left); near(0.25f, b.top); near(0.5f, b.right); near(0.325f, b.bottom)
        assertEquals(MarkKind.HINT, a.marks[1].kind)
        assertEquals("Что будет, если приравнять к нулю?", a.marks[1].text)
    }

    @Test
    fun geminiBox2dOrderIsYx() {
        val a = ScreenAnswerParser.parse("@@ ok 100 200 300 600\nВерно.", CoordMode.NORM_YX, 1400, 900)
        val b = a.marks.single().box!!
        near(0.2f, b.left); near(0.1f, b.top); near(0.6f, b.right); near(0.3f, b.bottom)
    }

    @Test
    fun headerVariantsAreAccepted() {
        val raw = "@@ Ошибка: [120, 80, 340, 140]\nтекст 1\n@@ theory (10,10,20,20)\nтекст 2\n@@ note\nтекст 3"
        val a = ScreenAnswerParser.parse(raw, CoordMode.NORM_XY, 1000, 1000)
        assertEquals(listOf(MarkKind.ERROR, MarkKind.THEORY, MarkKind.NOTE), a.marks.map { it.kind })
        assertNotNull(a.marks[0].box)
        assertNull(a.marks[2].box)
        // Крошечная рамка расширена до видимой.
        assertTrue(a.marks[1].box!!.width >= 0.039f)
    }

    @Test
    fun textWithoutHeaderBecomesNote() {
        val a = ScreenAnswerParser.parse("Просто ответ без меток.", CoordMode.PIXELS_XY, 1000, 800)
        assertEquals(MarkKind.NOTE, a.marks.single().kind)
        assertNull(a.marks.single().box)
    }

    @Test
    fun streamingHidesUnfinishedHeaderAndMarker() {
        val a = ScreenAnswerParser.parse("@@ hint 10 20 300 80\nПодумай о производной.\n@@ err", CoordMode.PIXELS_XY, 1000, 800, streaming = true)
        assertEquals(1, a.marks.size)
        assertEquals("Подумай о производной.", a.marks[0].text)
        val b = ScreenAnswerParser.parse("@@ ok 10 20 300 80\nВерно. [[ГОТ", CoordMode.PIXELS_XY, 1000, 800, streaming = true)
        assertEquals("Верно.", b.marks.single().text)
        // Только что пришла строка-метка, текста ещё нет — заметка уже есть (её можно поставить на место).
        val c = ScreenAnswerParser.parse("@@ hint 10 20 300 80\n", CoordMode.PIXELS_XY, 1000, 800, streaming = true)
        assertEquals(1, c.marks.size)
        assertEquals("", c.marks[0].text)
    }

    @Test
    fun allGoodMarkerForSilentCheck() {
        val a = ScreenAnswerParser.parse("[[ВЕРНО]]", CoordMode.PIXELS_XY, 1000, 800)
        assertTrue(a.allGood)
        assertTrue(a.marks.isEmpty())
        val b = ScreenAnswerParser.parse("@@ error 1 1 100 100\nТут ошибка.\n[[ВЕРНО]]", CoordMode.PIXELS_XY, 1000, 800)
        assertFalse("с ошибкой «всё верно» не считается", b.allGood)
    }

    @Test
    fun pixelsInsteadOfThousandthsAreDetected() {
        // Модель прислала пиксели, хотя просили доли от 1000.
        val a = ScreenAnswerParser.parse("@@ error 1200 300 1400 400\nx", CoordMode.NORM_XY, 1500, 900)
        val b = a.marks.single().box!!
        near(0.8f, b.left); near(1200f / 1500 + 200f / 1500, b.right)
    }

    @Test
    fun boxOutsideImageIsDropped() {
        val a = ScreenAnswerParser.parse("@@ error 2000 2000 2100 2100\nx", CoordMode.PIXELS_XY, 1000, 800)
        assertNull(a.marks.single().box)
    }

    @Test
    fun plainTextForHistory() {
        val a = ScreenAnswerParser.parse("@@ error 1 1 100 100\nПроверь знак.\n@@ note\nИ ещё.", CoordMode.PIXELS_XY, 1000, 800)
        assertEquals("**Ошибка.** Проверь знак.\n\nИ ещё.", ScreenAnswerParser.toPlain(a))
    }

    @Test
    fun promptMentionsCoordinateSystem() {
        val px = Prompts.mentorSystem("Физика", Strictness.HINTS, CoordMode.PIXELS_XY)
        assertTrue(px.contains("в пикселях"))
        assertTrue(px.contains("@@ error 410 520 980 590"))
        assertFalse(px.contains("{"))
        val g = Prompts.mentorSystem("Физика", Strictness.HINTS, CoordMode.NORM_YX)
        assertTrue(g.contains("box_2d"))
        assertEquals("Вопрос\n\n[Снимок экрана: 1383×864 пикселей]", Prompts.withShotSize("Вопрос", 1383, 864))
    }

    @Test
    fun coordModeByProviderAndModel() {
        assertEquals(CoordMode.PIXELS_XY, AiProvider.ANTHROPIC.coordMode("claude-sonnet-5-5"))
        assertEquals(CoordMode.NORM_YX, AiProvider.GEMINI.coordMode("gemini-pro-latest"))
        assertEquals(CoordMode.NORM_XY, AiProvider.OPENAI_COMPAT.coordMode("gpt-5"))
        assertEquals(CoordMode.NORM_XY, AiProvider.ANTHROPIC_COMPAT.coordMode("gpt-5-codex"))
        assertEquals(CoordMode.PIXELS_XY, AiProvider.ANTHROPIC_COMPAT.coordMode("claude-sonnet-4-5"))
    }
}
