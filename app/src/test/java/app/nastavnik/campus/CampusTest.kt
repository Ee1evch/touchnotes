package app.nastavnik.campus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CampusTest {
    companion object {
        /** Юнит-тесты Android идут из папки модуля, проверочная сборка — из своей: ищем assets от обеих. */
        val root: File = listOf("src/main/assets/campus", "app/src/main/assets/campus", "/home/user/touchnotes/app/src/main/assets/campus")
            .map(::File).first { it.isDirectory }

        val gmc: Site by lazy { Site.parse(File(root, "gmc/site.json").readText()) }
        private val grids = HashMap<Int, NavGrid>()
        fun grid(n: Int) = grids.getOrPut(n) { NavGrid.parse(File(root, "gmc/f$n.nav").readBytes()) }
    }

    @Test fun parsesSiteAndFloors() {
        assertEquals(listOf(1, 2, 3), gmc.floors.map { it.n })
        assertEquals("1 этаж", gmc.floor(1)!!.short)
        assertTrue(gmc.rooms.size > 1000)
        val geo = FloorGeo.parse(File(root, "gmc/f1.geo").readBytes())
        val rooms = geo.layer(GeoLayer.ROOMS)!!
        assertTrue(rooms.triangles && rooms.colors != null && rooms.indices!!.size % 3 == 0)
        assertTrue(rooms.indices!!.all { it < rooms.verts.size / 2 })
        val walls = geo.layer(GeoLayer.WALLS)!!
        assertTrue(walls.indices!!.size % 3 == 0)
        val b = geo.bounds
        assertTrue(b[2] - b[0] > 100 && b[3] - b[1] > 100)
    }

    @Test fun findsRoomsFromSchedule() {
        val sites = listOf(gmc)
        assertEquals("Альфа 5.8", RoomSearch.forClassroom(sites, "Альфа 5.8Альфа 5.8 (Основной)")!!.second.title)
        assertEquals("Бета 3.3", RoomSearch.forClassroom(sites, "1-07-18 Бета 3.3 (комп)")!!.second.title)
        assertTrue(RoomSearch.forClassroom(sites, "К_У. К4 (комп)")!!.second.nums.contains("К4"))
        // «Альфа 5.1» не должна найти «Альфа 5.10».
        assertEquals("Альфа 5.1", RoomSearch.forClassroom(sites, "Альфа 5.1")!!.second.names.first().substringBefore(" ·"))
        assertNull(RoomSearch.forClassroom(sites, "Дистанционно"))
        val found = RoomSearch.search(sites, "альфа 5")
        assertTrue(found.size >= 5 && found.all { it.second.title.startsWith("Альфа 5") })
        assertTrue(RoomSearch.search(sites, "буран").isNotEmpty())
    }

    @Test fun routesOnOneFloorAndAcrossFloors() {
        val router = Router(gmc, ::grid)
        val a = RoomSearch.forClassroom(listOf(gmc), "Альфа 5.8")!!.second
        val b = RoomSearch.forClassroom(listOf(gmc), "Бета 3.3")!!.second
        val r = router.route(router.placeOf(a)!!, router.placeOf(b)!!)
        assertNotNull(r)
        assertTrue(r!!.legs.single().meters > 20)
        // Спрямлённая ломаная — десятки точек, а не тысячи клеток.
        assertTrue(r.legs.single().points.size < 80)

        val up = gmc.rooms.first { it.floor == 3 && !it.isGeneric && it.reachable }
        val r2 = router.route(router.placeOf(a)!!, router.placeOf(up)!!)
        assertNotNull(r2)
        assertEquals(listOf(1, 3), r2!!.legs.map { it.floor })
        assertEquals(1, r2.transfers.size)
        assertTrue(r2.minutes in 1..30)
    }

    @Test fun wifiFingerprintsLocateTheirOwnPoints() {
        val loc = WifiLocator.parse(File(root, "gmc/wifi.bin").readBytes())
        assertEquals(0x18c3f4b609d0L, WifiLocator.macKey("18:c3:f4:b6:09:d0"))
        assertNull(loc.locate(mapOf("00:11:22:33:44:55" to -50)))
    }
}
