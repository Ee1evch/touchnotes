package app.nastavnik.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ShapesTest {
    /** Ломаная через вершины, с лёгким дрожанием руки. */
    private fun hand(vararg xy: Float, step: Float = 3f, wobble: Float = 1.2f): FloatArray {
        val out = ArrayList<Float>()
        var k = 0
        for (i in 0 until xy.size / 2 - 1) {
            val x0 = xy[i * 2]; val y0 = xy[i * 2 + 1]; val x1 = xy[i * 2 + 2]; val y1 = xy[i * 2 + 3]
            val n = (kotlin.math.hypot(x1 - x0, y1 - y0) / step).toInt().coerceAtLeast(1)
            for (j in 0 until n) {
                val f = j / n.toFloat()
                val w = if (k++ % 2 == 0) wobble else -wobble
                out += x0 + (x1 - x0) * f + w; out += y0 + (y1 - y0) * f - w; out += 0.5f
            }
        }
        out += xy[xy.size - 2]; out += xy[xy.size - 1]; out += 0.5f
        return out.toFloatArray()
    }

    @Test
    fun recognizesLineAndSnapsAngle() {
        val r = Shapes.recognize(hand(100f, 100f, 300f, 104f))!!
        assertEquals(ShapeKind.LINE, r.kind)
        // Почти горизонтальная — стала ровно горизонтальной.
        assertEquals(r.xy[1], r.xy[3], 0.01f)
    }

    @Test
    fun recognizesRectangleTriangleAndEllipse() {
        val rect = Shapes.recognize(hand(100f, 100f, 300f, 100f, 300f, 220f, 100f, 220f, 102f, 103f))
        assertEquals(ShapeKind.RECT, rect?.kind)
        val tri = Shapes.recognize(hand(200f, 100f, 300f, 260f, 100f, 260f, 199f, 103f))
        assertEquals(ShapeKind.TRIANGLE, tri?.kind)
        val circle = ArrayList<Float>()
        for (i in 0..80) {
            val a = 2 * PI * i / 80
            circle += (200 + cos(a) * 80 + (i % 3) - 1).toFloat(); circle += (200 + sin(a) * 60).toFloat(); circle += 0.5f
        }
        assertEquals(ShapeKind.ELLIPSE, Shapes.recognize(circle.toFloatArray())?.kind)
    }

    @Test
    fun scribbleStaysScribble() {
        // Буква «з» с хвостом — не фигура.
        val z = hand(100f, 100f, 140f, 95f, 150f, 120f, 120f, 135f, 150f, 150f, 140f, 180f, 100f, 185f, 90f, 210f)
        assertNull(Shapes.recognize(z))
        assertNull(Shapes.recognize(floatArrayOf(1f, 1f, 0.5f, 2f, 2f, 0.5f)))
    }

    @Test
    fun buildsShapesWithSharpCorners() {
        val rect = Shapes.build(ShapeKind.RECT, 10f, 10f, 110f, 60f, 3f, 0).single()
        assertEquals(Tool.SHAPE, rect.tool)
        assertEquals(5, rect.size)
        // Без сглаживания углы на месте.
        val g = InkCurve.lines(rect)
        assertTrue(g.lines.flatMap { it.toList() }.contains(110f))
        assertEquals(2, Shapes.build(ShapeKind.ARROW, 0f, 0f, 100f, 0f, 3f, 0).size)
        assertEquals(4, Shapes.build(ShapeKind.AXES, 50f, 50f, 150f, 0f, 3f, 0).size)
        val r = Shapes.resample(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f), 5)
        assertEquals(10, r.size)
        assertEquals(10f, r[4], 1e-3f); assertEquals(0f, r[5], 1e-3f) // середина пути — угол
    }

    @Test
    fun lassoSelectsMostlyInsideStrokesAndTexts() {
        val inside = Stroke(Tool.PEN, 0, 3f, hand(110f, 110f, 150f, 150f))
        val outside = Stroke(Tool.PEN, 0, 3f, hand(300f, 300f, 350f, 350f))
        val half = Stroke(Tool.PEN, 0, 3f, hand(180f, 120f, 260f, 120f))
        val poly = floatArrayOf(100f, 100f, 200f, 100f, 200f, 200f, 100f, 200f)
        val sel = Selection.strokesIn(poly, listOf(inside, outside, half))
        assertEquals(listOf(inside), sel)
        val t = TextBox(120f, 120f, 50f, "x", 10f, 0)
        assertEquals(listOf(t), Selection.textsIn(poly, listOf(t, TextBox(500f, 500f, 50f, "y", 10f, 0))))
        val b = Selection.bounds(sel, emptyList())!!
        assertTrue(b[0] < 110f && b[2] > 150f)
        val moved = inside.moved(10f, -5f)
        assertEquals(inside.x(0) + 10f, moved.x(0), 1e-4f)
        assertEquals(inside.p(0), moved.p(0), 1e-4f)
    }

    @Test
    fun partialEraserCutsStrokeInTwo() {
        val s = Stroke(Tool.PEN, 7, 2f, hand(0f, 0f, 200f, 0f, wobble = 0f))
        val pieces = Selection.cut(s, 100f, 0f, 10f)!!
        assertEquals(2, pieces.size)
        assertTrue(pieces.all { it.color == 7 })
        assertTrue(pieces[0].points.filterIndexed { i, _ -> i % 3 == 0 }.all { it < 90f })
        assertTrue(pieces[1].points.filterIndexed { i, _ -> i % 3 == 0 }.all { it > 110f })
        assertNull(Selection.cut(s, 100f, 50f, 5f))
        // Фигура с редкими точками тоже режется посередине стороны.
        val line = Shapes.build(ShapeKind.LINE, 0f, 0f, 200f, 0f, 2f, 0).single()
        assertEquals(2, Selection.cut(line, 100f, 0f, 8f)!!.size)
    }

    @Test
    fun replaceEditUndoesComplexChangesAndTextsSurviveSaving() {
        val a = Stroke(Tool.PEN, 1, 3f, hand(0f, 0f, 50f, 50f))
        val page = InkPage(1, Paper.GRID, strokes = mutableListOf(a))
        val undo = UndoStack()
        val text = TextBox(10f, 20f, 300f, "Дано: $ x^2 $\nНайти", 18f, 0xFF112233.toInt())
        val after = listOf(a.moved(5f, 5f))
        undo.push(InkEdit.Replace(page.strokes.toList(), page.texts.toList(), after, listOf(text)))
        page.set(after, listOf(text))
        assertTrue(undo.undo(page))
        assertEquals(listOf(a), page.strokes)
        assertTrue(page.texts.isEmpty())
        assertTrue(undo.redo(page))
        assertEquals(1, page.texts.size)

        val out = ByteArrayOutputStream()
        InkFormat.write(page, out)
        val back = InkFormat.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("Дано: $ x^2 $\nНайти", back.texts.single().text)
        assertEquals(18f, back.texts.single().size, 0f)
        assertEquals(page.strokes.single().x(0), back.strokes.single().x(0), 0f)
        val shape = Shapes.build(ShapeKind.ELLIPSE, 0f, 0f, 10f, 10f, 2f, 0).single()
        page.strokes += shape
        val out2 = ByteArrayOutputStream()
        InkFormat.write(page, out2)
        assertEquals(Tool.SHAPE, InkFormat.read(ByteArrayInputStream(out2.toByteArray())).strokes.last().tool)
        assertNotNull(text.bounds())
    }
}
