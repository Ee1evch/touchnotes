package app.nastavnik.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class InkTest {
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 20, p: Float = 0.5f) =
        Stroke(Tool.PEN, 0xFF16161A.toInt(), 3f, FloatArray(n * 3) { k ->
            val i = k / 3
            val t = i / (n - 1f)
            when (k % 3) { 0 -> x0 + (x1 - x0) * t; 1 -> y0 + (y1 - y0) * t; else -> p }
        })

    @Test
    fun outlineIsClosedAndAsWideAsThePressureSays() {
        val s = line(100f, 100f, 300f, 100f, p = 1f)
        val o = StrokeGeometry.outline(s)
        assertTrue(o.size > 20 * 4)
        val ys = o.filterIndexed { i, _ -> i % 2 == 1 }
        // Горизонтальная линия: контур выше и ниже на половину толщины (нажим 1 → 1,5 базовой).
        val half = StrokeGeometry.widthAt(3f, 1f, Tool.PEN) / 2
        assertEquals(100f - half, ys.min(), 0.05f)
        assertEquals(100f + half, ys.max(), 0.05f)
        // Слабый нажим — тоньше.
        assertTrue(StrokeGeometry.widthAt(3f, 0.1f, Tool.PEN) < StrokeGeometry.widthAt(3f, 0.9f, Tool.PEN))
        // Точка — кружок.
        assertEquals(24, StrokeGeometry.outline(Stroke(Tool.PEN, 0, 4f, floatArrayOf(5f, 5f, 0.5f))).size)
    }

    @Test
    fun eraserHitsOnlyTouchedStrokes() {
        val a = line(0f, 0f, 100f, 0f)
        val b = line(0f, 50f, 100f, 50f)
        val hit = StrokeGeometry.hit(listOf(a, b), 50f, 4f, 3f)
        assertEquals(listOf(a), hit)
        assertTrue(StrokeGeometry.hit(listOf(a, b), 50f, 25f, 3f).isEmpty())
    }

    @Test
    fun undoRedoRestoreStrokesInPlace() {
        val page = InkPage(1, Paper.GRID)
        val u = UndoStack()
        val a = line(0f, 0f, 10f, 0f); val b = line(0f, 10f, 10f, 10f); val c = line(0f, 20f, 10f, 20f)
        listOf(a, b, c).forEach { page.strokes += it; u.push(InkEdit.Added(it)) }
        // Стёрли средний — отмена возвращает его на то же место.
        page.strokes.remove(b); u.push(InkEdit.Erased(listOf(1 to b)))
        assertTrue(u.undo(page))
        assertEquals(listOf(a, b, c), page.strokes)
        assertTrue(u.redo(page))
        assertEquals(listOf(a, c), page.strokes)
        // Очистка и её отмена.
        val all = page.strokes.toList(); page.strokes.clear(); u.push(InkEdit.Cleared(all))
        u.undo(page)
        assertEquals(listOf(a, c), page.strokes)
        // Новое действие стирает «повтор».
        u.push(InkEdit.Added(b))
        assertFalse(u.canRedo)
    }

    @Test
    fun pageSurvivesSaveAndLoad() {
        val page = InkPage(7, Paper.LINED, "bg_7.jpg", 1200f, mutableListOf(line(1f, 2f, 3f, 4f, n = 5, p = 0.3f)))
        val bytes = ByteArrayOutputStream().also { InkFormat.write(page, it) }.toByteArray()
        val back = InkFormat.read(ByteArrayInputStream(bytes))
        assertEquals(7L, back.id)
        assertEquals(Paper.LINED, back.paper)
        assertEquals("bg_7.jpg", back.background)
        assertEquals(1200f, back.height)
        assertEquals(1, back.strokes.size)
        assertEquals(page.strokes[0].points.toList(), back.strokes[0].points.toList())
    }

    @Test
    fun thinningKeepsEndsAndDropsCrowdedPoints() {
        val s = line(0f, 0f, 10f, 0f, n = 101)
        val t = StrokeGeometry.thin(s.points, 1f)
        assertEquals(0f, t[0]); assertEquals(10f, t[t.size - 3])
        assertTrue(t.size / 3 in 10..13)
    }
}
