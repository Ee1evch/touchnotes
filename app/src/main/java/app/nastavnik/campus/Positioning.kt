package app.nastavnik.campus

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Где я: фильтр частиц, как в навигаторе ГМЦ, но настроенный на записях реальных прогулок по зданию.
 *
 * - **Wi-Fi**: вероятностная модель уровня сигнала (σ ≈ 8 дБ, до 40 самых сильных точек) против отпечатков,
 *   поправка — по абсолютному качеству совпадения: если похожих отпечатков рядом нет, Wi-Fi почти не двигает оценку;
 * - **шаги**: датчик шагов; у каждой частицы своя длина шага (0,6–1 м) и своя поправка компаса (в здании
 *   магнитный курс врёт по-разному) — фильтр сам находит, какие верны;
 * - **стены**: частица не проходит сквозь стену (сетка проходимости 0,3 м);
 * - **барометр**: быстрое изменение давления (подъём/спуск ≈ 0,1 гПа на метр) переводит частицы у лестниц
 *   и лифтов на соседний этаж;
 * - **GPS** — на территории, где он точен; в здании не используется.
 *
 * Итог: этаж — большинством, место — центр самого плотного облака, помещение — голосованием частиц.
 * На записях обхода ГМЦ (место, где обходчик проходил в другой момент): этаж — 99–100 %, помещение — ~80 %.
 */
class Positioning(
    private val site: Site,
    var wifi: WifiLocator,
    private val grid: (Int) -> NavGrid?,
    private val random: Random = Random(7),
    private val n: Int = 800,
) {
    data class Fix(
        val floor: Int,
        val x: Double,
        val y: Double,
        /** Помещение, где больше всего частиц (номер на сетке этажа). */
        val roomId: Int,
        /** Радиус неопределённости, м. */
        val radius: Double,
        /** Доля частиц на выбранном этаже. */
        val floorShare: Double,
        /** Курс движения в плане (радианы, 0 — ось x) или NaN. */
        val heading: Double,
    )

    private val px = DoubleArray(n)
    private val py = DoubleArray(n)
    private val pf = IntArray(n)
    private val ph = DoubleArray(n) // поправка к магнитному курсу
    private val pl = DoubleArray(n) // длина шага
    private val w = DoubleArray(n)
    var initialized = false
        private set

    private val pressure = ArrayDeque<Pair<Long, Double>>()
    private var floorCooldownUntil = 0L
    private var lastYaw = Double.NaN

    private val conns: Map<Int, List<Pair<Double, Double>>> =
        site.conns.flatMap { c -> c.points.map { (f, p) -> f to p } }.groupBy({ it.first }, { it.second })

    private fun gauss() = random.nextDouble().let { u -> sqrt(-2 * ln(max(u, 1e-12))) * cos(2 * PI * random.nextDouble()) }

    private fun walkable(f: Int, x: Double, y: Double): Boolean {
        val g = grid(f) ?: return true
        return g.walkable(site.grid.row(y), site.grid.col(x))
    }

    /** Скан Wi-Fi: MAC → дБм. */
    fun onWifi(scan: Map<String, Int>, now: Long) {
        val top = wifi.score(scan) ?: return
        if (!initialized) {
            seed(top, n, 0)
            initialized = true
            return
        }
        val k = top.size
        var sum = 0.0
        for (i in 0 until n) {
            var lik = EPS
            for (j in 0 until k) {
                val fp = top[j].first
                if (fp.floor != pf[i]) continue
                val dx = px[i] - fp.x; val dy = py[i] - fp.y
                lik += top[j].second * exp(-(dx * dx + dy * dy) / (2 * SIG_W * SIG_W))
            }
            w[i] *= lik
            sum += w[i]
        }
        normalize(sum)
        resampleIfNeeded()
        // Небольшая доля частиц — заново из Wi-Fi: если фильтр «потерялся», он найдётся.
        val m = (n * REINIT).toInt()
        val idx = IntArray(m) { random.nextInt(n) }
        seed(top, m, 0, idx)
    }

    /** Отпечатки с весами → новые частицы вокруг них. */
    private fun seed(top: List<Pair<WifiLocator.Fingerprint, Double>>, count: Int, from: Int, slots: IntArray? = null) {
        val total = top.sumOf { it.second }.takeIf { it > 0 } ?: return
        for (c in 0 until count) {
            val i = slots?.get(c) ?: (from + c)
            var r = random.nextDouble() * total
            var fp = top.last().first
            for ((f, wt) in top) { r -= wt; if (r <= 0) { fp = f; break } }
            px[i] = fp.x + gauss() * 3.0
            py[i] = fp.y + gauss() * 3.0
            pf[i] = fp.floor
            ph[i] = HEADING_BIAS + gauss() * HEADING_SPREAD
            pl[i] = 0.6 + random.nextDouble() * 0.38
            w[i] = 1.0 / n
        }
        if (slots != null) normalize(w.sum())
    }

    /**
     * Шаг. [yaw] — азимут оси Y телефона от севера к востоку (радианы) по вектору поворота с магнитометром.
     */
    fun onStep(yaw: Double, now: Long) {
        lastYaw = yaw
        if (!initialized) return
        val m = site.enToPlan
        var sum = 0.0
        for (i in 0 until n) {
            val h = yaw + ph[i] + gauss() * 0.12
            val len = pl[i] * (1 + gauss() * 0.08)
            val de = sin(h) * len; val dn = cos(h) * len
            val nx = px[i] + m[0] * de + m[1] * dn
            val ny = py[i] + m[2] * de + m[3] * dn
            if (walkable(pf[i], nx, ny) && walkable(pf[i], (px[i] + nx) / 2, (py[i] + ny) / 2)) {
                px[i] = nx; py[i] = ny
            } else {
                w[i] *= WALL_PENALTY
            }
            sum += w[i]
        }
        normalize(sum)
        baroFloorChange(now)
        resampleIfNeeded()
    }

    /** Человек сам показал, где он: все частицы — вокруг этой точки (с небольшим разбросом). */
    fun reset(floor: Int, x: Double, y: Double) {
        for (i in 0 until n) {
            px[i] = x + gauss() * 1.5; py[i] = y + gauss() * 1.5; pf[i] = floor
            ph[i] = HEADING_BIAS + gauss() * HEADING_SPREAD
            pl[i] = 0.6 + random.nextDouble() * 0.38
            w[i] = 1.0 / n
        }
        initialized = true
    }

    /** Давление, гПа; [now] — мс. */
    fun onPressure(hPa: Double, now: Long) {
        pressure.addLast(now to hPa)
        while (pressure.size > 2 && now - pressure.first().first > 40_000) pressure.removeFirst()
    }

    private fun pressureAt(t: Long): Double? {
        if (pressure.isEmpty()) return null
        var best = pressure.first()
        for (p in pressure) if (abs(p.first - t) < abs(best.first - t)) best = p
        return best.second.takeIf { abs(best.first - t) < 5_000 }
    }

    /** Давление заметно изменилось за 15 с — поднялись или спустились: частицы у лестниц и лифтов меняют этаж. */
    private fun baroFloorChange(now: Long) {
        if (now < floorCooldownUntil) return
        val p1 = pressureAt(now) ?: return
        val p0 = pressureAt(now - 15_000) ?: return
        val dp = p1 - p0
        if (abs(dp) < BARO_DELTA) return
        val dir = if (dp < 0) 1 else -1
        var moved = 0
        for (i in 0 until n) {
            val next = site.nextFloor(pf[i], dir) ?: continue
            val near = conns[pf[i]]?.any { (cx, cy) -> hypot(px[i] - cx, py[i] - cy) < 8 } == true
            if (near) { pf[i] = next; moved++ }
        }
        if (moved > 0) {
            floorCooldownUntil = now + 12_000
            pressure.clear()
        }
    }

    /** GPS на территории: точка плана и точность, м. В здании (точность хуже 25 м) не используем. */
    fun onGps(x: Double, y: Double, accuracy: Double) {
        if (!initialized || accuracy > 25) return
        val s = max(accuracy, 5.0)
        var sum = 0.0
        for (i in 0 until n) {
            val d = hypot(px[i] - x, py[i] - y)
            w[i] *= 0.2 + exp(-d * d / (2 * s * s))
            sum += w[i]
        }
        normalize(sum)
        resampleIfNeeded()
    }

    private fun normalize(sum: Double) {
        if (sum <= 0 || sum.isNaN()) { java.util.Arrays.fill(w, 1.0 / n); return }
        for (i in 0 until n) w[i] /= sum
    }

    private fun resampleIfNeeded() {
        var s2 = 0.0
        for (v in w) s2 += v * v
        if (1 / s2 >= n / 2.0) return
        // Систематическая выборка.
        val nx = DoubleArray(n); val ny = DoubleArray(n); val nf = IntArray(n); val nh = DoubleArray(n); val nl = DoubleArray(n)
        val step = 1.0 / n
        var u = random.nextDouble() * step
        var c = w[0]; var j = 0
        for (i in 0 until n) {
            while (u > c && j < n - 1) { j++; c += w[j] }
            nx[i] = px[j] + gauss() * 0.4; ny[i] = py[j] + gauss() * 0.4; nf[i] = pf[j]
            nh[i] = ph[j] + gauss() * 0.03; nl[i] = (pl[j] * (1 + gauss() * 0.02)).coerceIn(0.5, 1.1)
            u += step
        }
        nx.copyInto(px); ny.copyInto(py); nf.copyInto(pf); nh.copyInto(ph); nl.copyInto(pl)
        java.util.Arrays.fill(w, 1.0 / n)
    }

    fun estimate(): Fix? {
        if (!initialized) return null
        val byFloor = HashMap<Int, Double>()
        for (i in 0 until n) byFloor[pf[i]] = (byFloor[pf[i]] ?: 0.0) + w[i]
        val floor = byFloor.maxBy { it.value }.key
        // Самое плотное облако: клетки 3 м с соседями.
        val cells = HashMap<Long, Double>()
        for (i in 0 until n) {
            if (pf[i] != floor) continue
            val cx = floor(px[i] / 3).toLong(); val cy = floor(py[i] / 3).toLong()
            for (a in -1..1) for (b in -1..1) {
                val key = ((cx + a) shl 32) xor ((cy + b) and 0xffffffffL)
                cells[key] = (cells[key] ?: 0.0) + w[i]
            }
        }
        val best = cells.maxBy { it.value }.key
        val bx = (best shr 32).toInt() * 3 + 1.5
        val by = best.toInt() * 3 + 1.5
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        for (i in 0 until n) {
            if (pf[i] != floor || hypot(px[i] - bx, py[i] - by) > 6) continue
            sx += px[i] * w[i]; sy += py[i] * w[i]; sw += w[i]
        }
        if (sw <= 0) return null
        val x = sx / sw; val y = sy / sw
        var spread = 0.0; var tw = 0.0
        val votes = HashMap<Int, Double>()
        val g = grid(floor)
        for (i in 0 until n) {
            if (pf[i] != floor) continue
            spread += w[i] * ((px[i] - x) * (px[i] - x) + (py[i] - y) * (py[i] - y)); tw += w[i]
            val room = g?.room(site.grid.row(py[i]), site.grid.col(px[i])) ?: 0
            if (room != 0) votes[room] = (votes[room] ?: 0.0) + w[i]
        }
        val heading = if (lastYaw.isNaN()) Double.NaN else {
            val m = site.enToPlan
            val de = sin(lastYaw + HEADING_BIAS); val dn = cos(lastYaw + HEADING_BIAS)
            kotlin.math.atan2(m[2] * de + m[3] * dn, m[0] * de + m[1] * dn)
        }
        return Fix(
            floor, x, y, votes.maxByOrNull { it.value }?.key ?: 0,
            radius = sqrt(spread / max(tw, 1e-9)).coerceAtLeast(2.0),
            floorShare = byFloor[floor]!! / byFloor.values.sum(),
            heading = heading,
        )
    }

    companion object {
        /** Ширина ядра «отпечаток → место», м. */
        const val SIG_W = 5.0
        const val EPS = 0.2
        const val REINIT = 0.03
        const val WALL_PENALTY = 0.02
        /** Порог изменения давления за 15 с, гПа (≈ 4 м по высоте). */
        const val BARO_DELTA = 0.45
        /** На записях магнитный курс в ГМЦ отличался от настоящего на 5–14°. */
        val HEADING_BIAS = Math.toRadians(8.0)
        val HEADING_SPREAD = Math.toRadians(20.0)
    }
}
