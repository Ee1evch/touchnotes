package app.nastavnik.campus

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Точка маршрута: этаж и клетка сетки. */
data class Place(val floor: Int, val row: Int, val col: Int)

/** Участок маршрута на одном этаже: ломаная в метрах плана. */
data class Leg(val floor: Int, val points: List<Pair<Double, Double>>, val meters: Double)

data class Route(
    val legs: List<Leg>,
    /** Через что переходить между этажами (по участку на каждый переход). */
    val transfers: List<Connector>,
) {
    val meters: Double get() = legs.sumOf { it.meters } + transfers.size * FLOOR_METERS
    /** Минуты шагом ~1,2 м/с, на переход между этажами — полминуты. */
    val minutes: Int get() = max(1, ((legs.sumOf { it.meters } / 1.2 + transfers.size * 30) / 60.0).let { kotlin.math.ceil(it).toInt() })

    companion object {
        const val FLOOR_METERS = 12.0
    }
}

/**
 * Маршрут по сетке 0,3 м: A* по восьми соседям, клетки «по ключу» дороже, чтобы путь шёл через них,
 * только если иначе никак. Между этажами — через лестницу, лифт или эскалатор, общий для обоих этажей;
 * выбирается тот, что даёт самый короткий путь. Ломаная потом спрямляется по прямой видимости.
 */
class Router(private val site: Site, private val grids: (Int) -> NavGrid) {
    /** Помещения «по ключу» по этажам: флаг по номеру помещения — быстрее множества в горячем цикле. */
    private val restricted: Map<Int, BooleanArray> = site.rooms.filter { it.restricted }.groupBy { it.floor }
        .mapValues { (_, rooms) -> BooleanArray(65536).also { a -> rooms.forEach { a[it.id] = true } } }

    /** Где начинать или заканчивать путь в помещении. */
    fun placeOf(room: Room): Place? {
        val g = grids(room.floor)
        val (r, c) = room.anchor?.takeIf { g.walkable(it.first, it.second) }
            ?: g.nearestWalkable(site.grid.row(room.y), site.grid.col(room.x))
            ?: return null
        return Place(room.floor, r, c)
    }

    fun placeAt(floor: Int, x: Double, y: Double): Place? {
        val g = grids(floor)
        val (r, c) = g.nearestWalkable(site.grid.row(y), site.grid.col(x)) ?: return null
        return Place(floor, r, c)
    }

    fun route(from: Place, to: Place): Route? {
        if (from.floor == to.floor) {
            val leg = leg(from.floor, from.row, from.col, to.row, to.col) ?: return null
            return Route(listOf(leg), emptyList())
        }
        // Кандидаты: соединяют оба этажа.
        val candidates = site.conns.filter { from.floor in it.cells && to.floor in it.cells }
        if (candidates.isEmpty()) return null
        val gFrom = grids(from.floor)
        val gTo = grids(to.floor)
        val startCosts = distances(gFrom, from.floor, from.row, from.col, candidates.map { it.cells.getValue(from.floor) })
        val endCosts = distances(gTo, to.floor, to.row, to.col, candidates.map { it.cells.getValue(to.floor) })
        var best = -1
        var bestCost = Long.MAX_VALUE
        candidates.forEachIndexed { i, c ->
            val a = startCosts[i]; val b = endCosts[i]
            if (a < 0 || b < 0) return@forEachIndexed
            val floors = abs(from.floor - to.floor).coerceAtLeast(1)
            // Лифт — подождать; лестница — подниматься, но на один-два этажа это быстрее.
            val transfer = when (c.kind) { "lift" -> 60L + floors * 8; "escalator" -> 20L + floors * 10; else -> floors * 40L }
            val cost = a + b + transfer
            if (cost < bestCost) { bestCost = cost; best = i }
        }
        if (best < 0) return null
        val via = candidates[best]
        val (r1, c1) = gFrom.nearestWalkable(via.cells.getValue(from.floor).first, via.cells.getValue(from.floor).second) ?: return null
        val (r2, c2) = gTo.nearestWalkable(via.cells.getValue(to.floor).first, via.cells.getValue(to.floor).second) ?: return null
        val a = leg(from.floor, from.row, from.col, r1, c1) ?: return null
        val b = leg(to.floor, r2, c2, to.row, to.col) ?: return null
        return Route(listOf(a, b), listOf(via))
    }

    private fun cost(g: NavGrid, floor: Int, idx: Int): Int {
        val room = g.cells[idx].toInt() and 0xFFFF
        if (room == 0) return -1
        return if (restricted[floor]?.get(room) == true) 6 else 1
    }

    /** Участок по этажу или null, если не дойти. */
    fun leg(floor: Int, r0: Int, c0: Int, r1: Int, c1: Int): Leg? {
        val g = grids(floor)
        val cells = astar(g, floor, r0, c0, r1, c1) ?: return null
        val smooth = simplify(g, cells)
        val pts = smooth.map { site.grid.x(it % g.cols) to site.grid.y(it / g.cols) }
        var m = 0.0
        for (i in 1 until pts.size) m += hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second)
        return Leg(floor, pts, m)
    }

    private val dr = intArrayOf(-1, 1, 0, 0, -1, -1, 1, 1)
    private val dc = intArrayOf(0, 0, -1, 1, -1, 1, -1, 1)

    /**
     * Окно поиска: прямоугольник вокруг точек с запасом. Вся сетка ГМЦ — почти 6 млн клеток,
     * а путь по зданию почти всегда укладывается в окно вокруг начала и конца — памяти нужно в разы меньше.
     */
    private class Win(val r0: Int, val c0: Int, val h: Int, val w: Int) {
        val size get() = h * w
    }

    private fun window(g: NavGrid, pts: List<Pair<Int, Int>>, margin: Int): Win {
        val rMin = max(0, pts.minOf { it.first } - margin)
        val rMax = min(g.rows - 1, pts.maxOf { it.first } + margin)
        val cMin = max(0, pts.minOf { it.second } - margin)
        val cMax = min(g.cols - 1, pts.maxOf { it.second } + margin)
        return Win(rMin, cMin, rMax - rMin + 1, cMax - cMin + 1)
    }

    private fun full(g: NavGrid) = Win(0, 0, g.rows, g.cols)

    private fun astar(g: NavGrid, floor: Int, r0: Int, c0: Int, r1: Int, c1: Int): IntArray? {
        val pts = listOf(r0 to c0, r1 to c1)
        val span = max(abs(r0 - r1), abs(c0 - c1))
        return astarIn(g, floor, window(g, pts, 120 + span / 2), r0, c0, r1, c1)
            ?: astarIn(g, floor, full(g), r0, c0, r1, c1)
    }

    private fun astarIn(g: NavGrid, floor: Int, win: Win, r0: Int, c0: Int, r1: Int, c1: Int): IntArray? {
        val start = (r0 - win.r0) * win.w + (c0 - win.c0)
        val goal = (r1 - win.r0) * win.w + (c1 - win.c0)
        if (cost(g, floor, r0 * g.cols + c0) < 0 || cost(g, floor, r1 * g.cols + c1) < 0) return null
        val dist = IntArray(win.size) { Int.MAX_VALUE }
        // Откуда пришли: номер направления (0..7), −1 — не были.
        val from = ByteArray(win.size) { -1 }
        val heap = Heap()
        dist[start] = 0
        heap.push(start, heuristic(r0, c0, r1, c1))
        while (heap.size > 0) {
            val cur = heap.popIndex()
            if (cur == goal) break
            val lr = cur / win.w; val lc = cur % win.w
            val r = lr + win.r0; val c = lc + win.c0
            val d0 = dist[cur]
            if (heap.lastKey - heuristic(r, c, r1, c1) > d0) continue
            for (k in 0 until 8) {
                val nlr = lr + dr[k]; val nlc = lc + dc[k]
                if (nlr < 0 || nlc < 0 || nlr >= win.h || nlc >= win.w) continue
                val nr = nlr + win.r0; val nc = nlc + win.c0
                val w = cost(g, floor, nr * g.cols + nc)
                if (w < 0) continue
                // По диагонали — только если не срезаем угол стены.
                if (k >= 4 && (cost(g, floor, r * g.cols + nc) < 0 || cost(g, floor, nr * g.cols + c) < 0)) continue
                val ni = nlr * win.w + nlc
                val nd = d0 + (if (k < 4) 10 else 14) * w
                if (nd < dist[ni]) {
                    dist[ni] = nd
                    from[ni] = k.toByte()
                    heap.push(ni, nd + heuristic(nr, nc, r1, c1))
                }
            }
        }
        if (dist[goal] == Int.MAX_VALUE) return null
        val path = ArrayList<Int>()
        var cur = goal
        while (true) {
            val lr = cur / win.w; val lc = cur % win.w
            path.add((lr + win.r0) * g.cols + (lc + win.c0))
            if (cur == start) break
            val k = from[cur].toInt()
            cur = (lr - dr[k]) * win.w + (lc - dc[k])
        }
        path.reverse()
        return path.toIntArray()
    }

    /** Цена пути (в метрах) от точки до каждой из целей; −1 — не дойти. */
    private fun distances(g: NavGrid, floor: Int, r0: Int, c0: Int, targets: List<Pair<Int, Int>>): LongArray {
        val snapped = targets.map { (r, c) -> g.nearestWalkable(r, c) }
        val pts = snapped.filterNotNull() + (r0 to c0)
        val first = distancesIn(g, floor, window(g, pts, 120), r0, c0, snapped)
        if (first.any { it >= 0 } || snapped.all { it == null }) return first
        val second = distancesIn(g, floor, full(g), r0, c0, snapped)
        return LongArray(targets.size) { if (first[it] >= 0) first[it] else second[it] }
    }

    private fun distancesIn(g: NavGrid, floor: Int, win: Win, r0: Int, c0: Int, targets: List<Pair<Int, Int>?>): LongArray {
        val result = LongArray(targets.size) { -1 }
        val goals = HashMap<Int, MutableList<Int>>()
        targets.forEachIndexed { i, t ->
            val (r, c) = t ?: return@forEachIndexed
            goals.getOrPut((r - win.r0) * win.w + (c - win.c0)) { mutableListOf() }.add(i)
        }
        var left = goals.size
        if (left == 0) return result
        val dist = IntArray(win.size) { Int.MAX_VALUE }
        val start = (r0 - win.r0) * win.w + (c0 - win.c0)
        val heap = Heap()
        dist[start] = 0
        heap.push(start, 0)
        var nearest = -1
        while (heap.size > 0 && left > 0) {
            val cur = heap.popIndex()
            if (heap.lastKey > dist[cur]) continue
            // Дальние лестницы не нужны: дальше двойного пути до ближайшей (+60 м) не ищем.
            if (nearest >= 0 && dist[cur] > nearest * 2 + 2000) break
            goals.remove(cur)?.let { ids ->
                ids.forEach { result[it] = (dist[cur] / 10.0 * site.grid.res).toLong() }
                left--
                if (nearest < 0) nearest = dist[cur]
            }
            val lr = cur / win.w; val lc = cur % win.w
            val r = lr + win.r0; val c = lc + win.c0
            for (k in 0 until 8) {
                val nlr = lr + dr[k]; val nlc = lc + dc[k]
                if (nlr < 0 || nlc < 0 || nlr >= win.h || nlc >= win.w) continue
                val nr = nlr + win.r0; val nc = nlc + win.c0
                val w = cost(g, floor, nr * g.cols + nc)
                if (w < 0) continue
                if (k >= 4 && (cost(g, floor, r * g.cols + nc) < 0 || cost(g, floor, nr * g.cols + c) < 0)) continue
                val ni = nlr * win.w + nlc
                val nd = dist[cur] + (if (k < 4) 10 else 14) * w
                if (nd < dist[ni]) { dist[ni] = nd; heap.push(ni, nd) }
            }
        }
        return result
    }

    private fun heuristic(r: Int, c: Int, r1: Int, c1: Int): Int {
        val a = abs(r - r1); val b = abs(c - c1)
        return 10 * max(a, b) + 4 * min(a, b)
    }

    /** Спрямление: от точки идём к самой дальней, видимой напрямую по проходимым клеткам. */
    private fun simplify(g: NavGrid, cells: IntArray): List<Int> {
        if (cells.size <= 2) return cells.toList()
        val out = ArrayList<Int>()
        var i = 0
        out.add(cells[0])
        while (i < cells.size - 1) {
            var j = cells.size - 1
            // Видимость проверяем с шагом, иначе на длинном пути квадратично.
            while (j > i + 1 && !visible(g, cells[i], cells[j])) j = i + 1 + (j - i - 1) * 3 / 4
            out.add(cells[j])
            i = j
        }
        return out
    }

    private fun visible(g: NavGrid, a: Int, b: Int): Boolean {
        var r0 = a / g.cols; var c0 = a % g.cols
        val r1 = b / g.cols; val c1 = b % g.cols
        val dR = abs(r1 - r0); val dC = abs(c1 - c0)
        val sR = if (r0 < r1) 1 else -1; val sC = if (c0 < c1) 1 else -1
        var err = dC - dR
        while (true) {
            if (!g.walkable(r0, c0)) return false
            if (r0 == r1 && c0 == c1) return true
            val e2 = 2 * err
            if (e2 > -dR) { err -= dR; c0 += sC }
            if (e2 < dC) { err += dC; r0 += sR }
            // Не проскакиваем через угол.
            if (!g.walkable(r0, c0)) return false
        }
    }

    /** Двоичная куча (индекс клетки, ключ) с «ленивым» удалением устаревших записей. */
    private class Heap {
        private var idx = IntArray(1024)
        private var key = IntArray(1024)
        var size = 0
        var lastKey = 0

        fun push(i: Int, k: Int) {
            if (size == idx.size) { idx = idx.copyOf(size * 2); key = key.copyOf(size * 2) }
            var p = size++
            while (p > 0) {
                val parent = (p - 1) / 2
                if (key[parent] <= k) break
                idx[p] = idx[parent]; key[p] = key[parent]; p = parent
            }
            idx[p] = i; key[p] = k
        }

        fun popIndex(): Int {
            val top = idx[0]
            lastKey = key[0]
            size--
            if (size > 0) {
                val i = idx[size]; val k = key[size]
                var p = 0
                while (true) {
                    var ch = p * 2 + 1
                    if (ch >= size) break
                    if (ch + 1 < size && key[ch + 1] < key[ch]) ch++
                    if (key[ch] >= k) break
                    idx[p] = idx[ch]; key[p] = key[ch]; p = ch
                }
                idx[p] = i; key[p] = k
            }
            return top
        }
    }
}
