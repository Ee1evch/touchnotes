package app.nastavnik.campus

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Маршрут по шагам, как в обычных картах: «Прямо 30 м», «Поверни налево у „Альфа 5.3“»,
 * «Поднимись по лестнице на 3 этаж», «Ты на месте — справа».
 */
data class Maneuver(
    val kind: Kind,
    val floor: Int,
    /** Где манёвр (метры плана). */
    val x: Double,
    val y: Double,
    /** Сколько идти после манёвра до следующего, м. */
    val distance: Double,
    /** Короткая подсказка: «Поверни налево». */
    val title: String,
    /** Пояснение: «у „Альфа 5.3“ · 25 м». */
    val detail: String,
    /** Участок маршрута и расстояние от его начала до манёвра, м. */
    val leg: Int,
    val along: Double,
) {
    enum class Kind { START, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, UP, DOWN, LIFT, ARRIVE }
}

object Navigation {
    /** Повороты меньше этого угла — «прямо»: коридоры на плане не идеально прямые. */
    private const val STRAIGHT_DEG = 28.0
    private const val MIN_SEGMENT = 1.8
    /** С какого угла это поворот, о котором стоит сказать. */
    private const val TURN_DEG = 50.0

    fun maneuvers(site: Site, route: Route, target: Room?): List<Maneuver> {
        val out = ArrayList<Maneuver>()
        route.legs.forEachIndexed { li, leg ->
            val pts = cleanup(leg.points)
            if (pts.size < 2) return@forEachIndexed
            // Расстояние от начала участка до каждой точки.
            val along = DoubleArray(pts.size)
            for (i in 1 until pts.size) along[i] = along[i - 1] + dist(pts[i - 1], pts[i])
            val turns = ArrayList<Int>()
            // Пологие изгибы (дуга коридора) — не повороты: «держись правее» на каждом шаге только путает.
            for (i in 1 until pts.size - 1) if (abs(turnDeg(pts[i - 1], pts[i], pts[i + 1])) >= TURN_DEG) turns += i
            // Начало участка: старт или выход с лестницы.
            val firstLen = along[turns.firstOrNull() ?: pts.lastIndex]
            if (li == 0) {
                out += Maneuver(Maneuver.Kind.START, leg.floor, pts[0].first, pts[0].second, firstLen,
                    "Иди прямо ${meters(firstLen)}", landmark(site, leg.floor, pts[0], exclude = target)?.let { "от «$it»" } ?: "Начало маршрута", li, 0.0)
            } else {
                out += Maneuver(Maneuver.Kind.STRAIGHT, leg.floor, pts[0].first, pts[0].second, firstLen,
                    "Иди прямо ${meters(firstLen)}", "${site.floor(leg.floor)?.short.orEmpty()}, от выхода", li, 0.0)
            }
            turns.forEachIndexed { k, i ->
                val deg = turnDeg(pts[i - 1], pts[i], pts[i + 1])
                val kind = kindOf(deg)
                val next = turns.getOrNull(k + 1) ?: pts.lastIndex
                val len = along[next] - along[i]
                val near = landmark(site, leg.floor, pts[i], exclude = target)
                out += Maneuver(kind, leg.floor, pts[i].first, pts[i].second, len, titleOf(kind),
                    listOfNotNull(near?.let { "у «$it»" }, "дальше ${meters(len)}").joinToString(" · "), li, along[i])
            }
            // Переход на следующий этаж.
            route.transfers.getOrNull(li)?.let { c ->
                val to = route.legs[li + 1].floor
                val up = to > leg.floor
                val kind = if (c.kind == "lift") Maneuver.Kind.LIFT else if (up) Maneuver.Kind.UP else Maneuver.Kind.DOWN
                val floorName = site.floor(to)?.short?.lowercase() ?: "этаж $to"
                val title = when (c.kind) {
                    "lift" -> "Лифт на $floorName"
                    "escalator" -> (if (up) "Эскалатор вверх" else "Эскалатор вниз") + " на $floorName"
                    else -> (if (up) "Поднимись" else "Спустись") + " на $floorName"
                }
                out += Maneuver(kind, leg.floor, pts.last().first, pts.last().second, 0.0, title, c.name, li, along.last())
            }
        }
        val last = route.legs.lastOrNull() ?: return out
        val pts = cleanup(last.points)
        if (pts.isNotEmpty()) {
            val side = target?.let { sideOf(pts, it) }
            out += Maneuver(Maneuver.Kind.ARRIVE, last.floor, pts.last().first, pts.last().second, 0.0,
                "Ты на месте", listOfNotNull(target?.title?.let { "«$it»" }, side).joinToString(" · "), route.legs.lastIndex, last.meters)
        }
        return out
    }

    /** Где человек на маршруте: номер текущего манёвра, сколько до следующего и далеко ли от линии. */
    data class Progress(val step: Int, val toNext: Double, val offRoute: Double, val left: Double)

    fun progress(route: Route, steps: List<Maneuver>, floor: Int, x: Double, y: Double, from: Int = 0): Progress? {
        var best: Triple<Int, Double, Double>? = null // участок, расстояние вдоль, отклонение
        route.legs.forEachIndexed { li, leg ->
            if (leg.floor != floor) return@forEachIndexed
            val pts = cleanup(leg.points)
            var acc = 0.0
            for (i in 1 until pts.size) {
                val (ax, ay) = pts[i - 1]; val (bx, by) = pts[i]
                val dx = bx - ax; val dy = by - ay
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
                val d = hypot(ax + dx * t - x, ay + dy * t - y)
                val a = acc + kotlin.math.sqrt(l2) * t
                if (best == null || d < best!!.third - 0.5) best = Triple(li, a, d)
                acc += kotlin.math.sqrt(l2)
            }
        }
        val (leg, a, off) = best ?: return null
        // Текущий шаг — последний манёвр, до которого уже дошли (с запасом 3 м).
        var step = from.coerceIn(0, steps.lastIndex)
        for (i in steps.indices) {
            val m = steps[i]
            if (m.leg < leg || (m.leg == leg && m.along <= a + 3.0)) step = maxOf(step, i)
        }
        val next = steps.getOrNull(step + 1)
        val toNext = when {
            next == null -> 0.0
            next.leg == leg -> (next.along - a).coerceAtLeast(0.0)
            else -> (route.legs[leg].meters - a).coerceAtLeast(0.0)
        }
        val leftOnLeg = route.legs[leg].meters - a
        val left = leftOnLeg + route.legs.drop(leg + 1).sumOf { it.meters }
        return Progress(step, toNext, off, left)
    }

    fun meters(m: Double): String = when {
        m < 10 -> "${m.roundToInt().coerceAtLeast(1)} м"
        m < 100 -> "${(m / 5).roundToInt() * 5} м"
        else -> "${(m / 10).roundToInt() * 10} м"
    }

    private fun titleOf(k: Maneuver.Kind) = when (k) {
        Maneuver.Kind.SLIGHT_LEFT -> "Держись левее"
        Maneuver.Kind.LEFT -> "Поверни налево"
        Maneuver.Kind.SHARP_LEFT -> "Резко налево"
        Maneuver.Kind.SLIGHT_RIGHT -> "Держись правее"
        Maneuver.Kind.RIGHT -> "Поверни направо"
        Maneuver.Kind.SHARP_RIGHT -> "Резко направо"
        Maneuver.Kind.UTURN -> "Развернись"
        else -> "Прямо"
    }

    /** Знак угла: + налево (против часовой на плане, где y вверх). */
    fun kindOf(deg: Double): Maneuver.Kind {
        val a = abs(deg)
        val left = deg > 0
        return when {
            a < STRAIGHT_DEG -> Maneuver.Kind.STRAIGHT
            a < 60 -> if (left) Maneuver.Kind.SLIGHT_LEFT else Maneuver.Kind.SLIGHT_RIGHT
            a < 135 -> if (left) Maneuver.Kind.LEFT else Maneuver.Kind.RIGHT
            a < 165 -> if (left) Maneuver.Kind.SHARP_LEFT else Maneuver.Kind.SHARP_RIGHT
            else -> Maneuver.Kind.UTURN
        }
    }

    fun turnDeg(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>): Double {
        val h1 = atan2(b.second - a.second, b.first - a.first)
        val h2 = atan2(c.second - b.second, c.first - b.first)
        var d = h2 - h1
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        return Math.toDegrees(d)
    }

    /** Убираем крошечные отрезки и почти прямые изломы — иначе «поверни» на каждом шаге. */
    fun cleanup(pts: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (pts.size < 3) return pts
        val a = ArrayList<Pair<Double, Double>>()
        a += pts.first()
        for (i in 1 until pts.size - 1) if (dist(a.last(), pts[i]) >= MIN_SEGMENT) a += pts[i]
        if (dist(a.last(), pts.last()) < MIN_SEGMENT && a.size > 1) a.removeAt(a.lastIndex)
        a += pts.last()
        var changed = true
        while (changed && a.size > 2) {
            changed = false
            for (i in 1 until a.size - 1) {
                if (abs(turnDeg(a[i - 1], a[i], a[i + 1])) < 12) { a.removeAt(i); changed = true; break }
            }
        }
        return a
    }

    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>) = hypot(a.first - b.first, a.second - b.second)

    /** Ориентир у точки: ближайшее помещение с понятным названием в пределах 7 м. */
    private fun landmark(site: Site, floor: Int, p: Pair<Double, Double>, exclude: Room?): String? =
        site.rooms.asSequence()
            .filter { it.floor == floor && !it.isGeneric && it != exclude && it.area < 2500 }
            .map { r ->
                val (lx, ly) = r.labels.firstOrNull() ?: (r.x to r.y)
                r to hypot(lx - p.first, ly - p.second)
            }
            .filter { it.second < 7 }
            .minByOrNull { it.second }
            ?.first?.title
            // «1449 · Кабинет · Иванов…» → «1449»; «52 · Помещение» → «52».
            ?.let { t -> if (t.length > 22 || t.endsWith("· Помещение")) t.substringBefore(" · ") else t }

    /** С какой стороны цель в конце пути. */
    private fun sideOf(pts: List<Pair<Double, Double>>, target: Room): String? {
        if (pts.size < 2) return null
        val a = pts[pts.size - 2]; val b = pts.last()
        val dx = b.first - a.first; val dy = b.second - a.second
        val tx = target.x - b.first; val ty = target.y - b.second
        if (hypot(tx, ty) < 1.5) return null
        val cross = dx * ty - dy * tx
        val dot = dx * tx + dy * ty
        val ang = Math.toDegrees(atan2(cross, dot))
        return when {
            abs(ang) < 35 -> "впереди"
            ang > 0 -> "слева"
            else -> "справа"
        }
    }
}
