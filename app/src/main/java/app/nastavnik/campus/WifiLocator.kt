package app.nastavnik.campus

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * «Где я»: сравниваем видимые точки Wi-Fi с отпечатками, снятыми при обходе здания (kNN).
 * Точность — несколько метров и этаж; для «куда идти» этого хватает.
 */
class WifiLocator(private val bssids: Map<Long, Int>, private val points: List<Fingerprint>) {
    class Fingerprint(val floor: Int, val x: Double, val y: Double, val rssi: Map<Int, Int>)

    data class Fix(val floor: Int, val x: Double, val y: Double, val matched: Int, val spread: Double)

    /** [scan] — MAC точки (как в ScanResult.BSSID) → уровень сигнала, дБм. */
    fun locate(scan: Map<String, Int>, k: Int = 5): Fix? {
        val seen = scan.mapNotNull { (mac, level) -> bssids[macKey(mac)]?.let { it to level } }.toMap()
        if (seen.size < 3) return null
        val scored = points.mapNotNull { p ->
            var common = 0
            var sum = 0.0
            for ((ap, level) in seen) {
                val ref = p.rssi[ap]
                if (ref != null) { common++; val d = (ref - level).toDouble(); sum += d * d } else sum += 400.0
            }
            // Точки отпечатка, которых сейчас не видно, — тоже несовпадение, но слабее.
            for ((ap, ref) in p.rssi) if (ap !in seen && ref > -75) sum += 100.0
            if (common < 2) null else Triple(p, sqrt(sum / (seen.size + 1)), common)
        }.sortedBy { it.second }.take(k)
        if (scored.isEmpty()) return null
        val floor = scored.groupingBy { it.first.floor }.eachCount().maxBy { it.value }.key
        val same = scored.filter { it.first.floor == floor }
        var wx = 0.0; var wy = 0.0; var ws = 0.0
        for ((p, d, _) in same) { val w = 1.0 / (d + 1.0); wx += p.x * w; wy += p.y * w; ws += w }
        val x = wx / ws; val y = wy / ws
        val spread = same.maxOf { kotlin.math.hypot(it.first.x - x, it.first.y - y) }
        return Fix(floor, x, y, same.maxOf { it.third }, spread)
    }

    companion object {
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
