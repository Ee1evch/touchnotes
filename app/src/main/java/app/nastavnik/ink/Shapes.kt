package app.nastavnik.ink

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Фигуры, которые можно нарисовать инструментом «Фигуры». */
enum class ShapeKind(val label: String) {
    LINE("Линия"),
    ARROW("Стрелка"),
    RECT("Прямоугольник"),
    ELLIPSE("Овал"),
    TRIANGLE("Треугольник"),
    AXES("Оси координат"),
}

/**
 * Ровные фигуры: построение по двум точкам (инструмент «Фигуры») и распознавание нарисованного от руки
 * («нарисуй и задержи перо» — линия, треугольник, прямоугольник или овал становятся ровными).
 * Всё в единицах страницы; фигура — один или несколько штрихов с [Tool.SHAPE].
 */
object Shapes {
    private const val P = 0.5f

    /** Фигура по углу, где начали тянуть, и углу, где сейчас перо. */
    fun build(kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int): List<Stroke> {
        fun s(vararg xy: Float) = Stroke(Tool.SHAPE, color, width, densify(xy))
        val l = min(x0, x1); val r = max(x0, x1); val t = min(y0, y1); val b = max(y0, y1)
        return when (kind) {
            ShapeKind.LINE -> listOf(s(x0, y0, x1, y1))
            ShapeKind.ARROW -> listOf(s(x0, y0, x1, y1), arrowHead(x0, y0, x1, y1, width, color))
            ShapeKind.RECT -> listOf(s(l, t, r, t, r, b, l, b, l, t))
            ShapeKind.TRIANGLE -> listOf(s((l + r) / 2, t, r, b, l, b, (l + r) / 2, t))
            ShapeKind.ELLIPSE -> listOf(ellipse((l + r) / 2, (t + b) / 2, (r - l) / 2, (b - t) / 2, width, color))
            ShapeKind.AXES -> {
                // Оси через начало перетаскивания: x — вправо, y — вверх, со стрелками.
                val len = max(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(20f)
                val ox = x0; val oy = y0
                listOf(
                    s(ox - len * 0.25f, oy, ox + len, oy), arrowHead(ox - len * 0.25f, oy, ox + len, oy, width, color),
                    s(ox, oy + len * 0.25f, ox, oy - len), arrowHead(ox, oy + len * 0.25f, ox, oy - len, width, color),
                )
            }
        }
    }

    private fun arrowHead(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int): Stroke {
        val a = atan2(y1 - y0, x1 - x0)
        val len = (10f + width * 2.5f).coerceAtMost(hypot(x1 - x0, y1 - y0) * 0.4f)
        val spread = (PI / 7).toFloat()
        val ax = x1 - cos(a - spread) * len; val ay = y1 - sin(a - spread) * len
        val bx = x1 - cos(a + spread) * len; val by = y1 - sin(a + spread) * len
        return Stroke(Tool.SHAPE, color, width, densify(floatArrayOf(ax, ay, x1, y1, bx, by)))
    }

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, width: Float, color: Int, segments: Int = 72): Stroke {
        val pts = FloatArray((segments + 1) * 3)
        for (i in 0..segments) {
            val a = 2 * PI * i / segments
            pts[i * 3] = cx + (cos(a) * rx).toFloat()
            pts[i * 3 + 1] = cy + (sin(a) * ry).toFloat()
            pts[i * 3 + 2] = P
        }
        return Stroke(Tool.SHAPE, color, width, pts)
    }

    /** Ломаная x,y → точки штриха x,y,нажим (углы остаются точными). */
    private fun densify(xy: FloatArray): FloatArray {
        val n = xy.size / 2
        return FloatArray(n * 3) { k -> if (k % 3 == 2) P else xy[(k / 3) * 2 + k % 3] }
    }

    // ---------------- распознавание ----------------

    /** Что получилось из нарисованного от руки: вершины ломаной (x, y) и вид. */
    data class Recognized(val kind: ShapeKind, val xy: FloatArray)

    /**
     * Узнать фигуру в штрихе (точки x,y,нажим). null — каракули, оставляем как есть.
     * Прямая — если путь почти равен расстоянию между концами; замкнутые — по числу «углов» после упрощения.
     */
    fun recognize(points: FloatArray): Recognized? {
        val n = points.size / 3
        if (n < 4) return null
        var length = 0f
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = points[i * 3]; val y = points[i * 3 + 1]
            l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            if (i > 0) length += hypot(x - points[(i - 1) * 3], y - points[(i - 1) * 3 + 1])
        }
        val diag = hypot(r - l, b - t)
        if (diag < 12f || length < 15f) return null
        val sx = points[0]; val sy = points[1]
        val ex = points[(n - 1) * 3]; val ey = points[(n - 1) * 3 + 1]
        val chord = hypot(ex - sx, ey - sy)

        // Прямая: все точки близко к отрезку между концами (дрожание руки не мешает).
        // Почти горизонтальная/вертикальная/под 45° — выравниваем.
        var maxDev = 0f
        if (chord > 20f) for (i in 0 until n) {
            maxDev = max(maxDev, StrokeGeometry.segmentDistance(points[i * 3], points[i * 3 + 1], sx, sy, ex, ey))
        }
        if (chord > 20f && maxDev < chord * 0.06f) {
            var x1 = ex; var y1 = ey
            val angle = Math.toDegrees(atan2((ey - sy).toDouble(), (ex - sx).toDouble()))
            val snapped = (angle / 45).roundToInt() * 45.0
            if (abs(angle - snapped) < 6) {
                val a = Math.toRadians(snapped)
                x1 = sx + (cos(a) * chord).toFloat()
                y1 = sy + (sin(a) * chord).toFloat()
            }
            return Recognized(ShapeKind.LINE, floatArrayOf(sx, sy, x1, y1))
        }

        // Замкнутая: конец вернулся к началу.
        if (chord > max(diag * 0.25f, 14f)) return null
        val xy = FloatArray(n * 2) { k -> points[(k / 2) * 3 + k % 2] }
        val simple = simplify(xy, diag * 0.085f)
        var corners = simple.size / 2
        // Последняя вершина рядом с первой — это та же вершина.
        if (corners >= 2 && hypot(simple[0] - simple[simple.size - 2], simple[1] - simple[simple.size - 1]) < diag * 0.2f) corners--
        val vx = FloatArray(corners) { simple[it * 2] }
        val vy = FloatArray(corners) { simple[it * 2 + 1] }

        if (corners == 3) {
            return Recognized(ShapeKind.TRIANGLE, floatArrayOf(vx[0], vy[0], vx[1], vy[1], vx[2], vy[2], vx[0], vy[0]))
        }
        if (corners == 4 && rightAngles(vx, vy)) {
            // Почти ровные стороны — прямоугольник по рамке; повёрнутый — по вершинам.
            val axis = (0 until 4).all { i ->
                val j = (i + 1) % 4
                val a = Math.toDegrees(atan2((vy[j] - vy[i]).toDouble(), (vx[j] - vx[i]).toDouble()))
                val d = ((a % 90) + 90) % 90
                d < 12 || d > 78
            }
            val out = if (axis) floatArrayOf(l, t, r, t, r, b, l, b, l, t)
            else floatArrayOf(vx[0], vy[0], vx[1], vy[1], vx[2], vy[2], vx[3], vy[3], vx[0], vy[0])
            return Recognized(ShapeKind.RECT, out)
        }
        // Овал: точки лежат около эллипса, вписанного в рамку.
        val cx = (l + r) / 2; val cy = (t + b) / 2
        val rx = (r - l) / 2; val ry = (b - t) / 2
        if (rx < 4f || ry < 4f) return null
        var err = 0.0
        for (i in 0 until n) {
            val dx = (points[i * 3] - cx) / rx
            val dy = (points[i * 3 + 1] - cy) / ry
            err += abs(sqrt((dx * dx + dy * dy).toDouble()) - 1)
        }
        if (err / n > 0.22) return null
        val e = ellipse(cx, cy, rx, ry, 1f, 0)
        return Recognized(ShapeKind.ELLIPSE, FloatArray(e.size * 2) { k -> e.points[(k / 2) * 3 + k % 2] })
    }

    private fun rightAngles(vx: FloatArray, vy: FloatArray): Boolean = (0 until 4).all { i ->
        val p = (i + 3) % 4; val q = (i + 1) % 4
        val ax = vx[p] - vx[i]; val ay = vy[p] - vy[i]
        val bx = vx[q] - vx[i]; val by = vy[q] - vy[i]
        val cos = (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by)).coerceAtLeast(1e-3f)
        abs(cos) < 0.36f // угол 69…111°
    }

    /** Упрощение ломаной (Рамер — Дуглас — Пекер): остаются только «углы». */
    fun simplify(xy: FloatArray, eps: Float): FloatArray {
        val n = xy.size / 2
        if (n < 3) return xy
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to n - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var far = -1
            var best = eps
            for (i in a + 1 until b) {
                val d = StrokeGeometry.segmentDistance(xy[i * 2], xy[i * 2 + 1], xy[a * 2], xy[a * 2 + 1], xy[b * 2], xy[b * 2 + 1])
                if (d > best) { best = d; far = i }
            }
            if (far >= 0) {
                keep[far] = true
                stack.addLast(a to far)
                stack.addLast(far to b)
            }
        }
        val out = ArrayList<Float>()
        for (i in 0 until n) if (keep[i]) { out += xy[i * 2]; out += xy[i * 2 + 1] }
        return out.toFloatArray()
    }

    /** Ломаная, равномерно разложенная на [count] точек — для плавного «перетекания» каракули в фигуру. */
    fun resample(xy: FloatArray, count: Int): FloatArray {
        val n = xy.size / 2
        if (n == 0) return FloatArray(count * 2)
        if (n == 1) return FloatArray(count * 2) { xy[it % 2] }
        val acc = FloatArray(n)
        for (i in 1 until n) acc[i] = acc[i - 1] + hypot(xy[i * 2] - xy[(i - 1) * 2], xy[i * 2 + 1] - xy[(i - 1) * 2 + 1])
        val total = acc[n - 1]
        val out = FloatArray(count * 2)
        var j = 1
        for (k in 0 until count) {
            val d = if (count == 1) 0f else total * k / (count - 1)
            while (j < n - 1 && acc[j] < d) j++
            val seg = (acc[j] - acc[j - 1]).coerceAtLeast(1e-6f)
            val f = ((d - acc[j - 1]) / seg).coerceIn(0f, 1f)
            out[k * 2] = xy[(j - 1) * 2] + (xy[j * 2] - xy[(j - 1) * 2]) * f
            out[k * 2 + 1] = xy[(j - 1) * 2 + 1] + (xy[j * 2 + 1] - xy[(j - 1) * 2 + 1]) * f
        }
        return out
    }

    /** Штрих-фигура из вершин распознавания. */
    fun strokeOf(r: Recognized, width: Float, color: Int): Stroke =
        Stroke(Tool.SHAPE, color, width, densify(r.xy))
}

/** Лассо и частичный ластик. */
object Selection {
    /** Точка внутри многоугольника (x,y подряд) — правило чётности. */
    fun inside(poly: FloatArray, x: Float, y: Float): Boolean {
        val n = poly.size / 2
        var c = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]; val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    /** Штрихи, у которых внутри лассо больше половины точек. */
    fun strokesIn(poly: FloatArray, strokes: List<Stroke>): List<Stroke> {
        if (poly.size < 6) return emptyList()
        return strokes.filter { s ->
            var inside = 0
            for (i in 0 until s.size) if (inside(poly, s.x(i), s.y(i))) inside++
            inside * 2 > s.size
        }
    }

    /** Тексты, у которых внутри лассо середина. */
    fun textsIn(poly: FloatArray, texts: List<TextBox>): List<TextBox> {
        if (poly.size < 6) return emptyList()
        return texts.filter { t -> val b = t.bounds(); inside(poly, (b[0] + b[2]) / 2, (b[1] + b[3]) / 2) }
    }

    /** Общая рамка выделения. */
    fun bounds(strokes: List<Stroke>, texts: List<TextBox>): FloatArray? {
        if (strokes.isEmpty() && texts.isEmpty()) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        (strokes.map { it.bounds() } + texts.map { it.bounds() }).forEach { q ->
            l = min(l, q[0]); t = min(t, q[1]); r = max(r, q[2]); b = max(b, q[3])
        }
        return floatArrayOf(l, t, r, b)
    }

    /**
     * Частичный ластик: стереть у штриха точки в круге (x, y, r) и разрезать его на куски.
     * null — ластик штрих не задел; пустой список — штрих стёрт целиком.
     */
    fun cut(s: Stroke, x: Float, y: Float, r: Float): List<Stroke>? {
        val b = s.bounds()
        if (x < b[0] - r || x > b[2] + r || y < b[1] - r || y > b[3] + r) return null
        val reach = r + s.width / 2
        val hit = BooleanArray(s.size) { i -> hypot(s.x(i) - x, s.y(i) - y) <= reach }
        if (hit.none { it }) {
            // Ластик мог пройти между редкими точками (у фигур) — тогда штрих целиком.
            val crosses = (0 until s.size - 1).any { i -> StrokeGeometry.segmentDistance(x, y, s.x(i), s.y(i), s.x(i + 1), s.y(i + 1)) <= reach }
            if (!crosses) return null
            return if (s.tool == Tool.SHAPE) splitShape(s, x, y, reach) else emptyList()
        }
        val out = ArrayList<Stroke>()
        var start = -1
        for (i in 0..s.size) {
            val keep = i < s.size && !hit[i]
            if (keep && start < 0) start = i
            if (!keep && start >= 0) {
                if (i - start >= 2) out += Stroke(s.tool, s.color, s.width, s.points.copyOfRange(start * 3, i * 3))
                start = -1
            }
        }
        return out
    }

    /** У фигуры точки редкие: разрезаем отрезок, который задел ластик, сгущая его. */
    private fun splitShape(s: Stroke, x: Float, y: Float, reach: Float): List<Stroke> {
        val dense = ArrayList<Float>()
        for (i in 0 until s.size - 1) {
            val x0 = s.x(i); val y0 = s.y(i); val x1 = s.x(i + 1); val y1 = s.y(i + 1)
            val k = (hypot(x1 - x0, y1 - y0) / 2f).toInt().coerceIn(1, 400)
            for (j in 0 until k) {
                val f = j / k.toFloat()
                dense += x0 + (x1 - x0) * f; dense += y0 + (y1 - y0) * f; dense += s.p(i)
            }
        }
        dense += s.x(s.size - 1); dense += s.y(s.size - 1); dense += s.p(s.size - 1)
        return cut(Stroke(s.tool, s.color, s.width, dense.toFloatArray()), x, y, reach - s.width / 2) ?: listOf(s)
    }
}
