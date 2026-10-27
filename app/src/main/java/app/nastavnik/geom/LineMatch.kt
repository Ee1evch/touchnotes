package app.nastavnik.geom

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Снимок записи: яркость пикселей и найденные на нём строки. */
class PageImage(val luma: IntArray, val width: Int, val height: Int, val lines: LineMap)

/**
 * Находит отмеченную строку на новом снимке, даже если страницу увеличили, уменьшили или сдвинули.
 * У каждой строки есть «почерк» — сколько чернил в каждом столбике по её ширине. При изменении масштаба
 * он почти не меняется, поэтому строку можно узнать; соседние строки помогают не спутать похожие.
 */
object LineMatch {
    private const val BINS = 48

    /** Насколько уверенно узнали строку, чтобы перенести отметку (от −1 до 1). */
    private const val MIN_SCORE = 0.62f

    fun signature(p: PageImage, l: TextLine): FloatArray {
        val bg = p.lines.background
        val cols = FloatArray(BINS)
        val w = l.right - l.left
        if (w <= 0) return cols
        for (y in max(0, l.top) until min(p.height, l.bottom)) {
            val row = y * p.width
            for (x in max(0, l.left) until min(p.width, l.right)) {
                if (abs(p.luma[row + x] - bg) > InkLines.INK_DIFF) {
                    cols[((x - l.left).toLong() * BINS / w).toInt().coerceIn(0, BINS - 1)] += 1f
                }
            }
        }
        val mean = cols.average().toFloat()
        var n = 0.0
        for (i in cols.indices) {
            cols[i] -= mean
            n += cols[i] * cols[i]
        }
        val norm = sqrt(n).toFloat()
        if (norm > 0f) for (i in cols.indices) cols[i] /= norm
        return cols
    }

    private fun corr(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun overlap(l: TextLine, box: IRect): Int = min(l.bottom, box.bottom) - max(l.top, box.top)

    /**
     * Где на новом снимке [cur] то, что на старом [ref] было в рамке [box] (в пикселях [ref]).
     * Возвращает рамку в пикселях [cur] или null, если строку не узнали (её нет на экране).
     */
    fun relocate(ref: PageImage, box: IRect, cur: PageImage): IRect? {
        val rl = ref.lines.lines
        val cl = cur.lines.lines
        if (rl.isEmpty() || cl.isEmpty()) return null
        // Опорная строка — та, что больше всех перекрыта рамкой; рамка под строкой (подсказка) — ближайшая выше.
        var ai = rl.indices.maxBy { overlap(rl[it], box) }
        if (overlap(rl[ai], box) <= 0) {
            ai = rl.indices.filter { rl[it].bottom <= box.top + 2 }.maxByOrNull { rl[it].bottom }
                ?: rl.indices.minBy { abs(rl[it].centerY - box.centerY) }
        }
        val a = rl[ai]
        if (a.width < 8) return null
        val sigA = signature(ref, a)
        val sigPrev = rl.getOrNull(ai - 1)?.let { signature(ref, it) }
        val sigNext = rl.getOrNull(ai + 1)?.let { signature(ref, it) }
        val curSigs = cl.map { signature(cur, it) }

        var best = -1
        var bestScore = -2f
        for ((ci, c) in cl.withIndex()) {
            val s = c.width.toFloat() / a.width
            val sh = c.height.toFloat() / max(1, a.height)
            // Масштаб один на всю страницу: ширина и высота строки меняются одинаково.
            if (s < 0.3f || s > 4f || abs(ln(s / sh)) > 0.6f) continue
            var score = corr(sigA, curSigs[ci])
            var weight = 1f
            if (sigPrev != null && ci > 0) { score += 0.5f * corr(sigPrev, curSigs[ci - 1]); weight += 0.5f }
            if (sigNext != null && ci < cl.lastIndex) { score += 0.5f * corr(sigNext, curSigs[ci + 1]); weight += 0.5f }
            score /= weight
            if (score > bestScore) { bestScore = score; best = ci }
        }
        if (best < 0 || bestScore < MIN_SCORE) return null
        val c = cl[best]
        val s = c.width.toFloat() / a.width
        fun mx(x: Int) = (c.left + (x - a.left) * s).roundToInt()
        fun my(y: Int) = (c.top + (y - a.top) * s).roundToInt()
        return IRect(mx(box.left), my(box.top), mx(box.right), my(box.bottom))
    }
}
