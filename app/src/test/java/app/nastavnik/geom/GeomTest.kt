package app.nastavnik.geom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GeomTest {
    private val area = IRect(0, 0, 2560, 1500)

    @Test
    fun calloutGoesNextToAnchorWithoutCoveringIt() {
        val anchor = IRect(300, 400, 1100, 480)
        val p = CalloutPlacer.place(area, anchor, 600, 300, 10)
        assertFalse("заметка закрыла место", p.rect.intersects(anchor))
        assertEquals(Side.RIGHT, p.side)
        assertTrue(p.rect.left >= anchor.right)
    }

    @Test
    fun calloutFlipsWhenNoRoomOnTheRight() {
        val anchor = IRect(1900, 400, 2540, 480)
        val p = CalloutPlacer.place(area, anchor, 600, 300, 10)
        assertFalse(p.rect.intersects(anchor))
        assertTrue(p.rect.left >= 0 && p.rect.right <= 2560)
    }

    @Test
    fun calloutPrefersEmptyPaper() {
        val anchor = IRect(300, 400, 1100, 480)
        // Справа от места всё исписано — заметка должна уйти на пустое место.
        val busyRight = IRect(1100, 0, 2560, 1500)
        val p = CalloutPlacer.place(area, anchor, 600, 300, 10) { r -> if (r.intersects(busyRight)) 1f else 0f }
        assertFalse(p.rect.intersects(busyRight))
        assertFalse(p.rect.intersects(anchor))
    }

    @Test
    fun calloutsDoNotOverlapEachOther() {
        val anchor = IRect(300, 400, 1100, 480)
        val first = CalloutPlacer.place(area, anchor, 600, 300, 10).rect
        val second = CalloutPlacer.place(area, IRect(300, 500, 1100, 580), 600, 300, 10, avoid = listOf(first, anchor)).rect
        assertFalse(second.intersects(first))
    }

    @Test
    fun clampKeepsInsideScreen() {
        val r = IRect(-50, 1400, 550, 1700).clampInto(area)
        assertEquals(IRect(0, 1200, 600, 1500), r)
    }

    // ---------------- слежение за страницей ----------------

    private val w = 1600
    private val h = 1000

    /** Длинный «лист», из которого вырезаем окно экрана со сдвигом по вертикали. */
    private fun tall(seed: Int): IntArray {
        val hh = h * 3
        val px = IntArray(w * hh) { 0xFFFFFFFF.toInt() }
        val rnd = Random(seed)
        repeat(60) {
            val y = rnd.nextInt(0, hh - 30)
            val x = rnd.nextInt(40, w - 500)
            val len = rnd.nextInt(150, 450)
            for (yy in y until y + 22) for (xx in x until x + len) if ((xx / 7 + yy / 5) % 3 != 0) px[yy * w + xx] = 0xFF202020.toInt()
        }
        return px
    }

    private fun thumbOf(sheet: IntArray, scroll: Int): Thumb {
        val b = ThumbBuilder(w, h)
        val row = IntArray(w)
        for (y in 0 until h) {
            System.arraycopy(sheet, (y + scroll) * w, row, 0, w)
            b.addRow(y, row)
        }
        return b.build()
    }

    @Test
    fun sameScreenIsSame() {
        val sheet = tall(1)
        val m = ScreenMotion.estimate(thumbOf(sheet, 500), thumbOf(sheet, 500))
        assertTrue(m is Motion.Same)
    }

    @Test
    fun scrollIsDetectedWithItsDistance() {
        val sheet = tall(2)
        val ref = thumbOf(sheet, 800)
        val cur = thumbOf(sheet, 800 + 240) // прокрутили вниз — записи уехали вверх на 240 px
        val m = ScreenMotion.estimate(ref, cur)
        assertTrue("ожидали сдвиг, получили $m", m is Motion.Moved)
        m as Motion.Moved
        assertEquals(0, m.dx)
        assertTrue("dy=${m.dy}", m.dy in -260..-220)
    }

    /** Экран с неподвижной шапкой приложения сверху: прокручивается только содержимое под ней. */
    private fun thumbWithHeader(sheet: IntArray, scroll: Int, header: Int = 130): Thumb {
        val b = ThumbBuilder(w, h)
        val row = IntArray(w)
        for (y in 0 until h) {
            if (y < header) {
                for (x in 0 until w) row[x] = if (y in 50..80 && x in 60..400 && (x / 6) % 2 == 0) 0xFF303030.toInt() else 0xFFF0F4F9.toInt()
            } else {
                System.arraycopy(sheet, (y + scroll) * w, row, 0, w)
            }
            b.addRow(y, row)
        }
        return b.build()
    }

    @Test
    fun scrollByNonWholeCellsUnderFixedHeader() {
        val sheet = tall(7)
        for (dist in listOf(379, -237, 53)) {
            val ref = thumbWithHeader(sheet, 900)
            val cur = thumbWithHeader(sheet, 900 + dist)
            val m = ScreenMotion.estimate(ref, cur)
            assertTrue("сдвиг $dist: ожидали Moved, получили $m", m is Motion.Moved)
            m as Motion.Moved
            assertTrue("сдвиг $dist: dy=${m.dy}", kotlin.math.abs(m.dy + dist) <= 3)
        }
    }

    @Test
    fun scrollIsFoundWhenOurWindowsCoverPartOfScreen() {
        val sheet = tall(8)
        val ref = thumbOf(sheet, 600)
        val shifted = sheet.copyOf()
        val cur0 = thumbOf(sheet, 600 + 311)
        // Заметка и рамка поверх экрана: закрашены и замаскированы.
        val rects = listOf(IRect(900, 200, 1500, 600), IRect(100, 300, 800, 420))
        val px = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(shifted, (y + 600 + 311) * w, px, y * w, w)
        for (r in rects) for (y in r.top until r.bottom) for (x in r.left until r.right) px[y * w + x] = 0xFF4285F4.toInt()
        val b = ThumbBuilder(w, h)
        val row = IntArray(w)
        for (y in 0 until h) { System.arraycopy(px, y * w, row, 0, w); b.addRow(y, row) }
        val cur = b.build()
        val m = ScreenMotion.estimate(ref, cur, cur.maskOf(rects), cur.rowMaskOf(rects))
        assertTrue("ожидали Moved, получили $m (без окон: ${ScreenMotion.estimate(ref, cur0)})", m is Motion.Moved)
        assertTrue(kotlin.math.abs((m as Motion.Moved).dy + 311) <= 3)
    }

    @Test
    fun otherPageIsReplaced() {
        val a = thumbOf(tall(3), 0)
        val b = thumbOf(tall(4), 0)
        assertEquals(Motion.Replaced, ScreenMotion.estimate(a, b))
    }

    @Test
    fun writingAFewWordsIsNotAScroll() {
        val sheet = tall(5)
        val ref = thumbOf(sheet, 300)
        val edited = sheet.copyOf()
        for (yy in 300 + 700 until 300 + 720) for (xx in 200 until 420) edited[yy * w + xx] = 0xFF202020.toInt()
        val m = ScreenMotion.estimate(ref, thumbOf(edited, 300))
        assertTrue("дописали строку — это не прокрутка: $m", m is Motion.Same)
        assertTrue(ScreenMotion.changedCells(ref, thumbOf(edited, 300)) >= 2)
    }

    @Test
    fun maskedCellsAreIgnored() {
        val sheet = tall(6)
        val ref = thumbOf(sheet, 300)
        val covered = sheet.copyOf()
        // Наше окно (например, заметка) закрыло кусок экрана.
        for (yy in 300 + 100 until 300 + 600) for (xx in 900 until 1500) covered[yy * w + xx] = 0xFF4285F4.toInt()
        val cur = thumbOf(covered, 300)
        val mask = cur.maskOf(listOf(IRect(900, 100, 1500, 600)))
        assertEquals(0, ScreenMotion.changedCells(ref, cur, mask))
    }

    @Test
    fun inkFractionSeesWriting() {
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (yy in 100 until 200) for (xx in 100 until 600) px[yy * w + xx] = 0xFF000000.toInt()
        val b = ThumbBuilder(w, h)
        val row = IntArray(w)
        for (y in 0 until h) { System.arraycopy(px, y * w, row, 0, w); b.addRow(y, row) }
        val t = b.build()
        assertTrue(t.inkFraction(IRect(100, 100, 600, 200)) > 0.9f)
        assertEquals(0f, t.inkFraction(IRect(900, 500, 1400, 900)), 0.001f)
    }
}
