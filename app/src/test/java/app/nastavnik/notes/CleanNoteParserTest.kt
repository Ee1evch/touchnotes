package app.nastavnik.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CleanNoteParserTest {

    @Test
    fun cardsAreTrimmedDedupedAndCapped() {
        val many = (1..20).joinToString(",") { "{\"q\":\"Вопрос $it\",\"a\":\"Ответ\"}" }
        val raw = """{"title":"Тема","blocks":[{"type":"p","text":"x"}],"cards":[
            {"q":"  Что такое ${'$'}\\lim${'$'}?  ","a":" Предел "},
            {"q":"Что такое ${'$'}\\lim${'$'}?","a":"дубль"},
            {"q":"Без ответа","a":""},
            $many]}"""
        val note = CleanNoteParser.parse(raw)
        assertEquals(CleanNoteParser.MAX_CARDS, note.cards.size)
        assertEquals("Что такое ${'$'}\\lim${'$'}?", note.cards[0].q)
        assertEquals("Предел", note.cards[0].a)
        assertTrue(note.cards.none { it.q == "Без ответа" })
        // Старые чистовики без карточек читаются как раньше.
        assertTrue(CleanNoteParser.fromStoredJson("""{"title":"Т"}""").cards.isEmpty())
    }

    @Test
    fun parsesJsonWrappedInMarkdownFence() {
        val raw = """
            Вот чистовик:
            ```json
            {"subject":"Матанализ","title":"Предел функции","blocks":[
              {"type":"def","title":"Определение: предел","text":"Число ${'$'}A${'$'} — предел ${'$'}f(x)${'$'} при ${'$'}x\\to a${'$'}"},
              {"type":"formula","tex":"\\lim_{x\\to 0}\\frac{\\sin x}{x}=1"},
              {"type":"unknown","text":"выбросить"}
            ],"summary":["пункт"],"questions":["вопрос?"]}
            ```
            Готово!
        """.trimIndent()
        val note = CleanNoteParser.parse(raw)
        assertEquals("Предел функции", note.title)
        assertEquals(2, note.blocks.size)
        assertEquals("\\lim_{x\\to 0}\\frac{\\sin x}{x}=1", note.blocks[1].tex)
    }

    @Test
    fun repairsSingleBackslashesFromModel() {
        // Модель забыла удвоить: \frac стал бы form feed, \sqrt — ошибкой разбора.
        val raw = """{"title":"Корни","blocks":[{"type":"formula","tex":"\frac{1}{\sqrt{x}} \neq \theta \cdot \alpha"},""" +
            """{"type":"p","text":"Строка\nвторая"}]}"""
        val note = CleanNoteParser.parse(raw)
        assertEquals("\\frac{1}{\\sqrt{x}} \\neq \\theta \\cdot \\alpha", note.blocks[0].tex)
        // Настоящий перевод строки перед кириллицей сохраняется.
        assertEquals("Строка\nвторая", note.blocks[1].text)
    }

    @Test
    fun keepsCorrectlyEscapedJson() {
        val raw = """{"title":"T","blocks":[{"type":"p","text":"кавычка \" и \\int и A"}]}"""
        val note = CleanNoteParser.parse(raw)
        assertEquals("кавычка \" и \\int и A", note.blocks[0].text)
    }

    @Test
    fun lenientAboutNumbersInTablesAndTrailingCommas() {
        val raw = """{"title":"Таблица","blocks":[{"type":"table","head":["x","y"],"rows":[[1,2],[3,"4"]]},],}"""
        val note = CleanNoteParser.parse(raw)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4")), note.blocks[0].rows)
    }

    @Test
    fun plotBlock() {
        val raw = """{"title":"График","blocks":[{"type":"plot","title":"парабола","functions":[{"expr":"x^2-1","label":"y=x^2-1"}],"x":[-3,3],"y":[-2,8],"points":[{"x":1,"y":0,"label":"A"}]}]}"""
        val b = CleanNoteParser.parse(raw).blocks.single()
        assertEquals("x^2-1", b.functions!!.single().expr)
        assertEquals(listOf(-3.0, 3.0), b.x)
        assertEquals("A", b.points!!.single().label)
    }

    @Test
    fun failsWithoutJson() {
        try {
            CleanNoteParser.parse("Извини, не могу разобрать снимок.")
            fail()
        } catch (e: CleanNoteParseException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun failsOnEmptyNote() {
        try {
            CleanNoteParser.parse("{\"blocks\":[]}")
            fail()
        } catch (_: CleanNoteParseException) {
        }
    }

    @Test
    fun roundTripsThroughToJson() {
        val note = CleanNoteParser.parse("""{"title":"T","blocks":[{"type":"p","text":"${'$'}\\alpha${'$'}"}]}""")
        val again = CleanNoteParser.fromStoredJson(CleanNoteParser.toJson(note))
        assertEquals(note, again)
    }
}
