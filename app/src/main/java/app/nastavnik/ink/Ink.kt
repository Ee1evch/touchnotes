package app.nastavnik.ink

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Чем пишем: ручка или полупрозрачный маркер. */
enum class Tool { PEN, HIGHLIGHTER }

/** Бумага страницы. Клетка — как в обычной студенческой тетради. */
enum class Paper(val label: String) { GRID("Клетка"), LINED("Линейка"), DOTS("Точки"), BLANK("Чистый") }

/**
 * Штрих в координатах страницы (ширина страницы — [PAGE_W] единиц). [points] — тройки x, y, нажим 0…1.
 */
class Stroke(val tool: Tool, val color: Int, val width: Float, val points: FloatArray) {
    val size: Int get() = points.size / 3
    fun x(i: Int) = points[i * 3]
    fun y(i: Int) = points[i * 3 + 1]
    fun p(i: Int) = points[i * 3 + 2]

    /** Рамка штриха с запасом на толщину. */
    fun bounds(): FloatArray {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until size) {
            l = minOf(l, x(i)); r = maxOf(r, x(i)); t = minOf(t, y(i)); b = maxOf(b, y(i))
        }
        val pad = width
        return floatArrayOf(l - pad, t - pad, r + pad, b + pad)
    }
}

/**
 * Страница тетради: бумага, необязательная картинка-подложка (фото тетради или страница PDF) и штрихи.
 * Высота в единицах страницы: у бумаги — как у листа A4, у картинки — по её пропорциям.
 */
class InkPage(
    val id: Long,
    var paper: Paper,
    /** Имя файла подложки в папке тетради или null. */
    val background: String? = null,
    val height: Float = A4_H,
    val strokes: MutableList<Stroke> = mutableListOf(),
) {
    /** Растёт при каждом изменении — по нему перерисовываются превью и снимки для наставника. */
    var version: Long = 0
        private set

    fun touch() {
        version++
    }
}

const val PAGE_W = 1000f
const val A4_H = 1414f

/** Сглаживание и контур штриха переменной толщины — чтобы линия была как от настоящей ручки. */
object StrokeGeometry {
    /**
     * Сглаживает точки (скользящее среднее по соседям) — убирает дрожание руки, не съедая углы букв.
     */
    fun smooth(points: FloatArray, passes: Int = 1): FloatArray {
        var src = points
        repeat(passes) {
            val n = src.size / 3
            if (n < 3) return src
            val out = src.copyOf()
            for (i in 1 until n - 1) {
                out[i * 3] = (src[(i - 1) * 3] + 2 * src[i * 3] + src[(i + 1) * 3]) / 4f
                out[i * 3 + 1] = (src[(i - 1) * 3 + 1] + 2 * src[i * 3 + 1] + src[(i + 1) * 3 + 1]) / 4f
            }
            src = out
        }
        return src
    }

    /** Толщина в точке: от нажима, но не тоньше трети и не толще полутора базовых. */
    fun widthAt(base: Float, pressure: Float, tool: Tool): Float =
        if (tool == Tool.HIGHLIGHTER) base else base * (0.35f + 1.15f * pressure.coerceIn(0f, 1f)).coerceIn(0.35f, 1.5f)

    /**
     * Контур штриха: левый край вперёд, правый назад — замкнутый многоугольник x0,y0,x1,y1…
     * Концы скругляются полукругами. Одна точка — кружок.
     */
    fun outline(s: Stroke): FloatArray {
        val pts = if (s.tool == Tool.PEN) smooth(s.points) else s.points
        val n = pts.size / 3
        if (n == 0) return FloatArray(0)
        if (n == 1) return circle(pts[0], pts[1], widthAt(s.width, pts[2], s.tool) / 2)
        val left = ArrayList<Float>(n * 2 + 32)
        val right = ArrayList<Float>(n * 2 + 32)
        var lastAngle = 0f
        for (i in 0 until n) {
            val x = pts[i * 3]
            val y = pts[i * 3 + 1]
            val (dx, dy) = if (i == 0) {
                pts[3] - x to pts[4] - y
            } else if (i == n - 1) {
                x - pts[(i - 1) * 3] to y - pts[(i - 1) * 3 + 1]
            } else {
                pts[(i + 1) * 3] - pts[(i - 1) * 3] to pts[(i + 1) * 3 + 1] - pts[(i - 1) * 3 + 1]
            }
            val angle = if (abs(dx) + abs(dy) < 1e-4f) lastAngle else atan2(dy, dx)
            lastAngle = angle
            val hw = widthAt(s.width, pts[i * 3 + 2], s.tool) / 2
            val nx = -sin(angle) * hw
            val ny = cos(angle) * hw
            left += x + nx; left += y + ny
            right += x - nx; right += y - ny
        }
        val out = ArrayList<Float>(left.size + right.size + 64)
        out.addAll(left)
        // Скругление конца: полукруг от левого края к правому.
        cap(out, pts[(n - 1) * 3], pts[(n - 1) * 3 + 1], widthAt(s.width, pts[(n - 1) * 3 + 2], s.tool) / 2, lastAngleOf(pts, n - 1), true)
        for (i in right.size / 2 - 1 downTo 0) {
            out += right[i * 2]; out += right[i * 2 + 1]
        }
        cap(out, pts[0], pts[1], widthAt(s.width, pts[2], s.tool) / 2, lastAngleOf(pts, 0), false)
        return out.toFloatArray()
    }

    private fun lastAngleOf(pts: FloatArray, i: Int): Float {
        val n = pts.size / 3
        val a = if (i == 0) 0 else i - 1
        val b = if (i == 0) 1 else i
        if (n < 2) return 0f
        return atan2(pts[b * 3 + 1] - pts[a * 3 + 1], pts[b * 3] - pts[a * 3])
    }

    private fun cap(out: ArrayList<Float>, cx: Float, cy: Float, r: Float, angle: Float, end: Boolean) {
        // На конце обходим от левой нормали через «вперёд» к правой; на начале — через «назад».
        val start = angle + (Math.PI / 2).toFloat() * if (end) 1 else -1
        val steps = 8
        for (k in 1 until steps) {
            val a = start - (Math.PI.toFloat() * k / steps) * if (end) 1 else -1
            out += cx + cos(a) * r; out += cy + sin(a) * r
        }
    }

    private fun circle(cx: Float, cy: Float, r: Float): FloatArray {
        val steps = 12
        return FloatArray(steps * 2) { i ->
            val a = (2 * Math.PI * (i / 2) / steps).toFloat()
            if (i % 2 == 0) cx + cos(a) * r else cy + sin(a) * r
        }
    }

    /** Штрихи, которые задевает ластик в точке (x, y) радиусом [r]. */
    fun hit(strokes: List<Stroke>, x: Float, y: Float, r: Float): List<Stroke> = strokes.filter { s ->
        val b = s.bounds()
        if (x < b[0] - r || x > b[2] + r || y < b[1] - r || y > b[3] + r) return@filter false
        val reach = r + s.width / 2
        if (s.size == 1) return@filter hypot(s.x(0) - x, s.y(0) - y) <= reach
        (0 until s.size - 1).any { i -> segmentDistance(x, y, s.x(i), s.y(i), s.x(i + 1), s.y(i + 1)) <= reach }
    }

    fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Прореживает точки: соседние ближе [minDist] единиц не нужны — штрих легче и плавнее. */
    fun thin(points: FloatArray, minDist: Float): FloatArray {
        val n = points.size / 3
        if (n <= 2) return points
        val out = ArrayList<Float>(points.size)
        out += points[0]; out += points[1]; out += points[2]
        var lx = points[0]
        var ly = points[1]
        for (i in 1 until n - 1) {
            val x = points[i * 3]
            val y = points[i * 3 + 1]
            if (hypot(x - lx, y - ly) >= minDist) {
                out += x; out += y; out += points[i * 3 + 2]
                lx = x; ly = y
            }
        }
        out += points[(n - 1) * 3]; out += points[(n - 1) * 3 + 1]; out += points[(n - 1) * 3 + 2]
        return out.toFloatArray()
    }
}

/**
 * Штрих, готовый к рисованию: отрезки, сгруппированные по толщине. Каждый отрезок рисуется линией
 * со скруглёнными концами — соседние отрезки перекрываются, поэтому линия сплошная при любых разворотах
 * пера (у контура-многоугольника на развороте «перекручивается» заливка, и часть буквы пропадает).
 */
class StrokeLines(
    /** Толщина каждой группы. */
    val widths: FloatArray,
    /** Отрезки группы: x0,y0,x1,y1 подряд — прямо для Canvas.drawLines. */
    val lines: Array<FloatArray>,
    /** Штрих-точка (одно касание): x, y, радиус; иначе пусто. */
    val dot: FloatArray,
)

object InkCurve {
    /**
     * Плавная кривая через точки пера: между серединами соседних отрезков — квадратичная кривая
     * с вершиной в точке пера. Концы штриха не сдвигаются; нажим сглаживается по соседям.
     */
    fun curve(points: FloatArray, step: Float = 1.6f): FloatArray {
        val n = points.size / 3
        if (n < 3) return points
        val p = FloatArray(n) { i ->
            val a = points[(maxOf(i - 1, 0)) * 3 + 2]
            val b = points[i * 3 + 2]
            val c = points[(minOf(i + 1, n - 1)) * 3 + 2]
            (a + 2 * b + c) / 4f
        }
        val out = ArrayList<Float>(points.size * 2)
        fun add(x: Float, y: Float, pr: Float) { out += x; out += y; out += pr }
        add(points[0], points[1], p[0])
        var sx = points[0]
        var sy = points[1]
        var sp = p[0]
        for (i in 1 until n - 1) {
            val cx = points[i * 3]
            val cy = points[i * 3 + 1]
            val ex = (cx + points[(i + 1) * 3]) / 2
            val ey = (cy + points[(i + 1) * 3 + 1]) / 2
            val ep = (p[i] + p[i + 1]) / 2
            val len = hypot(cx - sx, cy - sy) + hypot(ex - cx, ey - cy)
            val k = (len / step).toInt().coerceIn(1, 12)
            for (j in 1..k) {
                val t = j / k.toFloat()
                val u = 1 - t
                add(
                    u * u * sx + 2 * u * t * cx + t * t * ex,
                    u * u * sy + 2 * u * t * cy + t * t * ey,
                    u * sp + t * ep,
                )
            }
            sx = ex; sy = ey; sp = ep
        }
        add(points[(n - 1) * 3], points[(n - 1) * 3 + 1], p[n - 1])
        return out.toFloatArray()
    }

    /** Отрезки штриха ручки, сгруппированные по толщине (шаг — 4 % базовой). */
    fun lines(s: Stroke): StrokeLines {
        val n0 = s.size
        if (n0 == 0) return StrokeLines(FloatArray(0), emptyArray(), FloatArray(0))
        val pts = curve(s.points)
        val n = pts.size / 3
        val tiny = s.width * 0.02f
        // Одно касание или все точки в одном месте — точка.
        var moved = false
        for (i in 1 until n) if (hypot(pts[i * 3] - pts[0], pts[i * 3 + 1] - pts[1]) > tiny) { moved = true; break }
        if (!moved) {
            val r = StrokeGeometry.widthAt(s.width, pts[2], s.tool) / 2
            return StrokeLines(FloatArray(0), emptyArray(), floatArrayOf(pts[0], pts[1], r))
        }
        val quantum = maxOf(s.width * 0.04f, 0.04f)
        val groups = HashMap<Int, ArrayList<Float>>()
        for (i in 0 until n - 1) {
            val x0 = pts[i * 3]; val y0 = pts[i * 3 + 1]
            val x1 = pts[(i + 1) * 3]; val y1 = pts[(i + 1) * 3 + 1]
            if (x0 == x1 && y0 == y1) continue
            val w = StrokeGeometry.widthAt(s.width, (pts[i * 3 + 2] + pts[(i + 1) * 3 + 2]) / 2, s.tool)
            val key = Math.round(w / quantum)
            val g = groups.getOrPut(key) { ArrayList() }
            g += x0; g += y0; g += x1; g += y1
        }
        val keys = groups.keys.sorted()
        return StrokeLines(
            FloatArray(keys.size) { keys[it] * quantum },
            Array(keys.size) { groups.getValue(keys[it]).toFloatArray() },
            FloatArray(0),
        )
    }
}

/** Отмена и повтор: что сделали со штрихами страницы. */
sealed interface InkEdit {
    data class Added(val stroke: Stroke) : InkEdit
    data class Erased(val strokes: List<Pair<Int, Stroke>>) : InkEdit
    data class Cleared(val strokes: List<Stroke>) : InkEdit
}

class UndoStack {
    private val done = ArrayDeque<InkEdit>()
    private val undone = ArrayDeque<InkEdit>()

    val canUndo: Boolean get() = done.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    fun push(e: InkEdit) {
        done.addLast(e)
        if (done.size > LIMIT) done.removeFirst()
        undone.clear()
    }

    fun undo(page: InkPage): Boolean {
        val e = done.removeLastOrNull() ?: return false
        when (e) {
            is InkEdit.Added -> page.strokes.remove(e.stroke)
            is InkEdit.Erased -> e.strokes.sortedBy { it.first }.forEach { (i, s) -> page.strokes.add(i.coerceAtMost(page.strokes.size), s) }
            is InkEdit.Cleared -> page.strokes.addAll(e.strokes)
        }
        undone.addLast(e)
        page.touch()
        return true
    }

    fun redo(page: InkPage): Boolean {
        val e = undone.removeLastOrNull() ?: return false
        when (e) {
            is InkEdit.Added -> page.strokes.add(e.stroke)
            is InkEdit.Erased -> page.strokes.removeAll(e.strokes.map { it.second }.toSet())
            is InkEdit.Cleared -> page.strokes.clear()
        }
        done.addLast(e)
        page.touch()
        return true
    }

    fun clear() {
        done.clear()
        undone.clear()
    }

    companion object {
        private const val LIMIT = 200
    }
}

/** Двоичный формат страницы: компактно и быстро даже для тысяч штрихов. */
object InkFormat {
    private const val MAGIC = 0x4E4B4931 // «NKI1»

    fun write(page: InkPage, out: OutputStream) {
        val o = DataOutputStream(out.buffered())
        o.writeInt(MAGIC)
        o.writeLong(page.id)
        o.writeUTF(page.paper.name)
        o.writeUTF(page.background.orEmpty())
        o.writeFloat(page.height)
        o.writeInt(page.strokes.size)
        for (s in page.strokes) {
            o.writeByte(s.tool.ordinal)
            o.writeInt(s.color)
            o.writeFloat(s.width)
            o.writeInt(s.size)
            for (v in s.points) o.writeFloat(v)
        }
        o.flush()
    }

    fun read(input: InputStream): InkPage {
        val i = DataInputStream(input.buffered())
        require(i.readInt() == MAGIC) { "не страница тетради" }
        val id = i.readLong()
        val paper = runCatching { Paper.valueOf(i.readUTF()) }.getOrDefault(Paper.GRID)
        val bg = i.readUTF().ifEmpty { null }
        val height = i.readFloat()
        val count = i.readInt()
        val strokes = ArrayList<Stroke>(count)
        repeat(count) {
            val tool = Tool.entries.getOrElse(i.readUnsignedByte()) { Tool.PEN }
            val color = i.readInt()
            val width = i.readFloat()
            val n = i.readInt()
            val pts = FloatArray(n * 3) { i.readFloat() }
            strokes += Stroke(tool, color, width, pts)
        }
        return InkPage(id, paper, bg, height, strokes)
    }
}
