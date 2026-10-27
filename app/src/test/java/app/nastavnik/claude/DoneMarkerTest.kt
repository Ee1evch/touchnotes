package app.nastavnik.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoneMarkerTest {

    @Test
    fun stripsMarkerOnLastLine() {
        val r = DoneMarker.process("Всё верно, ответ $2$ получен правильно.\n[[ГОТОВО]]")
        assertTrue(r.done)
        assertEquals("Всё верно, ответ $2$ получен правильно.", r.text)
    }

    @Test
    fun toleratesSpacesAndCase() {
        val r = DoneMarker.process("Отлично!\n[[ готово ]]\n")
        assertTrue(r.done)
        assertEquals("Отлично!", r.text)
    }

    @Test
    fun noMarker() {
        val r = DoneMarker.process("Проверь знак во второй строке.")
        assertFalse(r.done)
        assertEquals("Проверь знак во второй строке.", r.text)
    }

    @Test
    fun hidesPartialMarkerWhileStreaming() {
        assertEquals("Верно.", DoneMarker.process("Верно.\n[[ГОТ", streaming = true).text)
        assertEquals("Верно.", DoneMarker.process("Верно.\n[", streaming = true).text)
        // В законченном ответе обычная квадратная скобка остаётся.
        assertEquals("Отрезок [", DoneMarker.process("Отрезок [", streaming = false).text)
    }
}
