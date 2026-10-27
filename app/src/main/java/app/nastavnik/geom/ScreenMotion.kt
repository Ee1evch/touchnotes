package app.nastavnik.geom

import kotlin.math.abs

/**
 * Уменьшенный серый кадр экрана: [cols]×[rows] клеток, в каждой — средняя яркость 0…255.
 * Им приложение «видит» экран между вопросами: прокрутили ли страницу, пишет ли студент.
 */
class Thumb(val cols: Int, val rows: Int, val screenW: Int, val screenH: Int, val luma: IntArray) {
    val cellW: Float get() = screenW.toFloat() / cols
    val cellH: Float get() = screenH.toFloat() / rows

    operator fun get(c: Int, r: Int): Int = luma[r * cols + c]

    /** Клетки, которые закрывает прямоугольник экрана (с краями). */
    fun cellsOf(rect: IRect): IRect = IRect(
        (rect.left / cellW).toInt().coerceIn(0, cols),
        (rect.top / cellH).toInt().coerceIn(0, rows),
        kotlin.math.ceil(rect.right / cellW).toInt().coerceIn(0, cols),
        kotlin.math.ceil(rect.bottom / cellH).toInt().coerceIn(0, rows),
    )

    /** Маска «не сравнивать»: наши окна поверх экрана. */
    fun maskOf(rects: List<IRect>): BooleanArray {
        val m = BooleanArray(cols * rows)
        for (r in rects) {
            val c = cellsOf(r)
            for (y in c.top until c.bottom) for (x in c.left until c.right) m[y * cols + x] = true
        }
        return m
    }

    /** Яркость чистого листа — самая частая (по медиане). */
    val background: Int by lazy { luma.sorted()[luma.size / 2] }

    /** Доля исписанных клеток в прямоугольнике экрана — чтобы ставить заметки на пустое место. */
    fun inkFraction(rect: IRect): Float {
        val c = cellsOf(rect)
        if (c.width <= 0 || c.height <= 0) return 0f
        var ink = 0
        for (y in c.top until c.bottom) for (x in c.left until c.right) {
            if (abs(luma[y * cols + x] - background) > INK_DIFF) ink++
        }
        return ink.toFloat() / (c.width * c.height)
    }

    companion object {
        const val INK_DIFF = 18

        /** Сколько клеток по ширине: на планшете 2560 px это 16 px на клетку. */
        const val COLS = 160

        fun rowsFor(screenW: Int, screenH: Int): Int = (COLS * screenH.toFloat() / screenW).toInt().coerceAtLeast(8)
    }
}

/** Собирает [Thumb] из строк пикселей ARGB, честно усредняя каждую клетку (без «ряби» от простого уменьшения). */
class ThumbBuilder(private val srcW: Int, private val srcH: Int, val cols: Int = Thumb.COLS, val rows: Int = Thumb.rowsFor(srcW, srcH)) {
    private val sum = LongArray(cols * rows)
    private val count = IntArray(cols * rows)
    private val colOf = IntArray(srcW) { x -> (x.toLong() * cols / srcW).toInt() }

    fun addRow(y: Int, argb: IntArray, offset: Int = 0) {
        val r = (y.toLong() * rows / srcH).toInt().coerceIn(0, rows - 1)
        val base = r * cols
        for (x in 0 until srcW) {
            val p = argb[offset + x]
            // Яркость по ITU-R BT.601, целочисленно.
            val l = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            val i = base + colOf[x]
            sum[i] += l.toLong()
            count[i]++
        }
    }

    fun build(): Thumb = Thumb(cols, rows, srcW, srcH, IntArray(cols * rows) { i -> if (count[i] == 0) 255 else (sum[i] / count[i]).toInt() })
}

sealed interface Motion {
    /** Страница на месте (может быть, что-то дописали). */
    data class Same(val changedCells: Int) : Motion

    /** Страницу прокрутили: содержимое сдвинулось на (dx, dy) пикселей экрана. */
    data class Moved(val dx: Int, val dy: Int) : Motion

    /** На экране другое: новая страница, другое приложение, сильный зум. */
    data object Replaced : Motion
}

object ScreenMotion {
    /** Клетка «изменилась», если её средняя яркость сдвинулась больше чем на столько. */
    const val CELL_DIFF = 8

    /** Сколько клеток изменилось между кадрами (без маски). */
    fun changedCells(a: Thumb, b: Thumb, mask: BooleanArray? = null): Int {
        if (a.cols != b.cols || a.rows != b.rows) return a.cols * a.rows
        var n = 0
        for (i in a.luma.indices) {
            if (mask != null && mask[i]) continue
            if (abs(a.luma[i] - b.luma[i]) > CELL_DIFF) n++
        }
        return n
    }

    private class Score(val mean: Double, val compared: Int)

    /** Средняя разница, если считать, что содержимое [ref] сдвинулось на (dx, dy) клеток и стало [cur]. */
    private fun score(ref: Thumb, cur: Thumb, mask: BooleanArray?, dx: Int, dy: Int): Score {
        var total = 0L
        var n = 0
        val cols = cur.cols
        for (y in 0 until cur.rows) {
            val ry = y - dy
            if (ry < 0 || ry >= ref.rows) continue
            for (x in 0 until cols) {
                val rx = x - dx
                if (rx < 0 || rx >= cols) continue
                val i = y * cols + x
                if (mask != null && mask[i]) continue
                total += abs(cur.luma[i] - ref.luma[ry * cols + rx])
                n++
            }
        }
        return Score(if (n == 0) Double.MAX_VALUE else total.toDouble() / n, n)
    }

    /**
     * Сравнивает кадр [cur] с опорным [ref] (снят, когда наставник отвечал).
     * [mask] — клетки [cur], закрытые нашими окнами.
     */
    fun estimate(ref: Thumb, cur: Thumb, mask: BooleanArray? = null): Motion {
        if (ref.cols != cur.cols || ref.rows != cur.rows) return Motion.Replaced
        val total = cur.cols * cur.rows
        val changed0 = changedCells(ref, cur, mask)
        if (changed0 <= maxOf(2, total / 100)) return Motion.Same(changed0)

        val minCompared = (total * 0.4).toInt()
        val s0 = score(ref, cur, mask, 0, 0)
        var bestDx = 0
        var bestDy = 0
        var best = s0.mean
        fun tryShift(dx: Int, dy: Int) {
            val s = score(ref, cur, mask, dx, dy)
            if (s.compared >= minCompared && s.mean < best - 1e-9) {
                best = s.mean; bestDx = dx; bestDy = dy
            }
        }
        // Сначала вертикаль (обычная прокрутка), потом горизонталь, потом уточнение.
        for (dy in -cur.rows / 2..cur.rows / 2) tryShift(0, dy)
        val dy0 = bestDy
        for (dx in -cur.cols / 4..cur.cols / 4) tryShift(dx, dy0)
        val dx0 = bestDx
        for (dy in dy0 - 2..dy0 + 2) for (dx in dx0 - 2..dx0 + 2) tryShift(dx, dy)

        if ((bestDx != 0 || bestDy != 0) && best <= s0.mean * 0.45) {
            return Motion.Moved((bestDx * cur.cellW).toInt(), (bestDy * cur.cellH).toInt())
        }
        return if (changed0 > total * 0.15) Motion.Replaced else Motion.Same(changed0)
    }
}
