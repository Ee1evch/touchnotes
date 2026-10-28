package app.nastavnik.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteStorageTest {
    @Test
    fun plainTitleStripsLatex() {
        assertEquals("Предел 1/x при x → 0", NoteStorage.plainTitle("Предел \$\\frac{1}{x}\$ при \$x \\to 0\$"))
        assertEquals("Исследование функции f(x) = x³ - 3x", NoteStorage.plainTitle("Исследование функции \$f(x) = x^3 - 3x\$"))
        assertEquals("Ряд ∑ aₙ, n → ∞", NoteStorage.plainTitle("Ряд \$\\sum a_n\$, \$n \\to \\infty\$"))
        assertEquals("Корни x₁ и x₂ уравнения x² + 1 = 0", NoteStorage.plainTitle("Корни \$x_1\$ и \$x_{2}\$ уравнения \$x^{2} + 1 = 0\$"))
    }

    @Test
    fun fileBaseHasTopicAndDate() {
        val date = java.util.GregorianCalendar(2026, 9, 27).time
        assertEquals("Предел 1∕x — 27.10.2026", NoteStorage.fileBase("Предел \$\\frac{1}{x}\$", date))
        assertEquals("Конспект — 27.10.2026", NoteStorage.fileBase("$$", date))
    }

    @Test
    fun sanitizeRemovesIllegalFileChars() {
        assertEquals("a∕b c d", NoteStorage.sanitize("a/b:c?d"))
        assertEquals("Тема", NoteStorage.sanitize("  Тема.  "))
    }
}
