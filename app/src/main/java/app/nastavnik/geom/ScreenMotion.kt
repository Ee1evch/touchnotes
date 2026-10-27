package app.nastavnik.geom

import kotlin.math.abs

/**
 * Уменьшенный серый кадр экрана: [cols]×[rows] клеток, в каждой — средняя яркость 0…255.
 * Им приложение «видит» экран между вопросами: прокрутили ли страницу, пишет ли студент.
 */
class Thumb(
    val cols: Int,
    val rows: Int,
    val screenW: Int,
    val screenH: Int,
    val luma: IntArray,
    /**
     * Профиль строк в полном вертикальном разрешении: для каждой строки пикселей — средняя яркость
     * в [BANDS] вертикальных полосах. По нему прокрутка находится с точностью до пикселя.
     */
    val rowProfile: IntArray = IntArray(0),
) {
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

    /** Маска для [rowProfile]: строка y в полосе b закрыта нашим окном. */
    fun rowMaskOf(rects: List<IRect>): BooleanArray {
        val m = BooleanArray(screenH * BANDS)
        val bandW = screenW.toFloat() / BANDS
        for (r in rects) {
            val b0 = (r.left / bandW).toInt().coerceIn(0, BANDS - 1)
            val b1 = ((r.right - 1) / bandW).toInt().coerceIn(0, BANDS - 1)
            for (y in r.top.coerceAtLeast(0) until r.bottom.coerceAtMost(screenH)) for (b in b0..b1) m[y * BANDS + b] = true
        }
        return m
    }

    companion object {
        const val INK_DIFF = 18

        /** Сколько вертикальных полос в профиле строк. */
        const val BANDS = 8

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
    private val bandOf = IntArray(srcW) { x -> (x.toLong() * Thumb.BANDS / srcW).toInt() }
    private val bandCount = IntArray(Thumb.BANDS).also { c -> bandOf.forEach { c[it]++ } }
    private val profile = IntArray(srcH * Thumb.BANDS)
    private val bandSum = LongArray(Thumb.BANDS)

    fun addRow(y: Int, argb: IntArray, offset: Int = 0) {
        val r = (y.toLong() * rows / srcH).toInt().coerceIn(0, rows - 1)
        val base = r * cols
        bandSum.fill(0L)
        for (x in 0 until srcW) {
            val p = argb[offset + x]
            // Яркость по ITU-R BT.601, целочисленно.
            val l = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            val i = base + colOf[x]
            sum[i] += l.toLong()
            count[i]++
            bandSum[bandOf[x]] += l.toLong()
        }
        if (y in 0 until srcH) {
            for (b in 0 until Thumb.BANDS) profile[y * Thumb.BANDS + b] = (bandSum[b] / bandCount[b].coerceAtLeast(1)).toInt()
        }
    }

    fun build(): Thumb = Thumb(
        cols, rows, srcW, srcH,
        IntArray(cols * rows) { i -> if (count[i] == 0) 255 else (sum[i] / count[i]).toInt() },
        profile,
    )
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

    /** Средняя разница профилей строк, если содержимое сдвинулось на [dy] пикселей. */
    private fun profileScore(ref: Thumb, cur: Thumb, mask: BooleanArray?, dy: Int): Score {
        val bands = Thumb.BANDS
        val h = cur.screenH
        var total = 0L
        var n = 0
        val y0 = maxOf(0, dy)
        val y1 = minOf(h, h + dy)
        for (y in y0 until y1) {
            val ry = y - dy
            val ci = y * bands
            val ri = ry * bands
            for (b in 0 until bands) {
                if (mask != null && mask[ci + b]) continue
                total += abs(cur.rowProfile[ci + b] - ref.rowProfile[ri + b])
                n++
            }
        }
        return Score(if (n == 0) Double.MAX_VALUE else total.toDouble() / n, n)
    }

    /**
     * Сравнивает кадр [cur] с опорным [ref] (снят, когда наставник отвечал).
     * [mask] — клетки [cur], закрытые нашими окнами, [rowMask] — то же для профиля строк.
     */
    fun estimate(ref: Thumb, cur: Thumb, mask: BooleanArray? = null, rowMask: BooleanArray? = null): Motion {
        if (ref.cols != cur.cols || ref.rows != cur.rows) return Motion.Replaced
        val total = cur.cols * cur.rows
        val changed0 = changedCells(ref, cur, mask)
        if (changed0 <= maxOf(2, total / 100)) return Motion.Same(changed0)

        // 1. Обычная прокрутка — по вертикали, с точностью до пикселя (профиль строк).
        val canProfile = ref.rowProfile.size == ref.screenH * Thumb.BANDS && cur.rowProfile.size == ref.rowProfile.size
        if (canProfile) {
            val minCompared = (ref.screenH * Thumb.BANDS * 0.4).toInt()
            val p0 = profileScore(ref, cur, rowMask, 0).mean
            var bestDy = 0
            var best = p0
            fun tryDy(dy: Int) {
                val sc = profileScore(ref, cur, rowMask, dy)
                if (sc.compared >= minCompared && sc.mean < best - 1e-9) { best = sc.mean; bestDy = dy }
            }
            val range = cur.screenH / 2
            var dy = -range
            while (dy <= range) { tryDy(dy); dy += 3 }
            val coarse = bestDy
            for (d in coarse - 3..coarse + 3) tryDy(d)
            if (bestDy != 0 && best <= p0 * 0.5) return Motion.Moved(0, bestDy)
        }

        // 2. Сдвиг ещё и по горизонтали (страницу увеличили и двигают) — по сетке клеток.
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
