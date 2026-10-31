package app.nastavnik.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class NoteExportTest {
    private val note = CleanNote(
        subject = "Математический анализ",
        title = "Производная",
        date = "31 октября 2026",
        blocks = listOf(
            NoteBlock(type = "h", text = "Определения"),
            NoteBlock(type = "def", title = "Определение: производная", text = "Предел \$\\frac{\\Delta y}{\\Delta x}\$ при \$\\Delta x \\to 0\$"),
            NoteBlock(type = "formula", tex = "(uv)' = u'v + uv'"),
            NoteBlock(type = "steps", title = "Решение", items = listOf("Берём \$f(x)=x^2\$", "Получаем 2x")),
            NoteBlock(type = "table", head = listOf("f", "f'"), rows = listOf(listOf("\$x^n\$", "\$nx^{n-1}\$"))),
            NoteBlock(type = "note", text = "Проверь: 50% & #1_a"),
        ),
        summary = listOf("Производная — скорость"),
        questions = listOf("Что такое производная?"),
    )

    @Test
    fun markdownKeepsFormulasAndStructure() {
        val md = NoteExport.markdown(note)
        assertTrue(md.startsWith("# Производная\n\n*Математический анализ · 31 октября 2026*"))
        assertTrue(md.contains("## Определения"))
        assertTrue(md.contains("> **Определение: производная**"))
        assertTrue(md.contains("$$\n(uv)' = u'v + uv'\n$$"))
        assertTrue(md.contains("1. Берём \$f(x)=x^2\$\n2. Получаем 2x"))
        assertTrue(md.contains("| f | f' |\n| --- | --- |\n| \$x^n\$ | \$nx^{n-1}\$ |"))
        assertTrue(md.contains("## Вопросы для самопроверки\n\n1. Что такое производная?"))
    }

    @Test
    fun latexEscapesTextButNotMath() {
        val tex = NoteExport.latex(note)
        assertTrue(tex.contains("\\begin{definition}[производная]"))
        assertTrue(tex.contains("\$\\frac{\\Delta y}{\\Delta x}\$"))
        assertTrue(tex.contains("50\\% \\& \\#1\\_a"))
        assertTrue(tex.contains("\\[\n(uv)' = u'v + uv'\n\\]"))
        assertTrue(tex.trimEnd().endsWith("\\end{document}"))
        assertEquals("a\\_b \$x_1\$", NoteExport.texText("a_b \$x_1\$"))
    }

    @Test
    fun docxIsAValidZipWithEscapedText() {
        val bytes = NoteExport.docx(note)
        val names = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                names[e.name] = z.readBytes().toString(Charsets.UTF_8)
                e = z.nextEntry
            }
        }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "word/_rels/document.xml.rels", "word/styles.xml", "word/document.xml"), names.keys)
        val doc = names.getValue("word/document.xml")
        assertTrue(doc.contains("50% &amp; #1_a"))
        assertTrue(doc.contains("Cambria Math"))
        // Формула переписана понятнее: дробь и греческие буквы.
        assertTrue(doc.contains("(Δ y)/(Δ x)"))
        assertTrue(doc.contains("Δ x → 0"))
        assertFalse(doc.contains("<w:t xml:space=\"preserve\"></w:t>"))
    }

    @Test
    fun cheatSheetCollectsDefinitionsOnceBySubject() {
        val other = CleanNote(
            subject = "Линейная алгебра", title = "Матрицы",
            blocks = listOf(NoteBlock(type = "p", text = "текст"), NoteBlock(type = "thm", title = "Теорема", text = "det(AB)=det A det B")),
        )
        val dup = note.copy(title = "Производная (повтор)")
        val sheet = CheatSheet.build(listOf(note, dup, other), date = "сегодня")!!
        assertEquals("Шпаргалка", sheet.title)
        val types = sheet.blocks.map { it.type }
        // Предметы заголовками, темы — жирным абзацем; повтор формул выкинут вместе с пустой темой.
        assertEquals(listOf("h", "p", "def", "formula", "h", "p", "thm"), types)
        val one = CheatSheet.build(listOf(note, other), subject = "Линейная алгебра")!!
        assertEquals("Шпаргалка: Линейная алгебра", one.title)
        assertEquals(listOf("h", "thm"), one.blocks.map { it.type })
        assertNull(CheatSheet.build(listOf(CleanNote(title = "пусто", blocks = listOf(NoteBlock(type = "p", text = "x"))))))
    }

    @Test
    fun searchFindsAllWordsIgnoringCaseAndYo() {
        val w = Search.words("Ёмкость  конденсатора")
        assertEquals(listOf("емкость", "конденсатора"), w)
        assertTrue(Search.matches("Расчёт ЕМКОСТИ плоского конденсатора", listOf("емкост", "конденсатора")))
        assertFalse(Search.matches("Только конденсатора", w))
        assertTrue(Search.score("Конденсатор", "текст", listOf("конденсатор")) > Search.score("Другое", "конденсатор", listOf("конденсатор")))
        val text = "a ".repeat(100) + "здесь искомое слово" + " b".repeat(100)
        val s = Search.snippet(text, listOf("искомое"))
        assertTrue(s.startsWith("…") && s.endsWith("…") && s.contains("здесь искомое слово"))
        assertEquals("коротко", Search.snippet("коротко", listOf("нет")))
    }
}
