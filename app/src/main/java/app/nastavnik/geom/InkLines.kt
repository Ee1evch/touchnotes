package app.nastavnik.geom

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Строка записей на снимке (в пикселях снимка без поля с номерами). [number] начинается с 1. */
data class TextLine(
    val number: Int,
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
    /** Куски строки по горизонтали (несколько примеров в одной строке). */
    val segments: List<IntRange>,
) {
    val height: Int get() = bottom - top
    val width: Int get() = right - left
    val centerY: Int get() = (top + bottom) / 2
}

/** Ссылка на место из ответа модели: строки, «под строкой» и/или рамка (в пикселях снимка без поля). */
data class PlaceRef(val lines: IntRange? = null, val after: Int? = null, val box: IRect? = null)

/**
 * Строки записей, найденные на снимке. Приложение подписывает их номерами слева от снимка,
 * модель называет номер, а рамка ложится точно по чернилам — без угадывания координат.
 */
class LineMap(
    val lines: List<TextLine>,
    val width: Int,
    val height: Int,
    /** Яркость листа — от неё считается, что такое «чернила». */
    val background: Int = 255,
) {

    /** Типичная высота строки — от неё зависят отступы. */
    val lineHeight: Int = lines.map { it.height }.sorted().let { if (it.isEmpty()) max(24, height / 30) else it[it.size / 2] }

    fun line(n: Int): TextLine? = lines.getOrNull(n - 1)?.takeIf { it.number == n } ?: lines.firstOrNull { it.number == n }

    private val pad: Int get() = max(4, (lineHeight * 0.22f).roundToInt())

    /**
     * Где на снимке рисовать рамку. [hintLike] — подсказка следующего шага: если модель дала только рамку,
     * её не притягиваем к чернилам (подсказка указывает на пустое место).
     */
    fun resolve(ref: PlaceRef, hintLike: Boolean = false): IRect? = localOnly(resolveAny(ref, hintLike), ref.box)

    private fun resolveAny(ref: PlaceRef, hintLike: Boolean): IRect? {
        ref.lines?.let { range -> byLines(range, ref.box)?.let { return it } }
        ref.after?.let { n -> line(n)?.let { return below(it) } }
        val box = ref.box ?: return null
        return if (hintLike) box.clampInto(IRect(0, 0, width, height)) else snap(box)
    }

    /**
     * Отметка — небольшое место (строка, формула, кусок рисунка), а не весь лист:
     * не выше нескольких строк и не больше трети снимка по высоте.
     */
    fun isLocal(r: IRect): Boolean {
        val maxH = min(max(lineHeight * 6, height / 8), (height * 0.35f).roundToInt())
        val maxW = (width * 0.95f).roundToInt()
        return r.width in 1..maxW && r.height in 1..maxH
    }

    /** Слишком большую рамку (весь экран, полстраницы) не рисуем: берём рамку модели, если она сама небольшая. */
    private fun localOnly(r: IRect?, modelBox: IRect?): IRect? = when {
        r == null -> null
        isLocal(r) -> r
        else -> modelBox?.clampInto(IRect(0, 0, width, height))?.takeIf(::isLocal)
    }

    /** Ошибка относится к одной строке. Широкую или двусмысленную рамку не рисуем. */
    fun resolveError(ref: PlaceRef): IRect? = localOnly(resolveErrorAny(ref), ref.box)

    private fun resolveErrorAny(ref: PlaceRef): IRect? {
        val candidates = ref.lines?.let { range -> lines.filter { it.number in range } } ?: lines
        val box = ref.box
        val target = when {
            candidates.size == 1 && ref.lines != null -> candidates.first()
            box == null -> return null
            box.height > lineHeight * 2 -> return null
            else -> candidates.filter { it.bottom > box.top && it.top < box.bottom }
                .minByOrNull { abs(it.centerY - box.centerY) }
        }
        if (target == null) {
            // На рисунке может не быть распознанной строки, но рамка должна быть локальной.
            return box?.takeIf { it.width in 1..(width / 2) && it.height in 1..(lineHeight * 2) }
                ?.clampInto(IRect(0, 0, width, height))
        }
        val xParts = if (box != null && box.width < width / 2) {
            val slack = max(4, lineHeight / 3)
            target.segments.filter { it.last >= box.left - slack && it.first <= box.right + slack }
        } else emptyList()
        val left = if (xParts.isEmpty()) target.left else xParts.minOf { it.first }
        val right = if (xParts.isEmpty()) target.right else xParts.maxOf { it.last } + 1
        val tightPad = max(2, (lineHeight * 0.1f).roundToInt())
        return clampToPiece(left, target.top, right, target.bottom, box, tightPad)
    }

    private fun byLines(range: IntRange, box: IRect?): IRect? {
        // Отметка — это строка, а не блок: длинный диапазон от модели обрезаем до трёх строк.
        val ls = range.take(3).mapNotNull { line(it) }
        if (ls.isEmpty()) return null
        val top = ls.minOf { it.top }
        val bottom = ls.maxOf { it.bottom }
        val slack = max(lineHeight / 2, (box?.width ?: 0) / 8)
        var parts = if (box != null) {
            ls.flatMap { it.segments }.filter { it.last >= box.left - slack && it.first <= box.right + slack }
        } else emptyList()
        if (parts.isEmpty()) {
            parts = ls.flatMap { it.segments }
        }
        val left = parts.minOf { it.first }
        val right = parts.maxOf { it.last } + 1
        return clampToPiece(left, top, right, bottom, box, pad)
    }

    /**
     * Если модель выделила конкретный кусок внутри длинной строки или высокого блока
     * (например, одну формулу, слагаемое или слово), выделяем именно этот единый кусок,
     * а не растягиваем выделение на всю строку или полстраницы.
     */
    private fun clampToPiece(left: Int, top: Int, right: Int, bottom: Int, box: IRect?, p: Int): IRect {
        var l = left
        var r = right
        if (box != null && box.width > 0) {
            val slack = max(lineHeight / 2, box.width / 8)
            if (box.left - left > slack) {
                l = box.left
            }
            if (right - box.right > slack) {
                r = box.right
            }
            if (l >= r) {
                l = left
                r = right
            }
        }
        return IRect(l - p, top - p, r + p, bottom + p).clampInto(IRect(0, 0, width, height))
    }

    /** Место под строкой: туда студент пишет следующий шаг. */
    private fun below(l: TextLine): IRect {
        val gap = max(4, (lineHeight * 0.25f).roundToInt())
        val h = max(l.height, lineHeight)
        val top = min(l.bottom + gap, height - h)
        val w = max((l.width * 0.7f).roundToInt(), min(width / 4, width - l.left))
        return IRect(l.left - pad, top, min(width, l.left + w), min(height, top + h + pad)).clampInto(IRect(0, 0, width, height))
    }

    /** Рамка модели «на глаз» → по строкам, которые она задевает, и по чернилам внутри неё. */
    fun snap(box: IRect): IRect {
        val hitAll = lines.filter { l ->
            val overlap = min(l.bottom, box.bottom) - max(l.top, box.top)
            overlap >= l.height * 0.3f || overlap >= box.height * 0.4f || (l.centerY in box.top..box.bottom)
        }
        if (hitAll.isEmpty()) return box.clampInto(IRect(0, 0, width, height))
        // Одна отметка — одна строка: широкая рамка «на глаз» не должна захватывать полстраницы.
        val hit = if (hitAll.size > 2) listOf(hitAll.minBy { abs(it.centerY - box.centerY) }) else hitAll
        val slack = max(lineHeight / 2, box.width / 10)
        val parts = hit.flatMap { it.segments }.filter { it.last >= box.left - slack && it.first <= box.right + slack }
        if (parts.isEmpty()) return box.clampInto(IRect(0, 0, width, height))
        return clampToPiece(
            parts.minOf { it.first },
            hit.minOf { it.top },
            parts.maxOf { it.last } + 1,
            hit.maxOf { it.bottom },
            box,
            pad,
        )
    }

    companion object {
        val EMPTY = LineMap(emptyList(), 1, 1)
    }
}

/** Поиск строк рукописных записей на снимке по яркости пикселей. */
object InkLines {
    /** Пиксель — «чернила», если отличается от фона листа сильнее этого. */
    const val INK_DIFF = 60
    private const val MAX_LINES = 80

    fun detect(luma: IntArray, w: Int, h: Int): LineMap {
        if (w < 16 || h < 16) return LineMap(emptyList(), w, h)
        val bg = background(luma)
        val ink = BooleanArray(w * h) { abs(luma[it] - bg) > INK_DIFF }

        // Линовка и клетка тетради — длинные линии через весь лист — чернилами не считаются.
        val colCount = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) if (ink[y * w + x]) colCount[x]++
        for (x in 0 until w) if (colCount[x] > h * 0.5) for (y in 0 until h) ink[y * w + x] = false
        val rowCount = IntArray(h)
        for (y in 0 until h) {
            var c = 0
            for (x in 0 until w) if (ink[y * w + x]) c++
            if (c > w * 0.5) { for (x in 0 until w) ink[y * w + x] = false; c = 0 }
            rowCount[y] = c
        }

        // Полосы строк: подряд идущие строки пикселей с чернилами, маленькие разрывы склеиваем.
        val minInk = max(3, (w / 400).coerceAtLeast(2))
        val smallGap = max(2, (h / 250).coerceAtLeast(2))
        val bands = mutableListOf<IntArray>() // [top, bottom)
        var y = 0
        while (y < h) {
            if (rowCount[y] < minInk) { y++; continue }
            val start = y
            var end = y + 1
            var gap = 0
            var yy = y + 1
            while (yy < h) {
                if (rowCount[yy] >= minInk) { end = yy + 1; gap = 0 } else if (++gap > smallGap) break
                yy++
            }
            bands += intArrayOf(start, end)
            y = end + 1
        }
        // Шум (точки, пылинки) выбрасываем.
        bands.removeAll { b -> b[1] - b[0] < 3 || (b[0] until b[1]).sumOf { rowCount[it] } < 12 }
        if (bands.isEmpty()) return LineMap(emptyList(), w, h, bg)

        // Склеиваем индексы, степени, дробные черты и точки с соседней строкой.
        // Ограничиваем предельную высоту склеенной строки, чтобы строки не объединялись в полстраницы.
        val mh = bands.map { it[1] - it[0] }.sorted()[bands.size / 2].coerceAtLeast(4)
        val maxMergedH = max(mh * 2.2f, h * 0.10f).toInt()
        var merged = true
        while (merged && bands.size > 1) {
            merged = false
            for (i in 0 until bands.size - 1) {
                val a = bands[i]
                val b = bands[i + 1]
                if (b[1] - a[0] > maxMergedH) continue
                val gap = b[0] - a[1]
                val small = min(a[1] - a[0], b[1] - b[0]) < mh * 0.5f
                if (small && gap < mh * 0.6f) {
                    bands[i] = intArrayOf(a[0], b[1])
                    bands.removeAt(i + 1)
                    merged = true
                    break
                }
            }
        }

        val lines = mutableListOf<TextLine>()
        val split = max((mh * 1.5f).roundToInt(), min(w / 20, 60))
        for (b in bands) {
            val cols = IntArray(w)
            for (yy in b[0] until b[1]) for (x in 0 until w) if (ink[yy * w + x]) cols[x]++
            val segs = mutableListOf<IntRange>()
            var x = 0
            while (x < w) {
                if (cols[x] == 0) { x++; continue }
                val s = x
                var e = x
                var g = 0
                var xx = x + 1
                while (xx < w) {
                    if (cols[xx] > 0) { e = xx; g = 0 } else if (++g > split) break
                    xx++
                }
                // Одиночный столбик — шум.
                if (e - s >= 2 || (s..e).sumOf { cols[it] } >= 4) segs += s..e
                x = e + 1
            }
            if (segs.isEmpty()) continue
            lines += TextLine(lines.size + 1, b[0], b[1], segs.first().first, segs.last().last + 1, segs)
            if (lines.size >= MAX_LINES) break
        }
        return LineMap(lines, w, h, bg)
    }

    /** Яркость листа — самая частая (медиана по выборке). */
    private fun background(luma: IntArray): Int {
        val step = max(1, luma.size / 50_000)
        val sample = IntArray((luma.size + step - 1) / step) { luma[min(luma.size - 1, it * step)] }
        sample.sort()
        return sample[sample.size / 2]
    }
}
