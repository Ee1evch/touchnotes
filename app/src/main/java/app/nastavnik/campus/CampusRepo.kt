package app.nastavnik.campus

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Карта кампуса из assets/campus: здания, этажи, сетки проходимости, Wi-Fi-отпечатки.
 * Всё грузится лениво и по одному этажу: сетка ГМЦ — несколько мегабайт на этаж в памяти.
 */
class CampusRepo(private val context: Context) {
    private val lock = Mutex()
    private var sitesCache: List<Site>? = null
    private val geo = HashMap<String, FloorGeo>()
    /** Сетки: держим не больше трёх (два этажа маршрута + показанный). */
    private val grids = object : LinkedHashMap<String, NavGrid>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NavGrid>?) = size > 3
    }
    private val wifi = HashMap<String, WifiLocator?>()

    suspend fun sites(): List<Site> = lock.withLock {
        sitesCache ?: withContext(Dispatchers.IO) {
            val index = Json.parseToJsonElement(read("campus/sites.json").decodeToString()).jsonArray
            index.map { Site.parse(read("campus/${it.jsonObject["id"]!!.jsonPrimitive.content}/site.json").decodeToString()) }
        }.also { sitesCache = it }
    }

    suspend fun site(id: String): Site? = sites().firstOrNull { it.id == id }

    suspend fun floorGeo(site: Site, floor: Int): FloorGeo = withContext(Dispatchers.IO) {
        val key = "${site.id}/$floor"
        synchronized(geo) { geo[key] } ?: FloorGeo.parse(read("campus/${site.id}/f$floor.geo")).also { synchronized(geo) { geo[key] = it } }
    }

    fun grid(site: Site, floor: Int): NavGrid {
        val key = "${site.id}/$floor"
        synchronized(grids) { grids[key] }?.let { return it }
        val g = NavGrid.parse(read("campus/${site.id}/f$floor.nav"))
        synchronized(grids) { grids[key] = g }
        return g
    }

    fun router(site: Site) = Router(site) { grid(site, it) }

    suspend fun locator(site: Site): WifiLocator? = withContext(Dispatchers.IO) {
        synchronized(wifi) { if (wifi.containsKey(site.id)) return@withContext wifi[site.id] }
        val l = runCatching { WifiLocator.parse(read("campus/${site.id}/wifi.bin")) }.getOrNull()
        synchronized(wifi) { wifi[site.id] = l }
        l
    }

    private fun read(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }
}
