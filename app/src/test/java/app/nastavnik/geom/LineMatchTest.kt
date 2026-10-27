package app.nastavnik.geom

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class LineMatchTest {
    private val w = 1200
    private val h = 800

    /** Лист с «рукописными» строками разной длины и разным рисунком слов. */
    private fun page(seed: Int): IntArray {
        val px = IntArray(w * h) { 250 }
        val rnd = Random(seed)
        var top = 50
        while (top < h - 60) {
            val left = rnd.nextInt(30, 200)
            val right = rnd.nextInt(left + 250, w - 30)
            var x = left
            while (x < right) {
                val word = rnd.nextInt(30, 140)
                val y0 = top + rnd.nextInt(0, 8)
                val y1 = top + 34 - rnd.nextInt(0, 8)
                for (xx in x until minOf(x + word, right)) for (yy in y0 until y1) if ((xx * 7 + yy * 3) % 5 < 3) px[yy * w + xx] = 30
                x += word + rnd.nextInt(18, 45)
            }
            top += rnd.nextInt(70, 100)
        }
        return px
    }

    /** Та же страница, увеличенная в [k] раз и сдвинутая на (ox, oy) — как после жеста увеличения. */
    private fun zoom(src: IntArray, k: Float, ox: Int, oy: Int): IntArray =
        IntArray(w * h) { i ->
            val x = ((i % w - ox) / k).toInt()
            val y = ((i / w - oy) / k).toInt()
            if (x in 0 until w && y in 0 until h) src[y * w + x] else 250
        }

    private fun image(px: IntArray) = PageImage(px, w, h, InkLines.detect(px, w, h))

    @Test
    fun findsSameLineAfterZoomAndPan() {
        val src = page(3)
        val ref = image(src)
        for ((k, ox, oy) in listOf(Triple(1.5f, -300, -200), Triple(0.7f, 100, 60), Triple(1.0f, 0, -137), Triple(2.0f, -700, -500))) {
            val cur = image(zoom(src, k, ox, oy))
            for (n in 2..5) {
                val box = ref.lines.resolve(PlaceRef(lines = n..n)) ?: continue
                val exp = IRect((box.left * k + ox).toInt(), (box.top * k + oy).toInt(), (box.right * k + ox).toInt(), (box.bottom * k + oy).toInt())
                // Строка целиком на экране — её должны найти; иначе — можно не найти.
                if (exp.left < 0 || exp.top < 0 || exp.right > w || exp.bottom > h) continue
                val got = LineMatch.relocate(ref, box, cur)
                assertNotNull("масштаб $k: строка $n не найдена", got)
                val tol = (12 * k).toInt() + 4
                assertTrue("масштаб $k, строка $n: ждали $exp, а получили $got",
                    abs(got!!.centerX - exp.centerX) <= tol && abs(got.centerY - exp.centerY) <= tol &&
                        abs(got.width - exp.width) <= tol * 2)
            }
        }
    }

    @Test
    fun otherPageIsNotMatched() {
        val ref = image(page(3))
        val other = image(page(11))
        val box = ref.lines.resolve(PlaceRef(lines = 3..3))!!
        assertNull(LineMatch.relocate(ref, box, other))
    }
}
