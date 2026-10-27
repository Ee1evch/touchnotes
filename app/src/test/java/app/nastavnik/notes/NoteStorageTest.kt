package app.nastavnik.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteStorageTest {
    @Test
    fun plainTitleStripsLatex() {
        assertEquals("Предел 1/x при x 0", NoteStorage.plainTitle("Предел \$\\frac{1}{x}\$ при \$x \\to 0\$"))
        assertEquals("Исследование функции f(x) = x^3 - 3x", NoteStorage.plainTitle("Исследование функции \$f(x) = x^3 - 3x\$"))
    }

    @Test
    fun sanitizeRemovesIllegalFileChars() {
        assertEquals("a∕b c d", NoteStorage.sanitize("a/b:c?d"))
        assertEquals("Тема", NoteStorage.sanitize("  Тема.  "))
    }
}
