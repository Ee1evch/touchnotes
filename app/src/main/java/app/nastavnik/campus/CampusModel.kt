package app.nastavnik.campus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Карта кампуса: данные навигатора ГМЦ, ужатые `tools/campus/build_campus.py`.
 * Координаты — метры плана: x вправо, y вверх. Сетка проходимости — клетки 0,3 м, строка 0 сверху.
 */
data class Grid(val x0: Double, val y1: Double, val res: Double, val cols: Int, val rows: Int) {
    fun col(x: Double) = ((x - x0) / res).toInt()
    fun row(y: Double) = ((y1 - y) / res).toInt()
    fun x(col: Int) = x0 + (col + 0.5) * res
    fun y(row: Int) = y1 - (row + 0.5) * res
    fun inside(r: Int, c: Int) = r in 0 until rows && c in 0 until cols
}

data class Floor(val n: Int, val name: String) {
    /** «1 этаж (отм. 0.000)» → «1 этаж»: отметку высоты студенту знать незачем. */
    val short: String get() = name.substringBefore(" (").trim()
}

data class Room(
    val id: Int,
    val floor: Int,
    val nums: List<String>,
    val names: List<String>,
    val func: List<String>,
    val x: Double,
    val y: Double,
    val labels: List<Pair<Double, Double>>,
    /** Клетка сетки, от которой строится маршрут (строка, столбец). */
    val anchor: Pair<Int, Int>?,
    val reachable: Boolean,
    /** Вход по ключу или пропуску. */
    val restricted: Boolean,
    val area: Double,
) {
    /** Как помещение называть человеку: «Альфа 5.8», «К4 · Аудитория», «1459». */
    val title: String
        get() {
            // Учебная аудитория («Альфа 5.7») важнее склада или холла, записанных в том же помещении.
            val main = names.firstOrNull { looksLikeRoomName(it) } ?: names.firstOrNull { it.isNotBlank() && it !in GENERIC }
            val num = nums.firstOrNull()
            return when {
                main != null && num != null && !main.contains(num) && looksLikeNumber(num) && !looksLikeRoomName(main) -> "$num · $main"
                main != null -> main
                num != null -> num
                else -> names.firstOrNull() ?: "Помещение"
            }
        }

    /** Пояснение под названием: номер по плану и назначение. */
    val subtitle: String
        get() {
            val parts = (nums + names + func).filter { it.isNotBlank() && it != title && it !in GENERIC && !title.contains(it) }
            return parts.distinct().take(3).joinToString(" · ")
        }

    /** Всё, по чему помещение можно найти. */
    val searchText: String by lazy { (nums + names + func).joinToString(" ").lowercase().replace('ё', 'е') }

    /** Безымянные проходы и тамбуры в поиске только мешают. */
    val isGeneric: Boolean get() = (nums + names + func).all { it in GENERIC }

    companion object {
        val GENERIC = setOf("Открытое пространство", "Помещение без номера", "Проход", "Тамбур", "")
        private fun looksLikeNumber(s: String) = s.any { it.isDigit() }
        private fun looksLikeRoomName(s: String) = Regex("""^(Альфа|Бета|Гамма|Дельта)\s""").containsMatchIn(s)
    }
}

/** Лестница, лифт или эскалатор: где он на каждом этаже. */
data class Connector(val kind: String, val name: String, val cells: Map<Int, Pair<Int, Int>>, val points: Map<Int, Pair<Double, Double>>) {
    val label: String get() = when (kind) {
        "lift" -> "лифт"
        "escalator" -> "эскалатор"
        else -> "лестница"
    }
}

class Site(
    val id: String,
    val title: String,
    val lat: Double,
    val lon: Double,
    val grid: Grid,
    val floors: List<Floor>,
    val rooms: List<Room>,
    val conns: List<Connector>,
) {
    val byId: Map<Pair<Int, Int>, Room> = rooms.associateBy { it.floor to it.id }
    fun floor(n: Int) = floors.firstOrNull { it.n == n }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Site {
            val o = json.parseToJsonElement(text).jsonObject
            val g = o["grid"]!!.jsonObject
            val grid = Grid(
                g["x0"]!!.jsonPrimitive.double, g["y1"]!!.jsonPrimitive.double, g["res"]!!.jsonPrimitive.double,
                g["cols"]!!.jsonPrimitive.int, g["rows"]!!.jsonPrimitive.int,
            )
            fun strings(e: JsonObject, k: String) = (e[k] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            val rooms = o["rooms"]!!.jsonArray.map { e ->
                val r = e.jsonObject
                val a = (r["a"] as? JsonArray)?.let { it[0].jsonPrimitive.int to it[1].jsonPrimitive.int }
                Room(
                    id = r["id"]!!.jsonPrimitive.int,
                    floor = r["f"]!!.jsonPrimitive.int,
                    nums = strings(r, "nums"),
                    names = strings(r, "names"),
                    func = strings(r, "func"),
                    x = r["x"]!!.jsonPrimitive.double,
                    y = r["y"]!!.jsonPrimitive.double,
                    labels = (r["l"] as? JsonArray)?.map { it.jsonArray[0].jsonPrimitive.double to it.jsonArray[1].jsonPrimitive.double }.orEmpty(),
                    anchor = a,
                    reachable = r["unreach"] == null,
                    restricted = r["key"] != null,
                    area = (r["area"])?.jsonPrimitive?.double ?: 0.0,
                )
            }
            val conns = o["conns"]!!.jsonArray.map { e ->
                val c = e.jsonObject
                val pos = c["pos"]!!.jsonObject
                Connector(
                    kind = c["kind"]!!.jsonPrimitive.content,
                    name = c["name"]?.jsonPrimitive?.content.orEmpty(),
                    cells = pos.entries.associate { (k, v) -> k.toInt() to (v.jsonArray[2].jsonPrimitive.int to v.jsonArray[3].jsonPrimitive.int) },
                    points = pos.entries.associate { (k, v) -> k.toInt() to (v.jsonArray[0].jsonPrimitive.double to v.jsonArray[1].jsonPrimitive.double) },
                )
            }
            return Site(
                id = o["id"]!!.jsonPrimitive.content,
                title = o["title"]!!.jsonPrimitive.content,
                lat = o["lat"]!!.jsonPrimitive.double,
                lon = o["lon"]!!.jsonPrimitive.double,
                grid = grid,
                floors = o["floors"]!!.jsonArray.map { Floor(it.jsonObject["n"]!!.jsonPrimitive.int, it.jsonObject["name"]!!.jsonPrimitive.content) },
                rooms = rooms,
                conns = conns,
            )
        }
    }
}

/** Слой чертежа этажа: треугольники (помещения, стены) или отрезки (двери, мебель, ограды). */
class GeoLayer(
    val kind: Int,
    val triangles: Boolean,
    /** x0, y0, x1, y1… в метрах. */
    val verts: FloatArray,
    /** Цвет каждой вершины (ARGB) или null. */
    val colors: IntArray?,
    val indices: IntArray?,
) {
    companion object {
        const val ROOMS = 1
        const val WALLS = 2
        const val DOORS = 3
        const val STAIRS = 4
        const val FURNITURE = 5
        const val ROOF = 6
        const val FENCE = 7
        const val PATHS = 8
    }
}

class FloorGeo(val layers: List<GeoLayer>) {
    fun layer(kind: Int) = layers.firstOrNull { it.kind == kind }

    /** Границы чертежа (minX, minY, maxX, maxY) — без ограды территории, чтобы здание заполнило экран. */
    val bounds: FloatArray by lazy {
        val use = layers.filter { it.kind == GeoLayer.WALLS }.ifEmpty { layers }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (l in use) {
            var i = 0
            while (i < l.verts.size) {
                val x = l.verts[i]; val y = l.verts[i + 1]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                i += 2
            }
        }
        floatArrayOf(minX, minY, maxX, maxY)
    }

    companion object {
        fun parse(bytes: ByteArray): FloorGeo {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size > 10 && String(bytes, 0, 4) == "CGEO") { "не чертёж этажа" }
            b.position(4)
            b.get() // версия
            val unit = b.float
            val n = b.get().toInt() and 0xFF
            val layers = (0 until n).map {
                val kind = b.get().toInt() and 0xFF
                val tri = b.get().toInt() == 1
                val count = b.int
                val verts = FloatArray(count * 2) { b.short * unit }
                var colors: IntArray? = null
                if (b.get().toInt() == 1) {
                    val pc = b.get().toInt() and 0xFF
                    val palette = IntArray(pc) {
                        val r = b.get().toInt() and 0xFF; val g = b.get().toInt() and 0xFF; val bl = b.get().toInt() and 0xFF
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                    }
                    colors = IntArray(count) { palette[b.get().toInt() and 0xFF] }
                }
                val ni = b.int
                val wide = b.get().toInt() == 1
                val idx = if (ni == 0) null else IntArray(ni) { if (wide) b.int else b.short.toInt() and 0xFFFF }
                GeoLayer(kind, tri, verts, colors, idx)
            }
            return FloorGeo(layers)
        }
    }
}

/** Сетка проходимости: в каждой клетке — номер помещения (0 — стена или пустота). */
class NavGrid(val rows: Int, val cols: Int, val cells: ShortArray) {
    fun room(r: Int, c: Int): Int = if (r in 0 until rows && c in 0 until cols) cells[r * cols + c].toInt() and 0xFFFF else 0
    fun walkable(r: Int, c: Int) = room(r, c) != 0

    /** Ближайшая проходимая клетка (поиск расширяющимися квадратами). */
    fun nearestWalkable(r: Int, c: Int, maxRadius: Int = 60): Pair<Int, Int>? {
        if (walkable(r, c)) return r to c
        for (d in 1..maxRadius) {
            for (k in -d..d) {
                if (walkable(r - d, c + k)) return (r - d) to (c + k)
                if (walkable(r + d, c + k)) return (r + d) to (c + k)
                if (walkable(r + k, c - d)) return (r + k) to (c - d)
                if (walkable(r + k, c + d)) return (r + k) to (c + d)
            }
        }
        return null
    }

    companion object {
        fun parse(bytes: ByteArray): NavGrid {
            require(bytes.size > 13 && String(bytes, 0, 4) == "CNAV") { "не сетка проходимости" }
            var p = 5
            fun u32(): Int { val v = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or ((bytes[p + 2].toInt() and 0xFF) shl 16) or ((bytes[p + 3].toInt() and 0xFF) shl 24); p += 4; return v }
            fun varint(): Int {
                var v = 0; var shift = 0
                while (true) {
                    val b = bytes[p++].toInt() and 0xFF
                    v = v or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) return v
                    shift += 7
                }
            }
            val rows = u32()
            val cols = u32()
            val cells = ShortArray(rows * cols)
            for (r in 0 until rows) {
                val runs = varint()
                var c = 0
                repeat(runs) {
                    val v = varint().toShort()
                    val len = varint()
                    if (v.toInt() != 0) java.util.Arrays.fill(cells, r * cols + c, r * cols + c + len, v)
                    c += len
                }
            }
            return NavGrid(rows, cols, cells)
        }
    }
}
