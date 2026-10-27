package app.nastavnik.geom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class InkLinesTest {
    private val w = 1200
    private val h = 800

    /** Лист с «рукописными» строками: штрихи разной высоты, с индексами и пропусками между словами. */
    private fun page(lines: List<Triple<Int, Int, Int>>, ruled: Boolean = false, seed: Int = 1): IntArray {
        val px = IntArray(w * h) { 250 }
        if (ruled) for (y in 30 until h step 48) for (x in 0 until w) px[y * w + x] = 170
        val rnd = Random(seed)
        for ((top, left, right) in lines) {
            var x = left
            while (x < right) {
                val word = rnd.nextInt(40, 120)
                for (xx in x until minOf(x + word, right)) {
                    val y0 = top + rnd.nextInt(0, 6)
                    for (yy in y0 until top + 30 - rnd.nextInt(0, 6)) if ((xx + yy) % 3 != 0) px[yy * w + xx] = 30
                }
                x += word + rnd.nextInt(15, 30)
            }
            // Степень над строкой — должна приклеиться к ней, а не стать отдельной строкой.
            for (yy in top - 12 until top - 4) for (xx in left + 50 until left + 62) px[yy * w + xx] = 30
        }
        return px
    }

    @Test
    fun findsHandwrittenLinesInOrder() {
        val spec = listOf(Triple(60, 40, 700), Triple(140, 40, 900), Triple(230, 80, 500), Triple(320, 40, 1000))
        val map = InkLines.detect(page(spec), w, h)
        assertEquals(4, map.lines.size)
        map.lines.zip(spec).forEach { (l, s) ->
            assertTrue("строка ${l.number}: верх ${l.top} ≈ ${s.first - 12}", kotlin.math.abs(l.top - (s.first - 12)) <= 4)
            assertTrue("строка ${l.number}: низ ${l.bottom}", kotlin.math.abs(l.bottom - (s.first + 30)) <= 6)
            assertTrue(kotlin.math.abs(l.left - s.second) <= 3)
        }
        assertEquals(listOf(1, 2, 3, 4), map.lines.map { it.number })
    }

    @Test
    fun ruledPaperIsNotInk() {
        val spec = listOf(Triple(60, 40, 700), Triple(140, 40, 900))
        val map = InkLines.detect(page(spec, ruled = true), w, h)
        assertEquals(2, map.lines.size)
    }

    @Test
    fun lineReferenceGivesTightBox() {
        val spec = listOf(Triple(60, 40, 700), Triple(140, 40, 900), Triple(230, 80, 500))
        val map = InkLines.detect(page(spec), w, h)
        val r = map.resolve(PlaceRef(lines = 2..2))!!
        assertTrue(r.top in 115..135 && r.bottom in 165..185)
        assertTrue(r.left in 25..45 && r.right in 895..925)
        val two = map.resolve(PlaceRef(lines = 2..3))!!
        assertTrue(two.top < 140 && two.bottom > 255)
    }

    @Test
    fun afterLineGivesSpaceBelow() {
        val spec = listOf(Triple(60, 40, 700), Triple(140, 40, 900))
        val map = InkLines.detect(page(spec), w, h)
        val r = map.resolve(PlaceRef(after = 2))!!
        assertTrue("под строкой: ${r.top}", r.top >= 170)
        assertTrue(r.left <= 40)
    }

    @Test
    fun roughBoxSnapsToInk() {
        val spec = listOf(Triple(60, 40, 700), Triple(140, 40, 900), Triple(230, 80, 500))
        val map = InkLines.detect(page(spec), w, h)
        // Модель промахнулась на полстроки вниз и чуть вбок.
        val r = map.resolve(PlaceRef(box = IRect(120, 245, 520, 290)))!!
        assertNotNull(r)
        assertTrue("притянулась к строке 3: $r", r.top in 205..225 && r.bottom in 255..275)
        assertTrue(r.left in 65..95)
    }

    @Test
    fun partOfWideLineByBox() {
        // В строке два примера далеко друг от друга.
        val px = page(listOf(Triple(100, 40, 400)))
        for (yy in 100 until 130) for (xx in 800 until 1100) if ((xx + yy) % 3 != 0) px[yy * w + xx] = 30
        val map = InkLines.detect(px, w, h)
        assertEquals(1, map.lines.size)
        assertTrue(map.lines[0].segments.size >= 2)
        val r = map.resolve(PlaceRef(lines = 1..1, box = IRect(780, 90, 1110, 140)))!!
        assertTrue("только правый пример: $r", r.left in 780..805 && r.right in 1095..1120)
    }

    @Test
    fun emptyPageHasNoLines() {
        val map = InkLines.detect(IntArray(w * h) { 250 }, w, h)
        assertTrue(map.lines.isEmpty())
        val box = IRect(10, 10, 100, 100)
        assertEquals(box, map.resolve(PlaceRef(box = box)))
    }
}
