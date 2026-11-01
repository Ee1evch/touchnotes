package app.nastavnik.campus

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * «Где я»: сравниваем видимые точки Wi-Fi с отпечатками, снятыми при обходе здания (kNN).
 * Точность — несколько метров и этаж; для «куда идти» этого хватает.
 */
class WifiLocator(internal val bssids: Map<Long, Int>, internal val points: List<Fingerprint>) {
    /** Сколько точек доступа здания видно в скане — по этому выбираем здание. */
    fun known(scan: Map<String, Int>): Int = scan.keys.count { bssids.containsKey(macKey(it)) }

    /** Отпечаток из скана (для своих отпечатков: «я здесь»). Точки, которых здание не знает, отбрасываются. */
    fun fingerprintOf(scan: Map<String, Int>, floor: Int, x: Double, y: Double): Fingerprint? {
        val m = scan.mapNotNull { (mac, level) -> bssids[macKey(mac)]?.let { it to level } }.toMap()
        return if (m.size < 3) null else Fingerprint(floor, x, y, m)
    }

    /** Тот же справочник точек доступа, но с добавленными отпечатками. */
    fun plus(extra: List<Fingerprint>): WifiLocator = if (extra.isEmpty()) this else WifiLocator(bssids, points + extra)

    class Fingerprint(val floor: Int, val x: Double, val y: Double, val rssi: Map<Int, Int>)

    data class Fix(val floor: Int, val x: Double, val y: Double, val matched: Int, val spread: Double)

    /**
     * Лучшие отпечатки для скана с весами. Модель: уровень сигнала у отпечатка ~ N(уровень в скане, 8 дБ)
     * по 40 самым сильным точкам скана; точка, которой у отпечатка нет, штрафуется по тому, насколько она сильная.
     * Вес — по абсолютному качеству совпадения (а не относительно лучшего), чтобы при плохом совпадении
     * всех отпечатков Wi-Fi не тянул оценку к «наименее плохому».
     */
    fun score(scan: Map<String, Int>, top: Int = 40): List<Pair<Fingerprint, Double>>? {
        val seen = scan.mapNotNull { (mac, level) -> bssids[macKey(mac)]?.let { it to level } }
            .sortedByDescending { it.second }.take(40)
        if (seen.size < 3) return null
        val na = seen.size
        val scored = points.map { p ->
            var ll = 0.0
            for ((ap, level) in seen) {
                val ref = p.rssi[ap]
                ll += if (ref != null) {
                    val d = (ref - level) / SIGMA
                    -0.5 * d * d
                } else {
                    val over = ((level - THRESHOLD) / SIGMA).coerceAtLeast(0.0)
                    -MISS_WEIGHT * over * over - 0.5
                }
            }
            p to ll
        }.sortedByDescending { it.second }.take(top)
        return scored.map { (p, ll) -> p to kotlin.math.exp(BETA * (ll / na - LL0)) }
    }

    /** Одиночный скан → место (без шагов): этаж — голосованием, место — средним лучших. */
    fun locate(scan: Map<String, Int>, k: Int = 6): Fix? {
        val best = score(scan)?.take(k) ?: return null
        val floors = HashMap<Int, Double>()
        for ((p, w) in best) floors[p.floor] = (floors[p.floor] ?: 0.0) + w
        val floor = floors.maxBy { it.value }.key
        val same = best.filter { it.first.floor == floor }
        val ws = same.sumOf { it.second }.takeIf { it > 0 } ?: return null
        val x = same.sumOf { it.first.x * it.second } / ws
        val y = same.sumOf { it.first.y * it.second } / ws
        val spread = same.maxOf { kotlin.math.hypot(it.first.x - x, it.first.y - y) }
        return Fix(floor, x, y, same.size, spread)
    }

    companion object {
        const val SIGMA = 8.0
        const val THRESHOLD = -88.0
        const val MISS_WEIGHT = 0.35
        const val BETA = 15.0
        const val LL0 = -0.5

        fun macKey(mac: String): Long = mac.split(':').fold(0L) { acc, part -> (acc shl 8) or (part.toLongOrNull(16) ?: 0L) }

        fun parse(bytes: ByteArray): WifiLocator {
            require(String(bytes, 0, 4) == "CWIF") { "не Wi-Fi-отпечатки" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.position(5)
            val nb = b.int
            val macs = HashMap<Long, Int>(nb * 2)
            repeat(nb) { i ->
                var v = 0L
                repeat(6) { v = (v shl 8) or (b.get().toLong() and 0xFF) }
                macs[v] = i
            }
            val np = b.int
            val unit = 0.025
            val pts = ArrayList<Fingerprint>(np)
            repeat(np) {
                val floor = b.get().toInt()
                val x = b.short * unit
                val y = b.short * unit
                val n = b.get().toInt() and 0xFF
                val m = HashMap<Int, Int>(n * 2)
                repeat(n) { m[b.short.toInt() and 0xFFFF] = b.get().toInt() }
                pts.add(Fingerprint(floor, x, y, m))
            }
            return WifiLocator(macs, pts)
        }
    }
}
