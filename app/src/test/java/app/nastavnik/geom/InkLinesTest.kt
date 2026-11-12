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
    fun errorBoxStaysOnOneDetectedLine() {
        val map = InkLines.detect(page(listOf(Triple(60, 40, 700), Triple(140, 40, 900))), w, h)
        val wholePage = map.resolveError(PlaceRef(lines = 2..2, box = IRect(0, 0, w, h)))!!
        assertTrue("широкая рамка должна стать строкой: $wholePage", wholePage.top > 100 && wholePage.bottom < 190)
        assertTrue("высота строки: $wholePage", wholePage.height < 65)
        assertEquals(null, map.resolveError(PlaceRef(box = IRect(0, 0, w, h))))
        assertEquals(null, map.resolveError(PlaceRef(lines = 1..2)))
    }

    @Test
    fun errorBoxSelectsOneOfSeveralLines() {
        val map = InkLines.detect(page(listOf(Triple(60, 40, 700), Triple(140, 40, 900))), w, h)
        val mark = map.resolveError(PlaceRef(lines = 1..2, box = IRect(35, 138, 950, 166)))!!
        assertTrue("ошибка во второй строке: $mark", mark.top > 100 && mark.bottom < 190)
    }

    @Test
    fun emptyPageHasNoLines() {
        val map = InkLines.detect(IntArray(w * h) { 250 }, w, h)
        assertTrue(map.lines.isEmpty())
        val box = IRect(10, 10, 100, 100)
        assertEquals(box, map.resolve(PlaceRef(box = box)))
    }

    @Test
    fun wholeScreenBoxIsNotHighlighted() {
        val map = InkLines.detect(page(listOf(Triple(60, 40, 700), Triple(140, 40, 900))), w, h)
        // Модель обвела весь экран — подсветки нет, заметка просто без места.
        assertEquals(null, map.resolve(PlaceRef(box = IRect(0, 0, w, h)), hintLike = true))
        assertEquals(null, map.resolve(PlaceRef(box = IRect(0, 300, w, h))))
        // А небольшая рамка остаётся.
        assertNotNull(map.resolve(PlaceRef(box = IRect(100, 400, 400, 450)), hintLike = true))
    }

    @Test
    fun giantDetectedLineFallsBackToModelBox() {
        // Фото или PDF под записью: «строка» на весь лист.
        val px = IntArray(w * h) { 250 }
        for (yy in 20 until h - 20) for (xx in 40 until 1100) if ((xx * 7 + yy * 3) % 5 == 0) px[yy * w + xx] = 30
        val map = InkLines.detect(px, w, h)
        assertEquals(null, map.resolve(PlaceRef(lines = 1..1)))
        val box = IRect(300, 300, 600, 340)
        val resolved = map.resolve(PlaceRef(lines = 1..1, box = box))!!
        assertTrue("выделен только кусок: $resolved", resolved.left >= 285 && resolved.right <= 615 && resolved.height <= 65)
    }

    @Test
    fun subPieceInsideSingleContinuousLineIsNotStretchedToFullLine() {
        val map = InkLines.detect(page(listOf(Triple(140, 40, 960))), w, h)
        assertEquals(1, map.lines.size)
        // Даже если вся строка — один сегмент 40..960, рамка вокруг одного слагаемого выделяет именно этот кусок.
        val piece = IRect(380, 132, 520, 174)
        val byLineAndBox = map.resolve(PlaceRef(lines = 1..1, box = piece))!!
        assertTrue("не растянулось на всю строку: $byLineAndBox", byLineAndBox.left >= 365 && byLineAndBox.right <= 535)
        val byError = map.resolveError(PlaceRef(lines = 1..1, box = piece))!!
        assertTrue("ошибка выделяет только кусок: $byError", byError.left >= 370 && byError.right <= 530)
        val bySnap = map.resolve(PlaceRef(box = piece))!!
        assertTrue("snap выделяет только кусок: $bySnap", bySnap.left >= 365 && bySnap.right <= 535)
    }

    @Test
    fun denseConsecutiveLinesDoNotMergeIntoMegaBand() {
        // 6 плотно идущих строк с небольшими разрывами между ними
        val spec = (0 until 6).map { Triple(50 + it * 45, 50, 950) }
        val map = InkLines.detect(page(spec), w, h)
        assertTrue("строки не должны сливаться в 1-2 мега-строки: ${map.lines.size}", map.lines.size >= 4)
        map.lines.forEach { line ->
            assertTrue("высота строки не должна быть на полстраницы: ${line.height}", line.height < 65)
        }
    }

    @Test
    fun pieceCoordinatesResolveToUnifiedPieceNotWholePage() {
        val spec = listOf(Triple(60, 40, 700), Triple(140, 40, 900), Triple(230, 80, 500))
        val map = InkLines.detect(page(spec), w, h)
        // Модель выделила слагаемое в строке 2 (например, 200..320)
        val pieceBox = IRect(200, 135, 320, 170)
        val resolved = map.resolve(PlaceRef(lines = 2..2, box = pieceBox))!!
        assertTrue("выделение ограничено куском по горизонтали: $resolved", resolved.left in 190..210 && resolved.right in 315..335)
        assertTrue("выделение ограничено одной строкой: $resolved", resolved.height in 20..60)
    }
}
